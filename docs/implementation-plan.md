# EnglishPods 完整 MVP 实现计划

## 本轮交付

用户已取消“每次只实现一个小功能”和“修改前先说明文件”两项规则。本轮连续完成可运行 MVP，保留 Java21、Spring Boot MVC/JDBC、SQLite、原生 HTML/CSS/JS、Java HttpClient、FFmpeg、Maven/JUnit，以及其余项目约束。

已有基础：任务创建和查询、33项测试、Spring Boot3.5.16、SQLite JDBC3.51.0.0、Maven Wrapper。保留原始故事与旧数据库数据。

完成流程：输入故事 → 保存 → 启动生成 → DeepSeek 分角 → DeepSeek 复核 → Qwen 多音色合成及词时间戳 → FFmpeg 合并音频 → 校验及持久化时间轴 → 逐词高亮播放、点词/点句、倍速、单句循环。支持任务历史、失败详情和重试、重启后中断任务恢复为可重试状态。

## 对已有研究的更新

Qwen-Audio-TTS 官方 HTTP 流式接口现在支持 input.word_timestamp_enabled=true，响应包含词级毫秒时间戳。因此 MVP 使用 Qwen 原生时间戳，省去额外 Qwen ForcedAligner 服务。选用官方支持英文和时间戳的 qwen-audio-3.1-tts-flash 音色。请求格式、地域与工作空间地址以官方说明为准。

- Qwen接口：https://help.aliyun.com/en/model-studio/qwen-audio-tts-http-api
- 英语音色：https://help.aliyun.com/en/model-studio/qwen-audio-tts-voice-list
- DeepSeek接口：https://api-docs.deepseek.com/api/create-chat-completion/

Qwen 请求采用 SSE、WAV/PCM 音频、固定24kHz，时间戳保持秒单位写入本地。FFmpeg只做统一采样率/声道与拼接，不做会改变语速或裁切的操作。按实际PCM帧数计算各段偏移及停顿，输出WAV以避免有损编码延迟引入额外偏移。时间戳缺失、非法或不能完整匹配原文时任务失败，不用均分时长伪造高亮。

DeepSeek 默认 deepseek-flash，模型和完整请求端点可配置；两轮均使用严格JSON输出与本地完整性校验。为减少模型计算字符偏移错误，可以让模型返回原文连续片段文本，程序依序精确匹配回原文生成UTF-16区间；任何改写、缺失或重复都拒绝。

## MVP 功能和边界

1. 持久任务：保留创建/查询接口，新增历史列表、生成/失败重试、结果和音频接口。
2. 角色配置：Alex/Ben/Maya/June/Sam/narrator；未知角色保留身份，音色回退Sam；第一人称默认Alex、旁白默认女声，各自可配置；提示词单独资源文件。
3. 生成服务：两轮分角，校验原文完整；按同音色相邻片段和长度限制组织TTS块；Qwen SSE提取完整音频与词时间；合并并映射成词、句子和角色片段。
4. 单体后台工作：SQLite先保存QUEUED，单工作线程扫描领取；状态变更条件更新，HTTP/FFmpeg不占用数据库事务；阶段和错误持久化。重启将运行中任务标记为可重试FAILED，排队任务继续；不引入外部队列。
5. 文件存储：任务UUID与尝试次数限定目录；临时文件生成后才发布最终产物；音频支持HTTP Range以便拖动；路径由服务端确定，不接受用户传入任意路径。
6. 网页：响应式中文界面，英文故事阅读；提交/示例、任务列表、进度阶段、错误/重试、播放器、逐词高亮、句子点读/循环、倍速。未配置服务时清楚提示并保留输入，不伪装生成成功。
7. 设置仅来自服务端配置和环境变量。Key只从环境变量读取，不写进文件、返回前端或日志。启动不要求密钥，调用生成时检查配置。

默认部署面向本机单用户，暂不做登录、多人隔离、云托管、音色克隆、人工精修、批量章节、ASR质量审查。

## 一次执行的工作划分

- 后端任务部分：数据迁移、持久调度、Controller/Service/Repository、配置状态、音频与结果接口及测试。
- 生成部分：DeepSeek/Qwen HTTP适配、音色配置、提示词、SSE与时间轴、FFmpeg及测试。
- 前端部分：HTML/CSS/原生JavaScript界面与播放器、纯逻辑测试。
- 集成：Maven全量验证、旧SQLite迁移与重启测试、HTTP桩完整生成流程、真实FFmpeg、浏览器交互检查、运行文档。

以上执行代理均按用户要求使用GPT-6.1 Sol / high。本轮不在各小步骤之间等待确认。协作接口见 mvp-contract.md。

## 验收与交付说明

- 保留之前测试并适配新增字段；测试不依赖真实密钥、不向外部供应商发送用户故事。
- 用HTTP桩返回结构化分角、SSE词时间戳和测试音频，验证真实HTTP客户端→实际FFmpeg→SQLite→结果接口。
- 覆盖空/截断/非法模型结果、未知人物回退、撇号/弯引号/emoji原文范围、SSE事件去重、错误HTTP状态、失败重试、重复启动冲突、重启恢复、音频Range、路径约束与密钥不泄露。
- 浏览器验证提交、选择历史、错误提示、播放器时间驱动高亮、暂停/拖动/倍速与句子循环。
- 真实云服务调用需要用户环境中的DEEPSEEK_API_KEY、DASHSCOPE_API_KEY与QWEN_TTS_ENDPOINT。本机未发现这些配置，因此交付时如实报告真实服务未联调；不能把桩音频说成Qwen语音。

## 调用路径

浏览器 → TaskController → TaskService → TaskRepository → SQLite（CREATED/QUEUED）

单体工作线程 → SQLite领取任务 → StoryGenerationService → DeepSeek两轮 → Qwen SSE → FFmpeg → 时间轴校验 → 本地文件+SQLite（COMPLETED/FAILED）

浏览器轮询 → 任务/结果接口 → audio.currentTime → 单词高亮与句子循环。
