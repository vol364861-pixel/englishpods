package com.englishpods.generation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;
import static org.junit.jupiter.api.Assertions.*;

class GenerationSettingsTest {
    @Test void defaultsUseDeepSeekAndDedicatedNarratorAndThreeFemaleSlots() {
        var config = GenerationSettings.from(environment(), "test-only-deepseek", "test-only-qwen");
        assertEquals("deepseek", config.llmProvider());
        assertEquals("alex", config.firstPersonSpeaker());
        assertEquals("Andy_v3.1", config.voices().get("alex"));
        assertEquals("Beth_v3.1", config.voices().get("narrator"));
        assertEquals("Betty_v3.1", config.voices().get("betty"));
        assertEquals("Abby_v3.1", config.voices().get("maya"));
        assertEquals("Annie_v3.1", config.voices().get("june"));
        assertTrue(config.readiness().ready());
        assertTrue(GenerationSettings.from(environment().withProperty("STORY_FIRST_PERSON", "betty"),
                "test-only-deepseek", "test-only-qwen").readiness().ready());
    }

    @Test void bethCannotBeAssignedToDialogueEvenByEnvironmentOverride() {
        var config = GenerationSettings.from(environment().withProperty("STORY_VOICE_MAYA", "Beth_v3.1"),
                "test-only-deepseek", "test-only-qwen");
        assertTrue(config.readiness().issues().stream().anyMatch(s -> s.contains("Beth_v3.1 仅用于旁白")));
        var firstPerson = GenerationSettings.from(environment().withProperty("STORY_FIRST_PERSON", "narrator"),
                "test-only-deepseek", "test-only-qwen");
        assertFalse(firstPerson.readiness().ready());
    }
    private MockEnvironment environment() {
        return new MockEnvironment()
                .withProperty("QWEN_LLM_ENDPOINT", "https://workspace.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions")
                .withProperty("QWEN_TTS_ENDPOINT", "https://workspace.cn-beijing.maas.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer");
    }

    @Test void explicitBailianQwen38NeedsNoDeepSeekConfiguration() {
        var config = GenerationSettings.from(environment().withProperty("STORY_LLM_PROVIDER", "bailian").withProperty("DEEPSEEK_ENDPOINT", "invalid")
                .withProperty("DEEPSEEK_MODEL", ""), null, "test-only-qwen");
        assertEquals("bailian", config.llmProvider());
        assertEquals("qwen3.8-max", config.llmModel());
        assertEquals("qwen-audio-3.1-tts-flash", config.qwenModel());
        assertEquals("test-only-qwen", config.llmKey());
        assertTrue(config.readiness().ready(), () -> config.readiness().issues().toString());
    }

    @Test void deepSeekIgnoresInactiveBailianModelAndEndpoint() {
        var config = GenerationSettings.from(environment().withProperty("STORY_LLM_PROVIDER", " DeepSeek ")
                .withProperty("QWEN_LLM_ENDPOINT", "invalid").withProperty("QWEN_LLM_MODEL", ""),
                "test-only-deepseek", "test-only-qwen");
        assertEquals("deepseek", config.llmProvider());
        assertEquals("test-only-deepseek", config.llmKey());
        assertEquals("deepseek-flash", config.llmModel());
        assertTrue(config.readiness().ready());
        assertTrue(GenerationSettings.from(environment().withProperty("STORY_LLM_PROVIDER", "deepseek"), null,
                "test-only-qwen").readiness().issues().stream().anyMatch(s -> s.contains("DEEPSEEK_API_KEY")));
    }

    @Test void everyModelCanBeOverriddenIndependently() {
        var env = environment().withProperty("STORY_LLM_PROVIDER", "bailian").withProperty("DEEPSEEK_MODEL", "custom-deepseek")
                .withProperty("QWEN_LLM_MODEL", "custom-qwen-text")
                .withProperty("QWEN_TTS_MODEL", "custom-qwen-speech");
        var bailian = GenerationSettings.from(env, "test-only-deepseek", "test-only-qwen");
        assertEquals("custom-qwen-text", bailian.llmModel());
        assertEquals("custom-qwen-speech", bailian.qwenModel());
        var deepseek = GenerationSettings.from(env.withProperty("STORY_LLM_PROVIDER", "deepseek"),
                "test-only-deepseek", "test-only-qwen");
        assertEquals("custom-deepseek", deepseek.llmModel());
        assertEquals("custom-qwen-speech", deepseek.qwenModel());
    }

    @ParameterizedTest @ValueSource(strings = {"", "qwen", "unknown"})
    void invalidProviderCannotSilentlySelectAnotherProvider(String provider) {
        var config = GenerationSettings.from(environment().withProperty("STORY_LLM_PROVIDER", provider),
                "test-only-deepseek", "test-only-qwen");
        assertFalse(config.readiness().ready());
        assertTrue(config.readiness().issues().stream().anyMatch(s -> s.contains("STORY_LLM_PROVIDER")));
    }

    @Test void validatesActiveEndpointModelAndEnvironmentOnlyCredentials() {
        var env = environment().withProperty("STORY_LLM_PROVIDER", "bailian").withProperty("QWEN_LLM_ENDPOINT", "http://external.invalid/chat/completions")
                .withProperty("QWEN_LLM_MODEL", " ")
                .withProperty("DASHSCOPE_API_KEY", "property-key-must-not-be-used");
        var config = GenerationSettings.from(env, null, null);
        var issues = config.readiness().issues();
        assertTrue(issues.stream().anyMatch(s -> s.contains("QWEN_LLM_ENDPOINT")));
        assertTrue(issues.stream().anyMatch(s -> s.contains("模型名称不能为空")));
        assertTrue(issues.stream().anyMatch(s -> s.contains("DASHSCOPE_API_KEY")));
        assertFalse(issues.toString().contains("property-key-must-not-be-used"));
        assertFalse(config.toString().contains("property-key-must-not-be-used"));
    }
}
