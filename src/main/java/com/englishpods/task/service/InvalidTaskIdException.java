package com.englishpods.task.service;

public class InvalidTaskIdException extends RuntimeException {
    public InvalidTaskIdException() {
        super("Task ID must be a UUID in 8-4-4-4-12 format.");
    }
}
