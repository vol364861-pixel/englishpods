package com.englishpods.generation;

import java.util.List;
import java.util.Map;

/** Character identities stay independent of the reusable voice slots. */
public record StoryCasting(List<StorySegment> segments, Map<String, CharacterVoice> voiceMap) {
    public record CharacterVoice(String gender, String voiceId, boolean firstPerson) {
        public CharacterVoice(String gender, String voiceId) { this(gender, voiceId, false); }
    }
}
