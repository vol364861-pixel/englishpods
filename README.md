# EnglishPods

Java 21 / Spring Boot 单体英语学习应用：输入英文故事，阿里百炼或 DeepSeek 分角和复核，Qwen 多角色配音并提供单词时间戳，FFmpeg 拼接，网页按播放进度逐词高亮。默认使用 DeepSeek 完成两轮分角；显式设置 `STORY_LLM_PROVIDER=bailian` 时仍可使用百炼文本模型。

前端只使用 HTML、CSS 和原生 JavaScript；任务、阶段、失败信息和最终时间轴保存在 SQLite，音频保存在本机目录。

## 启动

需要 Java 21 和 FFmpeg。项目自带官方 Maven Wrapper，不需要全局安装 Maven。首次构建需要联网下载依赖。

```powershell
Set-Location 'D:\DevelopAI\englishpods'
.\mvnw.cmd clean verify
java -jar .\target\englishpods-0.0.1-SNAPSHOT.jar
```

浏览器打开 http://127.0.0.1:8080 。默认只监听本机，适合单用户本地使用。没有密钥也能启动、保存和浏览故事；生成按钮会说明缺少的配置。

开发运行：`.\mvnw.cmd spring-boot:run`。macOS/Linux 使用 `sh ./mvnw clean verify`。

## 真实模型配置

所有模型型号、供应商和端点集中在 [`.env.example`](.env.example) 中列出，可复制到本机私有 `.env` 后修改。程序的模型配置由 `GenerationSettings` 统一读取；API Key 仍只从进程环境变量读取，不接受 Spring 配置中的密钥。

项目已有的 `run.ps1` 会先将 `.env` 加载到进程环境，再启动 JAR。直接执行 `java -jar` 或 `spring-boot:run` 不会自动加载 `.env`，需要自行传入环境变量。

```powershell
# 仅首次创建，已有 .env 时保留其中的密钥和配置
if (-not (Test-Path -LiteralPath .env)) { Copy-Item .env.example .env }
# 编辑 .env，填写密钥并替换 YOUR_WORKSPACE_ID，然后构建和启动
.\mvnw.cmd clean verify
.\run.ps1
```

`.env` 使用不带引号的 `NAME=value` 格式，支持空行和以 `#` 开头的整行注释。现有启动脚本不做变量展开、去引号或行尾注释解析；文件中的值会覆盖当前进程的同名环境变量。`.env`、`.env.local` 等私有文件已忽略，`.env.example` 保留在版本库。真实密钥只保存在本机私有配置中，加载后通过环境变量传给 Java，不写入 `application.yml`、前端、日志或数据库。

已有 `.env` 时添加或更新以下配置即可，不要覆盖已有密钥：

```dotenv
STORY_LLM_PROVIDER=deepseek
QWEN_LLM_MODEL=qwen3.8-max
DEEPSEEK_MODEL=deepseek-flash
QWEN_TTS_MODEL=qwen-audio-3.1-tts-flash
QWEN_LLM_ENDPOINT=https://YOUR_WORKSPACE_ID.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions
DEEPSEEK_ENDPOINT=https://api.deepseek.com/chat/completions
QWEN_TTS_ENDPOINT=https://YOUR_WORKSPACE_ID.cn-beijing.maas.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer
```

将 `YOUR_WORKSPACE_ID` 换为实际工作空间 ID，并在 `.env` 的 `DASHSCOPE_API_KEY=` 后填写百炼密钥。百炼文本和 TTS 共用该密钥；选择百炼时 `DEEPSEEK_API_KEY` 可以留空。Qwen3.8 在这里用于文本分角，语音仍使用支持词时间戳的 TTS 型号；修改 `QWEN_TTS_MODEL` 时也要检查音色和词时间戳兼容性。

默认使用 DeepSeek，需要 `DEEPSEEK_API_KEY`，TTS 仍需要 `DASHSCOPE_API_KEY`。私有 `.env` 中已有的显式供应商设置优先，不会自动改写。无自动跨供应商降级；供应商、所选型号、端点、提示词或分角结构版本变化会使旧分角检查点失效，下次生成重新执行两轮。没有 `voiceMap` 的旧检查点和旧分角文件不能复用；已生成的历史音频仍保持原样。

也可完全使用进程环境变量，在 PowerShell 中交互输入密钥，避免出现在命令历史：

```powershell
$env:DASHSCOPE_API_KEY = [System.Net.NetworkCredential]::new('', (Read-Host '阿里百炼 API Key' -AsSecureString)).Password
$env:STORY_LLM_PROVIDER = 'bailian'
$env:QWEN_LLM_MODEL = 'qwen3.8-max'
$env:QWEN_LLM_ENDPOINT = 'https://YOUR_WORKSPACE_ID.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/chat/completions'
$env:QWEN_TTS_ENDPOINT = 'https://YOUR_WORKSPACE_ID.cn-beijing.maas.aliyuncs.com/api/v1/services/audio/tts/SpeechSynthesizer'
java -jar .\target\englishpods-0.0.1-SNAPSHOT.jar
```

