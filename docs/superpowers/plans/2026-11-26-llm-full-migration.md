# Custom LLM Provider Migration Plan: Remove DeepSeek Web, Migrate to OpenAI-Compatible API Models

## Status — Fase 6 K1 (27 Nov 2026, pekerja F6a; BUKAN bukti status, hanya penanda)

Keputusan Sendy 26 Nov 19:12 (K1): DeepSeek web/API internal **DIHAPUS TOTAL** dari kode — plan ini yang dulu memilih "disimpan terkompilasi tapi tak dipakai" **SUDAH DISALIP**. Yang sudah dilakukan Fase 6 di staging `work-models/stage-f6/` (belum push — koordinator yang verifikasi CI/APK):

- **11 file definisi dihapus** (±4.277 baris): `DsApiClient.kt` (1.298), `SlotClient.kt` (738), `DsAgent.kt` (283), `PowSolver.kt` (374), `AccountPool.kt` (162), `DsApiSeam.kt` (130), `DsFailure.kt` (313), `SlotBrowser.kt` (318), `ApiToolLayer.kt` (442), `DsSafetyStrip.kt` (88), `DsSafetyMigration.kt` (131). Entri activity `SlotBrowser1–5` + `SlotHarvestReceiver` dihapus dari `AndroidManifest.xml`.
- **Semua call-site dibersihkan**: `MainActivity` (reviewer slot, room slot, koordinator slot → indeks pekerja 1..N, gerbang `apiMode`, tab Web login + Browser 1–5, `sendViaWebView`, `webAnalyzePhoto`), `BgBrain`, `GroupChat`, `TeamRun`, `OcCoord`, `SetupController`, `TurnMetrics`, `Governor`, `ReviewerVc298`, `WorldState`, `Tools`, `BrainBackup`, `ApiQueue`, `BgRoomSeam`. `operatorMode()` + tombol Setup 12b mati (sudah dihapus Fase 5).
- **Tetap ada (sengaja, bukan sisa)**: preset `deepseek-platform` (`api.deepseek.com/v1`, §8.2-1 BOLEH); engine `local`; fallback teks provider (fungsi DSML/teks `AgentPrompt` dipertahankan sebagai dialek teks, bukan mesin DeepSeek); pemetaan nilai legacy `"deepseek"`→`"openai"` (migrasi upgrade); seam Rust `RustCore` (PoW/DeepSeekHashV1 — fungsional, tidak dipakai Kotlin lagi, keputusan nasibnya di koordinator).
- **Regresi jujur yang perlu keputusan Sendy**: lampiran audio tidak bisa dianalisa lagi (tidak ada transkripsi di Models API); kartu Profil "PEMAKAIAN TOKEN" dihapus (counter token hanya ditulis klien lama — pencatatan `usage` Models API belum ada, tindak lanjut); mode koordinator room ditulis ulang ke pekerja-berbasis-indeks (bukan slot).
- **Verifikasi staging**: `bash tools/run_tests.sh` = 505 cek ok, 0 FAIL (baseline Fase 5: 509; −1 cek gabung + −3 cek `reviewerIndependent` yang dihapus). Kurung `MainActivity` delta +2 (sama dengan baseline HEAD).
- **F6b (27 Nov 2026, sapuan akhir)**: grep-zero 12 pola di `app/`+`tools/` (`SlotClient`, `DsApiClient`, `DsAgent`, `AccountPool`, `PowSolver`, `chat.deepseek.com`, `sendViaWebView`, `webAnalyzePhoto`, `buildWebSlotRow`, `DeepSeek web`, `WebView DeepSeek`, `sesi server DeepSeek`) = 0; 3 fungsi DSML dihapus dari `AgentPrompt.kt` (`extractDsmlCall`, `extractDsmlCalls`, `normalizeDsmlDelimiters` + `parseDsmlParams`) — parser dialek-teks generik (`extractToolCall`/`extractToolCalls`/`extractTagCall`/`findToolSpans`/`repairToolJson`/`stripRawToolMarkupForDisplay`) TETAP untuk fallback teks Models API; label "🌐 Web Login" → "🌐 Web"; deskripsi `analyze_photo` ditulis ulang ke vision `askWithFiles`. Diskrepansi: klaim "8 fungsi DSML/teks @Deprecated" (Fase 5) TIDAK TERBUKTI — tidak ada satu pun `@Deprecated` di `AgentPrompt.kt`. Op Rust `normalizeDsmlDelimiters` jadi yatim (Rust di luar scope Fase 6).

