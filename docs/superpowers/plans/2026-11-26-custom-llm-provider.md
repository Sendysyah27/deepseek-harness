# Custom LLM Provider Implementation Plan

> For agentic workers: use executing-plans task by task; TDD (RED→GREEN) per task.

**Goal:** Add a custom OpenAI-compatible LLM provider as an alternative "brain" to DeepSeek, configurable at runtime, persisted on-device, usable via agent tools.

**Architecture:** Pure registry (`LlmProviders.kt`) holds provider definitions and invariants with no Android dependency so JVM tests run under `tools/run_tests.sh`. Android adapter (`LlmProviderStore.kt`) persists configs in `harness` prefs and keys in `SecureStore`. HTTP client (`OpenAiCompatClient.kt`) calls `/chat/completions`. Agent tools `llm_provider_list`, `llm_provider_set`, `llm_provider_ask` expose it. `AgentEngines` registers the custom provider as a selectable engine.

**Tech Stack:** Kotlin 2.0.21, org.json, java.net.HttpURLConnection, Android Keystore, existing `tools/run_tests.sh`.

## Global Constraints

- No new Gradle/Android dependencies. No API keys in chat, logs, or plain prefs — only `SecureStore`.
- Reserved ids `deepseek` and `local` cannot be replaced by custom providers.
- Custom provider id must match `[a-z0-9_]+`; base URL and model required; key optional but flagged when missing.
- DeepSeek's DSML/fragment parser remains untouched.
- All tests must pass via `bash tools/run_tests.sh` (JVM-only, no Android jar).

## Review Focus

- Blank base URL / model / id accepted → rejected by `LlmProviders.addCustom`.
- Reserved id overwritten → rejected.
- API key leaked into tool output / catalog → `llm_provider_list` shows only status, never the secret.
- Duplicate provider id creates two entries → upsert keeps one.
- Removing the active provider leaves active id pointing at nothing → resolve falls back to `deepseek`.

## Tasks

### Task 1: RED — LlmProvidersTest

- Create `app/src/test/LlmProvidersTest.kt` with checks: default deepseek present/active; addCustom requires `id`, `baseUrl`, `model`; reserved id rejected; duplicate id replaces; removeCustom rejects built-ins; active provider resolution falls back to deepseek when active id is missing.
- Modify `tools/run_tests.sh` to compile `LlmProviders.kt` + `LlmProvidersTest.kt`.
- Run; expect compile failure (class missing).

### Task 2: GREEN — pure LlmProviders

- Create `app/src/main/java/com/senzwh/deepharness/LlmProviders.kt` implementing `LlmKind`, `LlmProvider`, `LlmProviders` with the invariants above.
- Run `bash tools/run_tests.sh`; expect all green.

### Task 3: Android adapter + HTTP client

- Create `LlmProviderStore.kt`: load/save/delete persisted configs (`llm_provider_cfg_<id>`), key via `SecureStore` (`llm_provider_key_<id>`), active id via prefs.
- Create `OpenAiCompatClient.kt`: `ask(provider, key, prompt): Pair<String,String>` using `HttpURLConnection` POST `/chat/completions` with `messages`, parse `choices[0].message.content`, non-2xx → readable error, redact key from error messages.
- ToolRegistry: add `llm_provider_list`, `llm_provider_set`, `llm_provider_ask`.
- Tools.kt: dispatch the three tools near `api_read`/`api_write`.
- AgentEngine.kt: add `openai` engine info; `current(ctx)` honors custom active id.
- Run full suite; commit.

### Task 4: Push + CI build

- `git add` intended files, commit, `git push origin main`.
- Watch CI via `gh run watch`; report APK artifact.
