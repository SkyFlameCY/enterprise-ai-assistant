# Enterprise AI Assistant

[简体中文](README.md) | [English](README_EN.md)

An enterprise AI assistant backend built with Spring Boot, Spring AI, and DeepSeek.

The project is currently at the foundational MVP stage. It provides a single-turn chat API, uses separate System and User Prompts to define assistant behavior, and includes request validation, consistent error responses, and core automated tests. Conversation memory, enterprise knowledge retrieval (RAG), access control, and observability are planned for later stages.

## Current Features

- DeepSeek-powered enterprise assistant conversations
- Resource-based System Prompt and User Prompt management
- Chinese responses by default, with safeguards against fabricating internal company data
- Validation for blank messages and a maximum input length of 4,000 characters
- Consistent JSON responses for validation, AI service, and unexpected server errors
- Configuration, service, and controller tests based on mocks, without calling the real model

> The current version supports stateless, single-turn conversations only. It is not connected to an enterprise knowledge base, database, or internal business system.

## Technology Stack

| Technology | Purpose |
| --- | --- |
| Java 21 | Runtime environment |
| Spring Boot 3.5.16 | Web application and dependency management |
| Spring AI 1.1.8 | Model integration and prompt abstractions |
| DeepSeek | Large language model service |
| Jakarta Validation | Request validation |
| JUnit 5, Mockito, MockMvc | Automated testing |
| Maven Wrapper | Build and application execution |

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
│   │   └── service      # Chat service and implementation
│   └── resources
│       ├── prompts      # System and User Prompt templates
│       └── application.yaml
└── test                 # Configuration, service, controller, and context tests
```

## Getting Started

### 1. Prerequisites

- JDK 21
- Network access to the DeepSeek API
- A DeepSeek API key

The Maven Wrapper is included, so a separate Maven installation is not required.

### 2. Configure the API Key

Create a `.env` file in the project root:

```properties
DEEPSEEK_API_KEY=your_api_key
```

The `.env` file is ignored by Git. Never commit real credentials or place them in source code, prompts, or logs. In production, inject the environment variable through your deployment platform's secret management system.

Alternatively, set the environment variable directly:

```bash
export DEEPSEEK_API_KEY="your_api_key"
```

### 3. Run the Application

macOS / Linux:

```bash
./mvnw spring-boot:run
```

Windows:

```powershell
mvnw.cmd spring-boot:run
```

The service listens on `http://localhost:8082` by default.

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
  "answer": "I am an internal enterprise AI assistant..."
}
```

Request fields:

| Field | Type | Required | Constraints |
| --- | --- | --- | --- |
| `message` | String | Yes | Must not be blank; maximum 4,000 characters |

### Error Responses

All errors use the following structure:

```json
{
  "errorCode": "INVALID_REQUEST",
  "message": "message must not be blank"
}
```

| HTTP Status | Error Code | Description |
| --- | --- | --- |
| 400 | `INVALID_REQUEST` | The request parameters are invalid |
| 502 | `AI_SERVICE_ERROR` | The model call failed or returned no valid content |
| 500 | `INTERNAL_ERROR` | An unexpected server error occurred |

> Error messages returned by the current implementation are written in Chinese.

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

## Current Limitations

- Conversation context is not stored; each request is independent.
- The assistant cannot query enterprise knowledge bases, databases, or internal systems.
- Streaming responses are not supported.
- User authentication, role-based access control, and audit logs are not implemented.
- Prompts can reduce the risk of incorrect responses, but they cannot replace access controls, security policies, or model evaluation.

## Roadmap

1. Add a database, health checks, and environment-specific configuration.
2. Persist conversations and messages to support multi-turn chat.
3. Build document ingestion, chunking, embedding, and retrieval pipelines.
4. Add source citations and refusal behavior to knowledge-based answers.
5. Integrate enterprise authentication, document-level access filtering, and audit logs.
6. Add streaming, rate limiting, timeouts, retries, and observability.
7. Create an offline evaluation dataset to track accuracy, hallucination rate, and latency.

## Development Guidelines

- Controllers handle protocol conversion and request validation; model calls belong in the service layer.
- API keys must be injected through environment variables and must never be stored in source code or configuration files.
- New features should include unit tests. Automated tests must not call the real model service by default.
- Prompt changes should be evaluated against normal questions, insufficient context, fabricated internal data, and prompt-extraction attempts.

