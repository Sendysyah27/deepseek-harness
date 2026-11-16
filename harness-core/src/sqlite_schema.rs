//! Skema + migrasi SQLite per DB — DDL disalin VERBATIM dari sumber
//! Kotlin (porting murni, §2.2 BUILD-VC322.md). Modul ini TIDAK
//! menerima input luar selain path DB (sudah dibuka) dan kunci DB;
//! semua SQL adalah konstanta di sini, bukan dari pemanggil.
//!
//! `ensure_db(conn, key)` mereplikasi `SQLiteOpenHelper`:
//! - `PRAGMA user_version` 0 -> jalankan DDL `onCreate` (ketat).
//! - < target -> jalankan langkah `onUpgrade` + langkah lazy `onOpen`
//!   (longgar per pernyataan, seperti `try {}` Kotlin), lalu set
//!   `user_version` = target.
//!
//! Kunci: "events" (v1), "artifacts" (v1), "parked" (v1),
//! "graph" (v3), "memory" (v7, fondasi — wrapper Kotlin-nya sisa
//! kerja ronde berikut, BUILD-VC322.md §2.4).

#![forbid(unsafe_code)]

use rusqlite::{params, Connection};
use std::time::{SystemTime, UNIX_EPOCH};

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// `PRAGMA user_version` saat ini.
fn user_version(conn: &Connection) -> Result<i64, String> {
    conn.query_row("PRAGMA user_version", [], |r| r.get(0))
        .map_err(|e| e.to_string())
}

fn set_user_version(conn: &Connection, v: i64) -> Result<(), String> {
    conn.execute_batch(&format!("PRAGMA user_version = {v}"))
        .map_err(|e| e.to_string())
}

/// Jalankan tiap pernyataan; abaikan error per pernyataan (padanan
/// `try { db.execSQL(..) } catch (e: Exception) {}` Kotlin).
fn run_lenient(conn: &Connection, stmts: &[&str]) {
    for s in stmts {
        let _ = conn.execute_batch(s);
    }
}

/// Jalankan tiap pernyataan; gagal di pernyataan mana pun = error
/// (padanan `onCreate` Kotlin tanpa try).
fn run_strict(conn: &Connection, stmts: &[&str]) -> Result<(), String> {
    for s in stmts {
        conn.execute_batch(s).map_err(|e| e.to_string())?;
    }
    Ok(())
}

/// Apakah kolom `col` ada di tabel `table` (via PRAGMA table_info).
fn has_column(conn: &Connection, table: &str, col: &str) -> bool {
    let sql = format!("PRAGMA table_info({table})");
    let mut stmt = match conn.prepare(&sql) {
        Ok(s) => s,
        Err(_) => return false,
    };
    let mut rows = match stmt.query([]) {
        Ok(r) => r,
        Err(_) => return false,
    };
    while let Ok(Some(row)) = rows.next() {
        if let Ok(name) = row.get::<_, String>(1) {
            if name == col {
                return true;
            }
        }
    }
    false
}

/// Versi target tiap kunci DB.
pub fn target_version(key: &str) -> Option<i64> {
    match key {
        "events" => Some(1),
        "artifacts" => Some(1),
        "parked" => Some(1),
        "graph" => Some(3),
        "memory" => Some(7),
        _ => None,
    }
}

/// Titik masuk ensure: replikasi open-helper untuk `key`.
/// Mengembalikan `user_version` akhir.
pub fn ensure_db(conn: &Connection, key: &str) -> Result<i64, String> {
    let target = target_version(key).ok_or_else(|| format!("unknown db key: {key}"))?;
    let cur = user_version(conn)?;
    if cur == 0 {
        create_fresh(conn, key)?;
    } else if cur < target {
        upgrade(conn, key, cur)?;
    }
    // Langkah lazy `onOpen` (idempotent) — hanya memory yang punya.
    if key == "memory" {
        memory_on_open(conn);
    }
    set_user_version(conn, target)?;
    Ok(target)
}

// ---------------------------------------------------------------- events
const EVENTS_CREATE: &[&str] = &[
    "CREATE TABLE events(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, type TEXT, pkg TEXT, text TEXT)",
    "CREATE INDEX idx_events_ts ON events(ts)",
];

// ------------------------------------------------------------- artifacts
const ARTIFACTS_CREATE: &[&str] = &[
    "CREATE TABLE IF NOT EXISTS artifacts(id TEXT PRIMARY KEY, title TEXT, type TEXT, owner_session INTEGER, created INTEGER, updated INTEGER)",
    "CREATE TABLE IF NOT EXISTS artifact_versions(artifact_id TEXT, version INTEGER, content TEXT, created INTEGER, PRIMARY KEY(artifact_id, version))",
    "CREATE INDEX IF NOT EXISTS idx_artifacts_owner ON artifacts(owner_session, updated DESC)",
];

