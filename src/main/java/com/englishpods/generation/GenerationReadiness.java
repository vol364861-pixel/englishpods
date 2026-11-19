package com.englishpods.generation;

import java.util.List;
import java.util.Map;

public record GenerationReadiness(boolean ready, List<String> issues, Map<String, String> voices,
        String firstPersonSpeaker) {}
