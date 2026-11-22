# Qwen 词时间戳失败排查（2026-11-18）

## 最新结果（attempt 5）

通过 `run.ps1` 启动新版本后，原任务 `e51c2a9f-3910-43ef-8f45-f3ef7fb123f5` 的第 5 次尝试完成。任务复用已复核的分角检查点，保留 977 个 UTF-16 码元的原文。结果包含 21 个分角片段、25 个句子和 185 个单词。SQLite 中的任务状态为 `COMPLETED`，`PRAGMA integrity_check` 返回 `ok`。生成的 WAV 为 24 kHz 单声道 PCM，1,613,760 帧，共 67.24 秒。音频 Range 请求返回 HTTP `206`。

| 原文词 | 原文 UTF-16 区间 | 最终音轨时间 |
| --- | --- | --- |
| `the` | `[618,621)` | 41.36–41.44 秒 |
| `2008` | `[766,770)` | 52.12–53.32 秒 |

Chrome 中显示了全部 185 个词。点击 `the` 和 `2008` 分别跳转到 41.36 秒和约 52.122 秒，播放器随后继续播放。最终源码通过全部 94 项 JUnit 测试和 10 项 Node 测试。

## 早期失败（attempt 1）的证据

只读检查已有SQLite任务和第1次尝试目录：任务在 SYNTHESIZING 阶段失败，旧错误为“Qwen 词时间戳顺序、字符范围或时长无效”。目录存在 chunk-0 至 chunk-17 的中间音频及 combined.pcm，缺少最终 story.wav 与 timeline.json。原始SSE响应未保留，因此不能还原究竟哪个词、哪个字段触发旧混合条件。

用原始977个UTF-16码元正文、已有 segments-reviewed.json、Java 21 的英语 BreakIterator 以及当前分块合并规则复原：从0开始的 chunk18（第19块）源范围为 `[591,653)`，原文为 `There were five circles on the map, with a line between them. `（末尾有一个空格）。生成按块串行进行，已有18块音频且旧错误来自音频追加前的align，因此推断失败发生在此块。下一块 chunk19 才是含 café 的句子，数字2008也不在失败块中，不能把它们视作此故障原因。

## 历史失败（attempt 3）的确定证据

后续真实重试已经生成安全诊断文件 `data/audio/e51c2a9f-3910-43ef-8f45-f3ef7fb123f5/3/qwen-failure.json`。这次不再只是根据中间文件推断：文件明确记录 chunkIndex=18、源UTF-16 `[591,653)`、condition=word_duration、tokenIndex=5、sourceLength=62、lexicalCursor=22、previousEndSeconds=1.28、previousEndIndex=5。当前真实词的 lexicalLength=3、begin_index=5、end_index=6、begin_time=1280、end_time=1280。结合保留原文与前面各token词形长度，第6个朗读词是 `the`，供应商给它的时长为0毫秒。这次是实际朗读词的零时长，并非标点占位；早期标点适配修复仍然保留，两次证据不应混为同一原因。

安全数字快照已加入本地测试资源，测试只结合上述原句重建token，不依赖生产目录、真实密钥或供应商调用。该快照可以稳定复现原有严格校验的拒绝结果。它不能证明供应商为何生成零时长，也不能保证换seed必定成功。

## 可证实的代码缺陷与修复

旧align虽然注释说跳过标点，实际却在跳过前校验标点的时间和字符区间，并更新previousEnd/previousIndex。标点若带占位索引0/0、零时间、与前词重叠的时间，或者延伸到下一朗读词之后，会误拒绝本来有效的朗读词序列。以有效Hi/Sam词时间加逗号0/0占位可本地稳定复现旧逻辑错误；回归测试覆盖修复后的接受结果。

现在先跳过不含字母或数字的token；它们仍保留在原文中，但不创建单词字幕，不影响相邻朗读词的时钟和索引。所有格、缩写、连字符的合法分词继续按词形对齐回原文UTF-16范围。真实词仍要求字段有效、索引可转int且有界有序、有限时间、正时长、时间不倒退或重叠、完整匹配原文词形，不允许一个token跨多个原文词而伪造各词时间。实际音频帧时长验证保留。

