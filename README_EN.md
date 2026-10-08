# Enterprise AI Assistant

[简体中文](README.md) | [English](README_EN.md)

An enterprise AI assistant backend built with Spring Boot and Spring AI. DeepSeek handles chat generation, while local Ollama/BGE-M3 handles text embeddings.

The model can query PostgreSQL projects, ticket lists, and ticket details, then use a list result to request details within the same request. The server enforces identity, permissions, and tenant filters; the model cannot choose the query tenant.

This is a development-validation build, not a complete production Agent platform. As agreed for this project, the next step returns to Day 10: persisted conversations and bounded short-term memory.

## Current Features

- DeepSeek with native Spring AI `queryProject`, `searchTickets`, and `getTicketDetail` read-only tools
- Real PostgreSQL business queries; ticket lists filter by project and status, use stable ordering, and return at most 10 rows
- Trusted execution identity derived from authentication; per-tool checks for `project:read` or `ticket:read`
- Allowlisted DTO fields and tool audit events containing identity, counts, duration, outcome, and error category
- HTTP Basic development accounts in the `local` profile, with CSRF protection retained
- Local BGE-M3 text embeddings and cosine similarity between two texts
- Source implementations for document ingestion, chunk/vector persistence, filtered retrieval, and RAG responses with source fragments
- Resource-based System Prompt and User Prompt management
- Chinese responses by default, with safeguards against fabricating internal company data
- PostgreSQL, pgvector, and Liquibase database infrastructure
- Validation for blank input and a maximum input length of 4,000 characters
- Consistent JSON responses for validation, AI service, and unexpected server errors
- Offline tests mock model, query, or audit dependencies; database integration tests use temporary PostgreSQL containers; neither calls real models

> The current security policy exposes only CSRF token retrieval and the permission-checked Agent endpoint. Legacy chat, embedding, and knowledge HTTP routes are denied by default. Knowledge request tenant fields are not yet integrated with trusted authentication, so those routes must not simply be opened for testing. This stage is not a revalidation of all knowledge features or the full test suite.

## Technology Stack

| Technology | Purpose |
| --- | --- |
| Java 21 | Runtime environment |
| Spring Boot 3.5.16 | Web application and dependency management |
| Spring AI 1.1.8 | Model integration and prompt abstractions |
| DeepSeek | Chat model |
| Ollama + BGE-M3 | Local text embedding model producing 1,024-dimensional vectors |
| PostgreSQL 17 + pgvector | Business data, knowledge fragments, and vector retrieval |
| Spring JDBC | Parameterized SQL, DTO mapping, and transactional storage |
| Spring Security | Local authentication, endpoint admission, permissions, and CSRF |
| Liquibase | Database migrations |
| Jakarta Validation | Request validation |
| JUnit 5, Mockito, MockMvc, Testcontainers | Offline tests and temporary database integration tests |
| Docker Compose, Maven Wrapper | Local database, build, and application execution |

## Project Structure

```text
src
├── main
│   ├── java/cn/coder/sanwei/enterpriseaiassistant
│   │   ├── audit        # Tool audit events and logging
│   │   ├── config       # Models, security policy, and local accounts
│   │   ├── context      # Server-side execution identity
│   │   ├── controller   # HTTP endpoints
│   │   ├── dto          # Request and response objects
│   │   ├── error        # Consistent error responses
│   │   ├── exception    # Business exceptions and global error handling
│   │   ├── model        # Service-level result objects
│   │   ├── repository   # Knowledge and business queries
│   │   ├── security     # Trusted authenticated users
│   │   ├── service      # Agent, knowledge, chat, and embedding services
│   │   └── tool         # Read-only project and ticket tools
│   └── resources
│       ├── db           # Liquibase changelog
│       ├── prompts      # System and User Prompt templates
│       └── application.yaml
└── test                 # Configuration, service, controller, and context tests

compose.yaml             # Local PostgreSQL + pgvector
```

## Getting Started

### 1. Prerequisites

- JDK 21
- Docker and Docker Compose
- Ollama with the `bge-m3` model installed
- Network access to the DeepSeek API
- A DeepSeek API key

The Maven Wrapper is included, so a separate Maven installation is not required.
Check `./mvnw -v` to make sure it uses Java 21; set `JAVA_HOME` first if it reports another version.

### 2. Configure the Local Environment

Copy the example configuration to `.env` in the project root:

```bash
cp .env.example .env
```

On Windows PowerShell, use `Copy-Item .env.example .env`.

