package com.englishpods.generation;

import java.util.List;

public record GenerationResult(String text, String audioUrl, double duration,
        List<StorySegment> segments, List<TimedWord> words, List<TimedSentence> sentences) {}