// ---------------------------------------------------------------- parked
const PARKED_CREATE: &[&str] = &[
    "CREATE TABLE IF NOT EXISTS parked_approvals(pid TEXT PRIMARY KEY, sessionId TEXT, tool TEXT, args TEXT, risk TEXT, status TEXT, decision TEXT, decidedAtMs INTEGER, createdAtMs INTEGER, timeoutMs INTEGER, timeoutOwnerMs INTEGER, notifId INTEGER, task TEXT, origin TEXT, title TEXT, transcript TEXT, steps INTEGER, job TEXT, tools TEXT, ownerSession INTEGER)",
    "CREATE INDEX IF NOT EXISTS idx_parked_status ON parked_approvals(status)",
    "CREATE INDEX IF NOT EXISTS idx_parked_notif ON parked_approvals(notifId)",
];

// ----------------------------------------------------------------- graph
const GRAPH_CREATE: &[&str] = &[
    "CREATE TABLE IF NOT EXISTS nodes (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT, label TEXT, source TEXT, aliases_json TEXT DEFAULT '[]')",
    "CREATE INDEX IF NOT EXISTS idx_nodes_label ON nodes(label)",
    "CREATE TABLE IF NOT EXISTS edges (id INTEGER PRIMARY KEY AUTOINCREMENT, from_id INTEGER, to_id INTEGER, rel TEXT, weight REAL DEFAULT 1.0, tag TEXT DEFAULT 'EXTRACTED', confidence REAL DEFAULT 1.0, fact_text TEXT DEFAULT '', valid_at INTEGER, invalid_at INTEGER, recorded_at INTEGER, expired_at INTEGER, episode_id INTEGER)",
    "CREATE INDEX IF NOT EXISTS idx_edges_from ON edges(from_id)",
    "CREATE INDEX IF NOT EXISTS idx_edges_to ON edges(to_id)",
    "CREATE TABLE IF NOT EXISTS episodes (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT, role TEXT, text TEXT, source TEXT, ts INTEGER)",
    "CREATE INDEX IF NOT EXISTS idx_episodes_sess_text ON episodes(session_id, text)",
];

/// FTS5 — di Kotlin dibungkus try{} (onCreate). Sama di sini: gagal
/// membuat FTS = lanjut tanpa FTS (searchFacts Kotlin melempar lalu
/// pemanggil fallback ke LIKE).
fn graph_create_fts(conn: &Connection) {
    let _ = conn.execute_batch("CREATE VIRTUAL TABLE IF NOT EXISTS fts_fact USING fts5(fact_text)");
}

/// Langkah v1->v2 graph (GraphDb.onUpgrade oldV < 2). Longgar.
fn graph_upgrade_v2(conn: &Connection) {
    run_lenient(
        conn,
        &[
            "ALTER TABLE edges ADD COLUMN tag TEXT DEFAULT 'EXTRACTED'",
            "ALTER TABLE edges ADD COLUMN confidence REAL DEFAULT 1.0",
            "UPDATE edges SET tag = 'INFERRED', confidence = 0.5 WHERE rel NOT IN ('menghasilkan', 'memiliki', 'mengerjakan')",
            "UPDATE edges SET tag = 'EXTRACTED', confidence = 1.0 WHERE rel IN ('menghasilkan', 'memiliki', 'mengerjakan')",
        ],
    );
}

/// Backfill v2->v3 (GraphDb.onUpgrade oldV < 3): fact_text dari label
/// ujung + recorded_at/valid_at = waktu migrasi + insert fts_fact.
/// Replikasi persis, termasuk "insert fts per edge" (bukan OR IGNORE —
/// Kotlin memakai `insert()` biasa; duplikat dibiarkan).
fn graph_backfill_v3(conn: &Connection) {
    let rows: Vec<(i64, i64, i64, String)> = (|| -> Result<Vec<(i64, i64, i64, String)>, String> {
        let mut stmt = conn
            .prepare("SELECT id, from_id, to_id, rel FROM edges")
            .map_err(|e| e.to_string())?;
        let mut q = stmt.query([]).map_err(|e| e.to_string())?;
        let mut out = Vec::new();
        while let Some(row) = q.next().map_err(|e| e.to_string())? {
            let id: i64 = row.get(0).map_err(|e| e.to_string())?;
            let from: i64 = row.get(1).map_err(|e| e.to_string())?;
            let to: i64 = row.get(2).map_err(|e| e.to_string())?;
            let rel: String = row.get(3).map_err(|e| e.to_string())?;
            out.push((id, from, to, rel));
        }
        Ok(out)
    })()
    .unwrap_or_default();
    let now = now_ms();
    for (id, from, to, rel) in rows {
        let label = |node_id: i64| -> String {
            conn.query_row(
                "SELECT label FROM nodes WHERE id = ?",
                params![node_id],
                |r| r.get::<_, String>(0),
            )
            .unwrap_or_default()
        };
        let ft = format!("{} {} {}", label(from), rel, label(to));
        let _ = conn.execute(
            "UPDATE edges SET fact_text = ?1, recorded_at = ?2, valid_at = ?2 WHERE id = ?3",
            params![ft, now, id],
        );
        let _ = conn.execute(
            "INSERT INTO fts_fact(fact_text) VALUES(?1)",
            params![ft],
        );
    }
}