北京示例使用 `cn-beijing`；新加坡使用 `ap-southeast-1` 地域域名和对应密钥。两个百炼端点应与密钥所属地域和工作空间匹配；文本端点须包含完整的 `/chat/completions`，不能只填 Base URL。配置完整并不代表账号权限已经验证，实际错误会显示在任务中。

| 变量 | 默认值 / 用途 |
| --- | --- |
| STORY_LLM_PROVIDER | deepseek；可显式选 bailian，两轮分角和复核共用此选择 |
| DEEPSEEK_API_KEY | 仅选择 deepseek 时必填 |
| DASHSCOPE_API_KEY | 必填，Qwen 配音和百炼文本共用 |
| DEEPSEEK_ENDPOINT | https://api.deepseek.com/chat/completions |
| DEEPSEEK_MODEL | deepseek-flash，DeepSeek 分角和复核共用型号 |
| QWEN_LLM_ENDPOINT | 选择 bailian 时必填，完整的百炼兼容 Chat Completions 端点 |
| QWEN_LLM_MODEL | qwen3.8-max，百炼分角和复核共用型号 |
| QWEN_TTS_ENDPOINT | 必填，完整Qwen-Audio-TTS HTTP端点 |
| QWEN_TTS_MODEL | qwen-audio-3.1-tts-flash |
| FFMPEG_PATH | ffmpeg，也可指定可执行文件绝对路径 |
| STORY_DB_PATH | englishpods.db，相对于启动时工作目录 |
| STORY_AUDIO_DIR | data/audio，相对于启动时工作目录 |
| STORY_FIRST_PERSON | 默认 alex：第一人称主角 I 的对白固定使用 Alex / Andy_v3.1，人物身份独立保留；可显式指定其他槽位，auto 为按性别推断音色的可选模式 |
| STORY_PAUSE_MILLIS | 120，各合成块间停顿毫秒数，0～2000 |
| STORY_SERVER_ADDRESS | 127.0.0.1 |
| SERVER_PORT | 8080（Spring Boot配置） |

自定义数据库文件路径时先创建父目录。改变启动工作目录会改变默认数据路径，长期使用建议设置绝对路径。修改进程环境后重新启动应用。

