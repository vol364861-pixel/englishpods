package com.englishpods.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class QwenCardinalReadingTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void alignsObservedQwenCardinalToOneOriginalWord() throws Exception {
        String source = "At the top were three words: Saturday Walk, 2008.\n\n";
        var timings = List.of(
                token("At", 0, 80, 240), token(" the", 1, 240, 320), token(" top", 2, 320, 640),
                token(" were", 3, 640, 960), token(" three", 4, 960, 1200), token(" words", 5, 1200, 1520),
                token(": Saturday", 6, 2160, 2800), token(" Walk", 7, 2800, 3200),
                token(", two", 8, 3520, 3840), token(" thousand", 9, 3840, 4160), token(" eight.", 10, 4400, 4720));
        var event = Map.of("output", Map.of("sentence", Map.of("index", 0, "words", timings),
                "original_text", source.strip(), "finish_reason", "stop", "audio", Map.of("data", "AA==")));
        var client = new QwenSpeechClient(null, mapper, null);
        var speech = client.parse(source, "data: " + mapper.writeValueAsString(event) + "\n\n");

        assertEquals(9, speech.words().size());
        assertEquals(new QwenSpeechClient.WordTiming(38, 42, 2.8, 3.2), speech.words().get(7));
        assertEquals(new QwenSpeechClient.WordTiming(44, 48, 3.52, 4.72), speech.words().getLast());
        assertEquals("2008", source.substring(speech.words().getLast().start(), speech.words().getLast().end()));
        assertArrayEquals(new byte[]{0}, speech.audio());
    }

    @Test void acceptsExactAndSplitDigitsAndPreservesUtf16Offsets() {
        String source = "😀 ‘2008’ ended";
        var exact = QwenSpeechClient.align(source, timings("2008", "ended"));
        var split = QwenSpeechClient.align(source, mapper.valueToTree(List.of(token("20", 0, 0, 25),
                token("08", 1, 25, 100), token(" ended", 2, 100, 200))));

        assertEquals(List.of(new QwenSpeechClient.WordTiming(4, 8, 0, 0.1),
                new QwenSpeechClient.WordTiming(10, 15, 0.1, 0.2)), exact);
        assertEquals(exact, split);
    }

    @Test void acceptsCardinalAndHyphenFormsWithoutChangingAdjacentWords() {
        var split = QwenSpeechClient.align("‘1242’ then 0.", mapper.valueToTree(List.of(
                token("One", 0, 0, 100), token("thousand", 1, 100, 200), token("two hundred", 2, 200, 300),
                Map.of("text", "\""), token("and FORTY-TWO", 3, 400, 500), token("then", 4, 600, 700),
                token("zero.", 5, 800, 900))));

        assertEquals(List.of(new QwenSpeechClient.WordTiming(1, 5, 0, 0.5),
                new QwenSpeechClient.WordTiming(7, 11, 0.6, 0.7),
                new QwenSpeechClient.WordTiming(12, 13, 0.8, 0.9)), split);
        assertEquals(List.of(new QwenSpeechClient.WordTiming(0, 4, 0, 0.2)),
                QwenSpeechClient.align("2008.", timings("two thousand", "and eight.")));
    }

    @Test void rejectsWrongPartialAndBoundaryCrossingReadings() {
        assertMismatch("2008.", "two thousand", "nine");
        assertMismatch("2008.", "two", "thousand");
        assertMismatch("2008.", "twenty oh eight");
        assertMismatch("2008.", "two", "thousand", "eightfold");
        assertMismatch("2008.", "two", "thou", "sand", "eight");
        assertMismatch("2008.", "two. thousand eight");
        assertMismatch("2008.", "20", "eight");
        assertMismatch("In 2008.", "In two", "thousand eight");
        assertMismatch("2008 passed.", "two thousand", "eight passed");
        assertMismatch("2 5", "two five");
    }

    @Test void keepsExpansionLimitedToPlainBoundedIntegers() {
        assertMismatch("10000.", "ten thousand");
        assertMismatch("008.", "eight");
        assertMismatch("-2008.", "two thousand eight");
        assertMismatch("$2008.", "two thousand eight");
        assertMismatch("2008%.", "two thousand eight");
        assertMismatch("2.5.", "two", "five");
        assertMismatch("2,108.", "2", "one hundred eight");
        assertMismatch("2,108.", "two", "108");
        assertMismatch("2/5.", "two", "five");
        assertMismatch("12:45.", "twelve", "forty five");
        assertMismatch("12/5/2020.", "12", "5", "two thousand twenty");
        assertEquals(List.of(new QwenSpeechClient.WordTiming(1, 5, 0, 0.1)),
                QwenSpeechClient.align("-2008.", timings("-2008")));
    }

    @Test void requiresEverySpokenPartToPassTimingAndIndexChecks() {
        var words = timings("two", "thousand", "eight");
        words.set(1, mapper.valueToTree(token("thousand", 1, 90, 180)));
        assertFailure("time_order", words);
        words.set(1, mapper.valueToTree(token("thousand", 1, 100, 100)));
        assertTrue(assertFailure("word_duration", words).zeroDuration());
        words.set(1, mapper.valueToTree(token("hundred", 1, 100, 100)));
        assertFalse(assertFailure("lexical_mismatch", words).zeroDuration());
        words.set(1, mapper.valueToTree(token("thousand", 0, 100, 200)));
        assertFalse(assertFailure("index_order", words).zeroDuration());
    }

    private void assertMismatch(String source, String... values) {
        var failure = assertThrows(QwenSpeechClient.TimingException.class,
                () -> QwenSpeechClient.align(source, timings(values)));
        assertEquals("lexical_mismatch", failure.diagnostic().get("condition"), source);
    }

    private QwenSpeechClient.TimingException assertFailure(String condition, ArrayNode words) {
        var failure = assertThrows(QwenSpeechClient.TimingException.class, () -> QwenSpeechClient.align("2008.", words));
        assertEquals(condition, failure.diagnostic().get("condition"));
        return failure;
    }

    private ArrayNode timings(String... values) {
        var words = mapper.createArrayNode();
        for (int index = 0; index < values.length; index++) {
            words.add(mapper.valueToTree(token(values[index], index, index * 100, (index + 1) * 100)));
        }
        return words;
    }

    private static Map<String, Object> token(String text, int index, int begin, int end) {
        return Map.of("text", text, "begin_index", index, "end_index", index + 1, "begin_time", begin, "end_time", end);
    }
}