> For agentic workers: use executing-plans task by task; TDD (RED→GREEN) per task.

**Goal:** Replace DeepSeek WebView/DSML/PoW-based brain entirely with OpenAI-compatible API model as the primary "otak" (brain) for the harness. The agent loop, memory, tools, and UI remain; only the chat/engine pipeline switches to standard OpenAI `/chat/completions` + `tool_calls` + SSE. DeepSeek web becomes an optional fallback/disabled, not the default.

**Architecture:** `AgentEngines` now resolves to `openai` engine by default; `OpenAiCompatClient` handles `/chat/completions` POST + SSE parse; `LlmProviders` + `LlmProviderStore` persist config; `Tools.kt` dispatch `llm_provider_*` tools; DeepSeek web code (`DsApiClient`, `DsAgent`, `SlotClient`, `PowSolver`) remains on disk but **not used** by default chat loop. All streaming/THINK-parsing moved to `OpenAiCompatClient` with standard OpenAI format.

**Tech Stack:** Kotlin 2.0.21, org.json, java.net.HttpURLConnection, Android Keystore (SecureStore), existing `tools/run_tests.sh`. No new Gradle plugins. OpenAI API key stored via SecureStore, never in chat/logs.

**Spec:** `docs/superpowers/plans/2026-11-26-custom-llm-provider.md` (provider registry + tools already added); this plan extends to full engine swap.

## Global Constraints

- **No DeepSeek web in default chat loop** — `DsAgent`, `DsApiClient`, `SlotClient`, `PowSolver` deprecated but kept compiled (no delete to avoid breakage).
- **OpenAI-compatible API key** stored only in `SecureStore` (`llm_provider_key_<id>`), never in prefs/chats/logs.
- **Tool calling**: Replace DSML JSON blocks (`{"p":"...","v":"...","o":"BATCH"...}`) with standard OpenAI `tool_calls` / `function_call`. `Tools.kt` `api_read`/`api_write` remain; new `llm_provider_ask` returns raw model text + optional structured tool results.
- **Streaming**: OpenAI SSE format (`data: {...}\n\n`) parsed by `OpenAiCompatClient`; fallback non-streaming if stream fails.
- **Backward compatibility**: `AgentEngines.current(ctx)` returns `"openai"` as default; but `agentEngine` pref can still be `"deepseek"` for manual fallback.
- **Tests**: `tools/run_tests.sh` must still pass (JVM-only tests for `LlmProvidersTest` + `ToolRegistryTest`); UI tests not possible without Android emulator.

## Review Focus

- Provider config incomplete (baseUrl/model/key missing) → tool rejects with clear message; never leaves agent in broken state.
- Tool `llm_provider_ask` leaks API key in error output → redacted via `RedactSecrets`.
- DSML parser still active in some code path → must be fully disabled/guarded when `openai` engine active.
- SSE stream parse errors don't crash agent loop → graceful fallback to non-streaming.
- Agent memory (`MEMORY.md`, `recall_facts`) still works; only the brain's input/output format changes.

## Tasks (22 tasks, TDD RED→GREEN)

### Phase 1: Foundations (Tasks 1–6)

**Task 1: RED — LlmProvidersTest already written** (exists from prior plan). Verify it passes with current `LlmProviders.kt` + `LlmProviderStore.kt`.

**Task 2: GREEN — pure `OpenAiCompatClient`** (no Android dependency). Create `OpenAiCompatClient.kt` in `test` source set (pure Kotlin, uses only `org.json`, `java.net.HttpURLConnection`). Test: `ask(provider, key, prompt): Pair<String,String>` with success/failure cases. Run `bash tools/run_tests.sh` → all green.

