# 本地配置、开源与展示链接

## 选择给访客看的入口

建议公开两个入口：**GitHub 仓库**用于阅读实现、测试和架构；**GitHub Pages**用于快速看懂创作与审稿过程。Pages 是静态站点，不能运行 Java 后端，也不应携带模型密钥。

如果以后需要让访客实际提交任务，单独部署 Java 服务，使用 HTTPS 和访问控制，并设置模型费用上限。首版的 `ADMIN_TOKEN` 是整个工作区共享令牌；它不提供多用户数据隔离。

## 环境变量

| 变量 | 默认/示例 | 用途 |
| --- | --- | --- |
| `SCRIPT_FORGE_MODE` | `DEMO` | `DEMO` 固定模型替身；`MODEL` 真实供应商 |
| `SERVER_ADDRESS` | `127.0.0.1` | 服务监听地址 |
| `PORT` | `8080` | 服务端口 |
| `SERVER_FORWARD_HEADERS_STRATEGY` | `none` | 可信 HTTPS 代理后可设为 `native`，恢复外部协议和端口 |
| `SCRIPT_FORGE_DATA_DIR` | `./data` | H2 数据目录 |
| `READ_ONLY` | `false` | 只读查看已有工作区 |
| `MODEL_BASE_URL` | `https://api.openai.com/v1` | 兼容 Chat Completions 的 API 基地址 |
| `MODEL_NAME` | 空 | 供应商已开通的工具调用模型 |
| `MODEL_API_KEY` | 空 | 只放在服务端环境中 |
| `MODEL_TIMEOUT_SECONDS` | `60` | 单次模型请求超时 |
| `MODEL_MAX_OUTPUT_TOKENS` | `4096` | 每次请求输出预留 |
| `AGENT_INPUT_BUDGET` | `24000` | 单次组装上下文的输入预算 |
| `ADMIN_TOKEN` | 空 | 可选工作区访问令牌；真实模式非回环监听时必需 |

直接运行 `java -jar` 不会自动加载 `.env`。`.env` 是给 Docker Compose 的；直接启动时请使用 shell 环境变量或进程管理器的安全配置。不要在命令行参数里放密钥，以免出现在进程列表或历史中。

## 创建公开仓库

先在 GitHub 创建一个空仓库，例如 `scriptforge`。代码中没有预先写入你的账号或远程仓库地址。

在本项目根目录执行；如果目录已有 Git 仓库，跳过初始化：

```bash
git init -b main
git branch -M main
git add .
git diff --cached --stat
git diff --cached
git commit -m "Initial ScriptForge release"
git remote add origin git@github.com:YOUR_ACCOUNT/scriptforge.git
git push -u origin main
```

将 `YOUR_ACCOUNT` 替换为你的真实账号。推送前检查暂存内容，确认没有 `.env`、`data/`、业务数据、凭证或个人日志。这个目录包含完整独立项目；不要把上层工作目录一并提交。

## 发布 GitHub Pages

1. 打开仓库 **Settings → Pages**，将 Build and deployment 的 Source 设为 **GitHub Actions**。
2. 打开 **Actions → Publish read-only demo → Run workflow**，选择要发布的分支。
3. 等待工作流成功；工作流 Environment / Deploy 步骤会给出真实的站点地址。
4. 访问页面并检查手机布局、回放、报告、版本和下载。建议简历链接使用 `?demo=1`，明确进入离线演示。

标准项目站通常形如 `https://YOUR_ACCOUNT.github.io/scriptforge/?demo=1`；仓库名称、自定义域名和账号类型可能改变地址，以部署结果为准。源码包交付并不表示这个公网地址已经创建或上线。

工作流只复制 `src/main/resources/static/`，不复制 `.env`、数据库或服务器配置。页面使用固定样例说明机制，不能提交真实任务。

## 自托管真实服务

本地编辑 `.env` 后：

```bash
docker compose up --build -d
docker compose logs --tail=100 scriptforge
```

默认绑定宿主机 `127.0.0.1`。需要公网访问时，让反向代理通过回环端口连接并终止 HTTPS；不要直接开放裸露的模型付费接口。代理应设置访问日志脱敏、请求限额和合适超时。持有管理员令牌的人能够查看同一工作区内所有任务。

真实模型连接要先做小任务协议验证：能否返回合法工具调用、是否完整保留调用 ID、是否支持配置中的参数、超时和限流如何返回。不同供应商对 token 统计、结构化输出和拒答的行为可能不同。

MODEL 缺少模型名或密钥时会在启动阶段报错。无令牌工作区只接受 `localhost` / 回环 IP 的 Host，以避免 DNS rebinding；通过其他域名访问需要设置 `ADMIN_TOKEN`。可信反向代理应传递正确的请求协议、Host 和端口，并设置 `SERVER_FORWARD_HEADERS_STRATEGY=native`；部署后验证页面创建请求的同源检查。只在代理受控且后端不直连公网时信任这些转发头。

DEMO 服务启动后可执行 `python3 scripts/smoke.py` 做真实 HTTP 验收。该脚本会先核对模式，拒绝向 MODEL 服务创建任务，不产生付费模型请求。

## 数据与备份

本地默认使用文件型 H2；容器使用 `scriptforge-data` volume。备份前先停止应用，再备份整个数据目录/volume，避免直接拷贝正在写入的数据库。恢复时保留对应应用版本及 Flyway 迁移记录。

任务取消不会删除已有稿件；导出应显示真实状态。对不确定的外部模型调用，系统保守暂停或保留预留用量，不能把网络断开理解为供应商没有计费。

## 推荐的仓库展示顺序

README 首屏放项目定位与真实演示入口；展示一条“无依据声明 → 审稿证据 → 定向修订 → 新版本复审”的短流程。代码阅读入口指向模型适配器、执行宿主、工具校验和相应测试。性能、质量与节省成本数据只有实际评测后再填。