这证明代码存在可修复的标点适配问题，不能证明旧任务实际返回了这种标点，也不能保证真实重试一定成功。没有用放宽真实词检查、重写故事、均分时长来掩盖问题。

## 新诊断

每个块的安全错误包含用户可读块序号、chunkIndex（从0开始）、源文UTF-16范围。align错误进一步区分：

| condition | 意义 |
| --- | --- |
| fields | 字段缺失、类型无效或索引超int范围 |
| non_finite_time | 时间不是有限数 |
| time_order | 真实词时间倒退或重叠 |
| word_duration | 真实词零/负时长 |
| index_range | 字符范围越界或为空 |
| index_order | 真实词字符范围倒退或重叠 |
| lexical_mismatch | 词形不能匹配原文，可能漏读、改写或包含不支持的数字读法 |
| incomplete_coverage | 没有完整覆盖原文词 |
| cross_source_words | 供应商token跨多个原文单词，无法保证逐词高亮 |

失败尝试目录保存 qwen-failure.json：包含块范围、安全错误及alignment白名单数字快照（tokenIndex、lexicalCursor、前词结束时间和索引、各token的lexicalLength、begin_index/end_index/begin_time/end_time）。current指向报错token；token序号包含跳过的非词token。供应商原始时间单位毫秒，previousEndSeconds单位秒。非数字和非有限值仅记null。词文本、完整SSE、URL、请求头、密钥都不保存；文件不足以单独回放词形匹配，需结合保留原文及之后的真实响应排查。最终时间戳异常另附 `alignment.synthesisAttempts` 与 `alignment.seeds`（例如3与 `[0,1,2]`），SQLite及任务接口错误同时包含易读的尝试次数与seed。保留最后一次异常的原有condition/current/timings，不保存中间失败响应。若磁盘写入失败，原任务错误仍保留。

## 首轮修复的整块有限重合成

