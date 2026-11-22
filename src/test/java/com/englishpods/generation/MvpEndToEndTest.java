package com.englishpods.generation;

import com.englishpods.task.service.TaskWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariDataSource;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Local HTTP providers, actual FFmpeg, actual SQLite, and the production task worker. */
@SpringBootTest(properties = "story.worker.enabled=false")
@AutoConfigureMockMvc
@Import(MvpEndToEndTest.Providers.class)
@DirtiesContext
class MvpEndToEndTest {
    @TempDir static Path directory;
    static HttpServer server;
    static final AtomicBoolean failQwen = new AtomicBoolean();
    static final AtomicBoolean failDeepSeek = new AtomicBoolean();
    static final AtomicBoolean zeroDurationQwen = new AtomicBoolean();
    static final AtomicInteger deepSeekCalls = new AtomicInteger();
    static final AtomicInteger ttsCalls = new AtomicInteger();
    static final AtomicBoolean downloadHadCredential = new AtomicBoolean();
    static final ObjectMapper json = new ObjectMapper();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + directory.resolve("end-to-end.db"));
        registry.add("story.audio-dir", () -> directory.resolve("audio").toString());
    }

    @TestConfiguration
    static class Providers {
        @Bean @Primary
        StoryGenerationService localProviders(ObjectMapper mapper, SegmentationCheckpointRepository checkpoints) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/deepseek", exchange -> {
                deepSeekCalls.incrementAndGet();
                exchange.getRequestBody().readAllBytes();
                byte[] body;
                int status;
                if (failDeepSeek.get()) {
                    status = 401;
                    body = "secret-provider-body-must-never-reach-client".getBytes(StandardCharsets.UTF_8);
                } else {
                    status = 200;
                    String content = json.writeValueAsString(Map.of("voiceMap", Map.of(), "segments", List.of(Map.of(
                            "kind", "narration", "speakerId", "narrator", "text", "Hello world."))));
                    body = json.writeValueAsBytes(Map.of("choices", List.of(Map.of(
                            "finish_reason", "stop", "message", Map.of("content", content)))));
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.createContext("/qwen", exchange -> {
                ttsCalls.incrementAndGet();
                if (failQwen.get()) {
                    exchange.getRequestBody().readAllBytes();
                    exchange.sendResponseHeaders(401, -1); exchange.close(); return;
                }
                var request = json.readTree(exchange.getRequestBody());
                if (!request.path("input").path("word_timestamp_enabled").asBoolean()
                        || !"enable".equals(exchange.getRequestHeaders().getFirst("X-DashScope-SSE"))) {
                    exchange.sendResponseHeaders(400, -1); exchange.close(); return;
                }
                String text = request.at("/input/text").asText();
                var words = new java.util.ArrayList<Map<String, Object>>();
                if (text.startsWith("Hello")) {
                    words.add(Map.of("text", "Hello", "begin_index", 0, "end_index", 5, "begin_time", 50,
                            "end_time", zeroDurationQwen.get() ? 50 : 350));
                }
                if (text.endsWith("world.")) {
                    int start = text.indexOf("world");
                    words.add(Map.of("text", "world", "begin_index", start, "end_index", start + 5,
                            "begin_time", 400, "end_time", 900));
                }
                String first = json.writeValueAsString(Map.of("output", Map.of("type", "sentence-end",
                        "original_text", text, "sentence", Map.of("index", 0, "words", words))));
                String last = json.writeValueAsString(Map.of("output", Map.of("finish_reason", "stop",
                        "audio", Map.of("url", base() + "/audio"))));
                byte[] body = (": keep-alive\r\n\r\ndata: " + first + "\r\n\r\ndata: " + last + "\r\n\r\n")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.createContext("/audio", exchange -> {
                downloadHadCredential.set(exchange.getRequestHeaders().containsKey("Authorization"));
                byte[] body = silentWav();
                exchange.getResponseHeaders().set("Content-Type", "audio/wav");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return new StoryGenerationService(new GenerationSettings("test-only-deepseek", "test-only-qwen",
                    base() + "/deepseek", "deepseek-flash", base() + "/qwen", "qwen-audio-3.1-tts-flash", "ffmpeg",
                    Map.of("alex", "Andy_v3.1", "ben", "Brian_v3.1", "maya", "Abby_v3.1",
                            "june", "Annie_v3.1", "sam", "David_v3.1", "narrator", "Beth_v3.1", "betty", "Betty_v3.1"), "alex", 120,
                    "deepseek", "", "qwen3.8-max"), mapper, checkpoints);
        }
    }

    static String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    static byte[] silentWav() {
        ByteBuffer wav = ByteBuffer.allocate(44 + 48_000).order(ByteOrder.LITTLE_ENDIAN);
        wav.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + 48_000);
        wav.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1);
        wav.putInt(24_000).putInt(48_000).putShort((short) 2).putShort((short) 16);
        wav.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(48_000);
        return wav.array();
    }

    @Autowired MockMvc mvc;
    @Autowired TaskWorker worker;
    @Autowired JdbcTemplate jdbc;

    @AfterAll
    static void close(@Autowired HikariDataSource dataSource) {
        if (server != null) server.stop(0);
        dataSource.close();
    }

    @Test
    void savesRunsAndStreamsACompleteStoryThenRetriesARealHttpFailure() throws Exception {
        mvc.perform(get("/api/config")).andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(true));
        String id = create();
        complete(id);
        assertThat(deepSeekCalls.get()).isEqualTo(2);
        assertThat(ttsCalls.get()).isEqualTo(1);
        assertThat(downloadHadCredential.get()).isFalse();
        String result = mvc.perform(get("/api/tasks/" + id + "/result"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.text").value("Hello world."))
                .andExpect(jsonPath("$.words.length()").value(2))
                .andExpect(jsonPath("$.words[0].text").value("Hello"))
                .andExpect(jsonPath("$.words[0].start").value(0.05))
                .andExpect(jsonPath("$.audioUrl").value("/api/tasks/" + id + "/audio"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(result).path("duration").asDouble()).isGreaterThanOrEqualTo(1.0);
        assertThat(jdbc.queryForObject("SELECT result_json FROM story_tasks WHERE id=?", String.class, id))
                .contains("Hello world.").doesNotContain("test-only-");
        byte[] firstBytes = mvc.perform(get("/api/tasks/" + id + "/audio").header("Range", "bytes=0-43"))
                .andExpect(status().isPartialContent()).andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(firstBytes, 0, 4, StandardCharsets.US_ASCII)).isEqualTo("RIFF");
        assertThat(firstBytes).hasSize(44);

        String retryId = create();
        failDeepSeek.set(true);
        mvc.perform(post("/api/tasks/" + retryId + "/generate")).andExpect(status().isAccepted());
        assertThat(worker.processNext()).isTrue();
        String failed = mvc.perform(get("/api/tasks/" + retryId))
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(failed).doesNotContain("secret-provider-body", "test-only-");
        failDeepSeek.set(false);
        complete(retryId);
        mvc.perform(get("/api/tasks/" + retryId)).andExpect(jsonPath("$.attempt").value(2));

        String qwenRetry = create();
        failQwen.set(true);
        mvc.perform(post("/api/tasks/" + qwenRetry + "/generate")).andExpect(status().isAccepted());
        worker.processNext();
        mvc.perform(get("/api/tasks/" + qwenRetry)).andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.stage").value("SYNTHESIZING"))
                .andExpect(jsonPath("$.completedStage").value("REVIEWING"));
        int beforeRetry = deepSeekCalls.get();
        failQwen.set(false);
        complete(qwenRetry);
        assertThat(deepSeekCalls.get()).isEqualTo(beforeRetry);
        mvc.perform(get("/api/tasks/" + qwenRetry)).andExpect(jsonPath("$.reusedStage").value("REVIEWING"))
                .andExpect(jsonPath("$.checkpointOrigin").value("GENERATED"));

        String exhausted = create();
        int beforeExhaustion = ttsCalls.get();
        zeroDurationQwen.set(true);
        try {
            mvc.perform(post("/api/tasks/" + exhausted + "/generate")).andExpect(status().isAccepted());
            assertThat(worker.processNext()).isTrue();
        } finally { zeroDurationQwen.set(false); }
        assertThat(ttsCalls.get() - beforeExhaustion).isEqualTo(6);
        mvc.perform(get("/api/tasks/" + exhausted)).andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.stage").value("SYNTHESIZING"))
                .andExpect(jsonPath("$.attempt").value(1));
        String persisted = jdbc.queryForObject("SELECT error FROM story_tasks WHERE id=?", String.class, exhausted);
        assertThat(persisted).contains("chunkIndex=0", "源UTF-16 [0,12)", "子片段源UTF-16 [0,6)",
                "累计发起 6 次合成", "word_duration", "当前块已尝试 3 次合成", "seed=[0, 1, 2]")
                .doesNotContain("Hello", "test-only-", base());
        Path attempt = directory.resolve("audio").resolve(exhausted).resolve("1");
        var diagnostic = json.readTree(attempt.resolve("qwen-failure.json").toFile());
        assertThat(diagnostic.path("sourceEnd").asInt()).isEqualTo(12);
        assertThat(diagnostic.path("failedSourceEnd").asInt()).isEqualTo(6);
        assertThat(diagnostic.at("/synthesisRanges/0/seeds").toString()).isEqualTo("[0,1,2]");
        assertThat(diagnostic.at("/synthesisRanges/1/sourceEnd").asInt()).isEqualTo(6);
        assertThat(diagnostic.at("/alignment/synthesisAttempts").asInt()).isEqualTo(3);
        assertThat(diagnostic.at("/alignment/seeds").toString()).isEqualTo("[0,1,2]");
        assertThat(diagnostic.at("/alignment/current/begin_time").asInt()).isEqualTo(50);
        assertThat(diagnostic.at("/alignment/current/end_time").asInt()).isEqualTo(50);
        assertThat(Files.exists(attempt.resolve("chunk-0.wav"))).isFalse();
        assertThat(Files.exists(attempt.resolve("chunk-0-part-0.wav"))).isFalse();
        assertThat(Files.exists(attempt.resolve("story.wav"))).isFalse();
        assertThat(Files.exists(attempt.resolve("timeline.json"))).isFalse();
    }

    private String create() throws Exception {
        String response = mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Integration test\",\"text\":\"Hello world.\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response).path("id").asText();
    }

    private void complete(String id) throws Exception {
        mvc.perform(post("/api/tasks/" + id + "/generate")).andExpect(status().isAccepted());
        mvc.perform(post("/api/tasks/" + id + "/generate")).andExpect(status().isConflict());
        assertThat(worker.processNext()).isTrue();
        mvc.perform(get("/api/tasks/" + id)).andExpect(jsonPath("$.status").value("COMPLETED"));
    }
}
