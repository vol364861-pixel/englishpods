package com.englishpods.generation;

public record TimedWord(String id, String text, int sourceStart, int sourceEnd,
        double start, double end, String sentenceId) {}