`QwenSpeechClient.synthesize` 首次显式使用 `input.seed=0`。只有真实词触发 `word_duration` 且原始 `begin_time == end_time` 时，才为当前块重新请求合成，后续依次使用seed 1和2；包括首次在内最多3次。text、voice、模型、音频格式、采样率、语言提示及其他合成参数保持一致，语速没有改动。官方说明seed默认0、范围0到65535，相同seed与模型参数可复现相同输出，改变seed会产生不同结果，因此重复零时长时需要改变seed。[Qwen TTS HTTP API官方文档](https://help.aliyun.com/en/model-studio/qwen-audio-tts-http-api)

parse/align继续严格拒绝每个零/负时长真实词，不补成1毫秒、不均分邻词时间、不改写故事或删除单词。负时长、时间倒退/重叠、词形错误、覆盖不完整以及其他非目标错误直接停止这类重合成；若先遇到零时长、下一次遇到非目标错误，也立即停止。每次仅处理自己的响应，时间戳校验成功后才下载URL音频，且只有最终成功响应的音频与时间戳返回给AudioAssembler。失败响应中的音频不追加，前面成功的块在同一次任务内不重做。线程中断会阻止继续请求、解析及开始下一次尝试。

这3次是“获得有效词时间戳的合成尝试”，与既有HTTP传输层重试不同：每次合成请求中的HTTP 429、5xx或可重试网络错误仍最多发送3次相同请求，使用同一个seed；传输重试耗尽会直接失败，不触发额外的时间戳重合成。如果每个seed都先遇到可恢复的传输失败，再获得零时长响应，整个块最多可能发送9个POST。成功重合成和HTTP重试均可能增加供应商费用；本地测试不会产生费用。

3次均为零时长时，最后的TimingException保留结构化诊断，由StoryGenerationService写入既有 `qwen-failure.json`，再由TaskWorker将FAILED、SYNTHESIZING阶段及安全错误持久化到SQLite。内部合成尝试不增加任务的attempt字段，不创建新的任务目录。

本地验证：针对Qwen重合成、StoryGenerationService及真实任务工作线程的37项JUnit测试通过；隔离构建目录执行 `./mvnw.cmd verify`，全量82项JUnit测试通过、0失败/错误/跳过，完成Spring Boot可执行JAR打包。现有前端Node测试10项全部通过。覆盖实际数字故障、首次及第3次恢复、连续失败上限、请求参数保持、成功音频隔离、前块不重做、非目标错误不重试、中断、最终诊断文件及SQLite失败持久化。没有删除既有测试。

## 多个 seed 仍返回零时长时的恢复

独立的真实 Qwen 探测以 seed 0 请求整段 `[591,653)`，复现了 `the` 的 `1280–1280` 毫秒零时长。按逗号拆开后，使用同一音色合成原文 `[591,627)` 子段，Qwen 返回 `the` 的有效时间 `1440–1520` 毫秒。

`QwenSpeechClient.synthesizeParts` 在同一范围的三个 seed 都返回真实词零时长后，尝试在靠近单词中点的自然停顿处分段。没有适合的标点时，使用离中点最近的词间空白。两个子段依序合成，均使用原有音色、语音参数及 seed 0、1、2 的尝试上限。原始块最多拆分两次，共最多尝试五个范围、十五次合成，含 HTTP 传输重试最多四十五个 POST。每个子段都必须独立通过词形、索引、全覆盖、正时长和时间顺序校验。范围无法拆分、恢复次数耗尽、出现其他类型的校验或请求错误，以及线程中断均会结束当前块。

`StoryGenerationService` 使用子段在原文中的偏移把词区间还原为故事的 UTF-16 `[start,end)`。FFmpeg 逐段将音频归一化为 24 kHz 单声道 PCM，`AudioAssembler` 用累计帧数计算后续子段的时间偏移。配置的暂停只加在相邻原始块之间，子段内部使用 Qwen 返回的真实词时间。

失败文件 `qwen-failure.json` 的 `chunkIndex`、`sourceStart` 和 `sourceEnd` 标识原始块。`failedSourceStart`、`failedSourceEnd` 标识实际失败的子段，`synthesisRanges` 按请求顺序记录尝试过的原文范围及 seed。时间戳异常的 `alignment` 数值快照对应最后失败的子段。任务错误写入 SQLite 后，网页仍显示原始块位置，并在发生分段恢复时附上失败子段与该块累计合成次数。

原文后续句中的 `2008` 有独立的对齐情形。Qwen 将它读作 `two thousand eight`，并为三个实际朗读词各给出时间。基数词对齐只适用于 0 至 9999 的独立整数。验证完整读法与原文整数等值后，字幕保留原文单词 `2008` 和原来的 UTF-16 区间，其时间覆盖第一个朗读词的开始到最后一个朗读词的结束。精确数字匹配继续适用，错误或不完整的整数读法仍会被词形校验拒绝。

## 在哪里查看错误

当前没有独立日志管理页面，也没有默认落盘的Spring Boot日志文件。

1. 在网页选择失败任务查看错误；`GET /api/tasks/{id}` 同样返回 `stage/error/attempt`，SQLite的 `story_tasks.error` 持久保存安全错误。
2. 新版本块失败的数值诊断位于 `data/audio/<任务UUID>/<attempt>/qwen-failure.json`；设置 `STORY_AUDIO_DIR` 后以该目录为准。例如旧任务第1次尝试目录是 `data/audio/e51c2a9f-3910-43ef-8f45-f3ef7fb123f5/1/`，但当时没有诊断功能，所以其中不会有可补回的旧响应诊断。下一次重试使用新的attempt目录。
3. 一般运行输出在启动应用的PowerShell终端或IDE控制台。生成异常被任务工作线程捕获后写入任务状态，未必再打印到控制台；没有控制台输出不能说明没有任务错误。

## 首轮修复交付记录（历史）

首轮只用本地 HTTP 桩和真实 FFmpeg 验证整块 seed 重试，候选包另存为 `target/englishpods-qwen-zero-duration-fix.jar`，当时运行的实例继续使用旧 JAR。第 5 次尝试使用了包含分段恢复和整数对齐的后续版本。

任务重试会创建新尝试目录。有效的分角和复核检查点会复用，配音从第一块开始重新合成。旧尝试文件保留以供排查。

## 调用路径

浏览器 → `TaskController` → `TaskService` 与 `TaskWorker` → `StoryGenerationService` → `QwenSpeechClient`（当前块有限重合成、必要时分段、逐段校验）→ `AudioAssembler` 与 FFmpeg → 本地音频、字幕文件及 SQLite。块异常先保存白名单诊断，再通过既有任务失败路径持久化安全错误。
