package com.englishpods.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class StoryLlmClientTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<JsonNode> requests = new CopyOnWriteArrayList<>();
    private final List<String> paths = new CopyOnWriteArrayList<>();
    private final List<String> authorization = new CopyOnWriteArrayList<>();
    private HttpServer server;
    private String base;
    private String story = "I smiled. 😀 “Hi!”";
    private String content;
    private String finishReason = "stop";
    private int status = 200;

    @BeforeEach void start() throws Exception {
        content = mapper.writeValueAsString(Map.of("segments", List.of(
                Map.of("kind", "narration", "speakerId", "narrator", "text", "I smiled. 😀 "),
                Map.of("kind", "dialogue", "speakerId", "alex", "text", "“Hi!”"))));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", exchange -> {
            requests.add(mapper.readTree(exchange.getRequestBody()));
            paths.add(exchange.getRequestURI().getPath());
            authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = (status == 200 ? mapper.writeValueAsString(Map.of("choices", List.of(Map.of(
                    "finish_reason", finishReason, "message", Map.of("content", content)))))
                    : "fake-deepseek fake-qwen signed-url-secret").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach void stop() { server.stop(0); }

    private GenerationSettings settings(String provider) {
        return GenerationSettings.from(new MockEnvironment()
                .withProperty("STORY_LLM_PROVIDER", provider)
                .withProperty("DEEPSEEK_ENDPOINT", base + "/deepseek")
                .withProperty("DEEPSEEK_MODEL", "configured-deepseek-model")
                .withProperty("QWEN_LLM_ENDPOINT", base + "/bailian/chat/completions")
                .withProperty("QWEN_LLM_MODEL", "qwen3.8-max")
                .withProperty("STORY_FIRST_PERSON", "ben"), "fake-deepseek", "fake-qwen");
    }

    @ParameterizedTest @ValueSource(strings = {"deepseek", "bailian"})
    void bothRoundsUseSelectedEndpointKeyModelAndPreserveUtf16Source(String provider) throws Exception {
        var config = settings(provider);
        var segmenter = new StorySegmenter(config, mapper, new GenerationHttpClient());
        var initial = segmenter.segment(story, null);
        var reviewed = segmenter.segment(story, initial);
        assertEquals(initial, reviewed);
        assertEquals(story.length(), reviewed.getLast().sourceEnd());
        assertEquals("“Hi!”", story.substring(reviewed.getLast().sourceStart(), reviewed.getLast().sourceEnd()));
        assertEquals(List.of(config.usesBailian() ? "/bailian/chat/completions" : "/deepseek",
                config.usesBailian() ? "/bailian/chat/completions" : "/deepseek"), paths);
        assertEquals(List.of("Bearer " + config.llmKey(), "Bearer " + config.llmKey()), authorization);
        for (JsonNode request : requests) {
            assertEquals(config.llmModel(), request.path("model").asText());
            assertEquals("json_object", request.at("/response_format/type").asText());
            assertTrue(request.at("/messages/0/content").asText().contains("ben"));
            if (config.usesBailian()) {
                assertTrue(request.path("enable_thinking").isBoolean());
                assertFalse(request.path("enable_thinking").asBoolean());
            } else assertFalse(request.has("enable_thinking"));
        }
        JsonNode reviewInput = mapper.readTree(requests.getLast().at("/messages/1/content").asText());
        assertEquals(story, reviewInput.path("story").asText());
        var pieces = new ArrayList<String>();
        reviewInput.path("previousSegments").forEach(piece -> pieces.add(piece.path("text").asText()));
        assertEquals(story, String.join("", pieces));
    }

    @ParameterizedTest @ValueSource(strings = {"deepseek", "bailian"})
    void providerFailuresAreSafeAndDoNotFallback(String provider) {
        status = 401;
        var config = settings(provider);
        var error = assertThrows(GenerationException.class,
                () -> new StorySegmenter(config, mapper, new GenerationHttpClient()).segment(story, null));
        assertTrue(error.getMessage().contains(config.llmName()));
        assertTrue(error.getMessage().contains("401"));
        assertFalse(error.getMessage().contains("fake-"));
        assertFalse(error.getMessage().contains("secret"));
        assertNull(error.getCause());
        assertEquals(1, requests.size());
    }

    @ParameterizedTest @ValueSource(strings = {"deepseek", "bailian"})
    void incompleteMalformedAndRewrittenOutputAreRejected(String provider) {
        var config = settings(provider);
        var segmenter = new StorySegmenter(config, mapper, new GenerationHttpClient());
        finishReason = "length";
        assertTrue(assertThrows(GenerationException.class, () -> segmenter.segment(story, null))
                .getMessage().contains("不完整"));
        finishReason = "stop";
        content = "fake-qwen invalid-json";
        var malformed = assertThrows(GenerationException.class, () -> segmenter.segment(story, null));
        assertTrue(malformed.getMessage().contains(config.llmName()));
        assertFalse(malformed.getMessage().contains("fake-qwen"));
        content = "{\"segments\":[{\"kind\":\"narration\",\"speakerId\":\"narrator\",\"text\":\"changed\"}]}";
        assertTrue(assertThrows(GenerationException.class, () -> segmenter.segment(story, null))
                .getMessage().contains("改写"));
    }
}
