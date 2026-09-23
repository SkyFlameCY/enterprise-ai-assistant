# Enterprise AI Assistant

[简体中文](README.md) | [English](README_EN.md)

An enterprise AI assistant backend built with Spring Boot and Spring AI. DeepSeek handles chat generation, while local Ollama/BGE-M3 handles text embeddings.

The project is currently at the foundational stage. It provides single-turn chat, text embedding, and semantic similarity APIs, and includes PostgreSQL + pgvector infrastructure. Document retrieval, Agent tool calls, access control, and observability are planned for later stages.

## Current Features

- DeepSeek-powered enterprise assistant conversations
- Local BGE-M3 text embeddings and cosine similarity between two texts
- Resource-based System Prompt and User Prompt management
- Chinese responses by default, with safeguards against fabricating internal company data
- PostgreSQL, pgvector, and Liquibase database infrastructure
- Validation for blank input and a maximum input length of 4,000 characters
- Consistent JSON responses for validation, AI service, and unexpected server errors
- Configuration, service, and controller tests based on mocks; automated tests do not call real models

> The database currently supports infrastructure and extension initialization only. Documents and vectors are not stored yet, and enterprise knowledge retrieval and Agent tool calls are not implemented.

## Technology Stack

| Technology | Purpose |
| --- | --- |
| Java 21 | Runtime environment |
| Spring Boot 3.5.16 | Web application and dependency management |
| Spring AI 1.1.8 | Model integration and prompt abstractions |
| DeepSeek | Chat model |
| Ollama + BGE-M3 | Local text embedding model producing 1,024-dimensional vectors |
| PostgreSQL 17 + pgvector | Database and infrastructure for future vector retrieval |
| Liquibase | Database migrations |
| Jakarta Validation | Request validation |
| JUnit 5, Mockito, MockMvc | Automated testing |
| Docker Compose, Maven Wrapper | Local database, build, and application execution |

## Project Structure

```text
src
├── main
│   ├── java/cn/coder/sanwei/enterpriseaiassistant
│   │   ├── config       # ChatClient and System Prompt configuration
│   │   ├── controller   # HTTP endpoints
│   │   ├── dto          # Request and response objects
│   │   ├── error        # Consistent error responses
│   │   ├── exception    # Business exceptions and global error handling
│   │   ├── model        # Service-level result objects
│   │   └── service      # Chat and embedding services
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

Replace at least `DEEPSEEK_API_KEY` and `POSTGRES_PASSWORD` in `.env`. `POSTGRES_HOST=localhost` is for running the Java application on the host and connecting to the container through its published port. The default Ollama URL is `http://localhost:11434`, and the default model is `bge-m3`; use `OLLAMA_BASE_URL` and `OLLAMA_EMBEDDING_MODEL` to override them.

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
./mvnw spring-boot:run
```

Windows:

```powershell
mvnw.cmd spring-boot:run
```

The service listens on `http://localhost:8082` by default.

At startup, Liquibase enables the `vector` and related extensions in the target database. Completed changesets are not run again on subsequent starts.

## API Usage

### Start a Chat

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

### Generate a Text Embedding

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

### Calculate Semantic Similarity

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

All errors use the following structure:

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

If Ollama is unavailable or the model call fails, the embedding APIs return `502 AI_SERVICE_ERROR`. Blank input returns `400 INVALID_REQUEST`.

## Prompt Management

- `src/main/resources/prompts/enterprise-system-prompt.txt` defines the assistant's role, response principles, and capability boundaries.
- `src/main/resources/prompts/chat-user-prompt.txt` formats the user's question as a User Message.

The System Prompt is loaded when the application starts. If the file is missing or empty, startup fails immediately so that the service cannot run without its expected behavioral constraints.

## Testing and Building

Run all tests:

```bash
./mvnw test
```

Build an executable JAR:

```bash
./mvnw clean package
```

Run the packaged application:

```bash
java -jar target/enterprise-ai-assistant-0.0.1-SNAPSHOT.jar
```

The current test suite covers:

- Spring application context loading
- System Prompt loading and `ChatClient` configuration
- User Prompt template variable substitution
- Successful AI responses and blank-input rejection
- Successful chat API responses and request validation
- Embedding model failures, empty vectors, and cosine similarity edge cases
- Embedding and similarity API responses, request validation, and 502 error mapping

Tests use mock models and services, so Ollama, DeepSeek, and PostgreSQL are not needed to run them. Validate real model behavior separately in a local environment.

## Current Limitations

- Conversation context is not stored; each request is independent.
- The database is connected, but document ingestion, vector storage, knowledge retrieval, and internal system queries are not implemented.
- Agent tool calls and multi-step task execution are not implemented.
- Streaming responses are not supported.
- User authentication, role-based access control, and audit logs are not implemented.
- Prompts can reduce the risk of incorrect responses, but they cannot replace access controls, security policies, or model evaluation.

## Roadmap

1. Build document ingestion, chunking, vector storage, and Top-K retrieval.
2. Add source citations and refusal behavior when the evidence is insufficient.
3. Persist conversations and messages to support multi-turn chat.
4. Add permission-checked business tools, confirmation for writes, and Agent execution state.
5. Add auditing, timeouts, retries, observability, and offline evaluation.

## Development Guidelines

- Controllers handle protocol conversion and request validation; model calls belong in the service layer.
- API keys must be injected through environment variables and must never be stored in source code or configuration files.
- New features should include unit tests. Automated tests must not call the real model service by default.
- Prompt changes should be evaluated against normal questions, insufficient context, fabricated internal data, and prompt-extraction attempts.
