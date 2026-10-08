# Enterprise AI Assistant

[简体中文](README.md) | [English](README_EN.md)

基于 Spring Boot 和 Spring AI 构建的企业智能助手后端。DeepSeek 负责对话生成，本地 Ollama/BGE-M3 负责文本向量化。

当前已完成本地只读工具阶段：模型可以查询 PostgreSQL 中的项目、工单列表与详情，并在同一次请求中根据列表结果继续查询详情。服务端负责身份、权限和租户过滤，模型不能自行指定查询租户。

这仍是开发验证版本，不是完整的生产级 Agent 平台。按开发顺序约定，下一步实现会话持久化和有边界的短期记忆。

## 当前能力

- 调用 DeepSeek，注册 Spring AI 原生 `queryProject`、`searchTickets`、`getTicketDetail` 只读工具
- 查询真实 PostgreSQL 业务表；工单列表按项目、状态筛选，稳定排序，最多返回 10 条
- 从登录身份构造可信执行上下文；工具分别检查 `project:read` 或 `ticket:read`
- 返回独立 DTO 白名单字段；工具审计记录工具名、身份、结果数量、耗时和错误类别
- 本地 `local` profile 使用 HTTP Basic 开发账号，保留 CSRF 校验
- 调用本地 BGE-M3 生成文本向量，计算两段文本的余弦相似度
- 源码包含文档导入、分块与向量持久化、过滤检索，以及返回来源片段的 RAG 服务
- 使用资源文件统一管理 System Prompt 和 User Prompt
- 默认使用中文回答，并限制模型伪造企业内部数据
- 使用 PostgreSQL、pgvector 和 Liquibase 管理数据库基础设施
- 校验空白输入及 4000 字符的输入上限
- 将参数错误、AI 服务错误和系统错误转换为统一 JSON 响应
- 离线测试模拟模型、查询或审计依赖；数据库集成测试使用临时 PostgreSQL 容器，均不调用真实模型

> 当前安全策略只开放获取 CSRF Token 和受权限控制的 Agent 对话入口。旧聊天、Embedding 与知识库 HTTP 路由默认拒绝访问；知识库请求中的租户字段尚未接入可信登录身份，不能为了联调直接放开这些路由。本轮验收不等于重验全部知识库功能或全量测试。

## 技术栈

| 技术 | 用途 |
| --- | --- |
| Java 21 | 运行环境 |
| Spring Boot 3.5.16 | Web 应用与依赖管理 |
| Spring AI 1.1.8 | 模型调用与 Prompt 抽象 |
| DeepSeek | 聊天模型 |
| Ollama + BGE-M3 | 本地文本向量模型，输出 1024 维向量 |
| PostgreSQL 17 + pgvector | 业务数据、知识片段和向量检索 |
| Spring JDBC | 参数化 SQL、DTO 映射和事务存储 |
| Spring Security | 本地登录、接口准入、权限检查与 CSRF |
| Liquibase | 数据库变更管理 |
| Jakarta Validation | 请求参数校验 |
| JUnit 5、Mockito、MockMvc、Testcontainers | 离线测试与临时数据库集成测试 |
| Docker Compose、Maven Wrapper | 本地数据库、构建与运行 |

## 项目结构

```text
src
├── main
│   ├── java/cn/coder/sanwei/enterpriseaiassistant
│   │   ├── audit        # 工具审计事件与日志
│   │   ├── config       # 模型、安全策略与本地账号配置
│   │   ├── context      # 服务端执行身份
│   │   ├── controller   # HTTP 接口
│   │   ├── dto          # 请求与响应对象
│   │   ├── error        # 统一错误响应
│   │   ├── exception    # 业务异常与全局异常处理
│   │   ├── model        # 服务层结果对象
│   │   ├── repository   # 知识库与业务数据查询
│   │   ├── security     # 可信登录用户
│   │   ├── service      # Agent、知识库、对话与向量化服务
│   │   └── tool         # 项目与工单只读工具
│   └── resources
│       ├── db           # Liquibase changelog
│       ├── prompts      # System Prompt 与 User Prompt 模板
│       └── application.yaml
└── test                 # 配置、服务、接口与上下文测试

compose.yaml             # 本地 PostgreSQL + pgvector
```

## 快速开始

### 1. 环境要求

- JDK 21
- Docker 和 Docker Compose
- Ollama，并已下载 `bge-m3` 模型
- 可访问 DeepSeek API 的网络环境
- DeepSeek API Key

项目包含 Maven Wrapper，无需预先安装 Maven。
运行 `./mvnw -v` 时应确认其使用 Java 21；若显示其他版本，请先配置 `JAVA_HOME`。

### 2. 配置本地环境

复制示例配置，在项目根目录创建 `.env`：

```bash
cp .env.example .env
```

