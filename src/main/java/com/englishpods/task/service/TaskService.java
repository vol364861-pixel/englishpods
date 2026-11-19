package com.englishpods.task.service;

import com.englishpods.task.dto.CreateTaskRequest;
import com.englishpods.task.model.StoryTask;
import com.englishpods.task.model.TaskStatus;
import com.englishpods.task.repository.TaskRepository;
import com.englishpods.generation.GenerationResult;
import com.englishpods.generation.GenerationReadiness;
import com.englishpods.generation.StoryGenerationService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class TaskService {
    private final TaskRepository repository;
    private final StoryGenerationService generation;
    private final ObjectMapper mapper;
    private final Path audioRoot;

    @Autowired
    public TaskService(TaskRepository repository, StoryGenerationService generation, ObjectMapper mapper,
                       @Value("${story.audio-dir:data/audio}") String audioRoot) {
        this.repository = repository;
        this.generation = generation;
        this.mapper = mapper;
        this.audioRoot = Path.of(audioRoot).toAbsolutePath().normalize();
    }

    // Kept for small persistence tests and embedding the create/query service.
    public TaskService(TaskRepository repository) { this(repository, null, new ObjectMapper(), "data/audio"); }

    public StoryTask create(CreateTaskRequest request) {
        Instant now = Instant.now();
        StoryTask task = new StoryTask(UUID.randomUUID(), request.title(), request.text(), TaskStatus.CREATED, now, now);
        repository.save(task);
        return task;
    }

    public StoryTask get(String id) {
        UUID taskId;
        try {
            taskId = UUID.fromString(id);
            if (!taskId.toString().equalsIgnoreCase(id)) throw new InvalidTaskIdException();
        } catch (IllegalArgumentException exception) {
            throw new InvalidTaskIdException();
        }
        return repository.findById(taskId).orElseThrow(TaskNotFoundException::new);
    }

    public List<StoryTask> latest() { return repository.latest(); }
    public GenerationReadiness readiness() { return generation.readiness(); }

    public StoryTask enqueue(String id) {
        StoryTask task = get(id);
        if (task.status() != TaskStatus.CREATED && task.status() != TaskStatus.FAILED)
            throw new TaskOperationException(HttpStatus.CONFLICT, "Task is already queued, running or complete.");
        if (!readiness().ready())
            throw new TaskOperationException(HttpStatus.SERVICE_UNAVAILABLE, "Generation configuration is incomplete. Check configuration status.");
        if (!repository.enqueue(task.id()))
            throw new TaskOperationException(HttpStatus.CONFLICT, "Task is already queued, running or complete.");
        return get(id);
    }

    public GenerationResult result(String id) {
        StoryTask task = completed(id);
        try {
            String json = repository.result(task.id()).orElseThrow(() -> unavailable());
            return mapper.readValue(json, GenerationResult.class);
        } catch (JsonProcessingException exception) {
            throw unavailable();
        }
    }

    public Path audio(String id) {
        StoryTask task = completed(id);
        String reference = repository.audio(task.id()).orElseThrow(() -> unavailable());
        Path expected = attemptDirectory(task).resolve("story.wav");
        Path path = audioRoot.resolve(reference).normalize();
        if (!path.equals(expected) || !Files.isRegularFile(path)) throw unavailable();
        try {
            if (!path.toRealPath().startsWith(audioRoot.toRealPath())) throw unavailable();
        } catch (java.io.IOException exception) { throw unavailable(); }
        return path;
    }

    public Path attemptDirectory(StoryTask task) {
        Path path = audioRoot.resolve(task.id().toString()).resolve(Integer.toString(task.attempt())).normalize();
        if (!path.startsWith(audioRoot) || task.attempt() < 1) throw unavailable();
        return path;
    }

    public String audioReference(Path path) { return audioRoot.relativize(path).toString(); }

    private StoryTask completed(String id) {
        StoryTask task = get(id);
        if (task.status() != TaskStatus.COMPLETED)
            throw new TaskOperationException(HttpStatus.CONFLICT, "Task has no completed result yet.");
        return task;
    }

    private TaskOperationException unavailable() {
        return new TaskOperationException(HttpStatus.INTERNAL_SERVER_ERROR, "The generated result is unavailable. Please check local storage.");
    }
}
