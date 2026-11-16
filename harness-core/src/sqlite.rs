//! Mesin SQLite generik di atas `rusqlite` (slice 7).
//!
//! Satu fungsi JSON in/out (`sqlite_json`) melayani semua DB:
//! - `{"op":"ensure","db":<path>,"key":<kunci>}` — replikasi
//!   `SQLiteOpenHelper` (skema + migrasi di `sqlite_schema`).
//! - `{"op":"query","db":<path>,"sql":<sql>,"params":[...]}` —
//!   `{"ok":true,"columns":[...],"rows":[[...]]}`.
//! - `{"op":"exec","db":<path>,"sql":<sql>,"params":[...]}` —
//!   `{"ok":true,"changes":n,"last_id":n}`.
//! - `{"op":"tx","db":<path>,"steps":[{"sql":..,"params":..}]}` —
//!   transaksi atomik; gagal di satu langkah = rollback +
//!   `{"ok":false}`.
//!
//! Koneksi dibuka-tutup per operasi (satu writer dalam satu waktu
//! dijaga file lock SQLite + `busy_timeout` 5000 ms +
//! `journal_mode=WAL`). SQL datang dari Kotlin (string yang sama
//! persis dengan Kotlin lama); modul ini TIDAK membangun SQL dari
//! input luar selain menempelkan parameter `?` via rusqlite.

#![forbid(unsafe_code)]

use crate::sqlite_schema;
use base64::{engine::general_purpose::STANDARD as B64, Engine as _};
use rusqlite::{params_from_iter, Connection, types::Value};
use serde_json::{json, Value as Json};
use std::time::Duration;

/// Buka koneksi ke file `.db` (buat bila belum ada) + disiplin writer.
fn open_conn(path: &str) -> Result<Connection, String> {
    if path.is_empty() {
        return Err("empty db path".to_string());
    }
    let conn = Connection::open(path).map_err(|e| e.to_string())?;
    conn.busy_timeout(Duration::from_millis(5000))
        .map_err(|e| e.to_string())?;
    // WAL persisten di file (sekali set, tetap); gagal set = error
    // (lebih baik gagal + fallback Kotlin daripada tulis tanpa lock).
    let _mode: String = conn
        .query_row("PRAGMA journal_mode=WAL", [], |r| r.get(0))
        .map_err(|e| e.to_string())?;
    Ok(conn)
}

/// Parameter JSON -> `rusqlite::Value`.
/// null | number | string | bool(->1/0) | {"$b64":"..."} (BLOB).
fn json_to_value(v: &Json) -> Result<Value, String> {
    match v {
        Json::Null => Ok(Value::Null),
        Json::Bool(b) => Ok(Value::Integer(if *b { 1 } else { 0 })),
        Json::Number(n) => {
            if let Some(i) = n.as_i64() {
                Ok(Value::Integer(i))
            } else if let Some(f) = n.as_f64() {
                Ok(Value::Real(f))
            } else {
                Err("number out of range".to_string())
            }
        }
        Json::String(s) => Ok(Value::Text(s.clone())),
        Json::Object(o) => {
            if o.len() == 1 {
                if let Some(Json::String(b64)) = o.get("$b64") {
                    let bytes = B64.decode(b64).map_err(|e| e.to_string())?;
                    return Ok(Value::Blob(bytes));
                }
            }
            Err("unsupported object param".to_string())
        }
        Json::Array(_) => Err("array param not supported".to_string()),
    }
}

fn params_of(req: &Json) -> Result<Vec<Value>, String> {
    match req.get("params") {
        None | Some(Json::Null) => Ok(Vec::new()),
        Some(Json::Array(a)) => a.iter().map(json_to_value).collect(),
        Some(_) => Err("params must be an array".to_string()),
    }
}

/// `rusqlite::Value` -> JSON (Blob -> {"$b64":...}).
fn value_to_json(v: &Value) -> Json {
    match v {
        Value::Null => Json::Null,
        Value::Integer(i) => json!(*i),
        Value::Real(f) => json!(*f),
        Value::Text(s) => json!(s),
        Value::Blob(b) => json!({ "$b64": B64.encode(b) }),
    }
}