Windows PowerShell 可使用 `Copy-Item .env.example .env`。

在 `.env` 中至少替换 `DEEPSEEK_API_KEY`、`POSTGRES_PASSWORD` 和 `DEMO_SECURITY_PASSWORD`。最后一项只供 `local` profile 的开发账号使用，必须非空，不提供仓库内默认密码。`POSTGRES_HOST=localhost` 适用于在本机运行 Java 应用、通过映射端口访问容器中的数据库。Ollama 默认地址是 `http://localhost:11434`，模型名默认是 `bge-m3`；如需修改，使用 `OLLAMA_BASE_URL` 和 `OLLAMA_EMBEDDING_MODEL`。

`.env` 已被 Git 忽略。不要把真实密钥或数据库密码提交到仓库、Prompt 或日志中。生产环境应通过部署平台注入秘密配置。

### 3. 启动数据库和模型

启动 PostgreSQL + pgvector：

```bash
docker compose up -d postgres
docker compose ps
```

启动 Ollama 服务（如果系统没有自动启动），并下载模型：

```bash
ollama serve
```

在另一个终端执行：

```bash
ollama pull bge-m3
ollama list
```

应用配置为**不自动下载模型**；启动应用前需确认 Ollama 可访问且 `bge-m3` 已安装。

### 4. 启动应用

macOS / Linux：

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Windows：

```powershell
mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local
```

服务默认监听 `http://localhost:8082`。

IDEA 可在程序参数中设置 `--spring.profiles.active=local`；不要把本地开发 profile 强制写进共享配置。开发账号不应启用于生产环境。

启动时 Liquibase 会启用扩展并创建知识库与业务表；重复启动不会重复执行已完成的 changeset。验收业务数据需要单独准备，测试容器的数据不会保留在开发库。

## API 使用

### 当前入口：只读 Agent 对话

```http
POST /api/agent/chat
Content-Type: application/json
```

本地账号：`tenant-a`（用户 101 / 租户 1）、`tenant-b`（用户 102 / 租户 2）拥有两项只读权限；`viewer`（用户 103 / 租户 1）无业务查询权限。密码来自 `DEMO_SECURITY_PASSWORD`。

请求流程：

1. 在 HTTP 客户端调用 `GET /api/security/csrf`，保存会话 Cookie，以及响应中的 `token` 和 `headerName`。
2. 对 Agent 请求设置 HTTP Basic 身份，发送同一会话 Cookie，并使用返回的 headerName 携带 token。
3. 切换账号时使用该账号的请求会话；不要把密码、Cookie 或 CSRF Token 放进 Prompt、仓库或公开日志。

```json
{
  "message": "先查询 DB-CHECK-2026 项目下未处理的工单，再根据查询结果展示每条工单的详细说明。"
}
```

`message` 必填，不能为空白，最多 4000 字符。请求不需要用户编号、租户编号或权限；这些信息来自服务端登录身份。示例业务编号需有相应的本地数据。

响应仍为 `{"answer":"..."}`，尚无 conversationId 或跨请求历史。进入 Agent 接口后，各工具仍独立检查自己的权限。

### 历史接口说明（当前默认关闭）

下面保留旧接口的协议和响应示例，不能作为当前安全配置下的匿名调用示例。恢复这些路由前，需要先完成其登录身份和权限集成，不能通过关闭安全保护来绕过限制。

### 发起基础对话（当前默认关闭）

```http
POST /api/chat
Content-Type: application/json
```

请求示例：

```bash
curl -X POST http://localhost:8082/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"请介绍一下你能做什么"}'
```

成功响应：

```json
{
  "answer": "我是公司内部的企业智能助手……"
}
```

请求字段：

| 字段 | 类型 | 必填 | 约束 |
| --- | --- | --- | --- |
| `message` | String | 是 | 不能为空，最多 4000 个字符 |

### 生成文本向量（当前默认关闭）

```http
POST /api/embeddings
Content-Type: application/json
```

```bash
curl -X POST http://localhost:8082/api/embeddings \
  -H 'Content-Type: application/json' \
  -d '{"text":"员工出差住宿标准是多少？"}'
```

响应示例（数值仅作展示，实际结果取决于模型）：

```json
{
  "dimensions": 1024,
  "preview": [0.0123, -0.0456, 0.0789, 0.0012, 0.0345, -0.0678, 0.0234, 0.0567]
}
```

`text` 必填、不能为空白字符，最多 4000 个字符。`dimensions` 是完整向量维度；`preview` 只包含前 8 个数值，接口不会返回完整向量。

### 计算语义相似度（当前默认关闭）

```http
POST /api/embeddings/similarity
Content-Type: application/json
```

