package com.englishpods.generation;

public record TimedSentence(String id, String text, int sourceStart, int sourceEnd, double start, double end) {}
