package com.englishpods.task;

import com.englishpods.generation.*;
import com.englishpods.task.dto.CreateTaskRequest;
import com.englishpods.task.model.StoryTask;
import com.englishpods.task.model.TaskStatus;
import com.englishpods.task.repository.TaskRepository;
import com.englishpods.task.service.TaskAudioCleanup;
import com.englishpods.task.service.TaskService;
import com.englishpods.task.service.TaskWorker;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = "story.worker.enabled=false")
@AutoConfigureMockMvc
@DirtiesContext
class TaskWorkflowTest {
    @TempDir static Path directory;
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + directory.resolve("workflow.db"));
        registry.add("story.audio-dir", () -> directory.resolve("audio").toString());
    }
    @MockitoBean StoryGenerationService generation;
    @Autowired TaskService tasks;
    @Autowired TaskRepository repository;
    @Autowired TaskAudioCleanup audioCleanup;
    @Autowired TaskWorker worker;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @AfterAll
    static void close(@Autowired HikariDataSource dataSource) { dataSource.close(); }

    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM story_tasks");
        when(generation.readiness()).thenReturn(new GenerationReadiness(true, List.of(), Map.of("sam", "voice"), "alex"));
    }

    StoryTask create() { return tasks.create(new CreateTaskRequest("Story", "Hello 😀.")); }

    @Test
    void queuesPersistentlyRejectsDuplicatesAndPublishesResultAndAudioRange() throws Exception {
        StoryTask task = create();
        mvc.perform(get("/api/tasks/" + task.id() + "/result")).andExpect(status().isConflict());
        mvc.perform(post("/api/tasks/" + task.id() + "/generate"))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.attempt").value(1))
                .andExpect(jsonPath("$.status").value("QUEUED"));
        mvc.perform(post("/api/tasks/" + task.id() + "/generate")).andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT status FROM story_tasks WHERE id=?", String.class, task.id().toString())).isEqualTo("QUEUED");
        when(generation.generate(anyString(), any(), any())).thenAnswer(invocation -> {
            Consumer<String> stages = invocation.getArgument(2);
            stages.accept("REVIEWING");
            assertThat(tasks.get(task.id().toString()).stage()).isEqualTo("REVIEWING");
            stages.accept("SYNTHESIZING");
            Files.write(((Path) invocation.getArgument(1)).resolve("story.wav"), new byte[]{0,1,2,3,4,5,6,7});
            stages.accept("ASSEMBLING");
            return new GenerationResult(task.text(), null, 1.0,
                    List.of(new StorySegment("s1", "narration", "narrator", 0, task.text().length())),
                    List.of(new TimedWord("w1", "Hello", 0, 5, 0, .6, "sentence1")),
                    List.of(new TimedSentence("sentence1", task.text(), 0, task.text().length(), 0, 1)),
                    Map.of("lily", new StoryCasting.CharacterVoice("female", "betty")));
        });
        assertThat(worker.processNext()).isTrue();
        assertThat(worker.processNext()).isFalse();
        assertThat(tasks.get(task.id().toString()).status()).isEqualTo(TaskStatus.COMPLETED);
        mvc.perform(get("/api/tasks/" + task.id() + "/result"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.audioUrl").value("/api/tasks/" + task.id() + "/audio"))
                .andExpect(jsonPath("$.voiceMap.lily.voiceId").value("betty"))
                .andExpect(jsonPath("$.text").value(task.text()));
        mvc.perform(get("/api/tasks/" + task.id() + "/audio").header("Range", "bytes=2-5"))
                .andExpect(status().isPartialContent()).andExpect(header().string("Content-Range", "bytes 2-5/8"))
                .andExpect(content().bytes(new byte[]{2,3,4,5}));
        mvc.perform(get("/api/tasks/" + task.id() + "/audio").header("Range", "bytes=99-100"))
                .andExpect(status().isRequestedRangeNotSatisfiable());
        jdbc.update("UPDATE story_tasks SET audio_path=? WHERE id=?", "../outside.wav", task.id().toString());
        mvc.perform(get("/api/tasks/" + task.id() + "/audio")).andExpect(status().isInternalServerError());
    }

    @Test
    void missingConfigurationKeepsSavedTaskAndReportsReadiness() throws Exception {
        when(generation.readiness()).thenReturn(new GenerationReadiness(false, List.of("Set required credentials."), Map.of(), "alex"));
        StoryTask task = create();
        mvc.perform(post("/api/tasks/" + task.id() + "/generate")).andExpect(status().isServiceUnavailable());
        assertThat(tasks.get(task.id().toString()).status()).isEqualTo(TaskStatus.CREATED);
        assertThat(tasks.get(task.id().toString()).attempt()).isZero();
        mvc.perform(get("/api/config")).andExpect(status().isOk()).andExpect(jsonPath("$.ready").value(false))
                .andExpect(jsonPath("$.issues[0]").value("Set required credentials."));
        verify(generation, never()).generate(anyString(), any(), any());
    }

    @Test
    void failureIsSafeAndRetryUsesNewAttemptDirectory() throws Exception {
        StoryTask task = create();
        when(generation.generate(anyString(), any(), any()))
                .thenThrow(new IllegalStateException("secret credential signed URL must not leak"));
        tasks.enqueue(task.id().toString());
        worker.processNext();
        StoryTask failed = tasks.get(task.id().toString());
        assertThat(failed.status()).isEqualTo(TaskStatus.FAILED);
        assertThat(failed.error()).doesNotContain("secret", "credential", "signed URL");
        tasks.enqueue(task.id().toString());
        assertThat(tasks.get(task.id().toString()).attempt()).isEqualTo(2);
        worker.processNext();
        assertThat(Files.isDirectory(directory.resolve("audio").resolve(task.id().toString()).resolve("1"))).isTrue();
        assertThat(Files.isDirectory(directory.resolve("audio").resolve(task.id().toString()).resolve("2"))).isTrue();
    }

    @Test
    void restartFailsInterruptedWorkAndPreservesQueueAndCompletedRows() {
        StoryTask completed = create();
        tasks.enqueue(completed.id().toString());
        repository.complete(repository.claimNext().orElseThrow(), "{}", "saved.wav");
        StoryTask interrupted = create();
        tasks.enqueue(interrupted.id().toString());
        repository.claimNext();
        StoryTask queued = create();
        tasks.enqueue(queued.id().toString());
        repository.recoverInterrupted();
        assertThat(tasks.get(interrupted.id().toString()).status()).isEqualTo(TaskStatus.FAILED);
        assertThat(tasks.get(interrupted.id().toString()).error()).contains("restart");
        assertThat(tasks.get(queued.id().toString()).status()).isEqualTo(TaskStatus.QUEUED);
        assertThat(tasks.get(completed.id().toString()).status()).isEqualTo(TaskStatus.COMPLETED);
        repository.recoverInterrupted();
        assertThat(tasks.get(queued.id().toString()).status()).isEqualTo(TaskStatus.QUEUED);
        tasks.enqueue(interrupted.id().toString());
        assertThat(tasks.get(interrupted.id().toString()).attempt()).isEqualTo(2);
    }

    @Test
    void historyIsBoundedAndNewestFirst() throws Exception {
        StoryTask latest = null;
        for (int index = 0; index < 35; index++) {
            latest = create();
            // Windows clock resolution can give consecutive inserts identical timestamps.
            jdbc.update("UPDATE story_tasks SET created_at=? WHERE id=?",
                    java.time.Instant.parse("2026-01-01T00:00:00Z").plusSeconds(index).toString(), latest.id().toString());
        }
        var response = mvc.perform(get("/api/tasks")).andExpect(status().isOk()).andReturn().getResponse();
        var results = mapper.readTree(response.getContentAsString());
        assertThat(results.size()).isEqualTo(30);
        assertThat(results.get(0).get("id").asText()).isEqualTo(latest.id().toString());
    }

    @Test
    void searchesOlderStoriesByTitleOrOriginalTextWithLiteralKeywords() throws Exception {
        StoryTask older = tasks.create(new CreateTaskRequest("The Lost MAP", "A rare page with 100% certainty."));
        jdbc.update("UPDATE story_tasks SET created_at=? WHERE id=?", "2025-01-01T00:00:00Z", older.id().toString());
        for (int index = 0; index < 32; index++) create();

        mvc.perform(get("/api/tasks").param("q", "  lost map  "))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(older.id().toString()))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/tasks").param("q", "100%"))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].text").value(older.text()));
        mvc.perform(get("/api/tasks").param("q", "_"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void deletesStoryCheckpointAndAllAttemptFilesAndKeepsOtherStories() throws Exception {
        StoryTask deleted = create();
        StoryTask other = create();
        var audioRoot = directory.resolve("audio");
        var taskDirectory = audioRoot.resolve(deleted.id().toString());
        for (String attempt : List.of("1", "2")) {
            var folder = Files.createDirectories(taskDirectory.resolve(attempt));
            Files.writeString(folder.resolve("story.wav"), "test fixture");
        }
        var otherDirectory = Files.createDirectories(audioRoot.resolve(other.id().toString()));
        Files.writeString(otherDirectory.resolve("keep.txt"), "other story");
        jdbc.update("""
            UPDATE story_tasks SET status='COMPLETED', attempt=2, result_json='{}',
                reviewed_segments_json='[]', audio_path=? WHERE id=?
            """, deleted.id() + "/2/story.wav", deleted.id().toString());

        mvc.perform(delete("/api/tasks/" + deleted.id())).andExpect(status().isNoContent());
        mvc.perform(get("/api/tasks/" + deleted.id() + "/result")).andExpect(status().isNotFound());
        mvc.perform(get("/api/tasks/" + deleted.id() + "/audio")).andExpect(status().isNotFound());
        assertThat(repository.findById(deleted.id())).isEmpty();
        assertThat(taskDirectory).doesNotExist();
        assertThat(otherDirectory.resolve("keep.txt")).exists();
        assertThat(repository.pendingAudioCleanup()).isEmpty();
    }

    @Test
    void deletionAllowsQueuedStoriesAndRejectsClaimedWork() throws Exception {
        StoryTask active = create();
        tasks.enqueue(active.id().toString());
        repository.claimNext().orElseThrow();
        StoryTask queued = create();
        tasks.enqueue(queued.id().toString());

        mvc.perform(delete("/api/tasks/" + active.id())).andExpect(status().isConflict());
        mvc.perform(delete("/api/tasks/" + queued.id())).andExpect(status().isNoContent());
        assertThat(repository.findById(active.id()).orElseThrow().status()).isEqualTo(TaskStatus.SEGMENTING);
        assertThat(repository.claimNext()).isEmpty();
    }

    @Test
    void resumesAudioCleanupFromDurableDeletionRecord() throws Exception {
        StoryTask task = create();
        var folder = Files.createDirectories(directory.resolve("audio").resolve(task.id().toString()));
        Files.writeString(folder.resolve("incomplete-audio.wav"), "test fixture");
        assertThat(repository.removeAndQueueAudioCleanup(task.id())).isTrue();
        assertThat(repository.pendingAudioCleanup()).contains(task.id());
        assertThat(folder).exists();

        audioCleanup.retryPending();

        assertThat(folder).doesNotExist();
        assertThat(repository.pendingAudioCleanup()).doesNotContain(task.id());
    }

    @Test
    void invalidStagesFailInsteadOfPublishing() {
        StoryTask task = create();
        when(generation.generate(anyString(), any(), any())).thenAnswer(invocation -> {
            ((Consumer<String>) invocation.getArgument(2)).accept("COMPLETED");
            return null;
        });
        tasks.enqueue(task.id().toString());
        worker.processNext();
        assertThat(tasks.get(task.id().toString()).status()).isEqualTo(TaskStatus.FAILED);
        assertThat(tasks.get(task.id().toString()).error()).isEqualTo("Invalid generation stage.");
    }

    @Test
    void missingAudioFailsInsteadOfPublishing() {
        StoryTask task = create();
        when(generation.generate(anyString(), any(), any())).thenReturn(
                new GenerationResult(task.text(), null, 1, List.of(), List.of(), List.of()));
        tasks.enqueue(task.id().toString());
        worker.processNext();
        assertThat(tasks.get(task.id().toString()).status()).isEqualTo(TaskStatus.FAILED);
        assertThat(tasks.get(task.id().toString()).error()).isEqualTo("Generation produced no audio file.");
    }

    @Test
    void concurrentEnqueueClaimsExactlyOneAttempt() throws Exception {
        StoryTask task = create();
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var gate = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.Callable<Boolean> enqueue = () -> {
                gate.await();
                return repository.enqueue(task.id());
            };
            var first = pool.submit(enqueue);
            var second = pool.submit(enqueue);
            gate.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
        }
        assertThat(tasks.get(task.id().toString()).attempt()).isEqualTo(1);
    }

    @Test
    void rejectsRewrittenResultAndStorageFailureIsSafe() throws Exception {
        StoryTask rewritten = create();
        when(generation.generate(anyString(), any(), any())).thenReturn(
                new GenerationResult("Changed story", null, 1, List.of(), List.of(), List.of()));
        tasks.enqueue(rewritten.id().toString());
        worker.processNext();
        assertThat(tasks.get(rewritten.id().toString()).error()).contains("original story text");
        assertThat(tasks.get(rewritten.id().toString()).status()).isEqualTo(TaskStatus.FAILED);

        StoryTask storageFailure = create();
        Path root = directory.resolve("audio");
        Files.createDirectories(root);
        Files.writeString(root.resolve(storageFailure.id().toString()), "A file blocks the task directory");
        tasks.enqueue(storageFailure.id().toString());
        worker.processNext();
        assertThat(tasks.get(storageFailure.id().toString()).status()).isEqualTo(TaskStatus.FAILED);
        assertThat(tasks.get(storageFailure.id().toString()).error()).doesNotContain(directory.toString());
        verify(generation, times(1)).generate(anyString(), any(), any());
    }
}
