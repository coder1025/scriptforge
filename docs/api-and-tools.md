# HTTP 与工具协议

## HTTP API

基础路径为 `/api`。配置了管理员令牌时，请求携带 `Authorization: Bearer <ADMIN_TOKEN>`；UI 提供令牌输入入口。任务内容、模型输出和错误应按数据处理，不拼成可执行 HTML。

| 方法 | 路径 | 作用 |
| --- | --- | --- |
| GET | `/config` | 模式、版本、只读状态与模型配置状态 |
| GET | `/tasks` | 工作区任务列表 |
| POST | `/tasks` | 冻结输入并创建任务 |
| GET | `/tasks/{id}` | 当前产物、执行、步骤、报告、版本及检查点 |
| POST | `/tasks/{id}/cancel` | 取消后续执行，保留已有产物 |
| POST | `/tasks/{id}/resume` | 仅从 `PAUSED` 且有剩余预算时恢复 |
| GET | `/tasks/{id}/export` | 导出 Markdown；部分结果也显示真实状态 |
| GET | `/templates` | 提示词模板版本列表 |
| POST | `/templates` | 新增模板或新版本 |

创建请求使用客户端 `requestId` 实现幂等；同一逻辑提交重试应沿用原 ID。新的独立任务需要新 ID。取消与恢复也应有各自请求 ID。错误响应使用 `{ "error": "错误码", "message": "可理解的信息" }`。

示例输入见 [demo-task.json](examples/demo-task.json)，可直接在本地发送：

```bash
curl --fail-with-body -X POST http://localhost:8080/api/tasks \
  -H 'Content-Type: application/json' \
  --data-binary @docs/examples/demo-task.json
```

这个文件故意固定 `requestId`，重复发送表示重试同一创建请求。想创建第二份任务，先修改该字段。

## Writer 工具

| 工具 | 何时使用 | 副作用及约束 |
| --- | --- | --- |
| `read_context` | 阅读当前任务事实、计划、段落或报告 | 只读当前任务范围，返回真实版本 |
| `search_history` | 查找工作区内已有稿件的相关片段 | 有范围与条数限制；非全网检索、非向量语义查重 |
| `save_plan` | 写稿前创建或修订完整结构计划 | 校验预期计划版本；正文开始后计划冻结，结构变更需新任务 |
| `patch_script` | 写入/替换指定段落 | 正文在工具参数中；校验计划及稿件版本后保存新版本 |
| `evaluate_candidate` | 对已有完整稿件请求独立审稿 | 冻结快照、持久化等待并启动 Reviewer |
| `finish_run` | 请求提交，或声明需要补充/人工处理 | 宿主校验后决定状态；模型不能绕过验收 |

计划段落示例：

```json
{
  "id": "s2",
  "title": "闲置时的收纳",
  "purpose": "解释不用时如何收起",
  "angle": "日常空间安排",
  "factIds": ["f1"],
  "targetSeconds": 30,
  "entryContext": "上段提出杂物整理的问题",
  "newInformation": "不用时可以折叠收起",
  "exitExpectation": "引出有提手便于移动",
  "dependsOn": ["s1"]
}
```

稿件写入示例：

```json
{
  "expectedDraftRevision": 1,
  "planRevision": 1,
  "edits": [
    {
      "sectionId": "s2",
      "text": "不用的时候，这个盒子可以折叠收起。整理空间时，也可以按自己的使用习惯选择放置位置。",
      "factIds": ["f1"]
    }
  ],
  "reason": "补充闲置收纳段，承接上段整理场景"
}
```

`expectedDraftRevision` 是整份稿件的版本，不是段落编号。即使只改 `s2`，也会产生新稿件版本。`factIds` 表示引用关系，不能证明文字中的每个声明自动受到这些事实支持。

首版的计划最多24段，各段目标秒数之和必须等于任务目标。每次 patch 最多8段，每段20–6000字符；未修改的段落保留。无内容变化的 patch 被拒绝，避免空转制造版本。

## Reviewer 工具

| 工具 | 使用范围 | 宿主要求 |
| --- | --- | --- |
| `read_context` | 冻结的事实/计划/稿件/报告 | 不能读取一个正在变化的新稿冒充旧稿 |
| `search_history` | 同工作区允许检索的历史 | 返回检索来源；没有命中不等于没有重复 |
| `inspect_sections` | 阅读原文并执行事实/重复/衔接检查 | 记录实际段落与关系覆盖，模型还需做语义判断 |
| `submit_review` | 提交带证据的问题与结论 | 核对冻结版本、覆盖、引文和必检结果 |

Reviewer 不具备 `patch_script`、`save_plan`、`finish_run` 和启动子审稿的权限。它可以发现问题，不能改写原始事实来消除问题。

`inspect_sections` 只有在一次请求同时读取关系两端、并包含 `CONTINUITY` 检查时，才登记该关系的覆盖。每段要求覆盖 `FACTS`、`REPETITION`、`CONTINUITY`。这记录的是工具访问和检查范围，不能证明模型理解绝无遗漏。

问题记录示例：

```json
{
  "id": "unsupported-load",
  "sectionId": "s2",
  "type": "FACTS",
  "severity": "BLOCKER",
  "quote": "承重100公斤",
  "evidence": "f1-f3只支持折叠、提手和收纳场景，没有承重数据。",
  "repair": "删除承重声明，保留已知的折叠收纳事实。"
}
```

这是一条可以复核的修订任务。只有“感觉不自然”而没有原文和修订目标的评价难以作为自动修订依据。

## 三种结果不能混用

1. 工具成功：操作已经正确执行，例如稿件 v4 已保存。
2. 审稿通过：某个冻结版本满足当前检查要求。
3. 交付成功：宿主核对当前稿件、报告和来源后，允许该版本进入最终状态。

审稿针对 v3 通过之后，如果正文又变成 v4，v3 的报告仍然保留，但不能为 v4 放行。模型返回普通“已经写完”的文本，也不会改变任务状态。

## 首版确定性检查的实际范围

`QualityChecks` 负责计划段落完整性、最小正文长度、事实 ID 存在性、部分数量/单位原文匹配、18字以上完全相同长句，以及按3.8字符/秒估算时长。估计时长的验收范围为目标的55%–160%，只是宽松的文本生成保护，不能用于承诺成片长度。

数量规则可能误报无害数字，也可能漏掉换单位、文字数字或语义上的错误；完全相同长句检查不能发现所有同义改写。Reviewer 应读取事实和原文做进一步判断，必要时让人处理。这些边界在评测时要单独统计。

当前最多四轮审稿；同一稿件版本已有报告时不能重复启动审稿。连续工具失败会暂停任务，避免反复消耗预算。


