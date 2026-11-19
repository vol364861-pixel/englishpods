package com.englishpods.task.dto;

import com.englishpods.task.model.StoryTask;
import com.englishpods.task.model.TaskStatus;
import java.time.Instant;
import java.util.UUID;

public record TaskResponse(UUID id, String title, String text, TaskStatus status,
                           Instant createdAt, Instant updatedAt, String stage, String error, int attempt, String completedStage, String checkpointOrigin, String reusedStage) {
    public static TaskResponse from(StoryTask task) {
        return new TaskResponse(task.id(), task.title(), task.text(), task.status(),
                task.createdAt(), task.updatedAt(), task.stage(), task.error(), task.attempt(), task.completedStage(), task.checkpointOrigin(), task.reusedStage());
    }
}
