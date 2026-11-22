package com.englishpods.task.service;

import com.englishpods.generation.GenerationException;
import com.englishpods.generation.GenerationResult;
import com.englishpods.generation.StoryGenerationService;
import com.englishpods.task.model.StoryTask;
import com.englishpods.task.repository.TaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.nio.file.Files;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** SQLite is the queue; only this single thread runs remote generation and FFmpeg. */
@Component
public class TaskWorker {
    private static final Logger LOG = LoggerFactory.getLogger(TaskWorker.class);
    private static final Set<String> STAGES = Set.of("SEGMENTING", "REVIEWING", "SYNTHESIZING", "ASSEMBLING");
    private final TaskRepository repository;
    private final TaskService tasks;
    private final StoryGenerationService generation;
    private final ObjectMapper mapper;
    private final boolean enabled;
    private ScheduledExecutorService executor;

    public TaskWorker(TaskRepository repository, TaskService tasks, StoryGenerationService generation,
                      ObjectMapper mapper, @Value("${story.worker.enabled:true}") boolean enabled) {
        this.repository = repository;
        this.tasks = tasks;
        this.generation = generation;
        this.mapper = mapper;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        repository.recoverInterrupted();
        if (!enabled) return;
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "story-generation");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(() -> {
            try { processNext(); }
            catch (RuntimeException exception) {
                // Do not print exception messages: upstream errors may contain credentials.
                LOG.warn("Task queue scan failed; it will be retried.");
            }
        }, 0, 1, TimeUnit.SECONDS);
    }

    public synchronized boolean processNext() {
        var claimed = repository.claimNext();
        if (claimed.isEmpty()) return false;
        StoryTask task = claimed.get();
        try {
            var directory = tasks.attemptDirectory(task);
            // Reject symlinks leading outside the controlled storage before external tools write.
            for (var path = directory; path != null; path = path.getParent()) {
                if (Files.isSymbolicLink(path)) throw new GenerationException("Audio storage must not use symbolic links.");
            }
            Files.createDirectories(directory);
            GenerationResult generated = generation.generate(task.text(), directory, stage -> {
                if (!STAGES.contains(stage)) throw new GenerationException("Invalid generation stage.");
                repository.stage(task, stage);
            });
            if (generated == null || !task.text().equals(generated.text()))
                throw new GenerationException("Generation did not preserve the original story text.");
            var audio = directory.resolve("story.wav");
            if (!Files.isRegularFile(audio) || Files.isSymbolicLink(audio) || Files.size(audio) == 0)
                throw new GenerationException("Generation produced no audio file.");
            var result = new GenerationResult(generated.text(), "/api/tasks/" + task.id() + "/audio",
                    generated.duration(), generated.segments(), generated.words(), generated.sentences(), generated.voiceMap());
            repository.complete(task, mapper.writeValueAsString(result), tasks.audioReference(audio));
        } catch (GenerationException exception) {
            repository.fail(task, exception.getMessage());
        } catch (Exception exception) {
            repository.fail(task, "Generation failed. Check service configuration and local audio storage, then retry.");
        }
        return true;
    }

    @PreDestroy
    public void close() {
        if (executor == null) return;
        executor.shutdownNow();
        try { executor.awaitTermination(5, TimeUnit.SECONDS); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }
}