fn req_str<'a>(req: &'a Json, key: &str) -> Result<&'a str, String> {
    req.get(key)
        .and_then(|v| v.as_str())
        .ok_or_else(|| format!("missing {key}"))
}

/// Jalankan satu pernyataan tulis; kembalikan (changes, last_row_id).
fn run_exec(conn: &Connection, sql: &str, params: Vec<Value>) -> Result<(u64, i64), String> {
    let mut stmt = conn.prepare(sql).map_err(|e| e.to_string())?;
    let changes = stmt
        .execute(params_from_iter(params))
        .map_err(|e| e.to_string())? as u64;
    Ok((changes, conn.last_insert_rowid()))
}

/// Titik masuk seam JSON. `Err` -> pemanggil JNI mengembalikan JSON error.
pub fn sqlite_json(request: &str) -> Result<String, String> {
    let req: Json = serde_json::from_str(request).map_err(|e| e.to_string())?;
    let op = req_str(&req, "op")?;
    let db = req_str(&req, "db")?;
    match op {
        "ensure" => {
            let key = req_str(&req, "key")?;
            let conn = open_conn(db)?;
            let version = sqlite_schema::ensure_db(&conn, key)?;
            Ok(json!({ "ok": true, "version": version }).to_string())
        }
        "query" => {
            let sql = req_str(&req, "sql")?;
            let params = params_of(&req)?;
            let conn = open_conn(db)?;
            let mut stmt = conn.prepare(sql).map_err(|e| e.to_string())?;
            let col_names: Vec<String> =
                stmt.column_names().iter().map(|s| s.to_string()).collect();
            let mut q = stmt.query(params_from_iter(params)).map_err(|e| e.to_string())?;
            let mut rows: Vec<Json> = Vec::new();
            while let Some(row) = q.next().map_err(|e| e.to_string())? {
                let mut out: Vec<Json> = Vec::new();
                for i in 0..col_names.len() {
                    let v: Value = row.get(i).map_err(|e| e.to_string())?;
                    out.push(value_to_json(&v));
                }
                rows.push(Json::Array(out));
            }
            Ok(json!({ "ok": true, "columns": col_names, "rows": rows }).to_string())
        }
        "exec" => {
            let sql = req_str(&req, "sql")?;
            let params = params_of(&req)?;
            let conn = open_conn(db)?;
            let (changes, last_id) = run_exec(&conn, sql, params)?;
            Ok(json!({ "ok": true, "changes": changes, "last_id": last_id }).to_string())
        }
        "tx" => {
            let steps = req
                .get("steps")
                .and_then(|v| v.as_array())
                .ok_or_else(|| "missing steps".to_string())?;
            let mut conn = open_conn(db)?;
            let tx = conn.transaction().map_err(|e| e.to_string())?;
            let mut results: Vec<Json> = Vec::new();
            let mut outcome: Result<(), String> = Ok(());
            for step in steps {
                let sql = match step.get("sql").and_then(|v| v.as_str()) {
                    Some(s) => s,
                    None => {
                        outcome = Err("step missing sql".to_string());
                        break;
                    }
                };
                let params = match params_of(step) {
                    Ok(p) => p,
                    Err(e) => {
                        outcome = Err(e);
                        break;
                    }
                };
                match run_exec(&tx, sql, params) {
                    Ok((changes, last_id)) => {
                        results.push(json!({ "changes": changes, "last_id": last_id }));
                    }
                    Err(e) => {
                        outcome = Err(e);
                        break;
                    }
                }
            }
            match outcome {
                Ok(()) => {
                    tx.commit().map_err(|e| e.to_string())?;
                    Ok(json!({ "ok": true, "results": results }).to_string())
                }
                Err(e) => {
                    let _ = tx.rollback();
                    Err(e)
                }
            }
        }
        _ => Err(format!("unknown op: {op}")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU64, Ordering};

    static CTR: AtomicU64 = AtomicU64::new(0);

    fn tmp_db() -> String {
        let n = CTR.fetch_add(1, Ordering::SeqCst);
        let p = std::env::temp_dir().join(format!("vc322-sqlite-test-{n}.db"));
        let _ = std::fs::remove_file(&p);
        let _ = std::fs::remove_file(format!("{}-wal", p.display()));
        let _ = std::fs::remove_file(format!("{}-shm", p.display()));
        p.to_string_lossy().into_owned()
    }

    fn cleanup(path: &str) {
        let _ = std::fs::remove_file(path);
        let _ = std::fs::remove_file(format!("{path}-wal"));
        let _ = std::fs::remove_file(format!("{path}-shm"));
    }

    fn ensure(path: &str, key: &str) -> Json {
        let r = sqlite_json(&json!({ "op": "ensure", "db": path, "key": key }).to_string())
            .unwrap();
        serde_json::from_str(&r).unwrap()
    }

    #[test]
    fn crud_events_via_json() {
        let db = tmp_db();
        let e = ensure(&db, "events");
        assert_eq!(e["ok"], true);
        assert_eq!(e["version"], 1);
        // insert
        let r: Json = serde_json::from_str(
            &sqlite_json(
                &json!({ "op": "exec", "db": db, "sql": "INSERT INTO events(ts,type,pkg,text) VALUES(?,?,?,?)", "params": [1700000000000i64, "notif", "com.x", "halo 🌍"] }).to_string(),
            )
            .unwrap(),
        )
        .unwrap();
        assert_eq!(r["ok"], true);
        assert_eq!(r["changes"], 1);
        assert_eq!(r["last_id"], 1);
        // query + emoji/non-ASCII round-trip
        let q: Json = serde_json::from_str(
            &sqlite_json(
                &json!({ "op": "query", "db": db, "sql": "SELECT ts,type,pkg,text FROM events ORDER BY id DESC LIMIT ?", "params": [10] }).to_string(),
            )
            .unwrap(),
        )
        .unwrap();
        assert_eq!(q["ok"], true);
        assert_eq!(q["rows"][0][3], "halo 🌍");
        assert_eq!(q["rows"][0][0], 1700000000000i64);
        assert_eq!(q["columns"][1], "type");
        // update + delete
        let u: Json = serde_json::from_str(
            &sqlite_json(
                &json!({ "op": "exec", "db": db, "sql": "UPDATE events SET text=? WHERE id=?", "params": ["ubah", 1] }).to_string(),
            )
            .unwrap(),
        )
        .unwrap();
        assert_eq!(u["changes"], 1);
        let d: Json = serde_json::from_str(
            &sqlite_json(
                &json!({ "op": "exec", "db": db, "sql": "DELETE FROM events WHERE id=?", "params": [1] }).to_string(),
            )
            .unwrap(),
        )
        .unwrap();
        assert_eq!(d["changes"], 1);
        cleanup(&db);
    }

    #[test]
    fn tx_atomic_rollback() {
        let db = tmp_db();
        assert_eq!(ensure(&db, "events")["ok"], true);
        // Langkah ke-2 gagal (tabel tidak ada) -> rollback, langkah 1 hilang.
        let err = sqlite_json(
            &json!({ "op": "tx", "db": db, "steps": [
                { "sql": "INSERT INTO events(ts,type,pkg,text) VALUES(1,'a','p','t')", "params": [] },
                { "sql": "INSERT INTO tidak_ada(x) VALUES(1)", "params": [] }
            ] }).to_string(),
        );
        assert!(err.is_err());
        let q: Json = serde_json::from_str(
            &sqlite_json(
                &json!({ "op": "query", "db": db, "sql": "SELECT COUNT(*) FROM events", "params": [] }).to_string(),
            )
            .unwrap(),
        )
        .unwrap();
        assert_eq!(q["rows"][0][0], 0);
        // Tx sukses: 2 insert atomik.
        let ok: Json = serde_json::from_str(
            &sqlite_json(
                &json!({ "op": "tx", "db": db, "steps": [
                    { "sql": "INSERT INTO events(ts,type,pkg,text) VALUES(1,'a','p','t1')", "params": [] },
                    { "sql": "INSERT INTO events(ts,type,pkg,text) VALUES(2,'a','p','t2')", "params": [] }
                ] }).to_string(),
            )
            .unwrap(),
        )
        .unwrap();
        assert_eq!(ok["ok"], true);
        assert_eq!(ok["results"].as_array().unwrap().len(), 2);
        cleanup(&db);
    }

    #[test]
    fn bad_sql_errors_not_panics() {
        let db = tmp_db();
        assert!(sqlite_json(&json!({ "op": "query", "db": db, "sql": "SELECT * FROM tidak_ada", "params": [] }).to_string()).is_err());
        assert!(sqlite_json("bukan json").is_err());
        assert!(sqlite_json(&json!({ "op": "apa", "db": db }).to_string()).is_err());
        assert!(sqlite_json(&json!({ "op": "query", "db": "", "sql": "SELECT 1", "params": [] }).to_string()).is_err());
        cleanup(&db);
    }

    #[test]
    fn blob_roundtrip() {
        let db = tmp_db();
        assert_eq!(ensure(&db, "memory")["ok"], true);
        // vec BLOB embedding: byte 0..7.
        let b64 = B64.encode([0u8, 1, 2, 3, 4, 5, 6, 7]);
        let r: Json = serde_json::from_str(
            &sqlite_json(
                &json!({ "op": "exec", "db": db, "sql": "INSERT INTO embeddings(kind,ref,vec,thash) VALUES(?,?,?,?)", "params": ["fact", "k1", { "$b64": b64 }, 123] }).to_string(),
            )
            .unwrap(),
        )
        .unwrap();
        assert_eq!(r["ok"], true);
        let q: Json = serde_json::from_str(
            &sqlite_json(
                &json!({ "op": "query", "db": db, "sql": "SELECT vec FROM embeddings WHERE kind=? AND ref=?", "params": ["fact", "k1"] }).to_string(),
            )
            .unwrap(),
        )
        .unwrap();
        assert_eq!(q["rows"][0][0]["$b64"], B64.encode([0u8, 1, 2, 3, 4, 5, 6, 7]));
        cleanup(&db);
    }

    #[test]
    fn concurrent_writers_no_loss() {
        use std::sync::Arc;
        let db = Arc::new(tmp_db());
        assert_eq!(ensure(&db, "events")["ok"], true);
        let mut handles = Vec::new();
        for t in 0..4 {
            let path = Arc::clone(&db);
            handles.push(std::thread::spawn(move || {
                for i in 0..25 {
                    let req = json!({ "op": "exec", "db": *path, "sql": "INSERT INTO events(ts,type,pkg,text) VALUES(?,?,?,?)", "params": [i, format!("w{t}"), "p", "x"] }).to_string();
                    // busy_timeout menahan lock; gagal transient di-retry 1x.
                    let mut ok = sqlite_json(&req).is_ok();
                    if !ok {
                        std::thread::sleep(Duration::from_millis(10));
                        ok = sqlite_json(&req).is_ok();
                    }
                    assert!(ok, "writer {t} iter {i} gagal");
                }
            }));
        }
        for h in handles {
            h.join().unwrap();
        }
        let q: Json = serde_json::from_str(
            &sqlite_json(
                &json!({ "op": "query", "db": *db, "sql": "SELECT COUNT(*) FROM events", "params": [] }).to_string(),
            )
            .unwrap(),
        )
        .unwrap();
        assert_eq!(q["rows"][0][0], 100);
        cleanup(&db);
    }

    #[test]
    fn wal_mode_set() {
        let db = tmp_db();
        assert_eq!(ensure(&db, "events")["ok"], true);
        let conn = Connection::open(&db).unwrap();
        let mode: String = conn
            .query_row("PRAGMA journal_mode", [], |r| r.get(0))
            .unwrap();
        assert_eq!(mode, "wal");
        drop(conn);
        cleanup(&db);
    }
}
