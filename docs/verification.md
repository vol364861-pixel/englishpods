# 验证记录

## 2026-11-18 Qwen时间戳适配修复

- `.\mvnw.cmd -q verify`：63项Java测试全部通过，无失败、错误或跳过。包括19项生成服务测试、32项API测试，以及持久化、迁移、任务工作流和MvpEndToEndTest。
- `node --test src/test/js/timeline.test.mjs`：7项全部通过。
- 新增6项测试覆盖占位/重叠标点与空白、合法拆分所有格/连字符、真实词时间/索引/覆盖严格校验、字段缺失/溢出/非有限数/跨词token、安全数值诊断，以及HTTP桩→真实Java HTTP客户端→FFmpeg的完整标点生成。
- 保留既有实际音频时长超限拒绝、UTF-16/emoji/弯引号原文映射和SQLite整链测试。
- 测试不使用真实API密钥，不向供应商发送用户故事；生成测试音频不能视作真实Qwen合成验证。
- 已有用户数据库和旧尝试音频未修改，未自动重试任务。故障证据及剩余局限见 [时间戳排查](qwen-timestamp-failure.md)。