```bash
curl -X POST http://localhost:8082/api/embeddings/similarity \
  -H 'Content-Type: application/json' \
  -d '{"left":"员工出差住宿标准是多少？","right":"公司差旅酒店费用上限是多少？"}'
```

响应示例（分数仅作展示）：

```json
{
  "score": 0.8,
  "dimensions": 1024
}
```

`left` 和 `right` 都必填、不能为空白字符，且各自最多 4000 个字符。`score` 是余弦相似度；值越大，两个向量的方向越接近。一次请求只对两段文本各生成一次向量。

### 错误响应

由控制器异常处理器处理的错误使用以下结构；安全过滤器产生的 401/403 不承诺同样的 JSON 格式：

```json
{
  "errorCode": "INVALID_REQUEST",
  "message": "message 不能为空"
}
```

| HTTP 状态码 | 错误码 | 说明 |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | 请求参数不合法 |
| 502 | `AI_SERVICE_ERROR` | 模型服务调用失败或未返回有效内容 |
| 500 | `INTERNAL_ERROR` | 未预期的服务器错误 |

上表描述控制器层的错误映射，不意味着默认关闭的路由可以调用。未认证、无权限或缺少有效 CSRF Token 的请求可能先被安全过滤器拒绝。

## Prompt 管理

- `src/main/resources/prompts/enterprise-system-prompt.txt`：定义助手角色、回答原则和能力边界
- `src/main/resources/prompts/chat-user-prompt.txt`：将用户问题组织为 User Message
- Agent 专用系统提示词目前在 `AgentServiceImpl` 中，明确只读边界、可靠工单编号来源和空列表不继续查询详情的规则。

System Prompt 会在应用启动时加载；文件缺失或内容为空时，应用会快速失败，避免在约束失效的情况下运行。

## 测试与构建

运行本阶段验收的 7 个离线测试类：

```bash
./mvnw -Dtest=AgentSecurityTest,DemoUserConfigTest,AgentServiceImplTest,ProjectQueryToolsTest,TicketQueryToolsTest,JdbcBusinessRepositoryTest,JdbcTicketRepositoryTest test
```

2026-10-08 由助手在 Java 21 下复跑，160 个用例全部通过。这是指定范围，不是全部项目测试的通过声明；真实模型的选工具行为需单独联调。

数据库集成测试需运行 Docker，显式选择 `IT` 类：

```bash
./mvnw -Dtest=BusinessSchemaIT,JdbcBusinessRepositoryIT test
```

数据库测试使用临时容器，不连接开发库；开发者反馈两类均通过。普通 `./mvnw test` 的默认命名规则不包含这些 `IT` 类。

全量回归（本次未复验全部测试）：

```bash
./mvnw test
```

构建可执行 JAR：

```bash
./mvnw clean package
```

运行构建产物：

```bash
java -jar target/enterprise-ai-assistant-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

现有测试覆盖：

- Spring 应用上下文加载
- System Prompt 加载并注入 `ChatClient`
- User Prompt 模板变量替换
- AI 正常响应与空白输入拦截
- 聊天接口成功响应与参数校验
- Embedding 模型异常、空向量和余弦相似度边界
- 向量与相似度接口的正常响应、参数校验和 502 错误映射

上述历史覆盖清单不代表本轮已全部回归。7 个离线验收类无需启动模型或数据库；Testcontainers 集成测试需要 Docker 和 PostgreSQL 容器。真实模型联调需单独在本地环境执行。

## 当前边界

- 不保存会话上下文，每次请求相互独立
- 只读项目与工单查询已接入数据库；同一次请求可串联工具，但没有会话或任务状态持久化
- 知识库服务已有实现，其 HTTP 路由尚未完成可信身份集成，当前保持关闭
- 不支持创建、修改或删除业务数据，也没有写操作确认、幂等提交或 Agent 级执行步数预算
- 不支持流式响应
- 登录采用本地开发账号，不是生产 IAM；工具审计仅写日志，未实现数据库审计和事务一致性
- 工具异常的业务分类尚未完整贯穿模型调用层，不能承诺所有工具权限拒绝都表现为 HTTP 403
- Prompt 能降低错误回答风险，但不能替代权限、安全策略和模型评测

## 后续路线

1. 增加会话归属、消息持久化和有限历史窗口
2. 将知识库入口接入可信登录身份，再统一 RAG 与工具查询体验
3. 增加写操作预览、用户确认、幂等与事务审计
4. 增加执行步数与超时预算、失败恢复、可观测性和离线评测

## 开发说明

- Controller 只负责协议转换与参数校验，模型调用逻辑位于 Service 层。
- API Key 通过环境变量注入，不写入代码和配置文件。
- 新功能应同步补充单元测试；自动化测试默认不得请求真实模型服务。
- 修改 Prompt 后，应同时验证正常问答、信息不足、虚构内部数据和提示词探测等场景。
