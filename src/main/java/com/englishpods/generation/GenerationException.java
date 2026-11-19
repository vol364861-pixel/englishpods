package com.englishpods.generation;

/** Contains only messages suitable for task persistence and HTTP responses. */
public class GenerationException extends RuntimeException {
    public GenerationException(String message) { super(message); }
}
