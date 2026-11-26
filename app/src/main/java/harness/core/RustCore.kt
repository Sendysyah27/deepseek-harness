package harness.core

/**
 * Seam JNI ke core Rust (`libharness_core.so`, crate `harness-core`).
 *
 * Package ini SENGAJA `harness.core` — BUKAN package aplikasi — dan file
 * ini TIDAK BOLEH mengandung string package aplikasi di mana pun
 * (komentar sekalipun): build flavor Pure me-rewrite string itu dan
 * akan merusak linkage JNI. Simbol JNI statis:
 * `Java_harness_core_RustCore_*`.
 *
 * `available` false bila `.so` absen/rusak atau versi seam tidak cocok
 * -> pemanggil WAJIB fallback ke implementasi Kotlin lama.
 * Jangan panggil fungsi Rust di main thread.
 */
object RustCore {
    /** Versi seam JNI — naikkan tiap kali signature berubah. */
    const val VERSION = 10

    val available: Boolean

    init {
        var ok = false
        try {
            System.loadLibrary("harness_core")
            ok = rustCoreVersion() == VERSION
        } catch (t: Throwable) {
            ok = false
        }
        available = ok
    }

    external fun rustCoreVersion(): Int
    external fun rustCoreCapabilities(): String
    external fun echo(input: String): String
    external fun classifyFailureJson(code: Int, body: String, exceptionMessage: String): String

    // ---- Slice 3: RedactSecrets (redaksi kredensial) ----
    /**
     * Redaksi kredensial (String polos in/out, bukan JSON).
     * Sukses -> teks ter-redaksi; `null` -> Rust gagal (panic/error di
     * boundary) -> pemanggil WAJIB fallback ke implementasi Kotlin lama.
     */
    external fun redactSecrets(input: String): String?

    // ---- Slice 4: modul kecil (bulk JSON) ----
    external fun notifIdsJson(origin: String, key: String, workerId: String): String
    external fun systemBarSpecJson(isDark: Boolean): String
    external fun dsSafetyStripJson(text: String): String
    external fun mcpHelperJson(): String
    external fun sessionBackgroundJson(turnSessionId: Long, currentSessionId: Long, turnActive: Boolean): String
    external fun behaviorsJson(id: String): String
    external fun chatThemeJson(fg: Int, bg: Int): String

    // ---- Slice 5: ConcatApiClient (klien JSON-RPC server Concat) ----
    /**
     * Satu panggilan stateless ke server Concat (TCP 127.0.0.1:7420).
     * Request JSON: `{"op","token","args","previewDir"}`; respons:
     * `{"ok":true,"value":...}` (String untuk 20 op, array
     * `{name,path}` untuk listProjects) atau
     * `{"ok":false,"kind":"auth"|"down"|"api","message":...}`.
     * `null` = Rust gagal (panic/error di boundary) -> pemanggil WAJIB
     * fallback ke implementasi Kotlin lama.
     */
    external fun concatApiJson(request: String): String?

    // ---- Slice 6: ToolRegistry + AgentPrompt + SkillTrigger (bulk JSON) ----
    /**
     * ToolRegistry (data + scoring katalog): request
     * `{"op":"catalogSection"|"browserSkillDetail"|"names"|"namesByKind"|
     * "sectionCounts"|"nearestNames", ...}`; respons
     * `{"ok":true,"value":...}`. `null`/JSON-error = Rust gagal ->
     * pemanggil WAJIB fallback ke implementasi Kotlin lama.
     */
    external fun toolRegistryJson(request: String): String

    /**
     * AgentPrompt (fungsi murni): request
     * `{"op":"selectCatalog"|"continuationAnchor"|"stripRawToolMarkup"|
     * "normalizeDsmlDelimiters"|"applyDoublePass", ...}`; respons
     * `{"ok":true,"value":"..."}`.
     */
    external fun agentPromptJson(request: String): String

    /**
     * SkillJudge trigger scoring vc312: request
     * `{"op":"splitDescrSections"|"negWordsOf"|"triggerScore", ...}`;
     * respons `{"ok":true,"value":...}`.
     */
    external fun skillTriggerJson(request: String): String

