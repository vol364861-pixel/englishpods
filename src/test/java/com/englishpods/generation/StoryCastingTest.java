package com.englishpods.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class StoryCastingTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void actualCastCanExceedVoicePoolAndReuseVoicesWithoutLosingIdentities() {
        var output = mapper.createObjectNode();
        var pieces = output.putArray("segments");
        var voices = output.putObject("voiceMap");
        var names = List.of("lily", "sophie", "olivia", "emma", "harry", "jack", "noah", "liam", "maya", "ben");
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < names.size(); i++) {
            String text = names.get(i) + " says hello. ";
            source.append(text);
            pieces.addObject().put("kind", "dialogue").put("speakerId", names.get(i)).put("text", text);
            boolean female = i < 4 || names.get(i).equals("maya");
            voices.putObject(names.get(i)).put("gender", female ? "female" : "male")
                    .put("voiceId", names.get(i).equals("ben") ? "ben" : female ? "maya" : "sam");
        }
        var casting = StorySegmenter.validateCasting(source.toString(), output);
        assertEquals(names, casting.segments().stream().map(StorySegment::speakerId).toList());
        assertEquals(10, casting.voiceMap().size());
        assertEquals("maya", casting.voiceMap().get("lily").voiceId());
        assertEquals("maya", casting.voiceMap().get("sophie").voiceId());
        assertEquals("maya", casting.voiceMap().get("maya").voiceId());
        assertEquals("ben", casting.voiceMap().get("ben").voiceId());
        var config = GenerationSettings.from(new MockEnvironment(), "test-deepseek", "test-qwen");
        assertEquals("alex", config.firstPersonSpeaker());
        assertTrue(StoryText.chunks(source.toString(), casting, config).stream()
                .allMatch(chunk -> !chunk.voice().equals("Beth_v3.1")));
    }

    @Test void explicitlyNamedVoiceCharactersKeepTheirFixedSlotWhenTheModelSuggestsAnother() throws Exception {
        var output = mapper.readTree("""
                {"segments":[{"kind":"dialogue","speakerId":"june","text":"Hello!"}],
                 "voiceMap":{"june":{"gender":"female","voiceId":"betty"}}}
                """);
        var casting = StorySegmenter.validateCasting("Hello!", output);
        assertEquals("june", casting.segments().getFirst().speakerId());
        assertEquals(new StoryCasting.CharacterVoice("female", "june"), casting.voiceMap().get("june"));
    }

    @Test void firstPersonUsesFixedAlexVoiceWhileKeepingIdentityGenderAndNarrator() throws Exception {
        String source = "I smiled. 😀 “Hello!” I said. “I agree,” Ben replied.";
        var output = mapper.readTree("""
                {"segments":[
                  {"kind":"narration","speakerId":"narrator","text":"I smiled. 😀 "},
                  {"kind":"dialogue","speakerId":"lily","text":"“Hello!”"},
                  {"kind":"narration","speakerId":"narrator","text":" I said. "},
                  {"kind":"dialogue","speakerId":"ben","text":"“I agree,”"},
                  {"kind":"narration","speakerId":"narrator","text":" Ben replied."}],
                 "voiceMap":{"lily":{"gender":"female","voiceId":"maya","firstPerson":true},
                             "ben":{"gender":"male","voiceId":"ben","firstPerson":false}}}
                """);
        var casting = StorySegmenter.validateCasting(source, output);
        assertEquals(new StoryCasting.CharacterVoice("female", "alex", true), casting.voiceMap().get("lily"));
        assertEquals("ben", casting.voiceMap().get("ben").voiceId());
        assertEquals("lily", casting.segments().get(1).speakerId());
        assertEquals(source.length(), casting.segments().getLast().sourceEnd());
        var config = GenerationSettings.from(new MockEnvironment(), "test-deepseek", "test-qwen");
        for (var chunk : StoryText.chunks(source, casting, config)) {
            var segment = casting.segments().stream().filter(s -> s.sourceStart() <= chunk.start()
                    && s.sourceEnd() >= chunk.end()).findFirst().orElseThrow();
            assertEquals(segment.kind().equals("narration") ? "Beth_v3.1"
                    : segment.speakerId().equals("lily") ? "Andy_v3.1" : "Brian_v3.1", chunk.voice());
        }
        assertEquals("june", StorySegmenter.validateCasting(source, output, "june").voiceMap().get("lily").voiceId());
        assertEquals("maya", StorySegmenter.validateCasting(source, output, "auto").voiceMap().get("lily").voiceId());
    }

    @Test void fixedFirstPersonOverridesSameNameAndUnknownGenderFallback() throws Exception {
        for (String gender : List.of("female", "unknown")) {
            var output = mapper.readTree("""
                    {"segments":[{"kind":"dialogue","speakerId":"maya","text":"Hi!"}],
                     "voiceMap":{"maya":{"gender":"%s","voiceId":"sam","firstPerson":true}}}
                    """.formatted(gender));
            assertEquals("alex", StorySegmenter.validateCasting("Hi!", output).voiceMap().get("maya").voiceId());
        }
    }

    @Test void rejectsAmbiguousOrMalformedFirstPersonMarkers() throws Exception {
        var output = mapper.readTree("""
                {"segments":[{"kind":"dialogue","speakerId":"alex","text":"Hi!"}],
                 "voiceMap":{"alex":{"gender":"male","voiceId":"alex","firstPerson":true},
                             "lily":{"gender":"female","voiceId":"maya","firstPerson":true}}}
                """);
        assertTrue(assertThrows(GenerationException.class, () -> StorySegmenter.validateCasting("Hi!", output))
                .getMessage().contains("一个第一人称"));
        ((com.fasterxml.jackson.databind.node.ObjectNode) output.at("/voiceMap/lily")).put("firstPerson", "true");
        assertTrue(assertThrows(GenerationException.class, () -> StorySegmenter.validateCasting("Hi!", output))
                .getMessage().contains("布尔值"));
    }
}
