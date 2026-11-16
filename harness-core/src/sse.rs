//! Mesin parse SSE JSON-Patch `SlotClient` (slice 8, vc324) —
//! port 1:1 `applyChunk` / `applyValueObject` / `routeIsThinking` /
//! `declareFragment` dari `SlotClient.kt`.
//!
//! State dijaga sebagai JSON (bukan objek Rust jangka panjang —
//! peta §3.3: tanpa pointer mentah):
//! `{"last_path":"","saw_finish":false,"fragment_thinking":{},
//! "fragment_count":0,"current_thinking":false}`.
//! Op `sse_apply`: `{"op":"sse_apply","state":{...},"payload":"..."}`
//! -> `{"ok":true,"skipped":bool,"state":{...},"content_delta":"",
//! "thinking_delta":"","saw_finish":bool}`.
//! `skipped=true` = payload bukan JSON (cermin `catch { continue }`
//! di loop baca Kotlin — baris dilewati, state tidak berubah).
//!
//! Quirk Kotlin yang direplikasi (dikunci tes):
//! - `optString` org.json melakukan koersi (`toString()` untuk
//!   non-string) — lihat `opt_string_coerce` di `http.rs`.
//! - `p.contains("thinking", ignoreCase=true)` (ASCII) mengeset
//!   `currentThinking=true`; tapi `contentish` memakai `contains`
//!   CASE-SENSITIVE (`"thinking"`/`"reasoning"`).
//! - `fragments/(-?\d+)`: `\d` Java = ASCII — dipakai `[0-9]`;
//!   indeks -1 = current; indeks tak-dikenal == fragmentCount
//!   (dan count > 0) -> declare false; `toIntOrNull` gagal (overflow)
//!   -> current.
//! - `BATCH`: rekursif per sub-objek (non-objek dilewati).
//! - String `FINISHED`/`WIP` tanpa akhiran `content` di `p` diabaikan;
//!   di jalur `/status`, `FINISHED` mematikan `currentThinking`
//!   kecuali indeks fragmennya masih thinking.
//! - `applyValueObject`: `type` non-kosong -> declare fragmen
//!   (indeks dari `effPath`, atau `fragmentCount` bila tak ada);
//!   `content` langsung -> rute via `routeIsThinking`; bila tidak
//!   ada keduanya -> `response.fragments` (milik `v`, lalu milik
//!   `obj`, lalu `v` sendiri); `firstDecl` (count == 0 sebelum
//!   loop) mengeset `currentThinking` dari fragmen pertama.
//! - Elemen JSONArray non-objek: `optString` koersi (angka -> "42",
//!   null -> "") lalu dirute seperti string.

#![forbid(unsafe_code)]

use crate::http::{contains_ignore_ascii_case, opt_string_coerce};
use regex::Regex;
use serde_json::{json, Map, Value as Json};
use std::collections::HashMap;
use std::sync::OnceLock;

#[derive(Debug, Clone)]
struct SseState {
    last_path: String,
    saw_finish: bool,
    fragment_thinking: HashMap<i64, bool>,
    fragment_count: i64,
    current_thinking: bool,
}

impl Default for SseState {
    fn default() -> Self {
        SseState {
            last_path: String::new(),
            saw_finish: false,
            fragment_thinking: HashMap::new(),
            fragment_count: 0,
            current_thinking: false,
        }
    }
}

fn frag_idx_re() -> &'static Option<Regex> {
    static R: OnceLock<Option<Regex>> = OnceLock::new();
    R.get_or_init(|| Regex::new(r"fragments/(-?[0-9]+)").ok())
}

/// `fragIdxRe.find(path)?.groupValues[1].toIntOrNull()` — None bila
/// tak ada match atau parse Int gagal (overflow).
fn frag_idx(path: &str) -> Option<i32> {
    let re = frag_idx_re().as_ref()?;
    let caps = re.captures(path)?;
    caps.get(1)?.as_str().parse::<i32>().ok()
}

fn state_from_json(v: Option<&Json>) -> SseState {
    let mut st = SseState::default();
    let Some(Json::Object(o)) = v else {
        return st;
    };
    if let Some(Json::String(s)) = o.get("last_path") {
        st.last_path = s.clone();
    }
    if let Some(Json::Bool(b)) = o.get("saw_finish") {
        st.saw_finish = *b;
    }
    if let Some(Json::Number(n)) = o.get("fragment_count") {
        if let Some(i) = n.as_i64() {
            st.fragment_count = i;
        }
    }
    if let Some(Json::Bool(b)) = o.get("current_thinking") {
        st.current_thinking = *b;
    }
    if let Some(Json::Object(f)) = o.get("fragment_thinking") {
        for (k, v) in f {
            if let (Ok(idx), Json::Bool(b)) = (k.parse::<i64>(), v) {
                st.fragment_thinking.insert(idx, *b);
            }
        }
    }
    st
}