**Task 3: Android `OpenAiCompatClient`** — move pure implementation to main source set, keep Android `Context`-free core in `OpenAiCompatClient.kt`, add Android `Context` only for `SecureStore` access via `LlmProviderStore.apiKey()`. Run tests → green.

**Task 4: `LlmProviders` integrate `activeProvider()` → `Result`** — add `fun ask(prompt: String): Pair<String,String>` to `LlmProviders` that hydrates, calls `OpenAiCompatClient.ask()`, returns content + model. If provider is DeepSeek native, fallback to existing `DsApiClient` path (keeps compile). Run tests → green.

**Task 5: Tool `llm_provider_ask` dispatch** — in `Tools.kt`, ensure `llm_provider_ask` calls `LlmProviders.ask()` with key from `LlmProviderStore.apiKey(ctx, id)` or `null` for deepseek. Return model response text. Run `bash tools/run_tests.sh` → green.

**Task 6: `AgentEngines.current()` defaults to `"openai"`** — modify `AgentEngine.kt` so default active engine is `"openai"` when `LlmProviders.all()` contains non-deepseek provider; else `"deepseek"`. Run tests → green.

### Phase 2: Chat Loop Re-route (Tasks 7–14)

**Task 7: RED — `MainActivity` chat loop `DsAgent`/`DsApiClient` → compile error** intentionally: remove `agent = DsAgent(webView)` / `apiClient = DsApiClient(webView)` lines; replace with `apiClient = OpenAiCompatClient()` (no-web-view requirement). Run `bash assembleDebug` → expect failure (missing classes/imports).

**Task 7b: GREEN — Add `OpenAiCompatClient` import + instantiation** in `MainActivity.kt` near where `DsAgent` was. Keep `DsAgent` class compiled but not instantiated in default chat loop. Run build → compiles.

**Task 8: Replace `sendAndAwait` DeepSeek JS** — in `MainActivity.kt`, the `sendAndAwait` function currently uses `DsAgentSeam.buildSendJs` + JS injection to WebView. Replace with `OpenAiCompatClient.ask()` call: POST `/chat/completions` with `messages`, parse `choices[0].message.content`. Keep `onPartial` callback pattern but now it's model-stream not DOM polling. Run tests → green where possible (pure parts).

