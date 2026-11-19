package com.englishpods.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class QwenSpeechClient {
    private static final int MAX_SYNTHESIS_ATTEMPTS = 3;
    private static final int MAX_SPLITS_PER_CHUNK = 2;
    record WordTiming(int start, int end, double begin, double finish) {}
    record Speech(byte[] audio, List<WordTiming> words) {}
    record SpeechPart(StoryText.Range range, Speech speech) {}
    record RangeAttempt(StoryText.Range range, List<Integer> seeds) {}
    record Synthesis(List<SpeechPart> parts, List<RangeAttempt> attempts) {}
    private final GenerationSettings settings;
    private final ObjectMapper mapper;
    private final GenerationHttpClient http;

    QwenSpeechClient(GenerationSettings settings, ObjectMapper mapper, GenerationHttpClient http) {
        this.settings = settings; this.mapper = mapper; this.http = http;
    }

    Speech synthesize(String text, String voice) {
        return synthesize(text, voice, new ArrayList<>());
    }

    Synthesis synthesizeParts(String text, String voice) {
        var pending = new ArrayDeque<StoryText.Range>();
        pending.add(new StoryText.Range(0, text.length()));
        var parts = new ArrayList<SpeechPart>();
        var attempts = new ArrayList<RangeAttempt>();
        int splits = 0;
        while (!pending.isEmpty()) {
            var range = pending.removeFirst();
            String piece = text.substring(range.start(), range.end());
            var seeds = new ArrayList<Integer>();
            try {
                Speech speech;
                try {
                    speech = synthesize(piece, voice, seeds);
                } finally {
                    attempts.add(new RangeAttempt(range, List.copyOf(seeds)));
                }
                parts.add(new SpeechPart(range, speech));
            } catch (GenerationException failure) {
                int split = splits < MAX_SPLITS_PER_CHUNK && failure instanceof TimingException timing
                        && timing.zeroDuration() ? StoryText.speechSplit(piece) : -1;
                if (split < 0) throw new PartSynthesisException(failure, range, List.copyOf(attempts));
                splits++;
                int boundary = range.start() + split;
                pending.addFirst(new StoryText.Range(boundary, range.end()));
                pending.addFirst(new StoryText.Range(range.start(), boundary));
            }
        }
        return new Synthesis(List.copyOf(parts), List.copyOf(attempts));
    }

    private Speech synthesize(String text, String voice, List<Integer> seeds) {
        try {
            for (int attempt = 0; attempt < MAX_SYNTHESIS_ATTEMPTS; attempt++) {
                checkInterrupted();
                // Qwen's default seed is 0. Change only the seed when regenerating this chunk.
                seeds.add(attempt);
                String body = mapper.writeValueAsString(Map.of("model", settings.qwenModel(), "input", Map.of(
                        "text", text, "voice", voice, "format", "wav", "sample_rate", 24000,
                        "language_hints", List.of("en"), "word_timestamp_enabled", true, "seed", attempt)));
                byte[] response = http.post(settings.qwenEndpoint(), settings.qwenKey(), body, true, "Qwen");
                checkInterrupted();
                try {
                    Speech speech = parse(text, new String(response, StandardCharsets.UTF_8));
                    checkInterrupted();
                    return speech;
                } catch (TimingException e) {
                    checkInterrupted();
                    // Negative duration and all other validation failures remain terminal.
                    if (!e.zeroDuration() || attempt == MAX_SYNTHESIS_ATTEMPTS - 1)
                        throw e.withSynthesisAttempts(seeds);
                }
            }
            throw new IllegalStateException("Unreachable synthesis attempt limit");
        } catch (GenerationException e) { throw e; }
        catch (Exception e) { throw new GenerationException("Qwen 语音响应无效，请检查模型是否支持词时间戳"); }
    }

    static final class PartSynthesisException extends GenerationException {
        private final StoryText.Range range;
        private final List<RangeAttempt> attempts;
        private final Map<String, Object> alignment;

        PartSynthesisException(GenerationException failure, StoryText.Range range, List<RangeAttempt> attempts) {
            super(failure.getMessage());
            this.range = range;
            this.attempts = attempts;
            this.alignment = failure instanceof TimingException timing ? timing.diagnostic() : Map.of();
        }

        StoryText.Range range() { return range; }
        List<RangeAttempt> attempts() { return attempts; }
        Map<String, Object> alignment() { return alignment; }
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) throw new GenerationException("生成任务已中断，可重新生成");
    }

    Speech parse(String text, String sse) {
        try {
            TreeMap<Integer, JsonNode> sentenceWords = new TreeMap<>();
            TreeMap<Integer, String> sentenceText = new TreeMap<>();
            HashSet<String> seenEvents = new HashSet<>();
            ByteArrayOutputStream audio = new ByteArrayOutputStream();
            String audioUrl = null;
            boolean stopped = false;
            for (String event : sse.replace("\r\n", "\n").split("\n\n")) {
                StringBuilder data = new StringBuilder();
                String eventId = null;
                for (String line : event.split("\n")) {
                    if (line.startsWith("data:")) {
                        if (!data.isEmpty()) data.append('\n');
                        data.append(line.substring(5).stripLeading());
                    } else if (line.startsWith("id:")) eventId = line.substring(3).trim();
                }
                if (data.isEmpty() || data.toString().equals("[DONE]")) continue;
                // Only an explicit event id proves an audio frame is a resend. Identical PCM chunks
                // without ids can be legitimate consecutive silence and must both be appended.
                if (eventId != null && !eventId.isEmpty() && !seenEvents.add(eventId)) continue;
                JsonNode root = mapper.readTree(data.toString());
                String code = root.path("code").asText("");
                if ((!code.isBlank() && !code.equals("200") && !code.equalsIgnoreCase("success")) || root.hasNonNull("error"))
                    throw new GenerationException("Qwen 返回生成错误，请检查账号、音色和模型配置");
                JsonNode output = root.path("output");
                if (!output.isObject()) throw new GenerationException("Qwen SSE 缺少 output");
                if ("stop".equals(output.path("finish_reason").asText())) stopped = true;
                JsonNode sentence = output.path("sentence");
                if (sentence.path("index").canConvertToInt() && output.hasNonNull("original_text"))
                    sentenceText.put(sentence.path("index").asInt(), output.path("original_text").asText());
                if (sentence.has("words") && !sentence.path("words").isEmpty()) {
                    if (!sentence.path("index").canConvertToInt() || sentence.path("index").asInt() < 0
                            || !sentence.path("words").isArray()) throw new GenerationException("Qwen 词时间戳句子索引无效");
                    int index = sentence.path("index").asInt();
                    // Latest cumulative sentence word list replaces earlier events; never append repeats.
                    sentenceWords.put(index, sentence.path("words"));
                    if (output.hasNonNull("original_text")) sentenceText.put(index, output.path("original_text").asText());
                }
                JsonNode audioNode = output.path("audio");
                if (!audioNode.path("url").asText("").isBlank()) audioUrl = audioNode.path("url").asText();
                if (!audioNode.path("data").asText("").isBlank()) {
                    byte[] bytes = Base64.getDecoder().decode(audioNode.path("data").asText());
                    if (audio.size() + bytes.length > 32 * 1024 * 1024) throw new GenerationException("Qwen 单段音频过大");
                    audio.write(bytes);
                }
            }
            if (!stopped) throw new GenerationException("Qwen SSE 未完整结束，请重试");
            if (sentenceWords.isEmpty()) throw new GenerationException("Qwen 未返回词时间戳，请使用支持该功能的模型与音色");
            if (sentenceWords.size() != 1) throw new GenerationException("Qwen 将单个语音块再次分句，时间基准不明确，请缩短相应原文句子");
            int index = sentenceWords.firstKey();
            String original = sentenceText.get(index);
            if (original != null && !original.strip().equals(text.strip()))
                throw new GenerationException("Qwen 返回的句子与原文不一致");
            List<WordTiming> words = align(text, sentenceWords.get(index));
            // WAV SSE chunks concatenate in order; stop may additionally offer an OSS URL.
            // Stop and word coverage are checked above; FFmpeg validates audio format and duration next.
            byte[] bytes = audio.size() > 0 ? audio.toByteArray() : audioUrl != null ? http.download(audioUrl) : new byte[0];
            if (bytes.length == 0) throw new GenerationException("Qwen 未返回音频");
            return new Speech(bytes, words);
        } catch (GenerationException e) { throw e; }
        catch (Exception e) { throw new GenerationException("Qwen SSE 或词时间戳格式无效"); }
    }

    static List<WordTiming> align(String text, JsonNode raw) {
        List<StoryText.Range> sourceWords = StoryText.words(text);
        if (sourceWords.isEmpty()) throw new GenerationException("语音块没有可对齐的单词");
        StringBuilder sourceLexical = new StringBuilder();
        List<int[]> lexicalRanges = new ArrayList<>();
        for (var word : sourceWords) {
            int start = sourceLexical.length();
            sourceLexical.append(StoryText.lexical(text.substring(word.start(), word.end())));
            lexicalRanges.add(new int[]{start, sourceLexical.length()});
        }
        String lexicalSource = sourceLexical.toString();
        double[] begins = new double[sourceWords.size()];
        double[] ends = new double[sourceWords.size()];
        java.util.Arrays.fill(begins, Double.NaN);
        int cursor = 0;
        int sourceWord = 0;
        int cardinalEnd = -1;
        int token = -1;
        double previousEnd = 0;
        int previousIndex = -1;
        for (JsonNode word : raw) {
            token++;
            String value = word.path("text").asText("");
            String lexical = StoryText.lexical(value);
            // Non-lexical tokens have no word to highlight. Qwen may emit punctuation/space
            // with placeholder or overlapping indices/times; they must not advance the clock.
            if (lexical.isEmpty()) continue;
            if (!word.path("begin_time").isNumber() || !word.path("end_time").isNumber()
                    || !word.path("begin_index").isIntegralNumber() || !word.path("end_index").isIntegralNumber()
                    || !word.path("begin_index").canConvertToInt() || !word.path("end_index").canConvertToInt())
                throw timingFailure("fields", token, text, raw, cursor, previousEnd, previousIndex);
            double begin = word.path("begin_time").asDouble() / 1000.0;
            double end = word.path("end_time").asDouble() / 1000.0;
            int beginIndex = word.path("begin_index").asInt();
            int endIndex = word.path("end_index").asInt();
            if (!Double.isFinite(begin) || !Double.isFinite(end))
                throw timingFailure("non_finite_time", token, text, raw, cursor, previousEnd, previousIndex);
            if (begin < previousEnd)
                throw timingFailure("time_order", token, text, raw, cursor, previousEnd, previousIndex);
            if (end < begin)
                throw timingFailure("word_duration", token, text, raw, cursor, previousEnd, previousIndex);
            if (beginIndex < 0 || endIndex <= beginIndex || endIndex > text.length())
                throw timingFailure("index_range", token, text, raw, cursor, previousEnd, previousIndex);
            if (beginIndex < previousIndex)
                throw timingFailure("index_order", token, text, raw, cursor, previousEnd, previousIndex);
            while (sourceWord < lexicalRanges.size() && cursor >= lexicalRanges.get(sourceWord)[1]) sourceWord++;
            if (token > cardinalEnd && !lexicalSource.startsWith(lexical, cursor)) {
                if (sourceWord < sourceWords.size() && cursor == lexicalRanges.get(sourceWord)[0])
                    cardinalEnd = QwenCardinalReading.lastMatchingToken(text, sourceWords.get(sourceWord), raw, token);
                if (token > cardinalEnd)
                    throw timingFailure("lexical_mismatch", token, text, raw, cursor, previousEnd, previousIndex);
            }
            int wordEnd = lexicalRanges.get(sourceWord)[1];
            int lexicalEnd = token <= cardinalEnd ? wordEnd : cursor + lexical.length();
            if (lexicalEnd > wordEnd)
                throw timingFailure("cross_source_words", token, text, raw, cursor, previousEnd, previousIndex);
            // Retry zero duration only after this lexical token has a valid source word and indices.
            if (end == begin)
                throw timingFailure("word_duration", token, text, raw, cursor, previousEnd, previousIndex);
            if (Double.isNaN(begins[sourceWord])) begins[sourceWord] = begin;
            ends[sourceWord] = end;
            // All tokens spelling a cardinal cover one source word and contribute real timestamps.
            if (token >= cardinalEnd) cursor = lexicalEnd;
            previousIndex = endIndex;
            previousEnd = end;
        }
        if (cursor != sourceLexical.length()) throw timingFailure("incomplete_coverage", token, text, raw, cursor, previousEnd, previousIndex);
        List<WordTiming> result = new ArrayList<>();
        double lastEnd = 0;
        for (int i = 0; i < sourceWords.size(); i++) {
            if (Double.isNaN(begins[i]) || begins[i] < lastEnd)
                throw timingFailure("cross_source_words", token, text, raw, cursor, previousEnd, previousIndex);
            var range = sourceWords.get(i);
            result.add(new WordTiming(range.start(), range.end(), begins[i], ends[i]));
            lastEnd = ends[i];
        }
        return List.copyOf(result);
    }

    // Only allowlisted scalars are retained. Never attach the SSE, token text, audio URL or HTTP body.
    static final class TimingException extends GenerationException {
        private final Map<String, Object> diagnostic;
        TimingException(Map<String, Object> diagnostic) {
            super("Qwen 词时间戳校验失败：" + conditionDescription((String) diagnostic.get("condition"))
                    + "（" + diagnostic.get("condition") + "）"
                    + "，token=" + diagnostic.get("tokenIndex") + "，sourceLength=" + diagnostic.get("sourceLength")
                    + "，lexicalCursor=" + diagnostic.get("lexicalCursor")
                    + "，previousEndSeconds=" + diagnostic.get("previousEndSeconds")
                    + "，previousEndIndex=" + diagnostic.get("previousEndIndex")
                    + "，current=" + diagnostic.get("current")
                    + (diagnostic.containsKey("synthesisAttempts")
                    ? "；当前块已尝试 " + diagnostic.get("synthesisAttempts") + " 次合成，seed=" + diagnostic.get("seeds") : ""));
            this.diagnostic = diagnostic;
        }
        Map<String, Object> diagnostic() { return diagnostic; }

        boolean zeroDuration() {
            if (!"word_duration".equals(diagnostic.get("condition"))) return false;
            var current = (Map<?, ?>) diagnostic.get("current");
            return current.get("begin_time") instanceof Number begin && current.get("end_time") instanceof Number end
                    && begin.doubleValue() == end.doubleValue();
        }

        TimingException withSynthesisAttempts(List<Integer> seeds) {
            var details = new java.util.LinkedHashMap<>(diagnostic);
            details.put("synthesisAttempts", seeds.size());
            details.put("seeds", List.copyOf(seeds));
            return new TimingException(details);
        }
    }

    private static String conditionDescription(String condition) {
        return switch (condition) {
            case "fields" -> "字段缺失、类型无效或索引溢出";
            case "non_finite_time" -> "时间不是有限数值";
            case "time_order" -> "朗读词时间倒退或重叠";
            case "word_duration" -> "朗读词时长必须大于零";
            case "index_range" -> "字符范围越界或为空";
            case "index_order" -> "朗读词字符范围倒退或重叠";
            case "lexical_mismatch" -> "词形无法匹配原文，可能漏读、改写或不支持的数字读法";
            case "incomplete_coverage" -> "未完整覆盖原文单词";
            case "cross_source_words" -> "分词跨越多个原文单词，无法精确高亮";
            default -> "未知条件";
        };
    }

    private static TimingException timingFailure(String condition, int token, String text, JsonNode raw,
                                                 int cursor, double previousEnd, int previousIndex) {
        List<Map<String, Object>> timings = new ArrayList<>();
        for (JsonNode word : raw) {
            Map<String, Object> safe = new java.util.LinkedHashMap<>();
            safe.put("lexicalLength", StoryText.lexical(word.path("text").asText("")).length());
            for (String field : List.of("begin_index", "end_index", "begin_time", "end_time")) {
                JsonNode number = word.path(field);
                safe.put(field, number.isNumber() && Double.isFinite(number.asDouble()) ? number.numberValue() : null);
            }
            timings.add(safe);
        }
        return new TimingException(Map.of("condition", condition, "tokenIndex", token, "sourceLength", text.length(),
                "lexicalCursor", cursor, "previousEndSeconds", previousEnd, "previousEndIndex", previousIndex,
                "current", token >= 0 && token < timings.size() ? timings.get(token) : Map.of(), "timings", timings));
    }
}
