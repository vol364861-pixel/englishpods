package com.englishpods.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateTaskRequest(
        @NotBlank(message = "Title is required.")
        @Size(max = 200, message = "Title must not exceed 200 UTF-16 code units.")
        String title,
        @NotBlank(message = "Text is required.")
        @Size(max = 50_000, message = "Text must not exceed 50000 UTF-16 code units.")
        String text) {
}
