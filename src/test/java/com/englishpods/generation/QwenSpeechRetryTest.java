package com.englishpods.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QwenSpeechRetryTest {
    private static final String SOURCE = "These cats make gardens in the sun, with a lake beneath home. ";
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final List<String> responses = new ArrayList<>();
    private final AtomicInteger downloads = new AtomicInteger();
    private HttpServer server;
    private GenerationSettings settings;
    private String base;
    private ArrayNode raw;

    @BeforeEach void setup() throws Exception {
        // Synthetic timing data exercises a zero-duration word.
        try (var fixture = getClass().getResourceAsStream("/qwen/zero-duration-failure.json")) {
            raw = (ArrayNode) mapper.readTree(fixture).at("/alignment/timings");
        }
        var ranges = StoryText.words(SOURCE);
        for (int i = 0; i < ranges.size(); i++) {
            var range = ranges.get(i);
            ((ObjectNode) raw.get(i)).put("text", SOURCE.substring(range.start(), range.end()));
        }
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        settings = GenerationSettings.from(new MockEnvironment().withProperty("QWEN_TTS_ENDPOINT", base + "/qwen"),
                "test-deepseek", "test-qwen");
        server.createContext("/qwen", exchange -> {
            requests.add(mapper.readTree(exchange.getRequestBody()));
            byte[] body = responses.get(Math.min(requests.size() - 1, responses.size() - 1)).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.createContext("/audio", exchange -> {
            downloads.incrementAndGet();
            exchange.sendResponseHeaders(500, -1); exchange.close();
        });
        server.start();
    }

    @AfterEach void close() { server.stop(0); }

    @Test void syntheticSnapshotStillRejectsZeroDurationRealWord() {
        var failure = assertThrows(QwenSpeechClient.TimingException.class, () -> QwenSpeechClient.align(SOURCE, raw));
        var diagnostic = mapper.valueToTree(failure.diagnostic());
        assertTrue(failure.zeroDuration());
        assertEquals("word_duration", diagnostic.path("condition").asText());
        assertEquals(5, diagnostic.path("tokenIndex").asInt());
        assertEquals(62, diagnostic.path("sourceLength").asInt());
        assertEquals(22, diagnostic.path("lexicalCursor").asInt());
        assertEquals(1.28, diagnostic.path("previousEndSeconds").asDouble());
        assertEquals(5, diagnostic.path("previousEndIndex").asInt());
        assertEquals(3, diagnostic.at("/current/lexicalLength").asInt());
        assertEquals(5, diagnostic.at("/current/begin_index").asInt());
        assertEquals(6, diagnostic.at("/current/end_index").asInt());
        assertEquals(1280, diagnostic.at("/current/begin_time").asInt());
        assertEquals(1280, diagnostic.at("/current/end_time").asInt());
    }

    @Test void retriesZeroDurationWithNewSeedAndReturnsOnlySuccessfulAudioAndTiming() throws Exception {
        responses.add(stream(raw, new byte[]{99, 98, 97}));
        var valid = positiveDuration();
        responses.add(stream(valid, new byte[]{1, 2, 3, 4}));
        var speech = client().synthesize(SOURCE, "Emily_v3.1");
        assertArrayEquals(new byte[]{1, 2, 3, 4}, speech.audio());
        assertEquals(12, speech.words().size());
        assertEquals("the", SOURCE.substring(speech.words().get(5).start(), speech.words().get(5).end()));
        assertEquals(1.4, speech.words().get(5).finish());
        assertSeedsAndUnchangedInputs(List.of(0, 1));
        assertEquals(0, downloads.get());
    }

    @Test void consecutiveZeroDurationsStopAfterThreeAndRetainFinalStructuredDiagnostic() throws Exception {
        responses.add(stream(raw, null));
        var failure = assertThrows(QwenSpeechClient.TimingException.class, () -> client().synthesize(SOURCE, "Emily_v3.1"));
        assertSeedsAndUnchangedInputs(List.of(0, 1, 2));
        assertEquals(3, failure.diagnostic().get("synthesisAttempts"));
        assertEquals(List.of(0, 1, 2), failure.diagnostic().get("seeds"));
        assertTrue(failure.getMessage().contains("当前块已尝试 3 次合成"));
        assertTrue(failure.getMessage().contains("token=5"));
        assertEquals(0, downloads.get());
        String diagnostic = mapper.writeValueAsString(failure.diagnostic());
        assertFalse(diagnostic.contains("\"text\""));
        assertFalse(diagnostic.contains("\"the\""));
        assertFalse(failure.getMessage().contains(base));
    }

    @Test void thirdSynthesisCanSucceedWithSeedTwo() throws Exception {
        responses.add(stream(raw, null));
        responses.add(stream(raw, null));
        responses.add(stream(positiveDuration(), new byte[]{4, 5}));
        assertArrayEquals(new byte[]{4, 5}, client().synthesize(SOURCE, "Emily_v3.1").audio());
        assertSeedsAndUnchangedInputs(List.of(0, 1, 2));
        assertEquals(0, downloads.get());
    }

    @Test void limitsSplitsPerOriginalChunkEvenWhenLaterSubrangeAlsoHasZeroDuration() throws Exception {
        String source = "A B C D E F G H.";
        for (String piece : List.of(source, "A B C D ", "A B ", "C D ", "E F G H.")) {
            responses.add(timingStream(piece, true));
            responses.add(timingStream(piece, true));
            responses.add(timingStream(piece, !List.of("A B ", "C D ").contains(piece)));
        }
        var failure = assertThrows(QwenSpeechClient.PartSynthesisException.class,
                () -> client().synthesizeParts(source, "Emily_v3.1"));
        assertEquals(new StoryText.Range(8, source.length()), failure.range());
        assertEquals(5, failure.attempts().size());
        assertEquals(List.of(0, 1, 2, 0, 1, 2, 0, 1, 2, 0, 1, 2, 0, 1, 2), requests.stream()
                .map(request -> request.at("/input/seed").asInt()).toList());
        for (var attempt : failure.attempts()) assertEquals(List.of(0, 1, 2), attempt.seeds());
        assertEquals(List.of(source, "A B C D ", "A B ", "C D ", "E F G H."),
                failure.attempts().stream().map(attempt -> source.substring(attempt.range().start(), attempt.range().end())).toList());
        assertEquals("word_duration", failure.alignment().get("condition"));
        assertEquals(0, downloads.get());
    }

    @Test void midpointSplitPreservesUtf16SubstringsAndWordsWhenThereIsNoPunctuation() throws Exception {
        String left = "😀 One isn’t 😀 ";
        String right = "two re-done.";
        String source = left + right;
        for (int i = 0; i < 3; i++) responses.add(timingStream(source, true));
        responses.add(timingStream(left, false));
        responses.add(timingStream(right, false));
        var synthesis = client().synthesizeParts(source, "Emily_v3.1");
        assertEquals(List.of(left, right), synthesis.parts().stream()
                .map(part -> source.substring(part.range().start(), part.range().end())).toList());
        assertEquals(left.length(), synthesis.parts().getLast().range().start());
        assertEquals(List.of(source, source, source, left, right), requests.stream()
                .map(request -> request.at("/input/text").asText()).toList());
        assertEquals("isn’t", left.substring(synthesis.parts().getFirst().speech().words().getLast().start(),
                synthesis.parts().getFirst().speech().words().getLast().end()));
        assertEquals("re-done", right.substring(synthesis.parts().getLast().speech().words().getLast().start(),
                synthesis.parts().getLast().speech().words().getLast().end()));
    }

    @Test void doesNotSplitJoinedNumberWithoutAWordBoundary() throws Exception {
        responses.add(timingStream("2,008.", true));
        var failure = assertThrows(QwenSpeechClient.PartSynthesisException.class,
                () -> client().synthesizeParts("2,008.", "Emily_v3.1"));
        assertEquals(new StoryText.Range(0, 6), failure.range());
        assertEquals(3, requests.size());
        assertEquals(1, failure.attempts().size());
    }

    @Test void nonTimingFailureInFallbackPartStopsBeforeItsSibling() throws Exception {
        for (int i = 0; i < 3; i++) responses.add(stream(raw, null));
        responses.add("data: {\"output\":{\"finish_reason\":\"stop\"}}\n\n");
        var failure = assertThrows(QwenSpeechClient.PartSynthesisException.class,
                () -> client().synthesizeParts(SOURCE, "Emily_v3.1"));
        assertEquals(new StoryText.Range(0, 36), failure.range());
        assertTrue(failure.getMessage().contains("未返回词时间戳"));
        assertTrue(failure.alignment().isEmpty());
        assertEquals(List.of(0), failure.attempts().getLast().seeds());
        assertEquals(4, requests.size());
        assertEquals(0, downloads.get());
    }

    @Test void negativeDurationIsTerminalWithoutResynthesis() throws Exception {
        var negative = raw.deepCopy();
        ((ObjectNode) negative.get(5)).put("end_time", 1279);
        responses.add(stream(negative, null));
        var failure = assertThrows(QwenSpeechClient.TimingException.class, () -> client().synthesize(SOURCE, "Emily_v3.1"));
        assertFalse(failure.zeroDuration());
        assertEquals("word_duration", failure.diagnostic().get("condition"));
        assertSeedsAndUnchangedInputs(List.of(0));
        assertEquals(0, downloads.get());
    }

    @Test void lexicalMismatchAndTimeOrderFailuresDoNotTriggerResynthesis() throws Exception {
        for (String condition : List.of("lexical_mismatch", "time_order")) {
            requests.clear(); responses.clear();
            var invalid = positiveDuration();
            if (condition.equals("lexical_mismatch")) ((ObjectNode) invalid.get(5)).put("text", "wrong");
            else ((ObjectNode) invalid.get(5)).put("begin_time", 1279);
            responses.add(stream(invalid, null));
            var failure = assertThrows(QwenSpeechClient.TimingException.class, () -> client().synthesize(SOURCE, "Emily_v3.1"));
            assertEquals(condition, failure.diagnostic().get("condition"));
            assertSeedsAndUnchangedInputs(List.of(0));
        }
        assertEquals(0, downloads.get());
    }

    @Test void nonTargetFailureAfterAZeroDurationStopsAtThatAttempt() throws Exception {
        responses.add(stream(raw, null));
        var wrong = positiveDuration();
        ((ObjectNode) wrong.get(5)).put("text", "wrong");
        responses.add(stream(wrong, null));
        var failure = assertThrows(QwenSpeechClient.TimingException.class, () -> client().synthesize(SOURCE, "Emily_v3.1"));
        assertEquals("lexical_mismatch", failure.diagnostic().get("condition"));
        assertEquals(2, failure.diagnostic().get("synthesisAttempts"));
        assertSeedsAndUnchangedInputs(List.of(0, 1));
        assertEquals(0, downloads.get());
    }

    @Test void interruptBeforeSynthesisMakesNoRequestAndPreservesInterruptFlag() {
        Thread.currentThread().interrupt();
        try {
            assertTrue(assertThrows(GenerationException.class, () -> client().synthesize(SOURCE, "Emily_v3.1"))
                    .getMessage().contains("中断"));
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(requests.isEmpty());
        } finally { Thread.interrupted(); }
    }

    @Test void interruptAfterResponseStopsBeforeRetryOrDownload() throws Exception {
        var http = mock(GenerationHttpClient.class);
        byte[] response = stream(raw, null).getBytes(StandardCharsets.UTF_8);
        when(http.post(anyString(), anyString(), anyString(), eq(true), eq("Qwen"))).thenAnswer(invocation -> {
            Thread.currentThread().interrupt(); return response;
        });
        try {
            var failure = assertThrows(GenerationException.class,
                    () -> new QwenSpeechClient(settings, mapper, http).synthesize(SOURCE, "Emily_v3.1"));
            assertTrue(failure.getMessage().contains("中断"));
            assertTrue(Thread.currentThread().isInterrupted());
            verify(http, times(1)).post(anyString(), anyString(), anyString(), eq(true), eq("Qwen"));
            verify(http, never()).download(anyString());
        } finally { Thread.interrupted(); }
    }

    @Test void interruptDuringFirstSplitSubrangeStopsBeforeAnyFurtherRequests() throws Exception {
        var http = mock(GenerationHttpClient.class);
        byte[] response = stream(raw, null).getBytes(StandardCharsets.UTF_8);
        var calls = new AtomicInteger();
        when(http.post(anyString(), anyString(), anyString(), eq(true), eq("Qwen"))).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == 4) Thread.currentThread().interrupt();
            return response;
        });
        try {
            var failure = assertThrows(QwenSpeechClient.PartSynthesisException.class,
                    () -> new QwenSpeechClient(settings, mapper, http).synthesizeParts(SOURCE, "Emily_v3.1"));
            assertTrue(failure.getMessage().contains("中断"));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(List.of(0), failure.attempts().getLast().seeds());
            assertEquals(4, calls.get());
            verify(http, never()).download(anyString());
        } finally { Thread.interrupted(); }
    }

    private QwenSpeechClient client() { return new QwenSpeechClient(settings, mapper, new GenerationHttpClient()); }

    private ArrayNode positiveDuration() {
        var valid = raw.deepCopy();
        ((ObjectNode) valid.get(5)).put("end_time", 1400);
        ((ObjectNode) valid.get(6)).put("begin_time", 1400);
        return valid;
    }

    private String stream(ArrayNode words, byte[] audio) throws Exception {
        return stream(SOURCE, words, audio);
    }

    private String timingStream(String source, boolean zeroDuration) throws Exception {
        var words = mapper.createArrayNode();
        int i = 0;
        for (var range : StoryText.words(source)) {
            words.addObject().put("text", source.substring(range.start(), range.end()))
                    .put("begin_index", range.start()).put("end_index", range.end())
                    .put("begin_time", i * 100).put("end_time", i * 100 + (zeroDuration && i == 0 ? 0 : 80));
            i++;
        }
        return stream(source, words, zeroDuration ? null : new byte[]{1, 2, 3, 4});
    }

    private String stream(String source, ArrayNode words, byte[] audio) throws Exception {
        var payload = Map.of("output", Map.of("sentence", Map.of("index", 0, "words", words),
                "original_text", source, "finish_reason", "stop", "audio", audio == null
                        ? Map.of("url", base + "/audio?signed=never-download")
                        : Map.of("data", Base64.getEncoder().encodeToString(audio))));
        return "data: " + mapper.writeValueAsString(payload) + "\n\n";
    }

    private void assertSeedsAndUnchangedInputs(List<Integer> seeds) {
        assertEquals(seeds, requests.stream().map(r -> r.at("/input/seed").asInt()).toList());
        var expected = requests.getFirst().deepCopy();
        ((ObjectNode) expected.path("input")).remove("seed");
        for (var request : requests) {
            assertEquals(SOURCE, request.at("/input/text").asText());
            assertEquals("Emily_v3.1", request.at("/input/voice").asText());
            var withoutSeed = request.deepCopy();
            ((ObjectNode) withoutSeed.path("input")).remove("seed");
            assertEquals(expected, withoutSeed);
        }
    }
}
