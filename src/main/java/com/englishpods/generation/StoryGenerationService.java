package com.englishpods.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import java.nio.file.Path;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class StoryGenerationService {
    private final GenerationSettings settings;
    private final ObjectMapper mapper;
    private final SegmentationCheckpointRepository checkpoints;

    @Autowired
    public StoryGenerationService(Environment environment, ObjectMapper mapper, SegmentationCheckpointRepository checkpoints) {
        this(GenerationSettings.from(environment), mapper, checkpoints);
    }

    StoryGenerationService(GenerationSettings settings, ObjectMapper mapper) {
        this(settings, mapper, null);
    }

    StoryGenerationService(GenerationSettings settings, ObjectMapper mapper, SegmentationCheckpointRepository checkpoints) {
        this.settings = settings;
        this.mapper = mapper;
        this.checkpoints = checkpoints;
    }

    public GenerationReadiness readiness() { return settings.readiness(); }

    public GenerationResult generate(String text, Path directory, Consumer<String> stageChanged) {
        GenerationReadiness ready = readiness();
        if (!ready.ready()) throw new GenerationException(String.join("；", ready.issues()));
        if (text == null || text.isBlank() || StoryText.words(text).isEmpty())
            throw new GenerationException("故事必须包含可朗读的文本");
        try {
            Files.createDirectories(directory);
            GenerationHttpClient http = new GenerationHttpClient();
            StorySegmenter segmenter = new StorySegmenter(settings, mapper, http);
            SegmentationCheckpoints checkpoint = checkpoints == null ? null
                    : new SegmentationCheckpoints(checkpoints, mapper, settings, text, directory);
            StoryCasting casting = checkpoint == null ? null : checkpoint.reviewed();
            if (casting == null) {
                StoryCasting initial = checkpoint == null ? null : checkpoint.initial();
                if (initial == null) {
                    stageChanged.accept("SEGMENTING");
                    initial = segmenter.segment(text, null);
                    if (checkpoint != null) checkpoint.saveInitial(initial);
                }
                mapper.writeValue(directory.resolve("segments-initial.json").toFile(), initial);
                stageChanged.accept("REVIEWING");
                casting = segmenter.segment(text, initial);
                if (checkpoint != null) checkpoint.saveReviewed(casting);
            }
            mapper.writeValue(directory.resolve("segments-reviewed.json").toFile(), casting);
            List<StoryText.Chunk> chunks = StoryText.chunks(text, casting, settings);
            if (chunks.isEmpty()) throw new GenerationException("没有可合成的故事片段");
            QwenSpeechClient speech = new QwenSpeechClient(settings, mapper, http);
            AudioAssembler assembler = new AudioAssembler(settings.ffmpeg(), directory, settings.pauseMillis());
            List<TimedWord> words = new ArrayList<>();
            List<StoryText.Range> sentenceRanges = StoryText.sentences(text);
            stageChanged.accept("SYNTHESIZING");
            for (int i = 0; i < chunks.size(); i++) {
                var chunk = chunks.get(i);
                var range = new StoryText.Range(0, chunk.end() - chunk.start());
                List<QwenSpeechClient.RangeAttempt> attempts = List.of();
                try {
                    var synthesized = speech.synthesizeParts(text.substring(chunk.start(), chunk.end()), chunk.voice());
                    attempts = synthesized.attempts();
                    var parts = synthesized.parts();
                    for (int j = 0; j < parts.size(); j++) {
                        var part = parts.get(j);
                        range = part.range();
                        String name = parts.size() == 1 ? Integer.toString(i) : i + "-part-" + j;
                        boolean pauseAfter = j == parts.size() - 1 && i < chunks.size() - 1;
                        var position = assembler.append(part.speech().audio(), name, pauseAfter);
                        for (var word : part.speech().words()) {
                            if (word.finish() > position.duration() + 1.0 / 24000)
                                throw new GenerationException("Qwen 单词时间戳超出实际音频时长：wordEndSeconds="
                                        + word.finish() + "，audioDurationSeconds=" + position.duration());
                            int start = chunk.start() + range.start() + word.start();
                            int end = chunk.start() + range.start() + word.end();
                            int sentence = sentenceIndex(sentenceRanges, start, end);
                            words.add(new TimedWord("w" + (words.size() + 1), text.substring(start, end), start, end,
                                    position.offset() + word.begin(), position.offset() + word.finish(), "s" + (sentence + 1)));
                        }
                    }
                } catch (GenerationException e) {
                    throw describeSpeechFailure(directory, i, chunk, range, attempts, e);
                }
            }
            // Check all lexical source tokens were represented once, including contractions and UTF-16 positions.
            List<StoryText.Range> expected = StoryText.words(text);
            if (words.size() != expected.size()) throw new GenerationException("生成后的单词没有完整覆盖原文");
            double previousEnd = 0;
            for (int i = 0; i < words.size(); i++) {
                var word = words.get(i);
                var range = expected.get(i);
                if (word.sourceStart() != range.start() || word.sourceEnd() != range.end() || word.start() < previousEnd)
                    throw new GenerationException("生成后的单词区间或时间顺序无效");
                previousEnd = word.end();
            }
            List<TimedSentence> sentences = new ArrayList<>();
            for (int i = 0; i < sentenceRanges.size(); i++) {
                String id = "s" + (i + 1);
                List<TimedWord> sentenceWords = words.stream().filter(w -> w.sentenceId().equals(id)).toList();
                if (sentenceWords.isEmpty()) continue;
                var range = sentenceRanges.get(i);
                sentences.add(new TimedSentence(id, text.substring(range.start(), range.end()), range.start(), range.end(),
                        sentenceWords.getFirst().start(), sentenceWords.getLast().end()));
            }
            stageChanged.accept("ASSEMBLING");
            double duration = assembler.finish();
            if (previousEnd > duration + 1.0 / 24000) throw new GenerationException("最终时间轴超出音频时长");
            GenerationResult result = new GenerationResult(text, "", duration, casting.segments(), List.copyOf(words), List.copyOf(sentences), casting.voiceMap());
            mapper.writeValue(directory.resolve("timeline.json").toFile(), result);
            return result;
        } catch (GenerationException e) { throw e; }
        catch (Exception e) { throw new GenerationException("生成文件或时间轴处理失败，请检查磁盘空间后重试"); }
    }

    private GenerationException describeSpeechFailure(Path directory, int index, StoryText.Chunk chunk,
            StoryText.Range range, List<QwenSpeechClient.RangeAttempt> attempts, GenerationException failure) {
        var diagnostic = new LinkedHashMap<String, Object>();
        diagnostic.put("chunkIndex", index);
        diagnostic.put("sourceStart", chunk.start());
        diagnostic.put("sourceEnd", chunk.end());
        if (failure instanceof QwenSpeechClient.PartSynthesisException synthesis) {
            range = synthesis.range();
            attempts = synthesis.attempts();
            if (!synthesis.alignment().isEmpty()) diagnostic.put("alignment", synthesis.alignment());
        }
        int failedStart = chunk.start() + range.start();
        int failedEnd = chunk.start() + range.end();
        diagnostic.put("failedSourceStart", failedStart);
        diagnostic.put("failedSourceEnd", failedEnd);
        diagnostic.put("synthesisRanges", attempts.stream().map(attempt -> Map.of(
                "sourceStart", chunk.start() + attempt.range().start(),
                "sourceEnd", chunk.start() + attempt.range().end(), "seeds", attempt.seeds())).toList());
        String error = failure.getMessage();
        if (attempts.size() > 1) {
            int total = attempts.stream().mapToInt(attempt -> attempt.seeds().size()).sum();
            error = "子片段源UTF-16 [" + failedStart + "," + failedEnd + ")，该块累计发起 " + total + " 次合成：" + error;
        }
        diagnostic.put("error", error);
        try { mapper.writeValue(directory.resolve("qwen-failure.json").toFile(), diagnostic); }
        catch (java.io.IOException ignored) { /* Keep the original safe failure if disk writing fails. */ }
        return new GenerationException("Qwen 块 " + (index + 1) + "（chunkIndex=" + index + "，源UTF-16 ["
                + chunk.start() + "," + chunk.end() + ")）：" + error);
    }

    private static int sentenceIndex(List<StoryText.Range> ranges, int start, int end) {
        for (int i = 0; i < ranges.size(); i++) {
            if (start >= ranges.get(i).start() && end <= ranges.get(i).end()) return i;
        }
        throw new GenerationException("单词跨越了原文句子边界");
    }
}