Replace at least `DEEPSEEK_API_KEY`, `POSTGRES_PASSWORD`, and `DEMO_SECURITY_PASSWORD` in `.env`. The last value is required and nonblank for local development accounts; no repository default password is provided. `POSTGRES_HOST=localhost` is for running the Java application on the host and connecting to the container through its published port. The default Ollama URL is `http://localhost:11434`, and the default model is `bge-m3`; use `OLLAMA_BASE_URL` and `OLLAMA_EMBEDDING_MODEL` to override them.

The `.env` file is ignored by Git. Never commit real API keys or database passwords or put them in source code, prompts, or logs. In production, inject secrets through your deployment platform.

### 3. Start the Database and Model

Start PostgreSQL + pgvector:

```bash
docker compose up -d postgres
docker compose ps
```

Start Ollama if it is not already running:

```bash
ollama serve
```

In another terminal, download and verify the model:

```bash
ollama pull bge-m3
ollama list
```

The application **does not download models automatically**. Make sure Ollama is reachable and `bge-m3` is installed before using the embedding APIs.

### 4. Run the Application

macOS / Linux:

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

Windows:

```powershell
mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local
```

The service listens on `http://localhost:8082` by default.

In IDEA, use the program argument `--spring.profiles.active=local`; do not force the local profile in shared configuration. Development accounts must not be enabled in production.

Liquibase initializes extensions and knowledge/business tables. Completed changesets are not run again. Prepare acceptance fixtures separately: test-container data does not remain in the development database.

## API Usage

### Current Endpoint: Read-Only Agent Chat

```http
POST /api/agent/chat
Content-Type: application/json
```

Local accounts: `tenant-a` (user 101 / tenant 1) and `tenant-b` (user 102 / tenant 2) have both read permissions. `viewer` (user 103 / tenant 1) has no business query permission. Their password comes from `DEMO_SECURITY_PASSWORD`.

Request flow:

1. Call `GET /api/security/csrf` in an HTTP client. Keep the session cookie and the returned `token` and `headerName`.
2. Set HTTP Basic authentication for the Agent request, send the same session cookie, and include the token using the returned header name.
3. Use the appropriate account session when switching accounts. Never put passwords, cookies, or CSRF tokens into prompts, the repository, or public logs.

```json
{
  "message": "先查询 DB-CHECK-2026 项目下未处理的工单，再根据查询结果展示每条工单的详细说明。"
}
```

`message` is required, nonblank, and limited to 4,000 characters. User IDs, tenant IDs, and permissions are not required in the request; they come from server authentication.

The response remains `{"answer":"..."}`. There is no conversationId or cross-request history yet. Each tool independently checks its permission after endpoint admission.

### Legacy Endpoint Reference (Denied by Default)

The following examples preserve legacy request/response contracts, not working anonymous calls under the current security policy. Integrate trusted identity and permissions before restoring these routes; do not bypass security controls to make them callable.

### Basic Chat (Denied by Default)

```http
POST /api/chat
Content-Type: application/json
```

Example request:

```bash
curl -X POST http://localhost:8082/api/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"What can you help me with?"}'
```

Successful response:

```json
{
  "answer": "我是公司内部的企业智能助手……"
}
```

Request fields:

| Field | Type | Required | Constraints |
| --- | --- | --- | --- |
| `message` | String | Yes | Must not be blank; maximum 4,000 characters |

### Generate a Text Embedding (Denied by Default)

```http
POST /api/embeddings
Content-Type: application/json
```

```bash
curl -X POST http://localhost:8082/api/embeddings \
  -H 'Content-Type: application/json' \
  -d '{"text":"员工出差住宿标准是多少？"}'
```

Example response (the values are illustrative and depend on the model):

```json
{
  "dimensions": 1024,
  "preview": [0.0123, -0.0456, 0.0789, 0.0012, 0.0345, -0.0678, 0.0234, 0.0567]
}
```

`text` is required, must not be blank, and is limited to 4,000 characters. `dimensions` is the full vector size. `preview` contains only the first eight values; the API does not return the full vector.

### Calculate Semantic Similarity (Denied by Default)

```http
POST /api/embeddings/similarity
Content-Type: application/json
```

```bash
curl -X POST http://localhost:8082/api/embeddings/similarity \
  -H 'Content-Type: application/json' \
  -d '{"left":"员工出差住宿标准是多少？","right":"公司差旅酒店费用上限是多少？"}'
```

