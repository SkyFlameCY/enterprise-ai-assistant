# Enterprise AI Assistant

[简体中文](README.md) | [English](README_EN.md)

An enterprise AI assistant backend built with Spring Boot and Spring AI. DeepSeek handles chat generation, while local Ollama/BGE-M3 handles text embeddings.

The model can query PostgreSQL projects, ticket lists, and ticket details, then use a list result to request details. The server enforces identity, permissions, and tenant filters; the model cannot choose the query tenant. Conversations and messages are stored in PostgreSQL, supporting bounded multi-turn context and continued conversations after application restarts.

The Agent can also retrieve tenant-scoped knowledge and combine policy fragments with fresh project/ticket results, for example to review a ticket against a synthetic defect-reporting policy. Knowledge is evidence, not a replacement for live business queries or current permissions.

This is a development-validation build, not a complete production Agent platform.

## Current Features

- DeepSeek with four native Spring AI read-only tools: `queryProject`, `searchTickets`, `getTicketDetail`, and `searchKnowledge`
- Real PostgreSQL business queries; ticket lists filter by project and status, use stable ordering, and return at most 10 rows
- Trusted execution identity derived from authentication; independent tool checks for `project:read`, `ticket:read`, or `knowledge:read`
- Allowlisted DTO fields and tool audit events containing identity, counts, duration, outcome, and error category
- HTTP Basic development accounts in the `local` profile, with CSRF protection retained
- Conversation ownership bound to the authenticated tenant and user; client-supplied identities and history are not used
- Complete USER/ASSISTANT turns persisted with a bounded recent-history window and body-length budget
- Model calls outside database transactions; version-checked short transactions prevent stale concurrent answers and partial writes
- Local BGE-M3 text embeddings and cosine similarity between two texts
- Text knowledge ingestion, chunk/vector persistence, and retrieval filtered by the authenticated tenant, active state, and embedding revision
- Endpoint and service checks for `knowledge:read` or `knowledge:write`, with retrieved source fragments returned alongside RAG answers
- Resource-based System Prompt and User Prompt management
- Chinese responses by default, with safeguards against fabricating internal company data
- PostgreSQL, pgvector, and Liquibase database infrastructure
- Validation for blank input and a maximum input length of 4,000 characters
- Consistent JSON responses for validation, AI service, and unexpected server errors
- Offline tests mock model, query, or audit dependencies; database integration tests use temporary PostgreSQL containers; neither calls real models

> The security policy exposes CSRF token retrieval, permission-checked Agent chat, and knowledge text import, search, and question answering. Knowledge identity comes from authentication; clients cannot choose the tenant, and the Agent knowledge tool independently checks permission. Legacy chat, embedding, and file-import HTTP routes remain denied by default. Existing validation is not a full-suite or production acceptance claim.

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
│   │   ├── memory       # Bounded history validation and structured messages
│   │   ├── model        # Service-level result objects
│   │   ├── repository   # Knowledge, business queries, and conversation storage
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

Liquibase initializes extensions and knowledge, business, conversation, and message tables. Completed changesets are not run again. Prepare demonstration fixtures separately: test-container data does not remain in the development database.

## API Usage

### Conversation-Aware Read-Only Agent Chat

```http
POST /api/agent/chat
Content-Type: application/json
```

Local accounts: `tenant-a` (user 101 / tenant 1) and `tenant-b` (user 102 / tenant 2) have both read permissions. `viewer` (user 103 / tenant 1) has no business query permission. Their password comes from `DEMO_SECURITY_PASSWORD`.

`agent-reader-a` (user 401 / tenant 1) and `agent-reader-b` (user 402 / tenant 2) have project, ticket, and knowledge read permissions for combined queries, but no write permissions. Original accounts remain unchanged; Agent admission does not grant access to every tool.

Request flow:

1. Call `GET /api/security/csrf` in an HTTP client. Keep the session cookie and the returned `token` and `headerName`.
2. Set HTTP Basic authentication for the Agent request, send the same session cookie, and include the token using the returned header name.
3. Use the appropriate account session when switching accounts. Never put passwords, cookies, or CSRF tokens into prompts, the repository, or public logs.

```json
{
  "message": "查询 EA-2026 项目"
}
```

`message` is required, nonblank, and limited to 4,000 characters. Omitting `conversationId` or setting it to null creates an owned conversation. A supplied ID must be positive and belong to the authenticated user and tenant. The example project requires corresponding local business data.

