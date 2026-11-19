package com.englishpods.task.service;

import org.springframework.http.HttpStatus;

public class TaskOperationException extends RuntimeException {
    private final HttpStatus status;
    public TaskOperationException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }
    public HttpStatus status() { return status; }
}
