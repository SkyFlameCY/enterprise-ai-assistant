# Enterprise AI Assistant

[简体中文](README.md) | [English](README_EN.md)

基于 Spring Boot 和 Spring AI 构建的企业智能助手后端。DeepSeek 负责对话生成，本地 Ollama/BGE-M3 负责文本向量化。

项目当前处于基础能力阶段：提供单轮对话、文本向量化和语义相似度接口，并已接入 PostgreSQL + pgvector 基础设施。后续将逐步加入文档检索、Agent 工具调用、权限控制和可观测性。

## 当前能力

- 调用 DeepSeek 完成企业助手问答
- 调用本地 BGE-M3 生成文本向量，计算两段文本的余弦相似度
- 使用资源文件统一管理 System Prompt 和 User Prompt
- 默认使用中文回答，并限制模型伪造企业内部数据
- 使用 PostgreSQL、pgvector 和 Liquibase 管理数据库基础设施
- 校验空白输入及 4000 字符的输入上限
- 将参数错误、AI 服务错误和系统错误转换为统一 JSON 响应
- 使用 Mock 完成配置层、服务层和接口层测试，自动化测试不调用真实模型

> 数据库目前只用于基础设施和扩展初始化；尚未保存文档或向量，也没有企业知识库检索和 Agent 工具调用能力。

## 技术栈

| 技术 | 用途 |
| --- | --- |
| Java 21 | 运行环境 |
| Spring Boot 3.5.16 | Web 应用与依赖管理 |
| Spring AI 1.1.8 | 模型调用与 Prompt 抽象 |
| DeepSeek | 聊天模型 |
| Ollama + BGE-M3 | 本地文本向量模型，输出 1024 维向量 |
| PostgreSQL 17 + pgvector | 数据库与后续向量检索基础设施 |
| Liquibase | 数据库变更管理 |
| Jakarta Validation | 请求参数校验 |
| JUnit 5、Mockito、MockMvc | 自动化测试 |
| Docker Compose、Maven Wrapper | 本地数据库、构建与运行 |

## 项目结构

```text
src
├── main
│   ├── java/cn/coder/sanwei/enterpriseaiassistant
│   │   ├── config       # ChatClient 与 System Prompt 配置
│   │   ├── controller   # HTTP 接口
│   │   ├── dto          # 请求与响应对象
│   │   ├── error        # 统一错误响应
│   │   ├── exception    # 业务异常与全局异常处理
│   │   ├── model        # 服务层结果对象
│   │   └── service      # 对话与向量化服务及实现
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

在 `.env` 中至少替换 `DEEPSEEK_API_KEY` 和 `POSTGRES_PASSWORD`。`POSTGRES_HOST=localhost` 适用于在本机运行 Java 应用、通过映射端口访问容器中的数据库。Ollama 默认地址是 `http://localhost:11434`，模型名默认是 `bge-m3`；如需修改，使用 `OLLAMA_BASE_URL` 和 `OLLAMA_EMBEDDING_MODEL`。

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
./mvnw spring-boot:run
```

Windows：

```powershell
mvnw.cmd spring-boot:run
```

服务默认监听 `http://localhost:8082`。

启动时 Liquibase 会在目标数据库中启用 `vector` 等扩展；重复启动不会重复执行已完成的 changeset。

## API 使用

### 发起对话

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

### 生成文本向量

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

### 计算语义相似度

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

所有错误均使用以下结构：

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

Ollama 未启动或模型调用失败时，向量接口返回 `502 AI_SERVICE_ERROR`；输入为空时返回 `400 INVALID_REQUEST`。

## Prompt 管理

- `src/main/resources/prompts/enterprise-system-prompt.txt`：定义助手角色、回答原则和能力边界
- `src/main/resources/prompts/chat-user-prompt.txt`：将用户问题组织为 User Message

System Prompt 会在应用启动时加载；文件缺失或内容为空时，应用会快速失败，避免在约束失效的情况下运行。

## 测试与构建

运行全部测试：

```bash
./mvnw test
```

构建可执行 JAR：

```bash
./mvnw clean package
```

运行构建产物：

```bash
java -jar target/enterprise-ai-assistant-0.0.1-SNAPSHOT.jar
```

现有测试覆盖：

- Spring 应用上下文加载
- System Prompt 加载并注入 `ChatClient`
- User Prompt 模板变量替换
- AI 正常响应与空白输入拦截
- 聊天接口成功响应与参数校验
- Embedding 模型异常、空向量和余弦相似度边界
- 向量与相似度接口的正常响应、参数校验和 502 错误映射

测试使用假的模型和服务，不需要启动 Ollama、DeepSeek 或 PostgreSQL。真实模型联调需单独在本地环境执行。

## 当前边界

- 不保存会话上下文，每次请求相互独立
- 数据库已接入，但还没有文档导入、向量存储、知识库检索或内部系统查询能力
- 尚未实现 Agent 工具调用和多步骤任务执行
- 不支持流式响应
- 不包含用户登录、角色权限和审计日志
- Prompt 能降低错误回答风险，但不能替代权限、安全策略和模型评测

## 后续路线

1. 建立文档导入、切片、向量存储和 Top-K 检索链路
2. 为知识库问答增加来源引用与证据不足时的拒答机制
3. 增加会话与消息持久化，实现多轮对话
4. 接入受权限约束的业务工具、写操作确认与 Agent 执行状态
5. 增加审计、超时、重试、可观测性和离线评测

## 开发说明

- Controller 只负责协议转换与参数校验，模型调用逻辑位于 Service 层。
- API Key 通过环境变量注入，不写入代码和配置文件。
- 新功能应同步补充单元测试；自动化测试默认不得请求真实模型服务。
- 修改 Prompt 后，应同时验证正常问答、信息不足、虚构内部数据和提示词探测等场景。