Example successful response structure (77 is illustrative; the answer depends on the current model and tool results):

```json
{
  "conversationId": 77,
  "turnNo": 1,
  "answer": "..."
}
```

Continue using the actual returned conversation ID:

```json
{
  "conversationId": 77,
  "message": "它有哪些未处理工单？"
}
```

Success returns the same conversationId and the actual saved turnNo. Client-supplied userId, tenantId, permissions, version, turnNo, or history are not used; identity and version come from the server. A conversation ID is not an access credential. Missing and unowned conversations both return 404, without creating a replacement.

History only provides reference and explicit-identifier hints. Current permissions, existence, and project/ticket state must still be checked through fresh tool queries. Each tool independently checks the current identity's permission after endpoint admission.

Example combined query using an account with all three read permissions:

```json
{
  "message": "查询 EA-2026 项目信息，取第一条未处理工单，按照公司的缺陷提单规范检查它的说明，告诉我需要补充什么，并说明依据。"
}
```

The tenant must have the project, an OPEN ticket, and the corresponding synthetic policy. The model selects tools according to the task; detail calls following a list must use an actual returned ticket number. searchKnowledge uses server-controlled topK=3 and minSimilarity=0.25, an initial setting rather than an evaluated optimum.

Agent source identifiers appear in answer text for manual checking. The Agent response has no new structured sources field or programmatic sentence-level citation validation. The separate knowledge ask endpoint still returns answer and sources.

### Conversation History and Consistency

Configure `agent.memory` at the same YAML level as `spring`:

```yaml
agent:
  memory:
    turn-limit: 5
    max-context-chars: 16000
    max-assistant-chars: 8000
```

- turn-limit accepts 1–20 and loads only recent complete USER/ASSISTANT turns.
- max-context-chars accepts 4,000–64,000 and counts history bodies plus current input using Java `String.length()`. After reserving the current input, keep consecutive complete turns from newest to oldest, stopping at the first turn that does not fit. Do not truncate bodies or skip that turn to select older content.
- max-assistant-chars accepts 1–16,000. Blank or oversized answers fail without being truncated and saved.
- The body budget is not a complete model-token budget: fixed system instructions, tool definitions, and the current native tool-call trajectory are excluded.
- Read the owned ACTIVE conversation, version, and history before the model call. Invoke the model outside a transaction, then recheck ownership, state, and the original version in a short transaction that advances version/time and inserts both messages.
- Only one concurrent answer based on the same old version can commit; other requests return a conflict, without silently resaving. Model failures do not append a turn; save failures roll back messages, version, and update time.
- A new conversation is created before the model call. A failed first call may leave an owned empty conversation; automatic cleanup and request idempotency are not provided.

After restarting the application, the same ID loads history from PostgreSQL. Retain database data and volumes; conversation recovery is not long-running task recovery.

### Enterprise Knowledge Base

Knowledge endpoints use the same HTTP Basic, session cookie, and CSRF request flow as the Agent endpoint. The original `tenant-a` and `tenant-b` accounts retain project/ticket read permissions. Knowledge uses dedicated `local` accounts with passwords from `DEMO_SECURITY_PASSWORD`:

| Account | User ID | Tenant ID | Permissions |
| --- | ---: | ---: | --- |
| `knowledge-reader-a` | 301 | 1 | `knowledge:read` |
| `knowledge-reader-b` | 302 | 2 | `knowledge:read` |
| `knowledge-editor-a` | 303 | 1 | `knowledge:read`, `knowledge:write` |
| `knowledge-editor-b` | 304 | 2 | `knowledge:read`, `knowledge:write` |

| Method and path | Permission | Result |
| --- | --- | --- |
| `POST /api/knowledge/import/text` | `knowledge:write` | Document ID |
| `POST /api/knowledge/search` | `knowledge:read` | Retrieved fragment list |
| `POST /api/knowledge/ask` | `knowledge:read` | `answer` and `sources` |

Example text import:

```json
{
  "documentKey": "demo-expense-policy",
  "version": 1,
  "title": "示例报销制度",
  "sourceRef": "demo:expense-v1",
  "text": "以下为模拟制度：本示例项目的每人报销上限为137元。"
}
```

`documentKey`, `version`, `title`, and `text` are required; text accepts at most 20,000 characters. Reimporting the same document key and version within a tenant returns 409; different tenants may use the same key. The import service checks write permission before duplicate lookup or embedding, and assigns the authenticated tenant to the entity.