fn graph_upgrade_v3(conn: &Connection) {
    run_lenient(
        conn,
        &[
            "ALTER TABLE nodes ADD COLUMN aliases_json TEXT DEFAULT '[]'",
            "ALTER TABLE edges ADD COLUMN fact_text TEXT DEFAULT ''",
            "ALTER TABLE edges ADD COLUMN valid_at INTEGER",
            "ALTER TABLE edges ADD COLUMN invalid_at INTEGER",
            "ALTER TABLE edges ADD COLUMN recorded_at INTEGER",
            "ALTER TABLE edges ADD COLUMN expired_at INTEGER",
            "ALTER TABLE edges ADD COLUMN episode_id INTEGER",
            "CREATE TABLE IF NOT EXISTS episodes (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT, role TEXT, text TEXT, source TEXT, ts INTEGER)",
            "CREATE INDEX IF NOT EXISTS idx_episodes_sess_text ON episodes(session_id, text)",
        ],
    );
    graph_create_fts(conn);
    graph_backfill_v3(conn);
}

// ---------------------------------------------------------------- memory
// DDL onCreate MemoryDb (facts/history/reminders + createV2..V7 +
// createPerfIndexes), disalin verbatim dari MemoryDb.kt.
const MEMORY_CREATE_V1: &[&str] = &[
    "CREATE TABLE facts(id INTEGER PRIMARY KEY AUTOINCREMENT, k TEXT, v TEXT, ts INTEGER, confirmed INTEGER DEFAULT 0, source TEXT DEFAULT '', source_ts INTEGER DEFAULT 0, status TEXT DEFAULT 'active', superseded_by TEXT DEFAULT '', reason TEXT DEFAULT '', updated_ts INTEGER DEFAULT 0)",
    "CREATE TABLE history(id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT, text TEXT, ts INTEGER)",
    "CREATE TABLE reminders(id INTEGER PRIMARY KEY AUTOINCREMENT, due INTEGER, text TEXT, done INTEGER DEFAULT 0)",
];
const MEMORY_CREATE_V2: &[&str] = &[
    "CREATE TABLE IF NOT EXISTS schedules(id INTEGER PRIMARY KEY AUTOINCREMENT, hour INTEGER, minute INTEGER, prompt TEXT, enabled INTEGER DEFAULT 1, last_run_day INTEGER DEFAULT -1)",
    "CREATE TABLE IF NOT EXISTS rules(id INTEGER PRIMARY KEY AUTOINCREMENT, pkg TEXT, needle TEXT, action TEXT, enabled INTEGER DEFAULT 1)",
    "CREATE TABLE IF NOT EXISTS skills(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, instruction TEXT)",
    "CREATE TABLE IF NOT EXISTS geofences(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, lat REAL, lng REAL, radius INTEGER, prompt TEXT, inside INTEGER DEFAULT 0)",
    "CREATE TABLE IF NOT EXISTS finance(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, pkg TEXT, amount INTEGER, kind TEXT, text TEXT)",
];
const MEMORY_CREATE_V6: &[&str] = &[
    "CREATE TABLE IF NOT EXISTS oc_tasks(id INTEGER PRIMARY KEY AUTOINCREMENT, chain_id TEXT, text TEXT, session_id TEXT DEFAULT '', status TEXT DEFAULT 'queued', result TEXT DEFAULT '', created INTEGER, finished INTEGER DEFAULT 0)",
    "CREATE TABLE IF NOT EXISTS coord_rules(k TEXT PRIMARY KEY, v TEXT)",
];
const MEMORY_CREATE_V7_WORKERS: &str = "CREATE TABLE IF NOT EXISTS workers(id TEXT PRIMARY KEY, name TEXT, kind TEXT, session TEXT, command TEXT, script_path TEXT DEFAULT '', pid INTEGER DEFAULT 0, log_path TEXT, status TEXT, stopped INTEGER DEFAULT 0, created INTEGER, last_seen INTEGER DEFAULT 0, last_event TEXT DEFAULT '', owner TEXT DEFAULT '', work_dir TEXT DEFAULT '', quota_mb INTEGER DEFAULT 0)";
const MEMORY_PERF_INDEXES: &[&str] = &[
    "CREATE TABLE IF NOT EXISTS deleted_archive(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, ref TEXT, payload TEXT, deleted_ts INTEGER)",
    "CREATE INDEX IF NOT EXISTS idx_facts_ts ON facts(ts)",
    "CREATE INDEX IF NOT EXISTS idx_facts_k_status ON facts(k, status)",
    "CREATE INDEX IF NOT EXISTS idx_goal_steps_goal_pos ON goal_steps(goal_id, pos)",
    "CREATE INDEX IF NOT EXISTS idx_finance_ts ON finance(ts)",
    "CREATE INDEX IF NOT EXISTS idx_deleted_archive_ts ON deleted_archive(deleted_ts)",
    "CREATE INDEX IF NOT EXISTS idx_history_session_ts ON history(session, ts)",
];

