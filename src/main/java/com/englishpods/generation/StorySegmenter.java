package com.englishpods.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;

final class StorySegmenter {
    private final GenerationSettings settings;
    private final ObjectMapper mapper;
    private final GenerationHttpClient http;

    StorySegmenter(GenerationSettings settings, ObjectMapper mapper, GenerationHttpClient http) {
        this.settings = settings; this.mapper = mapper; this.http = http;
    }

    List<StorySegment> segment(String text, List<StorySegment> previous) {
        try {
            String prompt;
            try (var stream = getClass().getResourceAsStream(previous == null ? "/prompts/segment.txt" : "/prompts/review.txt")) {
                if (stream == null) throw new GenerationException("分角提示词资源缺失");
                prompt = new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                        .replace("{{FIRST_PERSON}}", settings.firstPersonSpeaker());
            }
            Object input = previous == null ? Map.of("story", text) : Map.of("story", text,
                    "previousSegments", previous.stream().map(s -> Map.of("kind", s.kind(), "speakerId", s.speakerId(),
                            "text", text.substring(s.sourceStart(), s.sourceEnd()))).toList());
            Map<String, Object> request = new LinkedHashMap<>(Map.of("model", settings.llmModel(), "temperature", 0,
                    "max_tokens", 16384, "response_format", Map.of("type", "json_object"), "messages", List.of(
                            Map.of("role", "system", "content", prompt),
                            Map.of("role", "user", "content", mapper.writeValueAsString(input)))));
            // Bailian JSON mode requires non-thinking output; this is a top-level HTTP parameter.
            if (settings.usesBailian()) request.put("enable_thinking", false);
            String body = mapper.writeValueAsString(request);
            JsonNode response = mapper.readTree(http.post(settings.llmEndpoint(), settings.llmKey(), body, false, settings.llmName()));
            JsonNode choice = response.path("choices").path(0);
            if (!"stop".equals(choice.path("finish_reason").asText()))
                throw new GenerationException(settings.llmName() + " 分角结果不完整，请缩短故事或重试");
            String json = choice.path("message").path("content").asText("");
            return validate(text, mapper.readTree(json));
        } catch (GenerationException e) { throw e; }
        catch (Exception e) { throw new GenerationException(settings.llmName() + " 分角响应格式无效，请重试"); }
    }

    static List<StorySegment> validate(String text, JsonNode response) {
        JsonNode segments = response == null ? null : response.get("segments");
        if (segments == null || !segments.isArray() || segments.isEmpty() || segments.size() > 10000)
            throw new GenerationException("分角结果必须包含非空 segments 数组");
        List<StorySegment> result = new ArrayList<>();
        int cursor = 0;
        for (JsonNode item : segments) {
            String piece = item.path("text").asText("");
            String kind = item.path("kind").asText("");
            String speaker = item.path("speakerId").asText("").trim().toLowerCase(Locale.ROOT);
            if (piece.isEmpty() || !text.startsWith(piece, cursor))
                throw new GenerationException("分角结果改写、遗漏或重复了原文，已拒绝生成，请重试");
            if (!(kind.equals("dialogue") || kind.equals("narration")) || speaker.isEmpty() || speaker.length() > 80
                    || speaker.codePoints().anyMatch(Character::isISOControl))
                throw new GenerationException("分角结果包含无效角色或片段类型");
            if (kind.equals("narration")) speaker = "narrator";
            int end = cursor + piece.length();
            if ((cursor > 0 && Character.isLowSurrogate(text.charAt(cursor)))
                    || (end < text.length() && Character.isLowSurrogate(text.charAt(end))))
                throw new GenerationException("分角结果在 Unicode 字符中间断开");
            result.add(new StorySegment("seg" + (result.size() + 1), kind, speaker, cursor, end));
            cursor = end;
        }
        if (cursor != text.length()) throw new GenerationException("分角结果没有完整覆盖原文，请重试");
        return List.copyOf(result);
    }
}