Search and question answering share the same request structure:

```json
{
  "question": "示例项目的报销上限是多少？",
  "topK": 5,
  "minSimilarity": 0.5
}
```

`question` must be nonblank and at most 4,000 characters; `topK` accepts 1–20; `minSimilarity` must be finite and within [-1,1]. The example threshold is not an evaluated recommendation, and similarity is not an answer correctness probability. Requests need no `tenantId`, `userId`, or `permissions`; additional identity fields cannot change the effective identity.

Retrieval uses the authenticated tenant and only ACTIVE documents with the current embedding model/revision. `sources` contains document ID, title, source reference, chunk ID/index, content, and similarity. It represents retrieved fragments; sentence-level citations and a complete document-version citation contract are not implemented. If no fragment passes the threshold, question answering returns the fixed insufficient-evidence message without calling the chat model. Document version replacement, publishing, and withdrawal are not yet implemented.

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
| 403 | `ACCESS_DENIED` | The knowledge service rejects the identity or permission |
| 404 | `CONVERSATION_NOT_ACCESSIBLE` | The conversation is missing or not owned by the current user and tenant |
| 409 | `DOCUMENT_ALREADY_EXISTS` | This tenant already has the same document key and version |
| 409 | `CONVERSATION_STATE_ERROR` | The conversation is archived or has reached its continuation state/version limit |
| 409 | `CONVERSATION_CONFLICT` | The conversation version changed while the model generated its answer |
| 502 | `AI_SERVICE_ERROR` | The model call failed or returned no valid content |
| 500 | `INTERNAL_ERROR` | An unexpected server error occurred |

> Error messages returned by the current implementation are written in Chinese.

The table describes controller-layer error mappings, not availability of denied routes. Unauthenticated, unauthorized, or invalid-CSRF requests may be rejected by the security filter before reaching a controller.

## Prompt Management

- `src/main/resources/prompts/enterprise-system-prompt.txt` defines the assistant's role, response principles, and capability boundaries.
- `src/main/resources/prompts/chat-user-prompt.txt` formats the user's question as a User Message.
- `AgentPromptProvider` loads and combines `prompts/agent/core-system.txt`, `ticket-workflow.txt`, and `knowledge-workflow.txt`, defining identity boundaries, tool responsibilities, combined queries, and source requirements. Missing or blank required resources fail startup.
- History uses separate USER/ASSISTANT messages, not one combined user body or a replay of unpersisted tool trajectories.

The System Prompt is loaded when the application starts. If the file is missing or empty, startup fails immediately so that the service cannot run without its expected behavioral constraints.

## Testing and Building

Run the selected 23 offline regression classes:

```bash
./mvnw -Dtest=ConversationServiceImplTest,ConversationTurnSaveTest,ConversationExceptionHandlerTest,JdbcMessageRepositoryTest,JdbcConversationRepositoryTest,TicketQueryToolsTest,JdbcTicketRepositoryTest,JdbcBusinessRepositoryTest,AgentServiceImplTest,DemoUserConfigTest,AgentSecurityTest,ProjectQueryToolsTest,AgentMemoryPropertiesTest,ConversationHistoryBuilderTest,ConversationAgentServiceImplTest,AgentMessageModelTest,PermissionServiceImplTest,KnowledgeImportServiceImplTest,KnowledgeRetrievalServiceImplTest,RagServiceImplTest,KnowledgeSecurityTest,KnowledgeQueryToolsTest,AgentPromptProviderTest test
```

On 2026-10-22, the assistant reran these 23 classes under Java 21: all 490 cases passed, with no failures, errors, or skips. Coverage includes fourth-tool registration/native callbacks, trusted identity, permissions, audit failure isolation, required prompt resources, and existing conversation/security behavior. Dependencies are mocked by layer; no live services are called. This is not a full-suite pass or proof of real-model tool selection.

The developer also reported all eight fixed live tasks passing: knowledge/business routing, a four-tool combined task, clarification, empty ticket lists, knowledge permission denial, unsupported facts, and cross-tenant read requests. Raw responses, tool sequences, and sources were not individually archived. This is developer-reported evidence, not independent assistant execution, systematic accuracy/performance/security validation, or a before-and-after improvement measurement.

Selected offline knowledge identity, permission, and account tests:

