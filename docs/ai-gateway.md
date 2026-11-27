# Claude through the AI gateway

How the functions call Claude through the AI gateway (DD-44006): which settings choose it, how it authenticates, how to run it locally, and how gateway errors and retries are handled.

## Choosing a provider per role

Each role has its own switch. `ChatServiceFactory.getInstance(ChatRole)` resolves the switch and builds the chat service.

| Role | Switch | Values |
|---|---|---|
| Answer generation | `LLM_CHAT_SERVICE_PROVIDER` | `openai` (default) \| `azure` \| `claude-gateway` |
| Scoring judge | `JUDGE_LLM_CHAT_SERVICE_PROVIDER` | Same values. If it is unset, the judge follows `LLM_CHAT_SERVICE_PROVIDER` |

Each provider's settings sit side by side, so switching a role only changes its switch:

| Provider | Generation settings | Judge settings |
|---|---|---|
| `openai` / `azure` | `AZURE_OPENAI_ENDPOINT`, `AZURE_OPENAI_CHAT_DEPLOYMENT_NAME` | `AZURE_JUDGE_OPENAI_ENDPOINT`, `AZURE_JUDGE_OPENAI_CHAT_DEPLOYMENT_NAME` |
| `claude-gateway` | `AI_GATEWAY_BASE_URL`, `AI_GATEWAY_CHAT_MODEL` | `AI_GATEWAY_BASE_URL`, `AI_GATEWAY_JUDGE_MODEL` |

- `AI_GATEWAY_BASE_URL` is the gateway's Messages base URL, without `/v1/messages`, for example `https://<host>/ai/platform/v1/bedrock-messages`. The SDK appends `/v1/messages` itself.
- `AI_GATEWAY_JUDGE_MODEL` does **not** fall back to `AI_GATEWAY_CHAT_MODEL`, so changing one role's model never changes the other's.
- If a setting the chosen provider needs is missing, the app fails at startup and names the missing key.

## Authentication

`AnthropicChatService` (shared artefacts) calls the gateway's Anthropic Messages route through `AnthropicClientFactory.getAiGatewayInstance(baseUrl)`, using `AiGatewayBackend`. Every request carries two credentials:

- `Authorization: Bearer <token>`: an Entra token for `AI_GATEWAY_TOKEN_SCOPE` (the gateway's app registration, `api://<client-id>/.default`), obtained through `DefaultAzureCredential`. `CachingTokenSupplier` reuses the token until 5 minutes before it expires.
- `Ocp-Apim-Subscription-Key`: the value of `AI_GATEWAY_SUBSCRIPTION_KEY`.

The SDK's Foundry backend isn't used, because it appends `/anthropic` to the base URL, which is not the gateway's path.

### Local development with two accounts

Sometimes the account that has the gateway role is not the account used for the other Azure services. For example, a user in one tenant may have the gateway role while a user in another tenant has access to Search, Document Intelligence and embeddings.

In that case, set `AI_GATEWAY_LOCAL_CLI_SUBSCRIPTION` to a subscription of the gateway account:

- Gateway tokens then come from the Azure CLI, pinned to that subscription. The CLI ties each subscription to the account that signed in to it. Choosing by `--tenant` instead would keep the default account.
- Every other Azure call keeps the default subscription.
- The app refuses to start with this setting when `WEBSITE_INSTANCE_ID` is present, which is the case on every Azure-hosted app. Deployed apps therefore always use their managed identity.

## Request settings

| Setting | Effect |
|---|---|
| `LLM_MODEL_RESPONSE_MAX_TOKENS` | `max_tokens` |
| `LLM_ANTHROPIC_TEMPERATURE` | Optional. `top_p` is never sent, because Claude rejects a request that sets both temperature and top_p |
| `LLM_ANTHROPIC_THINKING` | Optional: `adaptive` \| `disabled`. If unset, the model's default applies, and newer models such as Sonnet 5 think by default |
| `LLM_ANTHROPIC_EFFORT` | Optional output effort |
| `AZURE_CLIENT_MAX_RETRIES`, `HTTP_CLIENT_{RESPONSE,CONNECT,WRITE}_TIMEOUT_IN_SECONDS` | Retries and timeouts. These are the same settings as the OpenAI client and map the same way (see `OpenAiClientConfiguration`) |

## Gateway errors and retries

The gateway's error codes, its error body shape and its `x-correlation-id` header are documented by the gateway team. See the error codes page in the internal `hmcts/platform-ai-gateway-docs` repository (`source/reference/errors.html.md.erb`). This section covers only how our client handles them.

The Anthropic SDK keeps the raw error body, so the gateway's `code` (for example `token_quota_exceeded`) appears in the logged exception. It retries the same statuses as the OpenAI SDK: 408, 409, 429 and any 5xx, unless an `X-Should-Retry` response header says otherwise. Every other status, including the gateway's 400, 401, 403, 404 and 413 responses and their Bedrock pass-through equivalents, fails at once.

### Retry wait cap

The gateway sends `Retry-After: 60` on every rate or quota rejection. The Anthropic SDK otherwise waits however long the server asks. With `AZURE_CLIENT_MAX_RETRIES=3`, one attempt sat idle for 3 minutes. Adding the model's own time took it past the answer-generation queue's 5-minute visibility timeout and idempotency lease, so a second worker picked up the message.

`AiGatewayBackend.prepareResponse` runs before the SDK's retry loop reads the response. If a `Retry-After` or `Retry-After-Ms` header asks for longer than 8 s, it replaces the header with `Retry-After-Ms: 8000`. That is the same ceiling the OpenAI SDK uses. Shorter waits, and successful responses, pass through unchanged.

Keep this cap in mind before moving the scoring judge onto the gateway. The scoring queue's visibility timeout is 30 s, so 3 capped waits plus the model's time can still go past it.

### Correlation ID

- Every non-2xx response is logged as a WARN from `AiGatewayBackend`, with the status, the correlation ID, and the wait requested and applied. Retried attempts are logged too.
- When a call finally fails, `AnthropicChatService` adds the ID to the `ChatServiceException` message. On the async path, that message is written to the answer-generation table's reason column.

## Orchestration tests

- Each failsafe leg pins the generation switch with the `it.llm.provider` property. The default is `openai`, and the Azure rollback leg stays on `azure`.
- To run Claude generation, pass `-Dit.llm.provider=claude-gateway`.
- `ClaudeGatewayEnv` forwards the gateway settings to the function hosts only when they are set.

## Related code

- The harness's own `AnthropicChatService` (`ai-document-system-prompt-harness-eval`, used for Foundry and direct Bedrock) delegates to the shared class.
- Keep `anthropic-java` below 3.0.0, and keep Kotlin pinned at 1.9.0 in the root `dependencyManagement`. The SDK needs Kotlin 1.9.0, and `openai-java` on its own would resolve 1.8.0.
