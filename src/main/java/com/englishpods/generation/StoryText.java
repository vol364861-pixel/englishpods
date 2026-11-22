package com.englishpods.generation;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class StoryText {
    static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}]+(?:['’\\-][\\p{L}\\p{N}]+)*");
    record Range(int start, int end) {}
    record Chunk(int start, int end, String voice) {}

    static List<Range> words(String text) {
        List<Range> words = new ArrayList<>();
        var matcher = WORD.matcher(text);
        while (matcher.find()) words.add(new Range(matcher.start(), matcher.end()));
        return words;
    }

    static int speechSplit(String text) {
        List<Range> words = words(text);
        int closest = -1;
        int closestDistance = Integer.MAX_VALUE;
        int punctuation = -1;
        int punctuationDistance = Integer.MAX_VALUE;
        for (int i = 1; i < words.size(); i++) {
            int start = words.get(i - 1).end();
            int end = words.get(i).start();
            int boundary = -1;
            boolean clauseEnd = false;
            for (int pos = start; pos < end; pos++) {
                char ch = text.charAt(pos);
                if (",;:.!?…—–".indexOf(ch) >= 0) clauseEnd = true;
                if (Character.isWhitespace(ch) || Character.isSpaceChar(ch)) boundary = pos + 1;
            }
            // A separating space avoids splitting joined forms such as 2,008 or 21st-century.
            if (boundary < 0) continue;
            int distance = Math.abs(words.size() - 2 * i);
            if (distance < closestDistance) {
                closest = boundary;
                closestDistance = distance;
            }
            if (clauseEnd && distance * 2 <= words.size() && distance < punctuationDistance) {
                punctuation = boundary;
                punctuationDistance = distance;
            }
        }
        return punctuation >= 0 ? punctuation : closest;
    }

    static List<Range> sentences(String text) {
        BreakIterator iterator = BreakIterator.getSentenceInstance(Locale.US);
        iterator.setText(text);
        List<Range> result = new ArrayList<>();
        int start = iterator.first();
        for (int end = iterator.next(); end != BreakIterator.DONE; start = end, end = iterator.next()) {
            result.add(new Range(start, end));
        }
        return result;
    }

    static List<Chunk> chunks(String text, StoryCasting casting, GenerationSettings settings) {
        List<Chunk> chunks = new ArrayList<>();
        for (Range sentence : sentences(text)) {
            for (StorySegment segment : casting.segments()) {
                int start = Math.max(sentence.start(), segment.sourceStart());
                int end = Math.min(sentence.end(), segment.sourceEnd());
                if (start >= end) continue;
                String slot = segment.kind().equals("narration") ? "narrator"
                        : casting.voiceMap().get(segment.speakerId()).voiceId();
                String voice = settings.voices().get(slot);
                if (voice == null || voice.isBlank()) throw new GenerationException("缺少角色音色配置");
                if (!chunks.isEmpty()) {
                    Chunk last = chunks.getLast();
                    if (last.end() == start && last.start() >= sentence.start() && last.voice().equals(voice)
                            && end - last.start() <= 400) {
                        chunks.set(chunks.size() - 1, new Chunk(last.start(), end, voice));
                        continue;
                    }
                }
                while (end - start > 400) {
                    int split = start + 400;
                    while (split > start && !Character.isWhitespace(text.charAt(split))) split--;
                    if (split == start) throw new GenerationException("原文包含超过400字符且无法安全拆分的单词");
                    chunks.add(new Chunk(start, split, voice));
                    start = split;
                }
                chunks.add(new Chunk(start, end, voice));
            }
        }
        // Whitespace and standalone quotation marks remain in the original source, but need no speech.
        return chunks.stream().filter(c -> WORD.matcher(text.substring(c.start(), c.end())).find()).toList();
    }

    static String lexical(String text) {
        StringBuilder result = new StringBuilder();
        text.codePoints().filter(Character::isLetterOrDigit).forEach(c -> result.appendCodePoint(Character.toLowerCase(c)));
        return result.toString();
    }
}