/// createV3 — termasuk seed sessions(1,'Utama',now) + ALTER history.
/// Dipakai onCreate segar maupun upgrade o<3.
fn memory_create_v3(conn: &Connection) {
    run_lenient(
        conn,
        &["CREATE TABLE IF NOT EXISTS sessions(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, created INTEGER)"],
    );
    // ALTER gagal bila kolom sudah ada — ditangkap (pola Kotlin).
    let _ = conn.execute_batch("ALTER TABLE history ADD COLUMN session INTEGER DEFAULT 1");
    let now = now_ms();
    let _ = conn.execute(
        "INSERT OR IGNORE INTO sessions(id,title,created) VALUES(1,'Utama',?1)",
        params![now],
    );
}

fn memory_create_v4(conn: &Connection) {
    run_lenient(
        conn,
        &["CREATE TABLE IF NOT EXISTS embeddings(kind TEXT, ref TEXT, vec BLOB, thash INTEGER, PRIMARY KEY(kind, ref))"],
    );
}

fn memory_create_v5(conn: &Connection) {
    run_lenient(
        conn,
        &[
            "CREATE TABLE IF NOT EXISTS goals(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, status TEXT DEFAULT 'aktif', created INTEGER, updated INTEGER)",
            "CREATE TABLE IF NOT EXISTS goal_steps(id INTEGER PRIMARY KEY AUTOINCREMENT, goal_id INTEGER, text TEXT, due INTEGER DEFAULT 0, done INTEGER DEFAULT 0, done_ts INTEGER DEFAULT 0, pos INTEGER DEFAULT 0)",
        ],
    );
}

/// Replikasi `ensureFactProvenanceVc285` (MemoryDb.onOpen): bila tabel
/// facts lama (`k TEXT PRIMARY KEY`, tanpa kolom `id`) masih ada,
/// rebuild transaksional ke skema baru; bila sudah ada `id` tapi
/// belum ada `status`, tambah kolom provenance.
fn memory_ensure_fact_provenance(conn: &Connection) {
    let has_id = has_column(conn, "facts", "id");
    let has_status = has_column(conn, "facts", "status");
    let has_confirmed = has_column(conn, "facts", "confirmed");
    if has_id {
        if !has_status {
            run_lenient(
                conn,
                &[
                    "ALTER TABLE facts ADD COLUMN source TEXT DEFAULT ''",
                    "ALTER TABLE facts ADD COLUMN source_ts INTEGER DEFAULT 0",
                    "ALTER TABLE facts ADD COLUMN status TEXT DEFAULT 'active'",
                    "ALTER TABLE facts ADD COLUMN superseded_by TEXT DEFAULT ''",
                    "ALTER TABLE facts ADD COLUMN reason TEXT DEFAULT ''",
                    "ALTER TABLE facts ADD COLUMN updated_ts INTEGER DEFAULT 0",
                ],
            );
        }
        return;
    }
    if !has_confirmed {
        let _ = conn.execute_batch("ALTER TABLE facts ADD COLUMN confirmed INTEGER DEFAULT 0");
    }
    // Rebuild transaksional (persis Kotlin, termasuk INSERT ... SELECT
    // dengan default provenance). `transaction()` butuh &mut — dipakai
    // BEGIN/COMMIT manual lewat execute_batch.
    if conn.execute_batch("BEGIN IMMEDIATE").is_err() {
        return;
    }
    let ok = (|| -> Result<(), String> {
        conn.execute_batch("CREATE TABLE IF NOT EXISTS facts_vc285(id INTEGER PRIMARY KEY AUTOINCREMENT, k TEXT, v TEXT, ts INTEGER, confirmed INTEGER DEFAULT 0, source TEXT DEFAULT '', source_ts INTEGER DEFAULT 0, status TEXT DEFAULT 'active', superseded_by TEXT DEFAULT '', reason TEXT DEFAULT '', updated_ts INTEGER DEFAULT 0)")
            .map_err(|e| e.to_string())?;
        conn.execute_batch("INSERT INTO facts_vc285(k, v, ts, confirmed, source, source_ts, status, superseded_by, reason, updated_ts) SELECT k, v, ts, confirmed, '', 0, 'active', '', '', 0 FROM facts")
            .map_err(|e| e.to_string())?;
        conn.execute_batch("DROP TABLE facts").map_err(|e| e.to_string())?;
        conn.execute_batch("ALTER TABLE facts_vc285 RENAME TO facts")
            .map_err(|e| e.to_string())?;
        Ok(())
    })();
    match ok {
        Ok(()) => {
            let _ = conn.execute_batch("COMMIT");
        }
        Err(_) => {
            let _ = conn.execute_batch("ROLLBACK");
        }
    }
}

