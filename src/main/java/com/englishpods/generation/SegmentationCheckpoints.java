package com.englishpods.generation;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** One generation attempt's checkpoint context. Only production task directories activate persistence. */
final class SegmentationCheckpoints {
    private final SegmentationCheckpointRepository repository;
    private final ObjectMapper mapper;
    private final UUID task;
    private final String sourceHash;
    private final String configHash;
    private final String firstPersonSlot;
    private String initialJson;
    private String origin = "GENERATED";
    private StoryCasting initial;
    private StoryCasting reviewed;

    SegmentationCheckpoints(SegmentationCheckpointRepository repository, ObjectMapper mapper,
                            GenerationSettings settings, String text, Path directory) throws Exception {
        this.repository = repository; this.mapper = mapper;
        firstPersonSlot = settings.firstPersonSpeaker();
        task = UUID.fromString(directory.getParent().getFileName().toString());
        int attempt = Integer.parseInt(directory.getFileName().toString());
        if (attempt < 1) throw new IllegalArgumentException("Invalid attempt");
        sourceHash = hash(text);
        String prompts = resource("/prompts/segment.txt") + resource("/prompts/review.txt");
        configHash = hash(mapper.writeValueAsString(List.of("segment-schema-v4-first-person", settings.llmProvider(), settings.llmEndpoint(),
                settings.llmModel(), settings.firstPersonSpeaker(), prompts)));
        var saved = repository.find(task, text).orElseThrow();
        if (sourceHash.equals(saved.sourceHash()) && configHash.equals(saved.configHash())) {
            try {
                initial = safeValidate(text, saved.initial());
                reviewed = safeValidate(text, saved.reviewed());
                initialJson = initial == null ? null : mapper.writeValueAsString(initial);
                origin = saved.origin() == null ? "GENERATED" : saved.origin();
                if (reviewed != null || initial != null) {
                    if (reviewed == null) repository.save(task, sourceHash, configHash, initialJson, null, origin);
                    repository.reused(task, reviewed == null ? "SEGMENTING" : "REVIEWING");
                    return;
                }
            } catch (Exception ignored) { initial = null; reviewed = null; }
        }
        boolean legacy = saved.sourceHash() == null && saved.configHash() == null;
        repository.reset(task, sourceHash, configHash, mapper.writeValueAsString(Map.of(
                "schemaVersion", 4, "provider", settings.llmProvider(), "model", settings.llmModel(),
                "firstPerson", settings.firstPersonSpeaker(), "promptHash", hash(prompts),
                "endpointHash", hash(settings.llmEndpoint()))));
        // A new fingerprint never falls back to stale files. Only pre-checkpoint installations import legacy artifacts.
        if (legacy && "deepseek".equals(settings.llmProvider())) {
            for (int previous = attempt - 1; previous >= 1; previous--) {
                Path old = directory.getParent().resolve(Integer.toString(previous));
                if (Files.isSymbolicLink(old)) continue;
                StoryCasting oldInitial = readLegacy(text, old.resolve("segments-initial.json"));
                StoryCasting oldReviewed = readLegacy(text, old.resolve("segments-reviewed.json"));
                if (oldInitial == null && oldReviewed == null) continue;
                initial = oldInitial; reviewed = oldReviewed;
                initialJson = initial == null ? null : mapper.writeValueAsString(initial);
                origin = "LEGACY_ATTEMPT_" + previous + "_CONFIG_UNKNOWN";
                repository.save(task, sourceHash, configHash, initialJson,
                        reviewed == null ? null : mapper.writeValueAsString(reviewed), origin);
                repository.reused(task, reviewed == null ? "SEGMENTING" : "REVIEWING");
                break;
            }
        }
    }

    StoryCasting initial() { return initial; }
    StoryCasting reviewed() { return reviewed; }
    void saveInitial(StoryCasting value) throws Exception {
        initialJson = mapper.writeValueAsString(value);
        repository.save(task, sourceHash, configHash, initialJson, null, "GENERATED");
        origin = "GENERATED";
    }
    void saveReviewed(StoryCasting value) throws Exception {
        repository.save(task, sourceHash, configHash, initialJson, mapper.writeValueAsString(value), origin);
    }

    private StoryCasting readLegacy(String text, Path path) {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 4 * 1024 * 1024) return null;
            return validate(text, Files.readString(path));
        } catch (Exception ignored) { return null; }
    }
    private StoryCasting safeValidate(String text, String json) {
        try { return validate(text, json); } catch (Exception ignored) { return null; }
    }
    private StoryCasting validate(String text, String json) throws Exception {
        if (json == null) return null;
        if (json.length() > 4 * 1024 * 1024) throw new IllegalArgumentException("Checkpoint too large");
        var root = mapper.readTree(json);
        // Older casts cannot establish whether the former inferred voice belonged to the protagonist.
        if (!root.path("voiceMap").isObject()) throw new IllegalArgumentException("Invalid checkpoint voice map");
        for (var voice : root.path("voiceMap")) {
            if (!voice.path("firstPerson").isBoolean()) throw new IllegalArgumentException("Outdated checkpoint voice map");
        }
        var node = root.path("segments");
        if (!node.isArray() || node.isEmpty() || node.size() > 10000) throw new IllegalArgumentException("Invalid checkpoint");
        var pieces = mapper.createArrayNode();
        int cursor = 0;
        for (var item : node) {
            var start = item.path("sourceStart"); var end = item.path("sourceEnd");
            if (!start.isIntegralNumber() || !end.isIntegralNumber() || !start.canConvertToInt() || !end.canConvertToInt()
                    || start.asInt() != cursor || end.asInt() <= cursor || end.asInt() > text.length())
                throw new IllegalArgumentException("Invalid checkpoint range");
            pieces.addObject().put("kind", item.path("kind").asText()).put("speakerId", item.path("speakerId").asText())
                    .put("text", text.substring(cursor, end.asInt()));
            cursor = end.asInt();
        }
        var wrapper = mapper.createObjectNode(); wrapper.set("segments", pieces);
        wrapper.set("voiceMap", root.path("voiceMap"));
        return StorySegmenter.validateCasting(text, wrapper, firstPersonSlot);
    }
    private static String resource(String name) throws Exception {
        try (var input = SegmentationCheckpoints.class.getResourceAsStream(name)) {
            if (input == null) throw new IllegalStateException("Missing prompt");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_16BE)));
    }
}
