# Enterprise AI Assistant

基于 Spring Boot、Spring AI 和 DeepSeek 构建的企业智能助手后端。

项目当前处于 MVP 基础能力阶段：提供单轮对话接口，通过独立的 System Prompt 和 User Prompt 约束助手行为，并包含请求校验、统一异常处理及核心单元测试。后续将逐步加入会话记忆、企业知识库检索（RAG）、权限控制和可观测性。

## 当前能力

- 调用 DeepSeek 完成企业助手问答
- 使用资源文件统一管理 System Prompt 和 User Prompt
- 默认使用中文回答，并限制模型伪造企业内部数据
- 校验空消息及 4000 字符的输入上限
- 将参数错误、AI 服务错误和系统错误转换为统一 JSON 响应
- 使用 Mock 完成配置层、服务层和接口层测试，不在测试中调用真实模型

> 当前版本仅支持无状态的单轮对话，尚未连接企业知识库、数据库或内部业务系统。

## 技术栈

| 技术 | 用途 |
| --- | --- |
| Java 21 | 运行环境 |
| Spring Boot 3.5.16 | Web 应用与依赖管理 |
| Spring AI 1.1.8 | 模型调用与 Prompt 抽象 |
| DeepSeek | 大语言模型服务 |
| Jakarta Validation | 请求参数校验 |
| JUnit 5、Mockito、MockMvc | 自动化测试 |
| Maven Wrapper | 构建与运行 |

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
│   │   └── service      # 对话服务及实现
│   └── resources
│       ├── prompts      # System Prompt 与 User Prompt 模板
│       └── application.yaml
└── test                 # 配置、服务、接口与上下文测试
```

## 快速开始

### 1. 环境要求

- JDK 21
- 可访问 DeepSeek API 的网络环境
- DeepSeek API Key

项目包含 Maven Wrapper，无需预先安装 Maven。

### 2. 配置密钥

在项目根目录创建 `.env`：

```properties
DEEPSEEK_API_KEY=你的_API_Key
```

`.env` 已被 Git 忽略。不要把真实密钥提交到仓库、Prompt 或日志中。生产环境建议通过部署平台的密钥管理功能注入环境变量。

也可以直接设置系统环境变量：

```bash
export DEEPSEEK_API_KEY="你的_API_Key"
```

### 3. 启动应用

macOS / Linux：

```bash
./mvnw spring-boot:run
```

Windows：

```powershell
mvnw.cmd spring-boot:run
```

服务默认监听 `http://localhost:8082`。

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

## 当前边界

- 不保存会话上下文，每次请求相互独立
- 不具备企业知识库、数据库或内部系统查询能力
- 不支持流式响应
- 不包含用户登录、角色权限和审计日志
- Prompt 能降低错误回答风险，但不能替代权限、安全策略和模型评测

## 后续路线

1. 接入数据库并增加健康检查与配置隔离
2. 增加会话与消息持久化，实现多轮对话
3. 建立文档导入、切片、向量化和检索链路
4. 为知识库回答增加来源引用与拒答机制
5. 接入企业身份认证、文档权限过滤和操作审计
6. 增加流式输出、限流、超时、重试和可观测性
7. 建立离线评测集，持续衡量准确率、幻觉率和响应时延

## 开发说明

- Controller 只负责协议转换与参数校验，模型调用逻辑位于 Service 层。
- API Key 通过环境变量注入，不写入代码和配置文件。
- 新功能应同步补充单元测试；自动化测试默认不得请求真实模型服务。
- 修改 Prompt 后，应同时验证正常问答、信息不足、虚构内部数据和提示词探测等场景。