**Task 9: Replace DSML tool parsing** — `AgentPrompt.kt` `stripCtxLabels`, `workNotesBlockFromRawVc302` etc. remain (they're prompt-text parsers, not engine-specific). However, the `DsApiClient`/`SlotClient` text-block-to-tool-call conversion (DSML `{"p":"...","v":"..."}`) must be disabled/guarded when `openai` engine active. Add `@Deprecated` or `if (engine != "deepseek")` guards in `Tools.kt` `execute` where DSML tool results were parsed. Run tests → green.

**Task 10: `AgentPrompt` system prompt adjustments** — the system prompt currently includes DSML-aware instructions (vc248, vc302, etc.). These are general agent rules; keep them but add note: "When using OpenAI-compatible API, tool calls use standard `function_call`/`tool_calls` format, not DSML blocks." Update `AgentPrompt.stripCtxLabels` if needed for OpenAI format. Run tests → green.

**Task 11: `BgBrain` loop migration** — `BgBrain.loop` / `loopInnerVc263` currently uses `DsApiClient.chat()` / `ensureClient` which reads token from WebView. Replace with `LlmProviders.ask()` → `OpenAiCompatClient.ask()`. The `ensureClient` function becomes `ensureProvider(ctx)` returning `LlmProvider`. Run `bash tools/run_tests.sh` for pure parts → green.

**Task 12: `AccountPool` / `Token` removal** — `AccountPool.kt` reads tokens from WebView session. Since `openai` engine uses SecureStore API key, `AccountPool` becomes unused for primary engine. Add `@Deprecated` and guard: only used when `agentEngine == "deepseek"`. Run tests → green.

**Task 13: `PowSolver` deprecated** — `PowSolver.kt` used for DeepSeek PoW. Mark `@Deprecated`; no longer called when `openai` engine active. Run tests → green.

**Task 14: `SlotClient`/`DsAgent` UI removal from `MainActivity`** — the WebView-related chat setup (`agent = DsAgent(webView)`, `probe`, `pageText`, etc.) can be kept compiled but never executed in default flow. Add `@Suppress("Unused")` or feature flag. Not critical for test pass; just compilation.

### Phase 3: Polish & CI (Tasks 15–22)

**Task 15: Full `bash tools/run_tests.sh` green** — all JVM tests pass including new `OpenAiCompatClient` test + `LlmProvidersTest` + `ToolRegistryTest` + `test_flavor.py`.

**Task 16: Git commit + push** — `git add` all modified files, commit `feat: full DeepSeek web removal + OpenAI-compatible engine migration`, push `origin main`. CI auto-run.

**Task 17: CI build APK** — `gh run watch` new build; verify APK builds successfully (senz + pure flavors). APK may have `DsApiClient`/DsAgent compiled but `#ifdef` guarded; verify no runtime crash.

**Task 18: APK artifact to `/sdcard/Download/`** — `cp` new CI-built APKs to download dir, same as prior build cycle.

**Task 19: Verify `llm_provider_list` / `llm_provider_set` / `llm_provider_ask` functional** — manual-ish: run `bash tools/run_tests.sh` then use tool commands in-session to register an OpenAI-compatible provider (e.g., OpenRouter) and ask a prompt. Confirm response returns.

**Task 20: Review Focus verification** — check each review focus item: key not leaked, DSML disabled when openai active, key handling, streaming fallback, memory still works.

**Task 21: Self-review of plan** — re-read plan against spec; fix any step that decides nothing ("TBD", "handle edge cases"); ensure each step unambiguous.

**Task 22: Final message** — summarize what was built, rulings made, APK location, how to use new provider tools, and any remaining DeepSeek web code status (deprecated but compiled).

## Rulings I Made (during execution)

> **DISALIP Fase 6 K1 (27 Nov 2026)** — tiga ruling di bawah ini ("simpan terkompilasi", "AccountPool dipertahankan", "DSML guard per-engine") tidak berlaku lagi: file-file itu DIHAPUS, bukan disimpan. Disimpan di sini sebagai catatan histori saja.

- **Ruling: DeepKeep vs FullRemove** — Chose "full remove from default loop" not "delete entirely": kept DeepSeek web classes compiled but `#ifdef`-guarded + not instantiated, to avoid breaking existing APKs and user data. Cost: larger APK, some dead code.
- **Ruling: DSML → OpenAI tool format** — Decided DSML blocks cannot be simultaneously active with OpenAI `tool_calls`. Added guard `if (engine != "deepseek")` in tool execution path. Cost: if user switches back to deepseek, must re-enable DSML path.
- **Ruling: `AccountPool` retention** — Kept `AccountPool` compiled with `@Deprecated` + guard, not deleted. Cost: minor memory footprint when deepseek engine used.
- **Ruling: `AgentEngines.default` → `"openai"`** — Changed default from `"deepseek"` to `"openai"` to reflect migration. Cost: existing users who haven't run `llm_provider_set` will see `"openai"` engine and may need to `llm_provider_set` or reset pref.

## Deferred Minors (not entered fix pass, listed for human partner)

- Minor: `DsAgent`/`DsApiClient` still referenced in `Build.py` / `Build.md` build steps — not removed, just not used. Minor visual cleanup.
- Minor: `PowSolver.kt` still imported in `SlotClient.kt` — guarded but not deleted.
- Minor: `AgentPrompt` system prompt still references vc248/vc302 DSML-aware rules; could add "OpenAI mode" variant but not critical.

## What This Builds

A harness where the agent's primary brain is an OpenAI-compatible API model (OpenAI, OpenRouter, Groq, Ollama, custom base URL). The agent loop, memory, tools, UI remain identical. DeepSeek web is deprecated but compiled; to fully remove, delete the `DsApiClient`, `DsAgent`, `SlotClient`, `PowSolver` classes and strip imports — a follow-up task.

> **Fase 6 K1 (27 Nov 2026): follow-up itu SUDAH DIKERJAKAN** — file-file di atas dihapus + semua call-site dibersihkan (lihat Status di atas). Paragraf ini dipertahankan sebagai histori.