fn state_to_json(st: &SseState) -> Json {
    let mut frag = Map::new();
    let mut keys: Vec<i64> = st.fragment_thinking.keys().copied().collect();
    keys.sort();
    for k in keys {
        if let Some(v) = st.fragment_thinking.get(&k) {
            frag.insert(k.to_string(), Json::Bool(*v));
        }
    }
    json!({
        "last_path": st.last_path,
        "saw_finish": st.saw_finish,
        "fragment_thinking": frag,
        "fragment_count": st.fragment_count,
        "current_thinking": st.current_thinking,
    })
}

fn declare_fragment(st: &mut SseState, idx: i64, is_think: bool, make_current: bool) {
    st.fragment_thinking.insert(idx, is_think);
    if idx >= st.fragment_count {
        st.fragment_count = idx + 1;
    }
    if make_current {
        st.current_thinking = is_think;
    }
}

fn route_is_thinking(st: &mut SseState, path: &str) -> bool {
    if contains_ignore_ascii_case(path, "thinking")
        || contains_ignore_ascii_case(path, "reasoning")
    {
        return true;
    }
    let Some(raw) = frag_idx(path) else {
        return st.current_thinking;
    };
    if raw == -1 {
        return st.current_thinking;
    }
    if let Some(&known) = st.fragment_thinking.get(&(raw as i64)) {
        return known;
    }
    if raw as i64 == st.fragment_count && st.fragment_count > 0 {
        declare_fragment(st, raw as i64, false, true);
        return false;
    }
    st.current_thinking
}

fn apply_chunk(
    obj: &Json,
    st: &mut SseState,
    content: &mut String,
    thinking: &mut String,
) {
    let p = opt_string_coerce(obj.get("p"));
    if p == "response/status" && opt_string_coerce(obj.get("v")) == "FINISHED" {
        st.saw_finish = true;
    }
    if !p.is_empty() && !p.contains("/status") {
        st.last_path = p.clone();
    }
    if contains_ignore_ascii_case(&p, "thinking") {
        st.current_thinking = true;
    }
    let eff_path = st.last_path.clone();
    if opt_string_coerce(obj.get("o")) == "BATCH" {
        if let Some(Json::Array(arr)) = obj.get("v") {
            for sub in arr {
                if sub.is_object() {
                    apply_chunk(sub, st, content, thinking);
                }
            }
        }
        return;
    }
    let Some(v) = obj.get("v") else { return };
    if v.is_null() {
        return;
    }
    match v {
        Json::String(s) => {
            if s.is_empty() {
                return;
            }
            if p.contains("/status") || eff_path.contains("/status") {
                if s == "FINISHED" {
                    let s_idx = frag_idx(&eff_path);
                    let kill = match s_idx {
                        None => true,
                        Some(i) if i < 0 => true,
                        Some(i) => {
                            st.fragment_thinking.get(&(i as i64)) == Some(&true)
                        }
                    };
                    if kill {
                        st.current_thinking = false;
                    }
                }
                return;
            }
            if (s == "FINISHED" || s == "WIP") && !p.ends_with("content") {
                return;
            }
            let contentish = eff_path.is_empty()
                || eff_path.ends_with("content")
                || eff_path.ends_with("fragments")
                || eff_path.contains("thinking")
                || eff_path.contains("reasoning");
            if !contentish {
                return;
            }
            if route_is_thinking(st, &eff_path) {
                thinking.push_str(s);
            } else {
                content.push_str(s);
            }
        }
        Json::Array(arr) => {
            for el in arr {
                if el.is_object() {
                    apply_value_object(obj, el, &eff_path, st, content, thinking);
                } else {
                    let s = opt_string_coerce(Some(el));
                    if !s.is_empty() {
                        if route_is_thinking(st, &eff_path) {
                            thinking.push_str(&s);
                        } else {
                            content.push_str(&s);
                        }
                    }
                }
            }
        }
        Json::Object(_) => {
            apply_value_object(obj, v, &eff_path, st, content, thinking);
        }
        _ => {}
    }
}

