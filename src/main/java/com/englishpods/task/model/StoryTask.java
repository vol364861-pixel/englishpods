package com.englishpods.task.model;

import java.time.Instant;
import java.util.UUID;

public record StoryTask(UUID id, String title, String text, TaskStatus status,
                        Instant createdAt, Instant updatedAt, String stage, String error, int attempt,
                        String completedStage, String checkpointOrigin, String reusedStage) {
    public StoryTask(UUID id, String title, String text, TaskStatus status, Instant createdAt, Instant updatedAt,
                     String stage, String error, int attempt) {
        this(id, title, text, status, createdAt, updatedAt, stage, error, attempt, null, null, null);
    }
    public StoryTask(UUID id, String title, String text, TaskStatus status, Instant createdAt, Instant updatedAt) {
        this(id, title, text, status, createdAt, updatedAt, null, null, 0);
    }
}
