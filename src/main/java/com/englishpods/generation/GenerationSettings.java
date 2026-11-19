package com.englishpods.generation;

import org.springframework.core.env.Environment;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Properties;
import java.net.URI;
import java.util.concurrent.TimeUnit;

record GenerationSettings(String deepSeekKey, String qwenKey, String deepSeekEndpoint,
        String deepSeekModel, String qwenEndpoint, String qwenModel, String ffmpeg,
        Map<String,String> voices, String firstPersonSpeaker, int pauseMillis,
        String llmProvider, String qwenLlmEndpoint, String qwenLlmModel) {
    static GenerationSettings from(Environment environment) {
        return from(environment, System.getenv("DEEPSEEK_API_KEY"), System.getenv("DASHSCOPE_API_KEY"));
    }

    static GenerationSettings from(Environment env, String deepSeekKey, String qwenKey) {
        Properties defaults = new Properties();
        try (var stream = GenerationSettings.class.getResourceAsStream("/voices.properties")) {
            if (stream == null) throw new IllegalStateException("Missing voices.properties");
            defaults.load(stream);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Unable to load voice configuration");
        }
        Map<String,String> voices = new LinkedHashMap<>();
        for (String role : List.of("alex", "ben", "maya", "june", "sam", "narrator")) {
            voices.put(role, env.getProperty("STORY_VOICE_" + role.toUpperCase(Locale.ROOT), defaults.getProperty(role)).trim());
        }
        return new GenerationSettings(deepSeekKey, qwenKey,
                env.getProperty("DEEPSEEK_ENDPOINT", "https://api.deepseek.com/chat/completions"),
                env.getProperty("DEEPSEEK_MODEL", "deepseek-flash"),
                env.getProperty("QWEN_TTS_ENDPOINT", ""),
                env.getProperty("QWEN_TTS_MODEL", "qwen-audio-3.1-tts-flash"),
                env.getProperty("FFMPEG_PATH", "ffmpeg"), Map.copyOf(voices),
                env.getProperty("STORY_FIRST_PERSON", "alex").trim().toLowerCase(Locale.ROOT),
                pause(env.getProperty("STORY_PAUSE_MILLIS", "120")),
                env.getProperty("STORY_LLM_PROVIDER", "bailian").trim().toLowerCase(Locale.ROOT),
                env.getProperty("QWEN_LLM_ENDPOINT", ""),
                env.getProperty("QWEN_LLM_MODEL", "qwen3.8-max"));
    }

    boolean usesBailian() { return "bailian".equals(llmProvider); }
    String llmEndpoint() { return usesBailian() ? qwenLlmEndpoint : deepSeekEndpoint; }
    String llmModel() { return usesBailian() ? qwenLlmModel : deepSeekModel; }
    String llmKey() { return usesBailian() ? qwenKey : deepSeekKey; }
    String llmName() { return usesBailian() ? "阿里百炼" : "DeepSeek"; }

    private static int pause(String value) {
        try { return Math.max(0, Math.min(2000, Integer.parseInt(value))); }
        catch (NumberFormatException e) { return 120; }
    }

    GenerationReadiness readiness() {
        List<String> issues = new ArrayList<>();
        if (!List.of("deepseek", "bailian").contains(llmProvider))
            issues.add("STORY_LLM_PROVIDER 必须是 deepseek 或 bailian");
        if ("deepseek".equals(llmProvider) && (deepSeekKey == null || deepSeekKey.isBlank()))
            issues.add("缺少环境变量 DEEPSEEK_API_KEY");
        if (qwenKey == null || qwenKey.isBlank()) issues.add("缺少环境变量 DASHSCOPE_API_KEY");
        if (!endpointValid(llmEndpoint())) issues.add((usesBailian() ? "QWEN_LLM_ENDPOINT" : "DEEPSEEK_ENDPOINT")
                + " 必须是 HTTPS 完整接口地址");
        if (!endpointValid(qwenEndpoint)) issues.add("请配置 QWEN_TTS_ENDPOINT 为所用地域和工作空间的 HTTPS 完整接口地址");
        if (llmModel().isBlank() || qwenModel.isBlank()) issues.add("模型名称不能为空");
        if (voices.values().stream().anyMatch(String::isBlank)) issues.add("角色音色配置不能为空");
        if (!voices.containsKey(firstPersonSpeaker)) issues.add("STORY_FIRST_PERSON 必须是 alex/ben/maya/june/sam/narrator");
        Process process = null;
        try {
            process = new ProcessBuilder(ffmpeg, "-version").redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) issues.add("FFmpeg 不可用，请检查 FFMPEG_PATH");
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            issues.add("FFmpeg 不可用，请检查 FFMPEG_PATH");
        } finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
        return new GenerationReadiness(issues.isEmpty(), List.copyOf(issues), voices, firstPersonSpeaker);
    }

    static boolean endpointValid(String value) {
        try {
            URI uri = URI.create(value);
            return uri.getHost() != null && uri.getUserInfo() == null && uri.getFragment() == null
                    && ("https".equalsIgnoreCase(uri.getScheme()) || ("http".equalsIgnoreCase(uri.getScheme())
                    && List.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())));
        } catch (RuntimeException e) { return false; }
    }

    @Override public String toString() { return "GenerationSettings[credentials=REDACTED]"; }
}