百炼调用使用官方兼容 Chat Completions HTTP 协议，设置 `response_format={"type":"json_object"}` 和顶层 `enable_thinking=false`，继续严格校验原文覆盖与 UTF-16 区间。可更换支持该模式的文本型号，无需改 Java 代码。型号与参数依据 [百炼 Chat API](https://help.aliyun.com/zh/model-studio/qwen-api-via-openai-chat-completions/) 和 [思考模式说明](https://help.aliyun.com/zh/model-studio/deep-thinking/)；不支持关闭思考或 JSON 输出的型号不适用于当前分角请求。

## 角色、音色与提示词

角色身份由故事实际人物决定，不限制为六人。故事明确出现 Alex、Ben、Maya、June、Betty 或 Sam 时，同名角色固定使用对应音色；其他角色保留自己的小写身份（例如 `lily`），由大模型结合姓名、代词和人物描述推断性别后分配音色，明确代词与描述优先。男声池为 alex/ben/sam，女性对白池为 maya/june/betty，性别无法推断时才使用 unknown/sam。人物多于音色时允许复用声音，每个人的身份和音色仍保持稳定。人物被提及或被称呼不会自动变成该人物的对白。旁白与第一人称角色分别配置。第一人称主角 I 默认固定使用 alex（Andy_v3.1）；即使故事揭示主角真实姓名或性别，也保留其身份并继续使用该固定音色。该规则优先于同名和性别分配规则。其他角色对白里的 I 不代表故事主角。I said 等叙述文字继续使用 narrator（Beth_v3.1）。

| 角色 | 默认英语音色 | 环境变量覆盖 |
| --- | --- | --- |
| Alex | Andy_v3.1 | STORY_VOICE_ALEX |
| Ben | Brian_v3.1 | STORY_VOICE_BEN |
| Maya | Abby_v3.1 | STORY_VOICE_MAYA |
| June | Annie_v3.1 | STORY_VOICE_JUNE |
| Betty | Betty_v3.1 | STORY_VOICE_BETTY |
| Sam / 性别未知时回退 | David_v3.1 | STORY_VOICE_SAM |
| 旁白（独立） | Beth_v3.1 | STORY_VOICE_NARRATOR |

默认映射在 `src/main/resources/voices.properties`。音色必须与所选 Qwen 模型兼容且支持词时间戳。Beth_v3.1 只用于旁白，不属于女性对白池；将任何对白槽位覆盖为 Beth 时，就绪检查会报错。

第一轮与第二轮提示词分别在 `src/main/resources/prompts/segment.txt` 和 `review.txt`。两轮均返回 `{segments, voiceMap}`，其中 `voiceMap` 形如 `{"lily":{"gender":"female","voiceId":"betty","firstPerson":false}}`。复核请求同时携带原文、上一轮分段和映射；后端校验每个对白角色都有映射、最多一个 firstPerson 主角、普通角色的性别与同名固定音色规则，以及旁白不进入对白映射。firstPerson=true 的主角由服务端强制应用 STORY_FIRST_PERSON 固定音色，再用于实际 TTS 请求；auto 模式保留推断音色。

前端左侧书架、品牌和服务设置均可收起；收起后只保留左上角竖排的展开、新建故事两个图标，并记住展开状态。手机首次访问默认收起。

两轮检查点在 SQLite 的 `initial_segments_json` / `reviewed_segments_json` 中同时保存片段和映射；尝试目录的 `segments-initial.json` / `segments-reviewed.json` 使用相同对象结构（包含 UTF-16 区间），最终 `timeline.json` 及结果 API 也含 `voiceMap`，便于核对。音色提供商 ID 仍由服务端槽位配置解析。修改提示词后重新构建；修改环境变量覆盖值后重新启动即可。

## 使用流程

1. 输入标题和英文原文，或载入书店示例，点击“保存并生成”。
2. 配置未齐全时故事先保存，补齐后点击“开始生成”。
3. 页面显示识别角色、复核分角、合成配音、整理音轨的阶段；可以刷新或稍后从书架返回。
4. 完成后点击播放，绿色标记随单词变化；点击单词跳转、点击句子点读，支持倍速、前后5秒、单句循环和上下句。
5. 左上角可收起书架，收起后仅保留展开与新建故事两个图标；选择会在本机浏览器中记住。
6. 失败任务保留原文和安全错误信息，可重新生成。重试复用有效的分角和复核检查点，从第一个配音块开始合成，可能产生新的 API 费用。

分角和字幕保留完整原文，包括标点和空白。`That's`、`Sam's` 等缩写或所有格按原文显示。对 0 至 9999 的独立整数，如果 Qwen 返回完整等值的英文基数词读法，字幕会保留原数字，并使用首尾朗读词的实际时间。缺词、错误读法和无法校验的时间戳会使该块失败。

## API

| 方法与路径 | 说明 |
| --- | --- |
| POST /api/tasks | `{title,text}` 创建故事，201 + Location，状态CREATED |
| GET /api/tasks | 最近30项任务 |
| GET /api/tasks/{id} | 原文、状态、stage、error、attempt与创建/更新时间 |
| POST /api/tasks/{id}/generate | 启动或重试，202；重复启动409；配置缺失503 |
| GET /api/tasks/{id}/result | 完成后的原文、片段、词、句子、时长和audioUrl |
| GET /api/tasks/{id}/audio | WAV音频，支持HTTP Range以便拖动 |
| GET /api/config | 就绪状态、待配置项、角色音色；不返回密钥 |

标题必填、非空白、最多200个UTF-16码元；正文必填、非空白、最多50,000码元。MVP建议先使用短篇。原文字符区间统一UTF-16左闭右开，时间轴单位为秒。无效JSON、字段或UUID返回400，任务不存在404，未完成任务获取结果409。

```powershell
$body = @{ title = 'A short story'; text = 'Maya smiled. "Hello, Alex," she said.' } | ConvertTo-Json
$task = Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:8080/api/tasks' -ContentType 'application/json; charset=utf-8' -Body ([Text.Encoding]::UTF8.GetBytes($body))
Invoke-RestMethod -Method Post -Uri "http://127.0.0.1:8080/api/tasks/$($task.id)/generate"
Invoke-RestMethod -Uri "http://127.0.0.1:8080/api/tasks/$($task.id)"
```

## 持久化与恢复

SQLite是任务事实来源，单工作线程从数据库领取排队任务，不引入Redis或外部队列。模型HTTP调用和FFmpeg处理不占用数据库事务。任务状态为：

CREATED → QUEUED → SEGMENTING → REVIEWING → SYNTHESIZING → ASSEMBLING → COMPLETED；任一阶段失败记为FAILED。

关闭或崩溃后的处理中任务会在下次启动时变为可重试FAILED；QUEUED继续处理。旧版本只支持CREATED的表会原子迁移，保留已有任务。数据库使用单连接和busy_timeout，本版只运行一个应用实例。

音频位于 `data/audio/<任务UUID>/<attempt>/story.wav`。中间音频、两轮分角和时间轴也留在该尝试目录便于检查；不会把签名下载地址或密钥存进去。MVP尚未提供自动清理，备份时一并保存SQLite和音频目录。

合成块失败时，任务错误会给出第几块、从0开始的 `chunkIndex`、源文UTF-16左闭右开范围及具体校验条件。该尝试目录中的 `qwen-failure.json` 保存安全错误和时间戳白名单数值（原始 `begin_time/end_time` 单位毫秒，`previousEndSeconds` 单位秒）；不会保存完整SSE、词文本、签名URL或密钥。非有限或非数字字段记为null。文件写入失败时仍保留任务错误。纯标点、引号、空白不创建高亮词，也不参与朗读词时间/索引顺序；朗读词的覆盖、次序、正时长和实际音频时长仍严格校验。

目前没有独立日志管理页面或默认日志文件，可在三处查看错误：

1. 页面打开失败任务的详情；相同安全错误也可通过 `GET /api/tasks/{id}` 的 `error` 字段查看，并保存在SQLite的 `story_tasks.error` 中。
2. 使用新版本后，打开对应尝试的 `data/audio/<任务UUID>/<attempt>/qwen-failure.json` 查看块范围和时间戳数值；自定义 `STORY_AUDIO_DIR` 时使用该目录。旧失败发生时未保存该诊断，更新后不会自动补回。
3. Spring Boot启动信息和运行日志在启动终端或IDE控制台。默认没有配置文件日志，被任务工作线程捕获的生成错误未必另行打印，排查生成失败应优先查看任务详情和诊断文件。

更新后先停止旧应用，再从配置了环境变量的终端运行新 JAR。已有失败任务由用户在页面点击“重新生成”。系统会创建新尝试目录，并保留旧尝试文件。仅有中间音频、缺少原始词时间戳时无法可靠恢复字幕。故障证据与实际恢复结果见 [Qwen 时间戳排查](docs/qwen-timestamp-failure.md)。

## 字幕与音频路线

使用 Qwen-Audio-TTS 流式 HTTP 接口的原生词时间戳。一个配音块用三个 seed 合成后仍出现朗读词零时长时，应用可在词间边界拆分该块，用同一音色重新合成原文子段。每个原始块最多拆分两次。FFmpeg 将音频转换为 24 kHz 单声道 PCM，按实际帧数计算时间轴并输出 WAV。配置的停顿加在原始块之间。播放器直接使用 `audio.currentTime`，倍速时不再次乘速度。

这一版不依赖另行部署的强制对齐服务。Qwen无有效词时间戳时不会用均分时长替代，也不会自动使用假音频。

- [Qwen HTTP接口](https://help.aliyun.com/en/model-studio/qwen-audio-tts-http-api)
- [Qwen英语音色](https://help.aliyun.com/en/model-studio/qwen-audio-tts-voice-list)
- [DeepSeek Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)

## 测试与调用路径

```powershell
.\mvnw.cmd clean verify
node --test src/test/js/timeline.test.mjs src/test/js/task-progress.test.mjs
```

Java测试包括真实SQLite、旧表迁移、状态/重试/恢复、音频Range，以及本地HTTP桩→真实Java HTTP客户端→实际FFmpeg→SQLite的整链验证。新增测试覆盖 DeepSeek / 百炼两轮请求的端点、鉴权、型号与 JSON 参数、独立 TTS 型号、供应商及型号/端点变化后的检查点失效、未启用供应商配置的隔离，以及失败响应和原文保护。网络桩只用于测试，生产应用没有假模型降级。测试音频不代表Qwen真人声，也不验证真实账号的模型权限。

前端测试覆盖时间区间、静音区间、拖动后查词、循环边界、UTF-16切片和原文保留。详细实际验收结果见 `docs/verification.md`。

请求路径：浏览器 → TaskController → TaskService → TaskRepository → SQLite。

生成路径：TaskWorker → StoryGenerationService → StorySegmenter → Java HttpClient → 按 `STORY_LLM_PROVIDER` 选择百炼或 DeepSeek 完成分角/审校 → QwenSpeechClient 配音/词时间戳 → FFmpeg → 本地文件及 SQLite 结果。分角检查点经 SegmentationCheckpointRepository 写入 SQLite，并按当前供应商、端点、型号、第一人称配置和提示词决定是否复用。

播放路径：结果/音频接口 → 原生audio → currentTime驱动的单词高亮和句子循环。

本版面向本机单用户，未实现账号隔离、公开部署、音色克隆或广播剧精修。后续设计见 `docs/implementation-plan.md`，原始调研保存在 `原始需求和具体功能实现参考.md`。