fn apply_value_object(
    obj: &Json,
    v: &Json,
    eff_path: &str,
    st: &mut SseState,
    content: &mut String,
    thinking: &mut String,
) {
    let type_str = opt_string_coerce(v.get("type"));
    if !type_str.is_empty() {
        let is_think = contains_ignore_ascii_case(&type_str, "THINK");
        let raw = frag_idx(eff_path);
        let idx = match raw {
            Some(r) if r >= 0 => r as i64,
            _ => st.fragment_count,
        };
        declare_fragment(st, idx, is_think, true);
        let c = opt_string_coerce(v.get("content"));
        if !c.is_empty() {
            if is_think {
                thinking.push_str(&c);
            } else {
                content.push_str(&c);
            }
        }
        return;
    }
    let direct_content = opt_string_coerce(v.get("content"));
    if !direct_content.is_empty() {
        if route_is_thinking(st, eff_path) {
            thinking.push_str(&direct_content);
        } else {
            content.push_str(&direct_content);
        }
        return;
    }
    let resp: &Json = match v.get("response").filter(|x| x.is_object()) {
        Some(r) => r,
        None => match obj.get("response").filter(|x| x.is_object()) {
            Some(r) => r,
            None => v,
        },
    };
    let Some(Json::Array(frags)) = resp.get("fragments") else {
        return;
    };
    let first_decl = st.fragment_count == 0;
    for (i, f) in frags.iter().enumerate() {
        let Some(fobj) = f.as_object() else { continue };
        let is_think = contains_ignore_ascii_case(
            &opt_string_coerce(fobj.get("type")),
            "THINK",
        );
        declare_fragment(st, i as i64, is_think, false);
        let c = opt_string_coerce(fobj.get("content"));
        if !c.is_empty() {
            if is_think {
                thinking.push_str(&c);
            } else {
                content.push_str(&c);
            }
        }
    }
    if first_decl && !frags.is_empty() {
        if let Some(f0) = frags[0].as_object() {
            st.current_thinking = contains_ignore_ascii_case(
                &opt_string_coerce(f0.get("type")),
                "THINK",
            );
        }
    }
}

