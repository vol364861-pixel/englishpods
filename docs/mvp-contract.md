# MVP 协作契约

## HTTP（字段名为camelCase）

- POST /api/tasks {title,text} → 201，保留旧响应字段。
- GET /api/tasks → 最新30项任务数组（TaskResponse）。
- GET /api/tasks/{id} → TaskResponse。
- TaskResponse包含旧字段id,title,text,status,createdAt,updatedAt，新增stage（字符串或null）、error（安全可读字符串或null）、attempt（整数）。
- POST /api/tasks/{id}/generate 无请求体 → 202 TaskResponse，允许CREATED/FAILED，其他状态409；配置缺失503错误JSON。保存任务仍不需要密钥。
- 状态：CREATED/QUEUED/SEGMENTING/REVIEWING/SYNTHESIZING/ASSEMBLING/COMPLETED/FAILED；stage同生成阶段或null。
- GET /api/tasks/{id}/result → GenerationResult（只有COMPLETED可读，否则409）。
- GenerationResult {text,audioUrl,duration,segments,words,sentences}。
- segment {id,kind,speakerId,sourceStart,sourceEnd}，kind=narration/dialogue。
- word {id,text,sourceStart,sourceEnd,start,end,sentenceId}。
- sentence {id,text,sourceStart,sourceEnd,start,end}。
- 所有source范围为UTF-16左闭右开，时间为秒，字词显示取原文。时间边界合法、顺序单调。
- GET /api/tasks/{id}/audio → audio/wav（支持Range）。audioUrl由后端设置为此路径。
- GET /api/config → {ready,issues,voices,firstPersonSpeaker}，issues为可读字符串数组，voices为角色→音色ID映射，不包含密钥。ready表示配置齐全和本地FFmpeg可用，不代表真实远端账号验证成功。
- 错误JSON沿用 {message,fieldErrors}。

## Java 生成模块（由生成代理负责）

包 com.englishpods.generation，提供Spring Bean：

public class StoryGenerationService {
  public GenerationResult generate(String text, java.nio.file.Path directory, java.util.function.Consumer<String> stageChanged);
  public GenerationReadiness readiness();
}

generate在directory内生成最终story.wav并返回时间轴，不访问数据库；网络/FFmpeg失败抛GenerationException（安全消息，不能含密钥或签名URL）。stageChanged接收SEGMENTING/REVIEWING/SYNTHESIZING/ASSEMBLING。

模块公开记录类均位于上述generation包，public独立文件：

record GenerationResult(String text, String audioUrl, double duration, List<StorySegment> segments, List<TimedWord> words, List<TimedSentence> sentences) {}
record StorySegment(String id, String kind, String speakerId, int sourceStart, int sourceEnd) {}
record TimedWord(String id, String text, int sourceStart, int sourceEnd, double start, double end, String sentenceId) {}
record TimedSentence(String id, String text, int sourceStart, int sourceEnd, double start, double end) {}
record GenerationReadiness(boolean ready, List<String> issues, Map<String,String> voices, String firstPersonSpeaker) {}

生成模块返回audioUrl为空；任务Service保存JSON前用当前taskId设置audioUrl。将完整result JSON存入SQLite即可，无需为每个词额外建表。生成分段中间成果可保存任务尝试目录JSON，错误时保留用于重试诊断。重试全量重新生成（可能产生新费用），MVP暂不跨尝试缓存。

## 文件归属

- 根代理：AGENTS.md、docs、最终README、集成检查。
- 核心代理：task包、数据库迁移/调度类、application.yml、schema.sql、核心Java测试、.gitignore。不要编辑generation包或static文件。
- 生成代理：generation包、resources/prompts、resources/voices.properties、生成JUnit测试。环境配置通过Environment读取，避免编辑application.yml。
- 前端代理：resources/static、src/test/js（node --test），不改Java/配置/README。

## 环境变量与生成约定

DEEPSEEK_API_KEY、DASHSCOPE_API_KEY仅从System.getenv读取（测试可通过构造函数显式注入桩凭证）。DEEPSEEK_ENDPOINT默认https://api.deepseek.com/chat/completions；DEEPSEEK_MODEL默认deepseek-flash；QWEN_TTS_ENDPOINT必须配置完整官方地域/工作空间URL；QWEN_TTS_MODEL默认qwen-audio-3.1-tts-flash；FFMPEG_PATH默认ffmpeg。

音色默认：alex=Andy_v3.1、ben=Brian_v3.1、maya=Abby_v3.1、june=Annie_v3.1、sam=David_v3.1、narrator=Emily_v3.1。可通过独立properties和对应环境变量覆盖。STORY_FIRST_PERSON默认alex。后台数据目录STORY_AUDIO_DIR默认data/audio；数据库STORY_DB_PATH默认englishpods.db。

Qwen官方POST input包含text,voice,format=wav,sample_rate=24000,language_hints=[en],word_timestamp_enabled=true；header X-DashScope-SSE:enable。解析SSE data JSON；output.sentence.words中的text,begin_index,end_index,begin_time,end_time；output.original_text及sentence.index辅助定位；成功finish_reason=stop；audio.url提供完整音频，或将audio.data按顺序汇集。下载音频不带API密钥、不记录完整签名URL，限制协议/重定向与长度，HTTP有超时和有限重试。每个TTS块尽量单句并且低于接口长度限制，以便消除句内索引和时间歧义；词文本必须顺序匹配原文，禁止凭时长生成时间戳。

使用PCM/WAV最终输出，实际合并偏移按音频帧数计算；最终校验时间轴不超音频时长。样例/桩只能在测试中，不自动用假音频替代配置缺失。