/// Replikasi `ensureVc294SchemaVc294` (onOpen).
fn memory_ensure_vc294(conn: &Connection) {
    run_lenient(
        conn,
        &[
            "ALTER TABLE facts ADD COLUMN tags TEXT DEFAULT ''",
            "ALTER TABLE facts ADD COLUMN ref_path TEXT DEFAULT ''",
            "CREATE TABLE IF NOT EXISTS lessons(id INTEGER PRIMARY KEY AUTOINCREMENT, text TEXT, confidence REAL DEFAULT 0.7, hits INTEGER DEFAULT 1, status TEXT DEFAULT 'active', source TEXT DEFAULT '', reason TEXT DEFAULT '', ts INTEGER, updated_ts INTEGER)",
            "CREATE TABLE IF NOT EXISTS fact_candidates(id INTEGER PRIMARY KEY AUTOINCREMENT, k TEXT, v TEXT, tags TEXT DEFAULT '', ref_path TEXT DEFAULT '', source TEXT DEFAULT '', status TEXT DEFAULT 'candidate', reason TEXT DEFAULT '', ts INTEGER, decided_ts INTEGER DEFAULT 0)",
            "CREATE INDEX IF NOT EXISTS idx_lessons_status ON lessons(status)",
            "CREATE INDEX IF NOT EXISTS idx_candidates_status ON fact_candidates(status)",
        ],
    );
}

/// Replikasi langkah lazy `onOpen` MemoryDb (selain vc285/vc294).
fn memory_on_open(conn: &Connection) {
    memory_ensure_fact_provenance(conn);
    memory_ensure_vc294(conn);
    run_lenient(
        conn,
        &[
            "CREATE TABLE IF NOT EXISTS fact_edges(from_k TEXT, to_k TEXT, rel TEXT, created_ts INTEGER, PRIMARY KEY(from_k, to_k, rel))",
            "CREATE TABLE IF NOT EXISTS dream_flags(id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT, fact_a TEXT, fact_b TEXT, detail TEXT, status TEXT DEFAULT 'open', created_ts INTEGER, resolved_ts INTEGER DEFAULT 0)",
        ],
    );
    run_lenient(conn, MEMORY_PERF_INDEXES);
    run_lenient(
        conn,
        &[
            "ALTER TABLE oc_tasks ADD COLUMN origin_session INTEGER DEFAULT 0",
            "ALTER TABLE schedules ADD COLUMN owner_session INTEGER DEFAULT 0",
            "ALTER TABLE geofences ADD COLUMN owner_session INTEGER DEFAULT 0",
        ],
    );
    let _ = conn.execute_batch(MEMORY_CREATE_V7_WORKERS);
}

fn create_fresh(conn: &Connection, key: &str) -> Result<(), String> {
    match key {
        "events" => run_strict(conn, EVENTS_CREATE),
        "artifacts" => run_strict(conn, ARTIFACTS_CREATE),
        "parked" => run_strict(conn, PARKED_CREATE),
        "graph" => {
            run_strict(conn, GRAPH_CREATE)?;
            graph_create_fts(conn);
            Ok(())
        }
        "memory" => {
            run_strict(conn, MEMORY_CREATE_V1)?;
            run_lenient(conn, MEMORY_CREATE_V2);
            memory_create_v3(conn);
            memory_create_v4(conn);
            memory_create_v5(conn);
            run_lenient(conn, MEMORY_CREATE_V6);
            let _ = conn.execute_batch(MEMORY_CREATE_V7_WORKERS);
            run_lenient(conn, MEMORY_PERF_INDEXES);
            Ok(())
        }
        _ => Err(format!("unknown db key: {key}")),
    }
}

