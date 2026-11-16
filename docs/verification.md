# v0.1.0 验证与发布记录

日期：2026-11-16。此记录区分工程机制验证与真实模型质量评测。

## 自动化结果

`mvn spotless:apply clean verify` 通过，22 项测试全部通过：

| 测试组 | 数量 | 覆盖 |
| --- | ---: | --- |
| OpenAiModelGatewayTest | 6 | HTTP 工具协议、调用 ID、越权、输出截断、429 不重试、响应体大小与未知用量 |
| ContextAssemblerTest | 4 | 原始事实和阻断问题、完整工具往返、超限拒绝、任务与角色隔离 |
| RuntimeInvariantTest | 5 | 工具权限/参数/严格 JSON、审稿覆盖和原句证据、确定性规则优先、冻结版本、超大上下文暂停 |
| EndToEndTest | 7 | 双 Agent 修订闭环、创建/取消幂等、工具意图重放、取消期间迟到响应、未知结果预算、HTTP 输入/Host/Origin、模板版本冻结 |

Java 源码采用 Google Java Format；前端 `node --check`、Compose 配置解析与文档相对链接检查通过。

## 启动实际服务的验证

从打包 JAR 启动 Spring Boot，连接文件型 H2，通过 HTTP 创建虚构折叠收纳盒任务：

- 最终状态 `DEMO_READY`；4 个稿件版本、2 轮审稿、12 次确定性模型替身调用。
- 首轮发现没有依据的承重声明，Writer 只修改问题段，复审绑定最新 v4。
- 预算预留归零；页面 HTML / JS / CSS、示例数据及 Markdown 导出返回成功。
- 停止应用后重新启动，原任务、4 个版本、24 条执行步骤及最新审稿报告保持完整。
- `scripts/smoke.py` 已在重启后的 DEMO 服务再次完成相同验收。

上述调用使用固定替身，不能据此推导真实模型的质量、费用、速度或成功率。

## 公开发布与浏览器验收

- [公开源码](https://github.com/coder1025/scriptforge)已发布，首个实现提交为 `a1db397`。
- [GitHub Actions CI](https://github.com/coder1025/scriptforge/actions/runs/43850886367)通过：Java 格式检查、22 项测试、前端语法检查、实际 JAR 的 HTTP 冒烟检查及测试报告/JAR 上传均成功。
- [GitHub Pages 发布任务](https://github.com/coder1025/scriptforge/actions/runs/43851225455)成功；[在线演示](https://coder1025.github.io/scriptforge/?demo=1)可以公开访问。
- 在桌面 Chrome 中查看实际线上页面及截图，验证重置、下一步和自动播放最终到达 v4；切换版本对比与审稿报告，确认显示删除无依据承重声明、3/3 段落及 2/2 关系覆盖。
- 线上页面为静态固定样例回放，不运行 Java 服务、不发起真实模型请求。上述浏览器验收不等于跨浏览器、移动端或真实模型模式的完整验收。

## 尚未验证

- 未使用付费模型密钥；真实供应商的模型兼容性与内容质量需要按 `docs/evaluation.md` 实测。
- 未完成 Docker 镜像构建与启动验证。
- 首版不包含24小时跨任务编排、多人隔离、分布式集群、TTS时长测量或数字人开播接入。

这些边界应与 README 和演示中的标记保持一致。
