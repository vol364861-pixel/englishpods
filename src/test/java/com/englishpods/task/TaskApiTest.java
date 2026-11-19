package com.englishpods.task;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext
class TaskApiTest {
    @TempDir
    static Path directory;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + directory.resolve("api.db"));
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbcTemplate;

    @AfterAll
    static void closeDatabaseBeforeTemporaryDirectoryCleanup(@Autowired HikariDataSource dataSource) {
        dataSource.close();
    }

    @Test
    void createsAndQueriesTaskWithoutChangingOriginalText() throws Exception {
        String title = "  Back to Sam's bookstore  ";
        String text = "  On Tuesday, Maya and I visited Sam's bookstore.\r\n\r\n"
                + "“That's good,” I said.\n“Two more people liked it,” she said. 😀  ";
        String response = mvc.perform(post("/api/tasks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", title, "text", text))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value(title))
                .andExpect(jsonPath("$.text").value(text))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andReturn().getResponse().getContentAsString();
        JsonNode created = objectMapper.readTree(response);
        UUID id = UUID.fromString(created.get("id").asText());
        assertThat(Instant.parse(created.get("createdAt").asText()))
                .isEqualTo(Instant.parse(created.get("updatedAt").asText()));
        assertThat(created.get("createdAt").asText()).endsWith("Z");

        JsonNode queried = objectMapper.readTree(mvc.perform(get("/api/tasks/" + id))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(queried).isEqualTo(created);
        assertThat(jdbcTemplate.queryForObject("SELECT source_text FROM story_tasks WHERE id = ?",
                String.class, id.toString())).isEqualTo(text);
    }

    @Test
    void returnsRelativeLocationForCreatedTask() throws Exception {
        var response = mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Story\",\"text\":\"Hello.\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse();
        String id = objectMapper.readTree(response.getContentAsString()).get("id").asText();
        assertThat(response.getHeader("Location")).isEqualTo("/api/tasks/" + id);
    }

    @Test
    void acceptsExactUtf16Limits() throws Exception {
        String title = "😀".repeat(100);
        String text = "😀".repeat(25_000);
        mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", title, "text", text))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value(title))
                .andExpect(jsonPath("$.text").value(text));
    }

    @ParameterizedTest(name = "invalid input: {0}")
    @MethodSource("invalidRequests")
    void rejectsInvalidInputWithoutSaving(String label, String json) throws Exception {
        long before = countTasks();
        String error = mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.trace").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(countTasks()).isEqualTo(before);
        assertThat(error).doesNotContain("Exception", "org.springframework", "java.lang", "SELECT", "INSERT");
    }

    static Stream<Arguments> invalidRequests() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        return Stream.of(
                Arguments.of("missing title", "{\"text\":\"Hello\"}"),
                Arguments.of("missing text", "{\"title\":\"Story\"}"),
                Arguments.of("null title", "{\"title\":null,\"text\":\"Hello\"}"),
                Arguments.of("null text", "{\"title\":\"Story\",\"text\":null}"),
                Arguments.of("blank title", "{\"title\":\" \\t\\r\\n\",\"text\":\"Hello\"}"),
                Arguments.of("blank text", "{\"title\":\"Story\",\"text\":\" \\t\\r\\n\"}"),
                Arguments.of("empty title", "{\"title\":\"\",\"text\":\"Hello\"}"),
                Arguments.of("empty text", "{\"title\":\"Story\",\"text\":\"\"}"),
                Arguments.of("title over 200", mapper.writeValueAsString(Map.of("title", "x".repeat(201), "text", "Hello"))),
                Arguments.of("text over 50000", mapper.writeValueAsString(Map.of("title", "Story", "text", "x".repeat(50_001)))),
                Arguments.of("emoji title over UTF16 limit", mapper.writeValueAsString(Map.of("title", "😀".repeat(101), "text", "Hello"))),
                Arguments.of("emoji text over UTF16 limit", mapper.writeValueAsString(Map.of("title", "Story", "text", "😀".repeat(25_001)))),
                Arguments.of("number title", "{\"title\":123,\"text\":\"Hello\"}"),
                Arguments.of("number text", "{\"title\":\"Story\",\"text\":123}"),
                Arguments.of("boolean title", "{\"title\":true,\"text\":\"Hello\"}"),
                Arguments.of("boolean text", "{\"title\":\"Story\",\"text\":false}"),
                Arguments.of("array title", "{\"title\":[],\"text\":\"Hello\"}"),
                Arguments.of("array text", "{\"title\":\"Story\",\"text\":[]}"),
                Arguments.of("object text", "{\"title\":\"Story\",\"text\":{}}"),
                Arguments.of("malformed JSON", "{\"title\":\"Story\",\"text\":"),
                Arguments.of("null body", "null"),
                Arguments.of("empty body", ""),
                Arguments.of("trailing JSON", "{\"title\":\"Story\",\"text\":\"Hello\"} {}"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "1-1-1-1-1", "00000000000000000000000000000000",
            "00000000-0000-0000-0000-00000000000g"})
    void rejectsInvalidId(String id) throws Exception {
        mvc.perform(get("/api/tasks/" + id)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isString())
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void returns404ForUnknownTask() throws Exception {
        mvc.perform(get("/api/tasks/" + UUID.randomUUID())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Task not found."))
                .andExpect(jsonPath("$.trace").doesNotExist());
    }

    @Test
    void preservesSqlLookingContentAsData() throws Exception {
        String text = "That's Sam's book. '); DROP TABLE story_tasks; --";
        String response = mvc.perform(post("/api/tasks").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("title", "SQL ' title", "text", text))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = objectMapper.readTree(response).get("id").asText();
        mvc.perform(get("/api/tasks/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value(text));
        assertThat(countTasks()).isPositive();
    }

    private long countTasks() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM story_tasks", Long.class);
    }
}