    // ---- Slice 2: PoW (DeepSeekHashV1) ----
    /** SHA3-256 sebagai 64 digit hex lowercase; "" / bukan-hex = gagal. */
    external fun powSha3Hex(input: String): String
    /** DeepSeekHashV1 (Keccak skip round 0) sebagai 64 digit hex. */
    external fun powDsHashV1Hex(input: String): String
    /** SHA3-256 mentah 32 byte; null = gagal (panic/JNI error). */
    external fun powSha3Bytes(input: ByteArray): ByteArray?
    /** DeepSeekHashV1 mentah 32 byte; null = gagal. */
    external fun powDsHashV1Bytes(input: ByteArray): ByteArray?
    /**
     * Solve PoW -> JSON: `{"ok":true,"found":true,"nonce":...,
     * "hash_hex":"...","tries":...,"elapsed_ms":...}` atau
     * `{"ok":true,"found":false}` bila nonce tak ketemu (sama seperti
     * `null` Kotlin); `{"ok":false,...}` = error -> fallback Kotlin.
     */
    external fun powSolveDeepSeekJson(challengeHex: String, salt: String, expireAt: Long, difficulty: Long): String
    external fun powSolveFastJson(challenge: String, salt: String, difficulty: Long, maxTries: Long): String
    external fun powSolveFmtJson(challenge: String, salt: String, difficulty: Long, format: String, expireAt: Long, maxTries: Long): String
    external fun powSolveJson(challenge: String, salt: String, difficulty: Long, maxTries: Long): String

    // ---- Slice 7: SQLite via rusqlite (seam generik, satu fungsi) ----
    /**
     * Operasi SQLite generik. Request JSON:
     * `{"op":"ensure"|"query"|"exec"|"tx","db":<path absolut .db>,
     * "key":"events"|"artifacts"|"parked"|"graph"|"memory",...}`.
     * Respons `{"ok":true,...}` atau `{"ok":false,"error":...}`.
     * `null` = Rust gagal (panic/error di boundary) -> pemanggil WAJIB
     * fallback ke implementasi Kotlin lama.
     */
    external fun sqliteJson(request: String): String?

    // ---- Slice 8: HTTP clients (ureq + logika murni HTTP) ----
    /**
     * HTTP generik + logika murni HTTP. Request JSON `{"op":...}`:
     * `request` (method/url/headers/body/timeout/cap/want_headers),
     * `mcp_extract`, `shape_fingerprint`, `decode_word`,
     * `gmail_headers`, `pkce_challenge`, `form_encode`, `form_body`,
     * `oauth_auth_url`, `oauth_parse_callback`, `sse_apply`.
     * Respons `{"ok":true,...}` atau `{"ok":false,"error":...}`.
     * `null` = Rust gagal (panic/error di boundary) -> pemanggil WAJIB
     * fallback ke implementasi Kotlin lama.
     */
    external fun httpJson(request: String): String?

    // ---- Slice 9: Server :4200 (WorkerEndpoint + WorkerEndpointLogic) ----
    /**
     * Logika murni endpoint worker :4200. Request JSON `{"op":...}`:
     * `parse_route`, `path_matches_other_method`, `parse_query`,
     * `bearer_token`, `is_authorized`, `is_safe_name`, `slice_preview`,
     * `preview_chars`, `hmac_sha256_hex`, `sign_queue_text`,
     * `has_queue_signature`, `verify_queue_text`, `worker_scoped`,
     * `parse_headers`, `build_response`, `idempotency_key`.
     * Respons `{"ok":true,...}` atau `{"ok":false,"error":...}`.
     * `null` = Rust gagal (panic/error di boundary) -> pemanggil WAJIB
     * fallback ke implementasi Kotlin lama.
     */
    external fun workerEndpointJson(request: String): String?

    // ---- Slice 10: Daun murni Tools.kt (parse DDG + YouTube) ----
    /**
     * Daun murni Tools.kt. Request JSON `{"op":...}`:
     * `strip_html`, `parse_ddg`, `truncate_marked`, `yt_runs_text`,
     * `yt_parse_video_renderer`, `extract_balanced_json`,
     * `yt_walk_results`, `yt_scan_renderers`, `parse_yt_search`,
     * `format_yt_results`.
     * Respons `{"ok":true,...}` atau `{"ok":false,"error":...}`.
     * `null` = Rust gagal (panic/error di boundary) -> pemanggil WAJIB
     * fallback ke implementasi Kotlin lama.
     */
    external fun toolsLeavesJson(request: String): String?