/// Op `sse_apply` (dispatch dari `http_json`).
pub fn sse_apply(req: &Json) -> Result<String, String> {
    let mut st = state_from_json(req.get("state"));
    let payload = req
        .get("payload")
        .and_then(|v| v.as_str())
        .ok_or_else(|| "missing payload".to_string())?;
    let parsed: Json = match serde_json::from_str(payload) {
        Ok(v) => v,
        Err(_) => {
            return Ok(json!({
                "ok": true, "skipped": true, "state": state_to_json(&st),
                "content_delta": "", "thinking_delta": "",
                "saw_finish": st.saw_finish,
            })
            .to_string());
        }
    };
    let mut content = String::new();
    let mut thinking = String::new();
    apply_chunk(&parsed, &mut st, &mut content, &mut thinking);
    Ok(json!({
        "ok": true, "skipped": false, "state": state_to_json(&st),
        "content_delta": content, "thinking_delta": thinking,
        "saw_finish": st.saw_finish,
    })
    .to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::http::http_json;

    fn apply(state: Json, payload: &str) -> Json {
        let req = json!({"op": "sse_apply", "state": state, "payload": payload});
        let out = http_json(&req.to_string()).expect("sse_apply ok");
        serde_json::from_str(&out).expect("resp json")
    }

    fn init_state() -> Json {
        json!({"last_path": "", "saw_finish": false, "fragment_thinking": {}, "fragment_count": 0, "current_thinking": false})
    }

    #[test]
    fn simple_content_via_path() {
        // p menunjuk content -> delta content.
        let r = apply(
            init_state(),
            r#"{"p":"response/fragments/0/content","v":"Halo"}"#,
        );
        assert_eq!(r["content_delta"], "Halo");
        assert_eq!(r["thinking_delta"], "");
        assert_eq!(r["state"]["last_path"], "response/fragments/0/content");
        // Baris tanpa p memakai last_path (effPath).
        let r2 = apply(r["state"].clone(), r#"{"v":" dunia"}"#);
        assert_eq!(r2["content_delta"], " dunia");
    }

    #[test]
    fn thinking_path_routes_to_thinking() {
        let r = apply(
            init_state(),
            r#"{"p":"response/thinking/content","v":"mikir"}"#,
        );
        assert_eq!(r["thinking_delta"], "mikir");
        assert_eq!(r["content_delta"], "");
        assert_eq!(r["state"]["current_thinking"], true);
    }

    #[test]
    fn finish_status_and_saw_finish() {
        let r = apply(
            init_state(),
            r#"{"p":"response/status","v":"FINISHED"}"#,
        );
        assert_eq!(r["saw_finish"], true);
        assert_eq!(r["state"]["saw_finish"], true);
        // FINISHED/WIP tanpa akhiran content diabaikan (bukan konten).
        let r2 = apply(init_state(), r#"{"p":"response/x","v":"FINISHED"}"#);
        assert_eq!(r2["content_delta"], "");
        let r3 = apply(init_state(), r#"{"p":"response/x","v":"WIP"}"#);
        assert_eq!(r3["content_delta"], "");
    }

    #[test]
    fn batch_recurses() {
        let r = apply(
            init_state(),
            r#"{"o":"BATCH","v":[{"p":"response/fragments/0/content","v":"a"},{"p":"response/fragments/0/content","v":"b"}]}"#,
        );
        assert_eq!(r["content_delta"], "ab");
    }

    #[test]
    fn fragments_declare_and_route() {
        // fragments: fragmen 0 THINK, fragmen 1 biasa.
        let r = apply(
            init_state(),
            r#"{"p":"response/fragments","v":{"response":{"fragments":[{"type":"THINK","content":"t0"},{"type":"TEXT","content":"c1"}]}}}"#,
        );
        assert_eq!(r["thinking_delta"], "t0");
        assert_eq!(r["content_delta"], "c1");
        assert_eq!(r["state"]["fragment_count"], 2);
        assert_eq!(r["state"]["fragment_thinking"]["0"], true);
        assert_eq!(r["state"]["fragment_thinking"]["1"], false);
        // currentThinking dari fragmen pertama (firstDecl) = THINK.
        assert_eq!(r["state"]["current_thinking"], true);
        // Chunk berikut ke fragments/0 -> thinking (dikenal).
        let r2 = apply(
            r["state"].clone(),
            r#"{"p":"response/fragments/0/content","v":"t0b"}"#,
        );
        assert_eq!(r2["thinking_delta"], "t0b");
        // Chunk ke fragments/1 -> content.
        let r3 = apply(
            r2["state"].clone(),
            r#"{"p":"response/fragments/1/content","v":"c1b"}"#,
        );
        assert_eq!(r3["content_delta"], "c1b");
    }

    #[test]
    fn unknown_fragment_index_equals_count_declares_false() {
        // count=1, chunk ke fragments/1 (== count) -> declare false.
        let mut st = init_state();
        st["fragment_count"] = json!(1);
        st["fragment_thinking"] = json!({"0": false});
        let r = apply(
            st,
            r#"{"p":"response/fragments/1/content","v":"baru"}"#,
        );
        assert_eq!(r["content_delta"], "baru");
        assert_eq!(r["state"]["fragment_thinking"]["1"], false);
        assert_eq!(r["state"]["fragment_count"], 2);
    }

    #[test]
    fn minus_one_index_uses_current() {
        // fragments/-1 -> current_thinking (false awal -> content).
        let r = apply(
            init_state(),
            r#"{"p":"response/fragments/-1/content","v":"x"}"#,
        );
        assert_eq!(r["content_delta"], "x");
    }

    #[test]
    fn typed_value_object_declares_fragment() {
        let r = apply(
            init_state(),
            r#"{"p":"response/fragments/3/content","v":{"type":"THINK","content":"deep"}}"#,
        );
        assert_eq!(r["thinking_delta"], "deep");
        assert_eq!(r["state"]["fragment_thinking"]["3"], true);
        assert_eq!(r["state"]["fragment_count"], 4);
    }

    #[test]
    fn non_json_payload_is_skipped() {
        let r = apply(init_state(), "bukan json");
        assert_eq!(r["skipped"], true);
        assert_eq!(r["content_delta"], "");
        assert_eq!(r["state"], init_state());
    }

    #[test]
    fn status_finished_kills_current_only_when_thinking() {
        // current=true via thinking path; status FINISHED di path tanpa
        // fragmen -> sIdx null -> current=false.
        let r = apply(
            init_state(),
            r#"{"p":"response/thinking/content","v":"m"}"#,
        );
        assert_eq!(r["state"]["current_thinking"], true);
        let r2 = apply(
            r["state"].clone(),
            r#"{"p":"response/status","v":"FINISHED"}"#,
        );
        // p mengandung "/status": status-branch; sIdx dari effPath
        // (last_path masih thinking/content — tanpa fragments/) -> null.
        assert_eq!(r2["state"]["current_thinking"], false);
        assert_eq!(r2["saw_finish"], true);
    }

    #[test]
    fn array_value_coerces_primitives() {
        // Elemen array non-objek: angka -> "42" (optString koersi).
        let r = apply(
            init_state(),
            r#"{"p":"response/fragments/0/content","v":[42,"s"]}"#,
        );
        assert_eq!(r["content_delta"], "42s");
    }
}
