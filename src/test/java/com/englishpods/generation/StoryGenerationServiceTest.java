package com.englishpods.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.AudioFileFormat;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class StoryGenerationServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir Path directory;
    private HttpServer server;
    private String base;
    private GenerationSettings settings;
    private final List<JsonNode> deepSeekRequests = new CopyOnWriteArrayList<>();
    private final List<JsonNode> qwenRequests = new CopyOnWriteArrayList<>();
    private final AtomicBoolean downloadHadAuthorization = new AtomicBoolean();
    private final AtomicInteger downloads = new AtomicInteger();
    private String story = "That's Sam's map. “Go!”";
    private String deepSeekJson;
    private byte[] wav;
    private String qwenOverride;
    private List<String> qwenResponses = List.of();
    private int qwenStatus = 200;
    private int deepSeekFailAt = -1;

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        settings = GenerationSettings.from(new MockEnvironment().withProperty("STORY_LLM_PROVIDER", "deepseek").withProperty("DEEPSEEK_ENDPOINT", base + "/deepseek")
                .withProperty("QWEN_TTS_ENDPOINT", base + "/qwen"), "fake-deepseek", "fake-qwen");
        deepSeekJson = mapper.writeValueAsString(Map.of("voiceMap", Map.of("visitor", Map.of("gender", "unknown", "voiceId", "sam")), "segments", List.of(
                Map.of("kind", "narration", "speakerId", "narrator", "text", "That's Sam's map. "),
                Map.of("kind", "dialogue", "speakerId", "visitor", "text", "“Go!”"))));
        try (var output = new ByteArrayOutputStream(); var input = new AudioInputStream(
                new ByteArrayInputStream(new byte[24000 * 2]), new AudioFormat(24000, 16, 1, true, false), 24000)) {
            AudioSystem.write(input, AudioFileFormat.Type.WAVE, output);
            wav = output.toByteArray();
        }
        server.createContext("/deepseek", exchange -> {
            deepSeekRequests.add(mapper.readTree(exchange.getRequestBody()));
            if (deepSeekRequests.size() == deepSeekFailAt) {
                respond(exchange, 401, "redacted-provider-error", "application/json"); return;
            }
            respond(exchange, 200, mapper.writeValueAsString(Map.of("choices", List.of(Map.of(
                    "finish_reason", "stop", "message", Map.of("content", deepSeekJson))))), "application/json");
        });
        server.createContext("/qwen", exchange -> {
            JsonNode request = mapper.readTree(exchange.getRequestBody());
            qwenRequests.add(request);
            assertEquals("enable", exchange.getRequestHeaders().getFirst("X-DashScope-SSE"));
            String text = request.path("input").path("text").asText();
            String response = qwenRequests.size() <= qwenResponses.size() ? qwenResponses.get(qwenRequests.size() - 1)
                    : qwenOverride != null ? qwenOverride : stream(text);
            respond(exchange, qwenStatus, response, "text/event-stream");
        });
        server.createContext("/audio", exchange -> {
            downloads.incrementAndGet();
            downloadHadAuthorization.set(exchange.getRequestHeaders().containsKey("Authorization"));
            exchange.getResponseHeaders().set("Content-Type", "audio/wav");
            exchange.sendResponseHeaders(200, wav.length);
            exchange.getResponseBody().write(wav); exchange.close();
        });
        server.start();
    }

    @AfterEach void close() { server.stop(0); }

    @Test void realHttpAndFfmpegPreserveSourceVoicesAndActualFrameTimeline() throws Exception {
        List<String> stages = new ArrayList<>();
        GenerationResult result = new StoryGenerationService(settings, mapper).generate(story, directory, stages::add);
        assertEquals(List.of("SEGMENTING", "REVIEWING", "SYNTHESIZING", "ASSEMBLING"), stages);
        assertEquals(story, result.text());
        assertEquals("", result.audioUrl());
        assertEquals(2.12, result.duration(), 0.00001);
        assertEquals(List.of("That's", "Sam's", "map", "Go"), result.words().stream().map(TimedWord::text).toList());
        assertEquals(1.14, result.words().getLast().start(), 0.00001);
        assertEquals(2, result.sentences().size());
        assertEquals("visitor", result.segments().getLast().speakerId());
        assertEquals(settings.voices().get("sam"), qwenRequests.getLast().path("input").path("voice").asText());
        assertEquals(settings.voices().get("narrator"), qwenRequests.getFirst().path("input").path("voice").asText());
        assertTrue(qwenRequests.getFirst().path("input").path("word_timestamp_enabled").asBoolean());
        assertEquals(24000, qwenRequests.getFirst().path("input").path("sample_rate").asInt());
        assertEquals(2, deepSeekRequests.size());
        assertTrue(deepSeekRequests.getLast().path("messages").get(1).path("content").asText().contains("previousSegments"));
        assertTrue(deepSeekRequests.getFirst().path("messages").get(0).path("content").asText().contains("alex"));
        assertFalse(downloadHadAuthorization.get());
        assertEquals(2, downloads.get());
        try (var audio = AudioSystem.getAudioInputStream(directory.resolve("story.wav").toFile())) {
            assertEquals(50880, audio.getFrameLength());
            assertEquals(24000, audio.getFormat().getSampleRate());
        }
        for (TimedWord word : result.words()) assertEquals(word.text(), story.substring(word.sourceStart(), word.sourceEnd()));
        assertTrue(Files.exists(directory.resolve("segments-reviewed.json")));
        assertTrue(Files.exists(directory.resolve("timeline.json")));
    }

    @Test void rejectsRewrittenStoryBeforeQwen() {
        deepSeekJson = "{\"segments\":[{\"kind\":\"narration\",\"speakerId\":\"narrator\",\"text\":\"changed\"}]}";
        GenerationException error = assertThrows(GenerationException.class,
                () -> new StoryGenerationService(settings, mapper).generate(story, directory, ignored -> {}));
        assertTrue(error.getMessage().contains("改写"));
        assertTrue(qwenRequests.isEmpty());
        assertFalse(Files.exists(directory.resolve("story.wav")));
    }

    @Test void bailianRunsBothRoundsThenUsesIndependentConfiguredSpeechModel() {
        var bailian = GenerationSettings.from(new MockEnvironment()
                .withProperty("STORY_LLM_PROVIDER", "bailian")
                .withProperty("QWEN_LLM_ENDPOINT", base + "/deepseek")
                .withProperty("QWEN_LLM_MODEL", "qwen3.8-max")
                .withProperty("QWEN_TTS_ENDPOINT", base + "/qwen")
                .withProperty("QWEN_TTS_MODEL", "configured-speech-model"), null, "fake-qwen");
        var stages = new ArrayList<String>();
        var result = new StoryGenerationService(bailian, mapper).generate(story, directory, stages::add);
        assertEquals(story, result.text());
        assertEquals(List.of("SEGMENTING", "REVIEWING", "SYNTHESIZING", "ASSEMBLING"), stages);
        assertEquals(2, deepSeekRequests.size());
        assertTrue(deepSeekRequests.stream().allMatch(r -> "qwen3.8-max".equals(r.path("model").asText())
                && r.path("enable_thinking").isBoolean() && !r.path("enable_thinking").asBoolean()));
        assertTrue(qwenRequests.stream().allMatch(r -> "configured-speech-model".equals(r.path("model").asText())));
        assertEquals(2.12, result.duration(), 0.00001);
    }

    @Test void zeroDurationResynthesizesOnlyCurrentChunkAndFfmpegUsesOnlyAcceptedAudio() throws Exception {
        String invalidGo = event(Map.of("output", Map.of("sentence", Map.of("index", 0,
                "words", List.of(timing("Go", 1, 3, 20, 20))), "finish_reason", "stop",
                "audio", Map.of("data", java.util.Base64.getEncoder().encodeToString(new byte[]{99, 98, 97})))));
        qwenResponses = List.of(stream("That's Sam's map. "), invalidGo, stream("“Go!”"));
        var result = new StoryGenerationService(settings, mapper).generate(story, directory, ignored -> {});
        assertEquals(List.of("That's Sam's map. ", "“Go!”", "“Go!”"), qwenRequests.stream()
                .map(r -> r.at("/input/text").asText()).toList());
        assertEquals(List.of(0, 0, 1), qwenRequests.stream().map(r -> r.at("/input/seed").asInt()).toList());
        assertEquals(qwenRequests.get(1).at("/input/voice"), qwenRequests.get(2).at("/input/voice"));
        assertEquals(2, deepSeekRequests.size());
        assertEquals(2, downloads.get());
        assertEquals(2.12, result.duration(), 0.00001);
        assertEquals(List.of("That's", "Sam's", "map", "Go"), result.words().stream().map(TimedWord::text).toList());
        try (var audio = AudioSystem.getAudioInputStream(directory.resolve("story.wav").toFile())) {
            assertEquals(50880, audio.getFrameLength());
        }
        assertFalse(Files.exists(directory.resolve("qwen-failure.json")));
    }

    @Test void repeatedActualZeroDurationSplitsOriginalChunkAtCommaAndKeepsFrameOffsets() throws Exception {
        String prefix = "😀 Before. ";
        String left = "There were five circles on the map, ";
        String right = "with a line between them. ";
        String chunk = left + right;
        String suffix = "“Go!”";
        story = prefix + chunk + suffix;
        deepSeekJson = mapper.writeValueAsString(Map.of("voiceMap", Map.of("visitor", Map.of("gender", "unknown", "voiceId", "sam")), "segments", List.of(
                Map.of("kind", "dialogue", "speakerId", "visitor", "text", prefix),
                Map.of("kind", "narration", "speakerId", "narrator", "text", chunk),
                Map.of("kind", "dialogue", "speakerId", "visitor", "text", suffix))));
        JsonNode invalid;
        try (var fixture = getClass().getResourceAsStream("/qwen/zero-duration-failure.json")) {
            invalid = mapper.readTree(fixture).at("/alignment/timings");
        }
        var ranges = StoryText.words(chunk);
        for (int i = 0; i < ranges.size(); i++) {
            var range = ranges.get(i);
            ((ObjectNode) invalid.get(i)).put("text", chunk.substring(range.start(), range.end()));
        }
        String invalidChunk = event(Map.of("output", Map.of("sentence", Map.of("index", 0, "words", invalid),
                "original_text", chunk, "finish_reason", "stop", "audio", Map.of("url", base + "/audio?rejected"))));
        qwenResponses = List.of(stream(prefix), invalidChunk, invalidChunk, invalidChunk, stream(left), stream(right), stream(suffix));

        var result = new StoryGenerationService(settings, mapper).generate(story, directory, ignored -> {});

        assertEquals(List.of(prefix, chunk, chunk, chunk, left, right, suffix), qwenRequests.stream()
                .map(r -> r.at("/input/text").asText()).toList());
        assertEquals(List.of(0, 0, 1, 2, 0, 0, 0), qwenRequests.stream().map(r -> r.at("/input/seed").asInt()).toList());
        for (var request : qwenRequests.subList(1, 6))
            assertEquals(settings.voices().get("narrator"), request.at("/input/voice").asText());
        assertEquals(2, deepSeekRequests.size());
        assertEquals(4, downloads.get());
        assertEquals(story, result.text());
        assertEquals(3, result.sentences().size());
        var expected = StoryText.words(story);
        assertEquals(expected.size(), result.words().size());
        for (int i = 0; i < result.words().size(); i++) {
            var range = expected.get(i);
            var word = result.words().get(i);
            assertEquals(range.start(), word.sourceStart());
            assertEquals(range.end(), word.sourceEnd());
            assertEquals(story.substring(range.start(), range.end()), word.text());
        }
        var the = result.words().get(6);
        assertEquals("the", the.text());
        assertEquals(1.64, the.start(), 0.00001);
        assertEquals(1.72, the.end(), 0.00001);
        var with = result.words().get(8);
        assertEquals("with", with.text());
        assertEquals(2.14, with.start(), 0.00001);
        assertEquals(the.sentenceId(), with.sentenceId());
        assertEquals(3.26, result.words().getLast().start(), 0.00001);
        assertEquals(4.24, result.duration(), 0.00001);
        try (var audio = AudioSystem.getAudioInputStream(directory.resolve("story.wav").toFile())) {
            assertEquals(101760, audio.getFrameLength());
        }
        assertTrue(Files.exists(directory.resolve("chunk-1-part-0.wav")));
        assertTrue(Files.exists(directory.resolve("chunk-1-part-1.wav")));
        assertFalse(Files.exists(directory.resolve("chunk-1.wav")));
        assertFalse(Files.exists(directory.resolve("qwen-failure.json")));
    }

    @Test void missingTimestampsNeverFallsBackToFakeAudio() throws Exception {
        qwenOverride = event(Map.of("output", Map.of("finish_reason", "stop", "audio", Map.of("url", base + "/audio?secret"))));
        GenerationException error = assertThrows(GenerationException.class,
                () -> new StoryGenerationService(settings, mapper).generate(story, directory, ignored -> {}));
        assertTrue(error.getMessage().contains("时间戳"));
        assertEquals(0, downloads.get());
        assertFalse(Files.exists(directory.resolve("story.wav")));
    }

    @Test void providerErrorBodyAndSecretsNeverReachSafeException() {
        qwenStatus = 401;
        qwenOverride = "fake-qwen https://secret.invalid/signed?token=do-not-expose";
        GenerationException error = assertThrows(GenerationException.class,
                () -> new StoryGenerationService(settings, mapper).generate(story, directory, ignored -> {}));
        assertTrue(error.getMessage().contains("401"));
        assertFalse(error.getMessage().contains("fake-qwen"));
        assertFalse(error.getMessage().contains("secret.invalid"));
        assertNull(error.getCause());
    }

    @Test void readinessDoesNotReadKeysFromSpringPropertiesOrRevealKeys() {
        GenerationSettings config = GenerationSettings.from(new MockEnvironment().withProperty("STORY_LLM_PROVIDER", "deepseek")
                .withProperty("DEEPSEEK_API_KEY", "not-an-environment-key")
                .withProperty("DASHSCOPE_API_KEY", "not-an-environment-key"), null, null);
        assertFalse(config.readiness().ready());
        assertTrue(config.readiness().issues().stream().anyMatch(s -> s.contains("DEEPSEEK_API_KEY")));
        assertFalse(config.toString().contains("not-an-environment-key"));
        assertFalse(settings.toString().contains("fake-deepseek"));
        assertTrue(settings.readiness().ready());
    }

    @Test void finiteRetryOnTransientProviderFailure() {
        qwenStatus = 503;
        qwenOverride = "temporary outage";
        assertThrows(GenerationException.class,
                () -> new StoryGenerationService(settings, mapper).generate(story, directory, ignored -> {}));
        assertEquals(3, qwenRequests.size());
    }

    @Test void sseErrorsAndTruncatedCompletionAreRejected() throws Exception {
        var client = new QwenSpeechClient(settings, mapper, new GenerationHttpClient());
        assertThrows(GenerationException.class, () -> client.parse("Hello", "data: {\"code\":\"InvalidApiKey\",\"message\":\"fake-qwen\"}\n\n"));
        assertThrows(GenerationException.class, () -> client.parse("Hello", "data: {\"output\":{}}\n\n"));
    }

    @Test void sourceCoverageRejectsMissingDuplicateAndSplitSurrogate() throws Exception {
        for (String invalid : List.of(
                "{\"segments\":[]}",
                "{\"segments\":[{\"kind\":\"narration\",\"speakerId\":\"narrator\",\"text\":\"Hello\"}]}",
                "{\"segments\":[{\"kind\":\"invalid\",\"speakerId\":\"narrator\",\"text\":\"Hello!\"}]}")) {
            assertThrows(GenerationException.class, () -> StorySegmenter.validate("Hello!", mapper.readTree(invalid)));
        }
        JsonNode split = mapper.valueToTree(Map.of("voiceMap", Map.of("visitor", Map.of("gender", "unknown", "voiceId", "sam")), "segments", List.of(
                Map.of("kind", "dialogue", "speakerId", "ben", "text", "\uD83D"),
                Map.of("kind", "dialogue", "speakerId", "ben", "text", "\uDE00 Hi"))));
        assertThrows(GenerationException.class, () -> StorySegmenter.validate("😀 Hi", split));
    }

    @Test void utf16EmojiOffsetsAndCurlyContractionsArePreserved() throws Exception {
        story = "😀 That’s Sam’s map.";
        deepSeekJson = mapper.writeValueAsString(Map.of("voiceMap", Map.of("visitor", Map.of("gender", "unknown", "voiceId", "sam")), "segments", List.of(Map.of(
                "kind", "narration", "speakerId", "narrator", "text", story))));
        GenerationResult result = new StoryGenerationService(settings, mapper).generate(story, directory, ignored -> {});
        assertEquals(3, result.words().getFirst().sourceStart());
        assertEquals("That’s", result.words().getFirst().text());
    }

    @Test void invalidOverlappingAndIncompleteWordTimesAreRejected() throws Exception {
        for (String words : List.of(
                "[{\"text\":\"Hi\",\"begin_index\":0,\"end_index\":2,\"begin_time\":100,\"end_time\":20}]",
                "[{\"text\":\"Hi\",\"begin_index\":0,\"end_index\":2,\"begin_time\":0,\"end_time\":20}]",
                "[{\"text\":\"Wrong\",\"begin_index\":0,\"end_index\":2,\"begin_time\":0,\"end_time\":20}]")) {
            assertThrows(GenerationException.class, () -> QwenSpeechClient.align("Hi Sam", mapper.readTree(words)));
        }
    }

    @Test void identicalAudioFramesWithoutEventIdsArePreservedAndEmptySuccessCodeAccepted() throws Exception {
        String words = event(Map.of("code", "", "output", Map.of("sentence", Map.of("index", 0,
                "words", List.of(timing("Hi", 0, 2, 0, 100))))));
        String frame = event(Map.of("output", Map.of("audio", Map.of("data", "AAAA"))));
        String stop = event(Map.of("output", Map.of("finish_reason", "stop")));
        var speech = new QwenSpeechClient(settings, mapper, new GenerationHttpClient()).parse("Hi", words + frame + frame + stop);
        assertEquals(6, speech.audio().length);
        assertEquals(1, speech.words().size());
        var resent = new QwenSpeechClient(settings, mapper, new GenerationHttpClient()).parse("Hi",
                words + "id: 1\n" + frame + "id: 1\n" + frame + stop);
        assertEquals(3, resent.audio().length);
    }

    @Test void zeroDurationPunctuationDoesNotCreateFakeWords() throws Exception {
        JsonNode words = mapper.valueToTree(List.of(timing("Hi", 0, 2, 0, 100), timing("!", 2, 3, 100, 100)));
        var aligned = QwenSpeechClient.align("Hi!", words);
        assertEquals(1, aligned.size());
        assertEquals(0.1, aligned.getFirst().finish());
    }

    @Test void rejectsTimestampsBeyondActualAudio() throws Exception {
        qwenOverride = event(Map.of("output", Map.of("sentence", Map.of("index", 0,
                "words", List.of(timing("That's", 0, 6, 0, 1500), timing("Sam's", 7, 12, 1500, 2000),
                        timing("map", 13, 16, 2000, 2500))), "finish_reason", "stop", "audio", Map.of("url", base + "/audio"))));
        GenerationException error = assertThrows(GenerationException.class,
                () -> new StoryGenerationService(settings, mapper).generate(story, directory, ignored -> {}));
        assertTrue(error.getMessage().contains("实际音频时长"));
        assertFalse(Files.exists(directory.resolve("story.wav")));
    }

    @Test void placeholderAndOverlappingPunctuationDoesNotChangeWordClockOrSource() throws Exception {
        var words = mapper.valueToTree(List.of(
                timing("“", 0, 0, 0, 0), timing("Hi", 1, 3, 0, 100),
                timing(",", 0, 0, 0, 0), timing(" ", 3, 4, 0, 2000),
                timing("Sam", 5, 8, 100, 200), timing("!”", 0, 0, 0, 0)));
        var aligned = QwenSpeechClient.align("“Hi, Sam!”", words);
        assertEquals(List.of(new QwenSpeechClient.WordTiming(1, 3, 0, .1),
                new QwenSpeechClient.WordTiming(5, 8, .1, .2)), aligned);
    }

    @Test void splitContractionsAndHyphensKeepExactSourceRanges() throws Exception {
        var words = mapper.valueToTree(List.of(timing("Sam", 0, 3, 0, 50),
                timing("'", 0, 0, 0, 0), timing("s", 4, 5, 50, 100),
                timing("well", 6, 10, 100, 150), timing("-", 0, 0, 0, 0),
                timing("known", 11, 16, 150, 200)));
        assertEquals(List.of(new QwenSpeechClient.WordTiming(0, 5, 0, .1),
                new QwenSpeechClient.WordTiming(6, 16, .1, .2)),
                QwenSpeechClient.align("Sam's well-known", words));
    }

    @Test void ignoresPunctuationWithoutRelaxingRealWordOrderDurationIndicesOrCoverage() {
        for (var invalid : List.of(
                List.of(timing("Hi", 0, 2, 0, 100), timing(",", 0, 0, 0, 0), timing("Sam", 4, 7, 90, 200)),
                List.of(timing("Hi", 0, 2, 0, 100), timing("Sam", 4, 7, 100, 100)),
                List.of(timing("Hi", 0, 2, 0, 100), timing("Sam", 1, 7, 100, 200)),
                List.of(timing("Hi", 0, 2, 0, 100), timing("Sam", 4, 8, 100, 200)),
                List.of(timing("Hi", 0, 2, 0, 100), timing("!", 0, 0, 0, 0)),
                List.of(timing("Hi", 0, 2, 0, 100), timing("Wrong", 4, 7, 100, 200)))) {
            assertThrows(QwenSpeechClient.TimingException.class,
                    () -> QwenSpeechClient.align("Hi, Sam", mapper.valueToTree(invalid)));
        }
    }

    @Test void rejectsOverflowMissingFieldsNonFiniteAndCrossWordTokens() {
        for (var words : List.of(
                List.of(Map.of("text", "Hi", "begin_index", 4294967296L, "end_index", 2,
                        "begin_time", 0, "end_time", 100)),
                List.of(Map.of("text", "Hi", "begin_index", 0, "end_index", 2, "end_time", 100)),
                List.of(Map.of("text", "Hi", "begin_index", 0, "end_index", 2,
                        "begin_time", Double.NaN, "end_time", 100)),
                List.of(timing("Hi", 0, 2, -1, 100)),
                List.of(timing("HiSam", 0, 6, 0, 100)))) {
            assertThrows(QwenSpeechClient.TimingException.class,
                    () -> QwenSpeechClient.align("Hi Sam", mapper.valueToTree(words)));
        }
    }

    @Test void failureIncludesChunkAndSafeNumericalDiagnosticWithoutProviderTextOrUrl() throws Exception {
        qwenOverride = event(Map.of("output", Map.of("sentence", Map.of("index", 0,
                "words", List.of(timing("That's", 0, 6, 100, 20))), "finish_reason", "stop",
                "audio", Map.of("url", base + "/audio?signed=secret"), "untrusted", "fake-qwen")));
        var error = assertThrows(GenerationException.class,
                () -> new StoryGenerationService(settings, mapper).generate(story, directory, ignored -> {}));
        assertTrue(error.getMessage().contains("chunkIndex=0"));
        assertTrue(error.getMessage().contains("源UTF-16 [0,18)"));
        assertTrue(error.getMessage().contains("word_duration"));
        JsonNode diagnostic = mapper.readTree(directory.resolve("qwen-failure.json").toFile());
        assertEquals(0, diagnostic.path("chunkIndex").asInt());
        assertEquals(18, diagnostic.path("sourceEnd").asInt());
        assertEquals(100, diagnostic.at("/alignment/current/begin_time").asInt());
        assertEquals(20, diagnostic.at("/alignment/current/end_time").asInt());
        String json = diagnostic.toString();
        assertFalse(json.contains("That's"));
        assertFalse(json.contains("signed"));
        assertFalse(json.contains("fake-qwen"));
        assertFalse(json.contains(base));
        assertEquals(0, downloads.get());
        assertFalse(Files.exists(directory.resolve("story.wav")));
    }

    @Test void realHttpAndFfmpegAcceptPlaceholderPunctuationInFullStory() throws Exception {
        story = "Hi, Sam!";
        deepSeekJson = mapper.writeValueAsString(Map.of("voiceMap", Map.of("visitor", Map.of("gender", "unknown", "voiceId", "sam")), "segments", List.of(Map.of(
                "kind", "narration", "speakerId", "narrator", "text", story))));
        qwenOverride = event(Map.of("output", Map.of("sentence", Map.of("index", 0,
                "words", List.of(timing("Hi", 0, 2, 0, 100), timing(",", 0, 0, 0, 0),
                        timing("Sam", 4, 7, 100, 200), timing("!", 0, 0, 0, 0))),
                "finish_reason", "stop", "audio", Map.of("url", base + "/audio"))));
        var result = new StoryGenerationService(settings, mapper).generate(story, directory, ignored -> {});
        assertEquals(List.of("Hi", "Sam"), result.words().stream().map(TimedWord::text).toList());
        assertEquals(0.1, result.words().getLast().start());
        assertEquals(4, result.words().getLast().sourceStart());
        assertTrue(Files.exists(directory.resolve("story.wav")));
        assertFalse(Files.exists(directory.resolve("qwen-failure.json")));
    }

    @Test void sseWavFramesAvoidUnavailableSignedUrlAndPassActualFfmpeg() throws Exception {
        story = "Hi!";
        deepSeekJson = mapper.writeValueAsString(Map.of("voiceMap", Map.of("visitor", Map.of("gender", "unknown", "voiceId", "sam")), "segments", List.of(Map.of(
                "kind", "narration", "speakerId", "narrator", "text", story))));
        int split = 1001;
        qwenOverride = event(Map.of("output", Map.of("audio", Map.of("data", java.util.Base64.getEncoder()
                .encodeToString(java.util.Arrays.copyOfRange(wav, 0, split))))))
                + event(Map.of("output", Map.of("audio", Map.of("data", java.util.Base64.getEncoder()
                .encodeToString(java.util.Arrays.copyOfRange(wav, split, wav.length))))))
                + event(Map.of("output", Map.of("sentence", Map.of("index", 0, "words", List.of(timing("Hi", 0, 2, 0, 100))),
                "finish_reason", "stop", "audio", Map.of("url", "https://unreachable.invalid/audio?secret=never-download"))));
        var result = new StoryGenerationService(settings, mapper).generate(story, directory, ignored -> {});
        assertEquals(1.0, result.duration());
        assertEquals(0, downloads.get());
        assertTrue(Files.exists(directory.resolve("story.wav")));
    }

    @Test void failedQwenRetryAfterReopeningDatabaseSkipsBothDeepSeekCallsAndKeepsFailureStage() throws Exception {
        var jdbc = checkpointDatabase();
        var task = savedTask(jdbc);
        qwenStatus = 401;
        var repo = new SegmentationCheckpointRepository(jdbc);
        var stages = new ArrayList<String>();
        assertThrows(GenerationException.class, () -> new StoryGenerationService(settings, mapper, repo)
                .generate(story, attempt(task, 1), stages::add));
        assertEquals("SYNTHESIZING", stages.getLast());
        assertEquals("REVIEWING", jdbc.queryForObject("SELECT completed_stage FROM story_tasks", String.class));
        assertEquals(2, deepSeekRequests.size());
        String metadata = jdbc.queryForObject("SELECT checkpoint_metadata FROM story_tasks", String.class);
        assertFalse(metadata.contains("fake-deepseek"));
        assertFalse(metadata.contains(base));
        // A fresh repository/client with new JDBC connections models an application restart.
        qwenStatus = 200;
        stages.clear();
        var reopened = new org.springframework.jdbc.core.JdbcTemplate(dataSource());
        var result = new StoryGenerationService(settings, mapper, new SegmentationCheckpointRepository(reopened))
                .generate(story, attempt(task, 2), stages::add);
        assertEquals(story, result.text());
        assertEquals(List.of("SYNTHESIZING", "ASSEMBLING"), stages);
        assertEquals(2, deepSeekRequests.size());
        assertEquals("REVIEWING", reopened.queryForObject("SELECT reused_stage FROM story_tasks", String.class));
    }

    @Test void failedReviewReusesOnlyInitialAndConfigChangeInvalidatesCheckpoint() throws Exception {
        var jdbc = checkpointDatabase();
        var task = savedTask(jdbc);
        var repo = new SegmentationCheckpointRepository(jdbc);
        deepSeekFailAt = 2;
        assertThrows(GenerationException.class, () -> new StoryGenerationService(settings, mapper, repo)
                .generate(story, attempt(task, 1), ignored -> {}));
        assertEquals("SEGMENTING", jdbc.queryForObject("SELECT completed_stage FROM story_tasks", String.class));
        var stages = new ArrayList<String>();
        new StoryGenerationService(settings, mapper, repo).generate(story, attempt(task, 2), stages::add);
        assertEquals(3, deepSeekRequests.size());
        assertEquals(List.of("REVIEWING", "SYNTHESIZING", "ASSEMBLING"), stages);
        assertEquals("SEGMENTING", jdbc.queryForObject("SELECT reused_stage FROM story_tasks", String.class));
        var changed = GenerationSettings.from(new MockEnvironment().withProperty("STORY_LLM_PROVIDER", "deepseek").withProperty("DEEPSEEK_ENDPOINT", base + "/deepseek")
                .withProperty("QWEN_TTS_ENDPOINT", base + "/qwen").withProperty("STORY_FIRST_PERSON", "ben"), "fake-deepseek", "fake-qwen");
        new StoryGenerationService(changed, mapper, repo).generate(story, attempt(task, 3), ignored -> {});
        assertEquals(5, deepSeekRequests.size());
    }

    @Test void corruptReviewedCheckpointReusesValidatedInitialAndVoiceChangesKeepSegmentation() throws Exception {
        var jdbc = checkpointDatabase(); var task = savedTask(jdbc);
        var repo = new SegmentationCheckpointRepository(jdbc);
        new StoryGenerationService(settings, mapper, repo).generate(story, attempt(task, 1), ignored -> {});
        jdbc.update("UPDATE story_tasks SET reviewed_segments_json='[]'");
        deepSeekFailAt = 3;
        assertThrows(GenerationException.class, () -> new StoryGenerationService(settings, mapper, repo)
                .generate(story, attempt(task, 2), ignored -> {}));
        assertEquals("SEGMENTING", jdbc.queryForObject("SELECT completed_stage FROM story_tasks", String.class));
        assertNull(jdbc.queryForObject("SELECT reviewed_segments_json FROM story_tasks", String.class));
        new StoryGenerationService(settings, mapper, repo).generate(story, attempt(task, 3), ignored -> {});
        assertEquals(4, deepSeekRequests.size());
        var changedVoice = GenerationSettings.from(new MockEnvironment().withProperty("STORY_LLM_PROVIDER", "deepseek").withProperty("DEEPSEEK_ENDPOINT", base + "/deepseek")
                .withProperty("QWEN_TTS_ENDPOINT", base + "/qwen").withProperty("STORY_VOICE_BEN", "NewVoice"), "fake-deepseek", "fake-qwen");
        new StoryGenerationService(changedVoice, mapper, repo).generate(story, attempt(task, 4), ignored -> {});
        assertEquals(4, deepSeekRequests.size());
    }

    @Test void legacyReviewedImportSkipsDeepSeekAndRecordsUnknownHistoricalConfiguration() throws Exception {
        var jdbc = checkpointDatabase(); var task = savedTask(jdbc);
        var old = attempt(task, 1); Files.createDirectories(old);
        mapper.writeValue(old.resolve("segments-initial.json").toFile(), StorySegmenter.validateCasting(story, mapper.readTree(deepSeekJson)));
        Files.copy(old.resolve("segments-initial.json"), old.resolve("segments-reviewed.json"));
        var stages = new ArrayList<String>();
        new StoryGenerationService(settings, mapper, new SegmentationCheckpointRepository(jdbc))
                .generate(story, attempt(task, 2), stages::add);
        assertEquals(0, deepSeekRequests.size());
        assertEquals(List.of("SYNTHESIZING", "ASSEMBLING"), stages);
        assertEquals("LEGACY_ATTEMPT_1_CONFIG_UNKNOWN", jdbc.queryForObject("SELECT checkpoint_origin FROM story_tasks", String.class));
    }

    @Test void latestLegacyInitialWinsOverOlderReviewedAndRejectsInvalidReviewed() throws Exception {
        var jdbc = checkpointDatabase(); var task = savedTask(jdbc);
        var older = attempt(task, 1); Files.createDirectories(older);
        var latest = attempt(task, 2); Files.createDirectories(latest);
        var initial = StorySegmenter.validateCasting(story, mapper.readTree(deepSeekJson));
        mapper.writeValue(older.resolve("segments-reviewed.json").toFile(), initial);
        mapper.writeValue(latest.resolve("segments-initial.json").toFile(), initial);
        Files.writeString(latest.resolve("segments-reviewed.json"), "[{\"kind\":\"dialogue\",\"speakerId\":\"ben\",\"sourceStart\":0,\"sourceEnd\":999999}]");
        new StoryGenerationService(settings, mapper, new SegmentationCheckpointRepository(jdbc))
                .generate(story, attempt(task, 3), ignored -> {});
        assertEquals(1, deepSeekRequests.size());
        assertTrue(deepSeekRequests.getFirst().path("messages").get(1).path("content").asText().contains("previousSegments"));
        assertEquals("LEGACY_ATTEMPT_2_CONFIG_UNKNOWN", jdbc.queryForObject("SELECT checkpoint_origin FROM story_tasks", String.class));
    }

    @Test void sourceAndModelChangesAndCorruptInitialSafelyRedoBothRounds() throws Exception {
        var jdbc = checkpointDatabase(); var task = savedTask(jdbc);
        var repo = new SegmentationCheckpointRepository(jdbc);
        qwenStatus = 401;
        assertThrows(GenerationException.class, () -> new StoryGenerationService(settings, mapper, repo)
                .generate(story, attempt(task, 1), ignored -> {}));
        jdbc.update("UPDATE story_tasks SET initial_segments_json='broken',reviewed_segments_json='[]'");
        assertThrows(GenerationException.class, () -> new StoryGenerationService(settings, mapper, repo)
                .generate(story, attempt(task, 2), ignored -> {}));
        assertEquals(4, deepSeekRequests.size());
        story = story + " ";
        jdbc.update("UPDATE story_tasks SET source_text=?", story);
        deepSeekJson = mapper.writeValueAsString(Map.of("voiceMap", Map.of("visitor", Map.of("gender", "unknown", "voiceId", "sam")), "segments", List.of(Map.of("kind", "narration", "speakerId", "narrator", "text", story))));
        assertThrows(GenerationException.class, () -> new StoryGenerationService(settings, mapper, repo)
                .generate(story, attempt(task, 3), ignored -> {}));
        assertEquals(6, deepSeekRequests.size());
        var changedModel = GenerationSettings.from(new MockEnvironment().withProperty("STORY_LLM_PROVIDER", "deepseek").withProperty("DEEPSEEK_ENDPOINT", base + "/deepseek")
                .withProperty("QWEN_TTS_ENDPOINT", base + "/qwen").withProperty("DEEPSEEK_MODEL", "another-model"), "fake-deepseek", "fake-qwen");
        assertThrows(GenerationException.class, () -> new StoryGenerationService(changedModel, mapper, repo)
                .generate(story, attempt(task, 4), ignored -> {}));
        assertEquals(8, deepSeekRequests.size());
    }

    @Test void providerAndActiveModelOrEndpointChangesInvalidatePersistedCheckpoints() throws Exception {
        var jdbc = checkpointDatabase(); var task = savedTask(jdbc);
        var repo = new SegmentationCheckpointRepository(jdbc);
        qwenStatus = 401;
        assertThrows(GenerationException.class, () -> new StoryGenerationService(settings, mapper, repo)
                .generate(story, attempt(task, 1), ignored -> {}));
        assertEquals(2, deepSeekRequests.size());
        // Keep model and endpoint identical to prove the provider itself is part of the fingerprint.
        var env = new MockEnvironment().withProperty("STORY_LLM_PROVIDER", "bailian")
                .withProperty("QWEN_LLM_ENDPOINT", base + "/deepseek")
                .withProperty("QWEN_LLM_MODEL", settings.llmModel())
                .withProperty("QWEN_TTS_ENDPOINT", base + "/qwen");
        for (int attempt = 2; attempt <= 4; attempt++) {
            if (attempt == 3) env.withProperty("QWEN_LLM_MODEL", "qwen3.8-max");
            if (attempt == 4) env.withProperty("QWEN_LLM_ENDPOINT", base + "/deepseek?changed=true");
            var changed = GenerationSettings.from(env, null, "fake-qwen");
            int number = attempt;
            assertThrows(GenerationException.class, () -> new StoryGenerationService(changed, mapper, repo)
                    .generate(story, attempt(task, number), ignored -> {}));
            assertEquals(attempt * 2, deepSeekRequests.size());
        }
        // An inactive provider's model and endpoint must not invalidate Bailian's valid checkpoint.
        var inactiveChanged = GenerationSettings.from(env.withProperty("DEEPSEEK_MODEL", "unused")
                .withProperty("DEEPSEEK_ENDPOINT", "invalid"), null, "fake-qwen");
        assertThrows(GenerationException.class, () -> new StoryGenerationService(inactiveChanged, mapper, repo)
                .generate(story, attempt(task, 5), ignored -> {}));
        assertEquals(8, deepSeekRequests.size());
        String metadata = jdbc.queryForObject("SELECT checkpoint_metadata FROM story_tasks", String.class);
        assertEquals("bailian", mapper.readTree(metadata).path("provider").asText());
        assertEquals("qwen3.8-max", mapper.readTree(metadata).path("model").asText());
        assertFalse(metadata.contains(base));
        assertFalse(metadata.contains("fake-qwen"));
    }

    @Test void bailianDoesNotImportLegacyDeepSeekArtifactsWithUnknownConfiguration() throws Exception {
        var jdbc = checkpointDatabase(); var task = savedTask(jdbc);
        var old = attempt(task, 1); Files.createDirectories(old);
        mapper.writeValue(old.resolve("segments-reviewed.json").toFile(), StorySegmenter.validateCasting(story, mapper.readTree(deepSeekJson)));
        var bailian = GenerationSettings.from(new MockEnvironment().withProperty("STORY_LLM_PROVIDER", "bailian")
                .withProperty("QWEN_LLM_ENDPOINT", base + "/deepseek")
                .withProperty("QWEN_TTS_ENDPOINT", base + "/qwen"), null, "fake-qwen");
        qwenStatus = 401;
        assertThrows(GenerationException.class, () -> new StoryGenerationService(bailian, mapper,
                new SegmentationCheckpointRepository(jdbc)).generate(story, attempt(task, 2), ignored -> {}));
        assertEquals(2, deepSeekRequests.size());
        assertEquals("GENERATED", jdbc.queryForObject("SELECT checkpoint_origin FROM story_tasks", String.class));
    }

    @Test void lilyUsesFemaleVoiceAndNarrationUsesBethAfterCheckpointReload() throws Exception {
        String prefix = "Lily called me. ";
        String quote = "\"Hey! Are you busy this weekend?\"";
        String suffix = " she asked.";
        story = prefix + quote + suffix;
        deepSeekJson = mapper.writeValueAsString(Map.of("voiceMap",
                Map.of("lily", Map.of("gender", "female", "voiceId", "betty")), "segments", List.of(
                        Map.of("kind", "narration", "speakerId", "narrator", "text", prefix),
                        Map.of("kind", "dialogue", "speakerId", "lily", "text", quote),
                        Map.of("kind", "narration", "speakerId", "narrator", "text", suffix))));
        var jdbc = checkpointDatabase(); var task = savedTask(jdbc);
        qwenStatus = 401;
        assertThrows(GenerationException.class, () -> new StoryGenerationService(settings, mapper,
                new SegmentationCheckpointRepository(jdbc)).generate(story, attempt(task, 1), ignored -> {}));
        var saved = mapper.readTree(jdbc.queryForObject("SELECT reviewed_segments_json FROM story_tasks", String.class));
        assertEquals("female", saved.at("/voiceMap/lily/gender").asText());
        assertEquals("betty", saved.at("/voiceMap/lily/voiceId").asText());
        qwenStatus = 200; qwenRequests.clear();
        var result = new StoryGenerationService(settings, mapper,
                new SegmentationCheckpointRepository(new org.springframework.jdbc.core.JdbcTemplate(dataSource())))
                .generate(story, attempt(task, 2), ignored -> {});
        assertEquals(2, deepSeekRequests.size());
        assertEquals(story, result.text());
        assertEquals("lily", result.segments().get(1).speakerId());
        assertEquals("betty", result.voiceMap().get("lily").voiceId());
        assertTrue(qwenRequests.stream().anyMatch(r -> r.at("/input/text").asText().contains("weekend")));
        for (var request : qwenRequests) {
            String source = request.at("/input/text").asText();
            String expected = source.contains("Lily") || source.contains("she asked") ? "Beth_v3.1" : "Betty_v3.1";
            assertEquals(expected, request.at("/input/voice").asText(), source);
        }
        var timeline = mapper.readTree(attempt(task, 2).resolve("timeline.json").toFile());
        assertEquals("betty", timeline.at("/voiceMap/lily/voiceId").asText());
    }

    @Test void firstPersonFixedVoiceSurvivesSqliteReloadAndReachesRealHttpTts() throws Exception {
        story = "I smiled. “Hello!” I said.";
        deepSeekJson = mapper.writeValueAsString(Map.of("voiceMap",
                Map.of("lily", Map.of("gender", "female", "voiceId", "betty", "firstPerson", true)),
                "segments", List.of(
                        Map.of("kind", "narration", "speakerId", "narrator", "text", "I smiled. "),
                        Map.of("kind", "dialogue", "speakerId", "lily", "text", "“Hello!”"),
                        Map.of("kind", "narration", "speakerId", "narrator", "text", " I said."))));
        var jdbc = checkpointDatabase(); var task = savedTask(jdbc);
        qwenStatus = 401;
        assertThrows(GenerationException.class, () -> new StoryGenerationService(settings, mapper,
                new SegmentationCheckpointRepository(jdbc)).generate(story, attempt(task, 1), ignored -> {}));
        var saved = mapper.readTree(jdbc.queryForObject("SELECT reviewed_segments_json FROM story_tasks", String.class));
        assertTrue(saved.at("/voiceMap/lily/firstPerson").asBoolean());
        assertEquals("alex", saved.at("/voiceMap/lily/voiceId").asText());
        qwenStatus = 200; qwenRequests.clear();
        var result = new StoryGenerationService(settings, mapper,
                new SegmentationCheckpointRepository(new org.springframework.jdbc.core.JdbcTemplate(dataSource())))
                .generate(story, attempt(task, 2), ignored -> {});
        assertEquals(2, deepSeekRequests.size());
        assertEquals(story, result.text());
        assertEquals(new StoryCasting.CharacterVoice("female", "alex", true), result.voiceMap().get("lily"));
        assertEquals("lily", result.segments().get(1).speakerId());
        assertTrue(qwenRequests.stream().anyMatch(r -> r.at("/input/text").asText().contains("Hello")));
        for (var request : qwenRequests) {
            String text = request.at("/input/text").asText();
            assertEquals(text.contains("Hello") ? "Andy_v3.1" : "Beth_v3.1", request.at("/input/voice").asText());
        }
        var timeline = mapper.readTree(attempt(task, 2).resolve("timeline.json").toFile());
        assertTrue(timeline.at("/voiceMap/lily/firstPerson").asBoolean());
    }

    @Test void preVoiceMapCheckpointFilesCannotSilentlyRestoreSamFallback() throws Exception {
        var jdbc = checkpointDatabase(); var task = savedTask(jdbc);
        var old = attempt(task, 1); Files.createDirectories(old);
        // This is the actual old file format: source ranges with no voiceMap.
        mapper.writeValue(old.resolve("segments-reviewed.json").toFile(), StorySegmenter.validate(story, mapper.readTree(deepSeekJson)));
        new StoryGenerationService(settings, mapper, new SegmentationCheckpointRepository(jdbc))
                .generate(story, attempt(task, 2), ignored -> {});
        assertEquals(2, deepSeekRequests.size());
        assertEquals("GENERATED", jdbc.queryForObject("SELECT checkpoint_origin FROM story_tasks", String.class));
    }

    @Test void preFirstPersonCheckpointCannotRestoreAnInferredProtagonistVoice() throws Exception {
        var jdbc = checkpointDatabase(); var task = savedTask(jdbc);
        var old = attempt(task, 1); Files.createDirectories(old);
        ObjectNode legacy = mapper.valueToTree(StorySegmenter.validateCasting(story, mapper.readTree(deepSeekJson)));
        for (var voice : legacy.path("voiceMap")) ((ObjectNode) voice).remove("firstPerson");
        mapper.writeValue(old.resolve("segments-reviewed.json").toFile(), legacy);
        new StoryGenerationService(settings, mapper, new SegmentationCheckpointRepository(jdbc))
                .generate(story, attempt(task, 2), ignored -> {});
        assertEquals(2, deepSeekRequests.size());
        assertEquals("GENERATED", jdbc.queryForObject("SELECT checkpoint_origin FROM story_tasks", String.class));
    }

    private org.sqlite.SQLiteDataSource dataSource() {
        var source = new org.sqlite.SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + directory.resolve("checkpoints.db")); return source;
    }
    private org.springframework.jdbc.core.JdbcTemplate checkpointDatabase() {
        var source = dataSource();
        new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("schema.sql")).execute(source);
        return new org.springframework.jdbc.core.JdbcTemplate(source);
    }
    private java.util.UUID savedTask(org.springframework.jdbc.core.JdbcTemplate jdbc) {
        var id = java.util.UUID.randomUUID(); var now = java.time.Instant.now();
        new com.englishpods.task.repository.TaskRepository(jdbc).save(new com.englishpods.task.model.StoryTask(id, "test", story,
                com.englishpods.task.model.TaskStatus.CREATED, now, now));
        return id;
    }
    private Path attempt(java.util.UUID task, int number) { return directory.resolve("audio").resolve(task.toString()).resolve(Integer.toString(number)); }

    private String stream(String text) throws IOException {
        List<Map<String,Object>> words = new ArrayList<>();
        int i = 0;
        var matcher = StoryText.WORD.matcher(text);
        while (matcher.find()) {
            String word = matcher.group();
            int apostrophe = Math.max(word.indexOf('\''), word.indexOf('’'));
            if (apostrophe > 0) {
                words.add(timing(word.substring(0, apostrophe), matcher.start(), matcher.start() + apostrophe, 20 + i * 100, 60 + i * 100));
                words.add(timing(word.substring(apostrophe), matcher.start() + apostrophe, matcher.end(), 60 + i * 100, 100 + i * 100));
            } else words.add(timing(word, matcher.start(), matcher.end(), 20 + i * 100, 100 + i * 100));
            i++;
        }
        String first = event(Map.of("output", Map.of("type", "sentence-end", "original_text", text,
                "sentence", Map.of("index", 0, "words", words), "audio", Map.of())));
        String finish = event(Map.of("output", Map.of("finish_reason", "stop", "audio", Map.of("url", base + "/audio?signed=secret"))));
        return first + first + finish;
    }

    private Map<String,Object> timing(String word, int start, int end, int begin, int finish) {
        return Map.of("text", word, "begin_index", start, "end_index", end, "begin_time", begin, "end_time", finish);
    }
    private String event(Object data) throws IOException { return "data: " + mapper.writeValueAsString(data) + "\n\n"; }
    private void respond(HttpExchange exchange, int status, String body, String type) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes); exchange.close();
    }
}
