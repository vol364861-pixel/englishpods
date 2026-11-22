package com.englishpods.generation;

import java.util.List;
import java.util.Map;

public record GenerationResult(String text, String audioUrl, double duration,
        List<StorySegment> segments, List<TimedWord> words, List<TimedSentence> sentences,
        Map<String, StoryCasting.CharacterVoice> voiceMap) {
    public GenerationResult(String text, String audioUrl, double duration,
            List<StorySegment> segments, List<TimedWord> words, List<TimedSentence> sentences) {
        this(text, audioUrl, duration, segments, words, sentences, Map.of());
    }
}