    // ---- Slice 11: split klien API lama (find + estTokens + upload) ----
    /**
     * Split klien API lama. Request JSON `{"op":...}`:
     * `find_session_id`, `find_challenge`, `find_file_id`,
     * `est_tokens`, `safe_file_name`, `upload_file`.
     * Respons `{"ok":true,...}` atau `{"ok":false,"error":...}`.
     * `null` = Rust gagal (panic/error di boundary) -> pemanggil WAJIB
     * fallback ke implementasi Kotlin lama. Transport JSON non-streaming
     * (postJson/getJson) + parse SSE tetap memakai seam `httpJson`
     * slice 8; PoW memakai slice 2; klasifikasi memakai slice 1.
     */
    external fun dsApiJson(request: String): String?

    // ---- Slice 12: otak latar/room ----
    /**
     * Logika murni BgBrain.kt. Request JSON `{"op":...}`:
     * `cut_boundary`, `trim_transcript`, `bg_quiet_hour`,
     * `battery_formula`, `submit_decision`, `continuation_prompt_bg`,
     * `honest_cap_stop_bg`, `bg_tool_route`, `watchdog_decision`,
     * `queue_fact_proposal`, `deliver_title`.
     * Respons `{"ok":true,...}` atau `{"ok":false,"error":...}`.
     * `null` = Rust gagal (panic/error di boundary) -> pemanggil WAJIB
     * fallback ke implementasi Kotlin lama. WebView latar, notifikasi,
     * thread/loop orkestrasi dan parkir/approval tetap Kotlin.
     */
    external fun bgBrainJson(request: String): String?

    /**
     * Logika murni GroupChat.kt. Request JSON `{"op":...}`:
     * `parse_mentions`, `member_info`, `select_queue`, `chair_hop`,
     * `slot_resolve`, `build_prompt`, `continuation_prompt_room`,
     * `honest_cap_stop_group`, `retrieve_memories_filter`,
     * `quiet_hour_group`, `bg_round_gate`, `group_tool_route`,
     * `save_last_round_plan`, `parse_plans`, `render_notif_3lines`.
     * Respons `{"ok":true,...}` atau `{"ok":false,"error":...}`.
     * `null` = Rust gagal -> fallback Kotlin. `build_prompt` menerima
     * katalog yang SUDAH dipotong (`catalogForTask` + backstop 30k
     * vc321 di Kotlin) — JANGAN kirim katalog penuh dari Rust.
     */
    external fun groupChatJson(request: String): String?

    /**
     * Logika murni pengirim WebView lama. Request JSON `{"op":...}`:
     * `poll_step`, `parse_eval_result`, `json_quote`,
     * `build_attach_js`, `build_send_js`, `ds_js`.
     * Respons `{"ok":true,...}` atau `{"ok":false,"error":...}`.
     * `null` = Rust gagal -> fallback Kotlin. Evaluasi WebView
     * (`evaluateJavascript`) + polling thread tetap Kotlin.
     */
    external fun dsAgentJson(request: String): String?

    /**
     * Logika murni OcCoord.kt. Request JSON `{"op":...}`:
     * `result_notif_id`, `rule_defaults`, `sanitize_rule`, `rule_int`,
     * `spinning_pattern`, `spin_observe`, `spin_flush`, `budget_over`,
     * `looks_failed`, `quiet_now`, `incremental_text`,
     * `collect_result`, `scan_messages`, `await_body`, `honest_status`,
     * `runloop_gate`, `session_rotate`, `serve_select`,
     * `probe_cache_read`, `probe_cache_write`, `status_text`,
     * `task_result_text`, `peek_text`, `parse_perm_event`,
     * `perm_stale`, `perm_decision`, `battery_pct`.
     * Respons `{"ok":true,...}` atau `{"ok":false,"error":...}`.
     * `null` = Rust gagal -> fallback Kotlin. HTTP ke serve,
     * Termux, DB dan notifikasi tetap Kotlin.
     */
    external fun ocCoordJson(request: String): String?
}