fn upgrade(conn: &Connection, key: &str, old: i64) -> Result<(), String> {
    match key {
        "events" | "artifacts" | "parked" => Ok(()),
        "graph" => {
            if old < 2 {
                graph_upgrade_v2(conn);
            }
            if old < 3 {
                graph_upgrade_v3(conn);
            }
            // onCreate(db) di akhir onUpgrade Kotlin.
            run_lenient(conn, GRAPH_CREATE);
            graph_create_fts(conn);
            Ok(())
        }
        "memory" => {
            if old < 2 {
                run_lenient(conn, MEMORY_CREATE_V2);
            }
            if old < 3 {
                memory_create_v3(conn);
            }
            if old < 4 {
                memory_create_v4(conn);
            }
            if old < 5 {
                memory_create_v5(conn);
            }
            if old < 6 {
                run_lenient(conn, MEMORY_CREATE_V6);
            }
            if old < 7 {
                let _ = conn.execute_batch(MEMORY_CREATE_V7_WORKERS);
            }
            Ok(())
        }
        _ => Err(format!("unknown db key: {key}")),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use rusqlite::Connection;
    use std::collections::HashSet;

    fn tables(conn: &Connection) -> HashSet<String> {
        let mut stmt = conn
            .prepare("SELECT name FROM sqlite_master WHERE type='table'")
            .unwrap();
        let rows = stmt.query_map([], |r| r.get::<_, String>(0)).unwrap();
        rows.map(|r| r.unwrap()).collect()
    }

    fn user_ver(conn: &Connection) -> i64 {
        conn.query_row("PRAGMA user_version", [], |r| r.get(0))
            .unwrap()
    }

    fn fresh_mem() -> Connection {
        let c = Connection::open_in_memory().unwrap();
        c.execute_batch("PRAGMA journal_mode=MEMORY").unwrap();
        c
    }

    #[test]
    fn ensure_events_fresh() {
        let c = fresh_mem();
        assert_eq!(ensure_db(&c, "events").unwrap(), 1);
        assert_eq!(user_ver(&c), 1);
        let t = tables(&c);
        assert!(t.contains("events"));
        assert!(t.contains("sqlite_sequence"));
    }

    #[test]
    fn ensure_artifacts_fresh() {
        let c = fresh_mem();
        assert_eq!(ensure_db(&c, "artifacts").unwrap(), 1);
        let t = tables(&c);
        assert!(t.contains("artifacts"));
        assert!(t.contains("artifact_versions"));
    }

    #[test]
    fn ensure_parked_fresh() {
        let c = fresh_mem();
        assert_eq!(ensure_db(&c, "parked").unwrap(), 1);
        let t = tables(&c);
        assert!(t.contains("parked_approvals"));
    }

    #[test]
    fn ensure_graph_fresh_and_fts() {
        let c = fresh_mem();
        assert_eq!(ensure_db(&c, "graph").unwrap(), 3);
        let t = tables(&c);
        assert!(t.contains("nodes"));
        assert!(t.contains("edges"));
        assert!(t.contains("episodes"));
        assert!(t.contains("fts_fact"));
        // FTS5 MATCH benar-benar jalan (bundled).
        c.execute("INSERT INTO fts_fact(fact_text) VALUES('Jakarta ibu kota')", [])
            .unwrap();
        let n: i64 = c
            .query_row(
                "SELECT COUNT(*) FROM fts_fact WHERE fts_fact MATCH 'Jakarta*'",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(n, 1);
    }

    #[test]
    fn ensure_graph_upgrade_v2_to_v3_backfill() {
        // Fixture v2: skema Kotlin v2 (tanpa aliases_json/fact_text/dll).
        let c = fresh_mem();
        c.execute_batch(
            "CREATE TABLE nodes (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT, label TEXT, source TEXT);\
             CREATE TABLE edges (id INTEGER PRIMARY KEY AUTOINCREMENT, from_id INTEGER, to_id INTEGER, rel TEXT, weight REAL DEFAULT 1.0, tag TEXT DEFAULT 'EXTRACTED', confidence REAL DEFAULT 1.0);\
             PRAGMA user_version = 2;",
        )
        .unwrap();
        c.execute(
            "INSERT INTO nodes(type,label,source) VALUES('entitas','Jakarta','llm')",
            [],
        )
        .unwrap();
        c.execute(
            "INSERT INTO nodes(type,label,source) VALUES('entitas','Indonesia','llm')",
            [],
        )
        .unwrap();
        c.execute(
            "INSERT INTO edges(from_id,to_id,rel) VALUES(1,2,'ibu kota dari')",
            [],
        )
        .unwrap();
        assert_eq!(ensure_db(&c, "graph").unwrap(), 3);
        assert_eq!(user_ver(&c), 3);
        assert!(has_column(&c, "nodes", "aliases_json"));
        assert!(has_column(&c, "edges", "fact_text"));
        assert!(has_column(&c, "edges", "episode_id"));
        let ft: String = c
            .query_row("SELECT fact_text FROM edges WHERE id=1", [], |r| r.get(0))
            .unwrap();
        assert_eq!(ft, "Jakarta ibu kota dari Indonesia");
        let rec: i64 = c
            .query_row("SELECT recorded_at FROM edges WHERE id=1", [], |r| r.get(0))
            .unwrap();
        assert!(rec > 0);
        let n: i64 = c
            .query_row(
                "SELECT COUNT(*) FROM fts_fact WHERE fts_fact MATCH 'Jakarta*'",
                [],
                |r| r.get(0),
            )
            .unwrap();
        assert_eq!(n, 1);
        let t = tables(&c);
        assert!(t.contains("episodes"));
    }

    #[test]
    fn ensure_graph_upgrade_v1_to_v3() {
        // Fixture v1: edges tanpa tag/confidence (pre-v2).
        let c = fresh_mem();
        c.execute_batch(
            "CREATE TABLE nodes (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT, label TEXT, source TEXT);\
             CREATE TABLE edges (id INTEGER PRIMARY KEY AUTOINCREMENT, from_id INTEGER, to_id INTEGER, rel TEXT, weight REAL DEFAULT 1.0);\
             PRAGMA user_version = 1;",
        )
        .unwrap();
        c.execute(
            "INSERT INTO nodes(type,label,source) VALUES('entitas','A','llm')",
            [],
        )
        .unwrap();
        c.execute(
            "INSERT INTO nodes(type,label,source) VALUES('entitas','B','llm')",
            [],
        )
        .unwrap();
        c.execute("INSERT INTO edges(from_id,to_id,rel) VALUES(1,2,'menghasilkan')", [])
            .unwrap();
        c.execute("INSERT INTO edges(from_id,to_id,rel) VALUES(1,2,'menyukai')", [])
            .unwrap();
        assert_eq!(ensure_db(&c, "graph").unwrap(), 3);
        // Aturan v2: 'menghasilkan' -> EXTRACTED/1.0, lainnya INFERRED/0.5.
        let (tag, conf): (String, f64) = c
            .query_row("SELECT tag, confidence FROM edges WHERE rel='menghasilkan'", [], |r| {
                Ok((r.get(0)?, r.get(1)?))
            })
            .unwrap();
        assert_eq!(tag, "EXTRACTED");
        assert!((conf - 1.0).abs() < 1e-9);
        let (tag2, conf2): (String, f64) = c
            .query_row("SELECT tag, confidence FROM edges WHERE rel='menyukai'", [], |r| {
                Ok((r.get(0)?, r.get(1)?))
            })
            .unwrap();
        assert_eq!(tag2, "INFERRED");
        assert!((conf2 - 0.5).abs() < 1e-9);
    }

    #[test]
    fn ensure_memory_fresh_v7() {
        let c = fresh_mem();
        assert_eq!(ensure_db(&c, "memory").unwrap(), 7);
        assert_eq!(user_ver(&c), 7);
        let t = tables(&c);
        for name in [
            "facts", "history", "reminders", "schedules", "rules", "skills",
            "geofences", "finance", "sessions", "embeddings", "goals",
            "goal_steps", "oc_tasks", "coord_rules", "workers",
            "deleted_archive", "fact_edges", "dream_flags", "lessons",
            "fact_candidates",
        ] {
            assert!(t.contains(name), "tabel {name} hilang");
        }
        // Seed sessions(1,'Utama') + kolom session di history.
        let title: String = c
            .query_row("SELECT title FROM sessions WHERE id=1", [], |r| r.get(0))
            .unwrap();
        assert_eq!(title, "Utama");
        assert!(has_column(&c, "history", "session"));
        assert!(has_column(&c, "oc_tasks", "origin_session"));
        assert!(has_column(&c, "facts", "tags"));
    }

    #[test]
    fn ensure_memory_upgrade_v6_to_v7_fixture() {
        // Fixture v6 realistis: skema Kotlin v6 — facts LAMA
        // (k TEXT PRIMARY KEY, tanpa id) + tabel v2..v6, tanpa workers.
        let c = fresh_mem();
        c.execute_batch(
            "CREATE TABLE facts(k TEXT PRIMARY KEY, v TEXT, ts INTEGER, confirmed INTEGER DEFAULT 0);\
             CREATE TABLE history(id INTEGER PRIMARY KEY AUTOINCREMENT, role TEXT, text TEXT, ts INTEGER, session INTEGER DEFAULT 1);\
             CREATE TABLE reminders(id INTEGER PRIMARY KEY AUTOINCREMENT, due INTEGER, text TEXT, done INTEGER DEFAULT 0);\
             CREATE TABLE schedules(id INTEGER PRIMARY KEY AUTOINCREMENT, hour INTEGER, minute INTEGER, prompt TEXT, enabled INTEGER DEFAULT 1, last_run_day INTEGER DEFAULT -1);\
             CREATE TABLE rules(id INTEGER PRIMARY KEY AUTOINCREMENT, pkg TEXT, needle TEXT, action TEXT, enabled INTEGER DEFAULT 1);\
             CREATE TABLE skills(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, instruction TEXT);\
             CREATE TABLE geofences(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, lat REAL, lng REAL, radius INTEGER, prompt TEXT, inside INTEGER DEFAULT 0);\
             CREATE TABLE finance(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER, pkg TEXT, amount INTEGER, kind TEXT, text TEXT);\
             CREATE TABLE sessions(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, created INTEGER);\
             CREATE TABLE embeddings(kind TEXT, ref TEXT, vec BLOB, thash INTEGER, PRIMARY KEY(kind, ref));\
             CREATE TABLE goals(id INTEGER PRIMARY KEY AUTOINCREMENT, title TEXT, status TEXT DEFAULT 'aktif', created INTEGER, updated INTEGER);\
             CREATE TABLE goal_steps(id INTEGER PRIMARY KEY AUTOINCREMENT, goal_id INTEGER, text TEXT, due INTEGER DEFAULT 0, done INTEGER DEFAULT 0, done_ts INTEGER DEFAULT 0, pos INTEGER DEFAULT 0);\
             CREATE TABLE oc_tasks(id INTEGER PRIMARY KEY AUTOINCREMENT, chain_id TEXT, text TEXT, session_id TEXT DEFAULT '', status TEXT DEFAULT 'queued', result TEXT DEFAULT '', created INTEGER, finished INTEGER DEFAULT 0);\
             CREATE TABLE coord_rules(k TEXT PRIMARY KEY, v TEXT);\
             PRAGMA user_version = 6;",
        )
        .unwrap();
        // Baris representatif (fakta lama + riwayat + tugas).
        c.execute(
            "INSERT INTO facts(k,v,ts,confirmed) VALUES('nama user','Sendy',1700000000000,1)",
            [],
        )
        .unwrap();
        c.execute(
            "INSERT INTO facts(k,v,ts,confirmed) VALUES('kota','Jakarta',1700000001000,0)",
            [],
        )
        .unwrap();
        c.execute(
            "INSERT INTO history(role,text,ts) VALUES('user','halo',1700000002000)",
            [],
        )
        .unwrap();
        c.execute(
            "INSERT INTO oc_tasks(chain_id,text,created) VALUES('c1','kerjakan X',1700000003000)",
            [],
        )
        .unwrap();
        assert_eq!(ensure_db(&c, "memory").unwrap(), 7);
        assert_eq!(user_ver(&c), 7);
        // Baris lama UTUH sesudah rebuild facts vc285.
        let (v, conf, status): (String, i64, String) = c
            .query_row("SELECT v, confirmed, status FROM facts WHERE k='nama user'", [], |r| {
                Ok((r.get(0)?, r.get(1)?, r.get(2)?))
            })
            .unwrap();
        assert_eq!(v, "Sendy");
        assert_eq!(conf, 1);
        assert_eq!(status, "active");
        let n: i64 = c
            .query_row("SELECT COUNT(*) FROM facts", [], |r| r.get(0))
            .unwrap();
        assert_eq!(n, 2);
        let t = tables(&c);
        assert!(t.contains("workers"));
        assert!(t.contains("lessons"));
        assert!(t.contains("fact_candidates"));
        assert!(t.contains("fact_edges"));
        assert!(t.contains("dream_flags"));
        assert!(t.contains("deleted_archive"));
        assert!(has_column(&c, "facts", "id"));
        assert!(has_column(&c, "facts", "tags"));
        assert!(has_column(&c, "facts", "ref_path"));
        assert!(has_column(&c, "history", "session"));
        assert!(has_column(&c, "oc_tasks", "origin_session"));
        assert!(has_column(&c, "oc_tasks", "dir") == false); // kolom dir hanya lazy per-metode, bukan onOpen
        // Riwayat + tugas utuh.
        let h: String = c
            .query_row("SELECT text FROM history WHERE role='user'", [], |r| r.get(0))
            .unwrap();
        assert_eq!(h, "halo");
    }

    #[test]
    fn ensure_unknown_key_errors() {
        let c = fresh_mem();
        assert!(ensure_db(&c, "tidak-ada").is_err());
    }

    #[test]
    fn ensure_idempotent() {
        let c = fresh_mem();
        assert_eq!(ensure_db(&c, "graph").unwrap(), 3);
        assert_eq!(ensure_db(&c, "graph").unwrap(), 3);
        assert_eq!(ensure_db(&c, "memory").unwrap(), 7);
        assert_eq!(ensure_db(&c, "memory").unwrap(), 7);
    }
}
