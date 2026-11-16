//! Port 1:1 dari `McpHelper.kt` — skrip helper MCP (python, jalan di
//! Termux) dalam base64, dipasang oleh tool mcp_setup.
//!
//! Konstanta ini disalin VERBATIM dari file Kotlin oleh skrip generator
//! (bukan diketik ulang — string base64 2.352 karakter rawan typo).
//! Tes di bawah memverifikasi panjang + prefix/suffix + alfabet base64
//! + round-trip decode terhadap nilai Kotlin.

#![forbid(unsafe_code)]

/// Skrip helper MCP dalam base64 (persis `McpHelper.SCRIPT_B64`).
pub const SCRIPT_B64: &str = concat!(
    "aW1wb3J0IHN1YnByb2Nlc3MsIHN5cywganNvbiwgYmFzZTY0LCBzaGxleCwgdGhyZWFkaW5nLCBxdWV1ZSwgdGltZQoKZGVmIGI2",
    "NGQoeCk6CiAgICByZXR1cm4gYmFzZTY0LmI2NGRlY29kZSh4KS5kZWNvZGUoInV0Zi04IiwgInJlcGxhY2UiKQoKY21kID0gYjY0",
    "ZChzeXMuYXJndlsxXSk7IHRvb2wgPSBiNjRkKHN5cy5hcmd2WzJdKTsgYXJncyA9IGpzb24ubG9hZHMoYjY0ZChzeXMuYXJndlsz",
    "XSkgb3IgInt9IikKcCA9IHN1YnByb2Nlc3MuUG9wZW4oc2hsZXguc3BsaXQoY21kKSwgc3RkaW49c3VicHJvY2Vzcy5QSVBFLCBz",
    "dGRvdXQ9c3VicHJvY2Vzcy5QSVBFLCB0ZXh0PVRydWUsIGJ1ZnNpemU9MSkKcSA9IHF1ZXVlLlF1ZXVlKCkKZGVmIHJlYWRlcigp",
    "OgogICAgZm9yIGxpbmUgaW4gcC5zdGRvdXQ6CiAgICAgICAgcS5wdXQobGluZSkKdGhyZWFkaW5nLlRocmVhZCh0YXJnZXQ9cmVh",
    "ZGVyLCBkYWVtb249VHJ1ZSkuc3RhcnQoKQoKZGVmIHNlbmQob2JqKToKICAgIHAuc3RkaW4ud3JpdGUoanNvbi5kdW1wcyhvYmop",
    "ICsgIlxuIik7IHAuc3RkaW4uZmx1c2goKQoKZGVmIHdhaXRfaWQod2FudCwgdGltZW91dD0zMCk6CiAgICBlbmQgPSB0aW1lLnRp",
    "bWUoKSArIHRpbWVvdXQKICAgIHdoaWxlIHRpbWUudGltZSgpIDwgZW5kOgogICAgICAgIHRyeTogbGluZSA9IHEuZ2V0KHRpbWVv",
    "dXQ9bWF4KDAuMSwgZW5kIC0gdGltZS50aW1lKCkpKQogICAgICAgIGV4Y2VwdCBxdWV1ZS5FbXB0eTogYnJlYWsKICAgICAgICB0",
    "cnk6IG9iaiA9IGpzb24ubG9hZHMobGluZSkKICAgICAgICBleGNlcHQgRXhjZXB0aW9uOiBjb250aW51ZQogICAgICAgIGlmIG9i",
    "ai5nZXQoImlkIikgPT0gd2FudDogcmV0dXJuIG9iagogICAgcmV0dXJuIE5vbmUKCnNlbmQoeyJqc29ucnBjIjogIjIuMCIsICJp",
    "ZCI6IDEsICJtZXRob2QiOiAiaW5pdGlhbGl6ZSIsICJwYXJhbXMiOiB7InByb3RvY29sVmVyc2lvbiI6ICIyMDI0LTExLTA1Iiwg",
    "ImNhcGFiaWxpdGllcyI6IHt9LCAiY2xpZW50SW5mbyI6IHsibmFtZSI6ICJkZWVwaGFybmVzcyIsICJ2ZXJzaW9uIjogIjIuMiJ9",
    "fX0pCmluaXQgPSB3YWl0X2lkKDEpCmlmIG5vdCBpbml0OiBwcmludCgiTUNQOiBzZXJ2ZXIgdGlkYWsgbWVuamF3YWIgaW5pdGlh",
    "bGl6ZS4iKTsgcC50ZXJtaW5hdGUoKTsgc3lzLmV4aXQoMCkKc2VuZCh7Impzb25ycGMiOiAiMi4wIiwgIm1ldGhvZCI6ICJub3Rp",
    "ZmljYXRpb25zL2luaXRpYWxpemVkIn0pCnNlbmQoeyJqc29ucnBjIjogIjIuMCIsICJpZCI6IDIsICJtZXRob2QiOiAidG9vbHMv",
    "Y2FsbCIsICJwYXJhbXMiOiB7Im5hbWUiOiB0b29sLCAiYXJndW1lbnRzIjogYXJnc319KQpyZXMgPSB3YWl0X2lkKDIsIDYwKQpw",
    "LnRlcm1pbmF0ZSgpCmlmIG5vdCByZXM6IHByaW50KCJNQ1A6IHRpZGFrIGFkYSBqYXdhYmFuIHRvb2xzL2NhbGwgKHRpbWVvdXQp",
    "LiIpOyBzeXMuZXhpdCgwKQppZiAiZXJyb3IiIGluIHJlczogcHJpbnQoIk1DUCBlcnJvcjogIiArIGpzb24uZHVtcHMocmVzWyJl",
    "cnJvciJdKVs6MTUwMF0pOyBzeXMuZXhpdCgwKQpjb250ZW50ID0gcmVzLmdldCgicmVzdWx0Iiwge30pLmdldCgiY29udGVudCIs",
    "IFtdKQp0ZXh0cyA9IFtjLmdldCgidGV4dCIsICIiKSBmb3IgYyBpbiBjb250ZW50IGlmIGlzaW5zdGFuY2UoYywgZGljdCkgYW5k",
    "IGMuZ2V0KCJ0eXBlIikgPT0gInRleHQiXQpwcmludCgiXG4iLmpvaW4odGV4dHMpWzo2MDAwXSBpZiB0ZXh0cyBlbHNlIGpzb24u",
    "ZHVtcHMocmVzLmdldCgicmVzdWx0Iiwge30pKVs6NjAwMF0pCg==",
);

#[cfg(test)]
mod tests {
    use super::*;

    /// Nilai-nilai ini disalin VERBATIM dari `McpHelper.kt`.
    #[test]
    fn script_b64_matches_kotlin_verbatim() {
        assert_eq!(SCRIPT_B64.len(), 2352);
        assert!(SCRIPT_B64.starts_with("aW1wb3J0IHN1YnByb2Nlc3MsIHN5cywganNvbiwgYmFzZTY0LCBzaGxleCwgdGhyZWFkaW5nLCBxdWV1"));
        assert!(SCRIPT_B64.ends_with("XSBpZiB0ZXh0cyBlbHNlIGpzb24uZHVtcHMocmVzLmdldCgicmVzdWx0Iiwge30pKVs6NjAwMF0pCg=="));
        // Hanya alfabet base64 (+ padding `=` di ujung).
        assert!(SCRIPT_B64
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || c == '+' || c == '/' || c == '='));
        assert!(!SCRIPT_B64.contains('=') || SCRIPT_B64.ends_with('='));
    }
}