Example response (the score is illustrative):

```json
{
  "score": 0.8,
  "dimensions": 1024
}
```

Both `left` and `right` are required, must not be blank, and are limited to 4,000 characters each. `score` is cosine similarity; a higher value means the vectors point in more similar directions. Each request embeds each text once.

### Error Responses

Errors handled by the controller advice use the following structure. Security-filter 401/403 responses are not guaranteed to use the same JSON format:

```json
{
  "errorCode": "INVALID_REQUEST",
  "message": "message 不能为空"
}
```

| HTTP Status | Error Code | Description |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | The request parameters are invalid |
| 502 | `AI_SERVICE_ERROR` | The model call failed or returned no valid content |
| 500 | `INTERNAL_ERROR` | An unexpected server error occurred |

> Error messages returned by the current implementation are written in Chinese.

The table describes controller-layer error mappings, not availability of denied routes. Unauthenticated, unauthorized, or invalid-CSRF requests may be rejected by the security filter before reaching a controller.

## Prompt Management

- `src/main/resources/prompts/enterprise-system-prompt.txt` defines the assistant's role, response principles, and capability boundaries.
- `src/main/resources/prompts/chat-user-prompt.txt` formats the user's question as a User Message.
- The Agent system prompt currently lives in `AgentServiceImpl`, defining read-only behavior, reliable ticket-number sources, and stopping detail calls when a list is empty.

The System Prompt is loaded when the application starts. If the file is missing or empty, startup fails immediately so that the service cannot run without its expected behavioral constraints.

## Testing and Building

Run the seven offline classes used for this stage:

```bash
./mvnw -Dtest=AgentSecurityTest,DemoUserConfigTest,AgentServiceImplTest,ProjectQueryToolsTest,TicketQueryToolsTest,JdbcBusinessRepositoryTest,JdbcTicketRepositoryTest test
```

The assistant reran these under Java 21 on 2026-10-08: all 160 cases passed. This is a selected scope, not a full-suite pass. Real model tool selection requires separate live validation.

Database integration tests require Docker and explicit selection of `IT` classes:

```bash
./mvnw -Dtest=BusinessSchemaIT,JdbcBusinessRepositoryIT test
```

These tests use temporary containers, not the development database. The developer reported both classes passing. Default `./mvnw test` naming rules do not include these `IT` classes.

Full regression (not fully rerun in this stage):

```bash
./mvnw test
```

Build an executable JAR:

```bash
./mvnw clean package
```

Run the packaged application:

```bash
java -jar target/enterprise-ai-assistant-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

The current test suite covers:

- Spring application context loading
- System Prompt loading and `ChatClient` configuration
- User Prompt template variable substitution
- Successful AI responses and blank-input rejection
- Successful chat API responses and request validation
- Embedding model failures, empty vectors, and cosine similarity edge cases
- Embedding and similarity API responses, request validation, and 502 error mapping

The historical coverage list above is not a claim that all those tests were rerun in this stage. The seven offline classes need no model or database service; Testcontainers integration tests require Docker and a PostgreSQL container. Validate real model behavior separately.

## Current Limitations

- Conversation context is not stored; each request is independent.
- Read-only project and ticket tools query the database and can chain within one request, but conversation and task state are not persisted.
- Knowledge service implementations exist; their HTTP routes remain closed pending trusted-identity integration.
- Business writes, write confirmation, idempotent submission, and Agent-level execution-step budgets are not implemented.
- Streaming responses are not supported.
- Authentication uses local development accounts, not production IAM. Tool audits are logs, not transactional database audit records.
- Tool error categories are not fully preserved through the model layer; not every tool-level permission denial is guaranteed to become HTTP 403.
- Prompts can reduce the risk of incorrect responses, but they cannot replace access controls, security policies, or model evaluation.

## Roadmap

1. Implement conversation ownership, message persistence, and bounded history.
2. Integrate knowledge endpoints with trusted authentication before combining RAG and business tools.
3. Add write previews, user confirmation, idempotency, and transactional auditing.
4. Add execution-step and timeout budgets, recovery, observability, and offline evaluation.

## Development Guidelines

- Controllers handle protocol conversion and request validation; model calls belong in the service layer.
- API keys must be injected through environment variables and must never be stored in source code or configuration files.
- New features should include unit tests. Automated tests must not call the real model service by default.
- Prompt changes should be evaluated against normal questions, insufficient context, fabricated internal data, and prompt-extraction attempts.
