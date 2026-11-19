# 分角检查点与 Qwen 音频下载修复

## 问题与已确认事实

历史报错 `Qwen 块 1（chunkIndex=0，源UTF-16 [0,69)）：音频下载 网络请求失败或响应过大` 发生在分角复核之后、第一块 Qwen 音频获取期间。旧客户端把所有 IOException 合并成同一句错误，历史记录没有异常类别，因此不能反推 DNS、TLS、超时或超限中的哪一种原因。

此前程序已收集 SSE 的 `output.audio.data`，但只要终帧还提供 `output.audio.url`，就放弃流内字节并额外下载 URL。官方 WAV SSE 协议允许按顺序拼接 audio.data，stop 终帧可以同时提供有时效的 URL。这使本已通过 SSE 返回的音频额外依赖 OSS 下载网络。

修复保持请求 `format=wav`、24kHz。收到 stop、完整词时间戳并通过原文对齐后，优先使用按顺序拼接的流字节；没有流字节的 URL-only 响应仍走受限下载。实际容器、PCM 帧数与词时间是否超出音频时长继续由 FFmpeg 和原有管线验证。不会把任意二进制猜成 raw PCM；坏流字节会明确失败，不静默从 URL 替换。仅出现 stop 并不能证明音频已正确解码。

官方协议：[Qwen TTS HTTP API](https://help.aliyun.com/en/model-studio/qwen-audio-tts-http-api)。

## 持久化行为

`story_tasks` 增量增加以下字段，启动时可重复迁移，保留原任务状态、原文、错误、attempt 和产物路径：

- `initial_segments_json`、`reviewed_segments_json`：经过本地校验的两轮有效 LLM 输出。
- `checkpoint_source_hash`、`checkpoint_config_hash`：精确原文及分角相关配置的 SHA-256 指纹。
- `checkpoint_metadata`：格式版本、DeepSeek 模型、第一人称设置、提示词及端点的指纹。
- `completed_stage`：已持久化的分角阶段 SEGMENTING / REVIEWING。
- `checkpoint_origin`：新生成或旧 attempt 导入来源；旧记录明确标注 CONFIG_UNKNOWN。
- `reused_stage`：当前 attempt 实际跳过的分角阶段。

以上内容不包含 API Key、签名音频 URL、原始 HTTP 响应或供应商错误正文。API Key 仍仅来自环境变量。

每轮 DeepSeek 输出先经过原文完整性校验，再用单条 SQLite UPDATE 写入有效结果，提交后才进行下一轮外部请求。进程重启或 Qwen 失败不会丢掉检查点。任务重试仍增加 attempt 并使用独立产物目录，保留失败 attempt 的诊断；不保留音频块的恢复点。

重试规则：

1. 原文和分角配置指纹匹配，复核结果有效：跳过两次 DeepSeek，从 Qwen 重新合成。
2. 只有有效初分：跳过初分，仅执行复核，再合成。坏复核会同步清除其持久化完成标记，即使后续复核再次失败也不会声称复核完成。
3. 缓存损坏、范围不合法、未完整覆盖原文、切开 UTF-16 代理对时，拒绝该缓存并安全重新调用对应分角阶段。
4. 原文、DeepSeek 端点/模型、第一人称设置、任一分角提示词或分角结构版本变化，重新执行两轮。音色、Qwen 模型和停顿设置不影响分角复用。
5. 配置变更后不会用旧 attempt JSON 绕过指纹失效。

## 已有失败任务的兼容

在旧任务首次重试时，若该任务尚无任何检查点指纹，按 attempt 从新到旧寻找可用的旧 `segments-initial.json` / `segments-reviewed.json`。只读取该任务受控目录的普通文件，拒绝符号链接与超限文件，逐片校验类型、角色、整数范围、连续完整覆盖和 UTF-16 边界；选择最新含有效结果的 attempt，不能让更旧的复核覆盖更新的初分。


旧文件仅存角色和偏移，没有历史模型、提示词、第一人称指纹，也没有历史原文全文哈希。兼容逻辑依赖同一任务原文在当前 API 中不可编辑这一约束；完整范围校验并不能独立证明历史文本完全一致。导入来源及 API/UI 明确保留历史配置不可追溯标记，导入时绑定当前配置供之后失效判断。若在数据库外人工改动过任务原文，应创建新任务重新分角，不能把旧导入当成历史配置相同的证据。

## HTTP 与界面

下载继续不附带供应商 Authorization，不跟随重定向，保留 HTTPS 校验（本地 HTTP 仅用于开发桩）及原有已知 OSS HTTP 升 HTTPS 规则。响应上限仍是 32MiB，不通过盲目增大限制掩盖错误。

HTTP IOException 按原因链区分请求超时、TLS 安全连接失败、DNS 解析失败、网络连接失败、网络传输失败、响应超限。超限立即失败，不重复下载三次；其余原有有限重试保留。消息只保留安全类别/HTTP 状态/数值限额，不写入异常原文、URL、Key 或响应正文。

任务 API 新增 `completedStage`、`checkpointOrigin`、`reusedStage`。失败状态和 `stage` 原样保留，已完成分角不代表整篇音频完成。前端显示保存点、实际复用情况、历史导入边界和出错阶段。重试说明明确：将校验复用分角，配音与拼接重新执行并可能产生 API 费用。

## 请求、业务与数据路径

浏览器重试 → `TaskController.generate` → `TaskService.enqueue` → `TaskRepository.enqueue` → SQLite 排队，attempt 增加但保留分角检查点。

`TaskWorker.processNext` → SQLite 领取任务 → `StoryGenerationService.generate` → `SegmentationCheckpoints` → `SegmentationCheckpointRepository` → SQLite 校验/读取/写入有效分角；按需要调用 `StorySegmenter` → `GenerationHttpClient` → DeepSeek。受控 task UUID/attempt 目录用于关联持久任务，原有无持久化构造器仅供独立生成嵌入与基础测试。

随后 `QwenSpeechClient` → Java HTTP Client 获取 SSE → 优先流内 WAV 或受限 URL 下载 → `AudioAssembler` 调用 FFmpeg 规范化/拼接 → 校验时间轴 → 本地产物与 SQLite 完成记录。失败仍由 worker 写入 FAILED 和当前错误阶段。

浏览器轮询 → `TaskResponse` 返回安全的保存点摘要 → 原生 JavaScript 展示；成功后播放链路不变。

## 验证范围与限制

新增 JUnit 覆盖实际本地 HTTP + FFmpeg 多帧 WAV（终帧不可达 URL）、URL-only 原流程、Qwen 失败后数据库重新连接复用两轮、复核失败仅复用初分、配置/模型/源文失效、音色修改仍复用、坏缓存降级、旧 attempt 导入与最新初分优先、HTTP 原因分类、超限不重试、worker → SQLite → API 重试集成。Node 测试覆盖保存点界面文案且不把分角完成当作音频完成。

所有供应商请求由本地 HTTP 桩返回，测试使用临时 SQLite/目录，不将真实失败任务重新排队。本机若没有环境密钥和地域端点，不能执行真实供应商复测；本修复不证明历史网络根因已经消失，也不把静音桩音频称为 Qwen 实际配音。本轮亲跑 `./mvnw.cmd -q verify`：72 项 Java 测试通过（0 failure / 0 error / 0 skipped）；`node --test src/test/js/*.test.mjs`：10 项通过；`app.js` 语法检查通过。
