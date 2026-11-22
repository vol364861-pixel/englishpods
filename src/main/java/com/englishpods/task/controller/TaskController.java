package com.englishpods.task.controller;

import com.englishpods.task.dto.CreateTaskRequest;
import com.englishpods.task.dto.TaskResponse;
import com.englishpods.task.service.TaskService;
import com.englishpods.generation.GenerationResult;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {
    private final TaskService service;

    public TaskController(TaskService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<TaskResponse> create(@Valid @RequestBody CreateTaskRequest request) {
        TaskResponse response = TaskResponse.from(service.create(request));
        return ResponseEntity.created(URI.create("/api/tasks/" + response.id())).body(response);
    }

    @GetMapping("/{id}")
    public TaskResponse get(@PathVariable String id) {
        return TaskResponse.from(service.get(id));
    }

    @GetMapping
    public List<TaskResponse> latest(@RequestParam(defaultValue = "") String q) {
        return service.search(q).stream().map(TaskResponse::from).toList();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        if (service.delete(id)) return ResponseEntity.noContent().build();
        return ResponseEntity.accepted().body(Map.of("cleanupPending", true));
    }

    @PostMapping("/{id}/generate")
    public ResponseEntity<TaskResponse> generate(@PathVariable String id) {
        return ResponseEntity.accepted().body(TaskResponse.from(service.enqueue(id)));
    }

    @GetMapping("/{id}/result")
    public GenerationResult result(@PathVariable String id) { return service.result(id); }

    @GetMapping("/{id}/audio")
    public ResponseEntity<Resource> audio(@PathVariable String id) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/wav"))
                .body(new FileSystemResource(service.audio(id)));
    }
}
