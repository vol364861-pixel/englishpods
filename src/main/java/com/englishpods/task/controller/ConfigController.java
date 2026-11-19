package com.englishpods.task.controller;

import com.englishpods.generation.GenerationReadiness;
import com.englishpods.task.service.TaskService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ConfigController {
    private final TaskService service;
    public ConfigController(TaskService service) { this.service = service; }
    @GetMapping("/api/config")
    public GenerationReadiness get() { return service.readiness(); }
}