```bash
./mvnw -Dtest=PermissionServiceImplTest,KnowledgeImportServiceImplTest,KnowledgeRetrievalServiceImplTest,RagServiceImplTest,KnowledgeSecurityTest,DemoUserConfigTest,AgentSecurityTest test
```

On 2026-10-15, the assistant reran these seven classes: 87/87 passed. A separate run against the real local application, PostgreSQL/pgvector, Ollama/BGE-M3, and DeepSeek passed 24 live checks, including shared document keys across tenants, forged identity fields, retrieval/answer source isolation, Basic/CSRF rejection, and duplicate imports. Synthetic fixtures were removed afterward. Two synthetic-policy answers and a deliberately empty-evidence case are not a systematic retrieval, refusal, or answer-quality evaluation.

The developer performs live business verification manually in an HTTP client. Start the `local` application, development database, and models, then use the Basic, cookie, and CSRF flow described above:

1. Import a synthetic policy as `knowledge-editor-a` and record its returned document ID.
2. Import the same key and version as `knowledge-editor-b`, with a different reimbursement limit.
3. Search and ask as each tenant's reader. Inspect the fragment content, answer, and source document IDs against that tenant's policy.
4. Confirm that reader imports return 403 and duplicate imports by an editor return 409.
5. Add `tenantId: 2` to tenant A's search request and confirm that the result still belongs to A.

The assistant prepares manual scenarios, explains their business meaning, and reviews the developer's results. Future live verification is performed by the developer unless explicitly requested otherwise. Manually imported fixtures may be retained for version-management exercises; use a new document key for another complete run. On 2026-10-16, the developer reported that all manual steps passed, without individually archived raw responses. This feedback is recorded separately from the assistant's 24 historical live checks above.

Database integration tests require Docker and explicit selection of `IT` classes:

```bash
./mvnw -Dtest=BusinessSchemaIT,JdbcBusinessRepositoryIT,AgentConversationSchemaIT,AgentMessageSchemaIT,AgentConversationVersionSchemaIT,JdbcConversationRepositoryIT,JdbcMessageRepositoryIT,ConversationServiceIT,ConversationAgentServiceIT test
```

These tests use temporary containers, not the development database. The classes are recorded as passing based on developer feedback; the assistant did not independently execute them. The conversation orchestration integration class contains 16 scenarios, mocks only the model boundary, and checks real history queries, short transactions, rollback, and concurrency conflicts. Default `./mvnw test` naming rules do not include these `IT` classes.

Local live-model follow-ups, cross-tenant conversation rejection, and continuation after application restart were reported as passing by the developer. Raw responses and audit logs were not individually archived; this is not evidence of independent assistant execution or production validation.

Full regression (the following full-suite command is not claimed to pass):

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
- Conversation configuration binding, complete history pairs, body budgets, and structured model messages
- Conversation ownership, pre-model version snapshots, short save transactions, and concurrency conflicts
- Conversation HTTP contracts, 404/409/500/502 mappings, and the existing security filter chain

The coverage list above is not a claim that every test was rerun. The selected 23 offline classes need no model or database service; Testcontainers integration tests require Docker and a PostgreSQL container. Validate real model behavior separately.

## Current Limitations

- Conversations persist only USER and final ASSISTANT text; summaries, long-term memory, native tool trajectories, and task execution state machines are not implemented.
- Trimming history may remove the information needed to resolve a reference, requiring clarification. Conversation persistence is not request idempotency or long-running task recovery.
- Knowledge is integrated into the Agent with tenant isolation and read/write permissions; department/document ACLs, version publishing/withdrawal, programmatic sentence-level citations, and structured Agent sources remain unimplemented.
- Business operations such as ticket creation, write confirmation, idempotent submission, and Agent-level execution-step budgets are not implemented.
- Streaming responses are not supported.
- Authentication uses local development accounts, not production IAM. Tool audits are logs, not transactional database audit records.
- Tool error categories are not fully preserved through the model layer; not every tool-level permission denial is guaranteed to become HTTP 403.
- Prompts can reduce the risk of incorrect responses, but they cannot replace access controls, security policies, or model evaluation.

## Development Guidelines

- Controllers handle protocol conversion and request validation; model calls belong in the service layer.
- API keys must be injected through environment variables and must never be stored in source code or configuration files.
- New features should include unit tests. Automated tests must not call the real model service by default.
- Prompt changes should be evaluated against normal questions, insufficient context, fabricated internal data, and prompt-extraction attempts.
