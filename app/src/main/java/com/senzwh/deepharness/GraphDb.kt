package com.senzwh.deepharness

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import harness.core.RustCore

// v4.48.9.21 (vc187): Memory Graph — peta di atas memori yang sudah ada.
// Transkrip, fakta, digest, skill TIDAK diganti. Tiap node menunjuk ke
// sumber mentahnya (provenance), jadi dari ringkasan bisa drill ke pesan asli.
// v4.48.9.32 (vc198 Fase 1): graph.db v3 — episodes, kolom temporal edge, FTS5 fts_fact, expire lunak.
class GraphDb private constructor(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "graph.db", null, 3) {

    // ---- Slice 7: seam rusqlite (vc322) ----
    @Volatile
    var rustGraphDb: Boolean = true
    private val dbPath: String =
        ctx.applicationContext.getDatabasePath("graph.db").absolutePath
    @Volatile
    private var ensuredRust = false

    private fun ensureRust(): Boolean {
        if (!rustGraphDb || !RustCore.available) return false
        if (ensuredRust) return true
        val ok = try {
            SqliteSeam.ensure(dbPath, "graph")
        } catch (t: Throwable) {
            false
        }
        if (!ok) {
            rustGraphDb = false
            return false
        }
        ensuredRust = true
        return true
    }


    data class Node(val id: Long, val type: String, val label: String, val source: String)
    data class Edge(val from: Long, val to: Long, val rel: String, val id: Long = -1, val tag: String = "EXTRACTED", val confidence: Double = 1.0)

    companion object {
        @Volatile private var INST: GraphDb? = null
        fun get(ctx: Context): GraphDb = INST ?: synchronized(this) {
            INST ?: GraphDb(ctx).also { INST = it }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS nodes (id INTEGER PRIMARY KEY AUTOINCREMENT, type TEXT, label TEXT, source TEXT, aliases_json TEXT DEFAULT '[]')")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_nodes_label ON nodes(label)")
        db.execSQL("CREATE TABLE IF NOT EXISTS edges (id INTEGER PRIMARY KEY AUTOINCREMENT, from_id INTEGER, to_id INTEGER, rel TEXT, weight REAL DEFAULT 1.0, tag TEXT DEFAULT 'EXTRACTED', confidence REAL DEFAULT 1.0, fact_text TEXT DEFAULT '', valid_at INTEGER, invalid_at INTEGER, recorded_at INTEGER, expired_at INTEGER, episode_id INTEGER)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_edges_from ON edges(from_id)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_edges_to ON edges(to_id)")
        db.execSQL("CREATE TABLE IF NOT EXISTS episodes (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT, role TEXT, text TEXT, source TEXT, ts INTEGER)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_episodes_sess_text ON episodes(session_id, text)")
        try { db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS fts_fact USING fts5(fact_text)") } catch (e: Exception) {}
    }

    override fun onUpgrade(db: SQLiteDatabase, oldV: Int, newV: Int) {
        if (oldV < 2) {
            try { db.execSQL("ALTER TABLE edges ADD COLUMN tag TEXT DEFAULT 'EXTRACTED'") } catch (e: Exception) {}
            try { db.execSQL("ALTER TABLE edges ADD COLUMN confidence REAL DEFAULT 1.0") } catch (e: Exception) {}
            try { db.execSQL("UPDATE edges SET tag = 'INFERRED', confidence = 0.5 WHERE rel NOT IN ('menghasilkan', 'memiliki', 'mengerjakan')") } catch (e: Exception) {}
            try { db.execSQL("UPDATE edges SET tag = 'EXTRACTED', confidence = 1.0 WHERE rel IN ('menghasilkan', 'memiliki', 'mengerjakan')") } catch (e: Exception) {}
        }
        if (oldV < 3) {
            // vc198 Fase 1: migrasi v2 ke v3. HANYA ADD COLUMN + CREATE +
            // backfill. TIDAK ADA DROP/DELETE — data nodes/edges lama utuh.
            try { db.execSQL("ALTER TABLE nodes ADD COLUMN aliases_json TEXT DEFAULT '[]'") } catch (e: Exception) {}
            try { db.execSQL("ALTER TABLE edges ADD COLUMN fact_text TEXT DEFAULT ''") } catch (e: Exception) {}
            try { db.execSQL("ALTER TABLE edges ADD COLUMN valid_at INTEGER") } catch (e: Exception) {}
            try { db.execSQL("ALTER TABLE edges ADD COLUMN invalid_at INTEGER") } catch (e: Exception) {}
            try { db.execSQL("ALTER TABLE edges ADD COLUMN recorded_at INTEGER") } catch (e: Exception) {}
            try { db.execSQL("ALTER TABLE edges ADD COLUMN expired_at INTEGER") } catch (e: Exception) {}
            try { db.execSQL("ALTER TABLE edges ADD COLUMN episode_id INTEGER") } catch (e: Exception) {}
            try { db.execSQL("CREATE TABLE IF NOT EXISTS episodes (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT, role TEXT, text TEXT, source TEXT, ts INTEGER)") } catch (e: Exception) {}
            try { db.execSQL("CREATE INDEX IF NOT EXISTS idx_episodes_sess_text ON episodes(session_id, text)") } catch (e: Exception) {}
            try { db.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS fts_fact USING fts5(fact_text)") } catch (e: Exception) {}
            // Backfill edge lama: fact_text dari label ujung, recorded_at
            // dan valid_at = waktu migrasi, invalid_at/expired_at = NULL.
            try {
                val nowMig = System.currentTimeMillis()
                val ec = db.rawQuery("SELECT id, from_id, to_id, rel FROM edges", null)
                val ids = ArrayList<Long>(); val froms = ArrayList<Long>(); val tos = ArrayList<Long>(); val rels = ArrayList<String>()
                while (ec.moveToNext()) { ids.add(ec.getLong(0)); froms.add(ec.getLong(1)); tos.add(ec.getLong(2)); rels.add(ec.getString(3) ?: "") }
                ec.close()
                for (i in ids.indices) {
                    try {
                        val fl = db.rawQuery("SELECT label FROM nodes WHERE id = ?", arrayOf(froms[i].toString()))
                        val f = if (fl.moveToFirst()) fl.getString(0) ?: "" else ""
                        fl.close()
                        val tl = db.rawQuery("SELECT label FROM nodes WHERE id = ?", arrayOf(tos[i].toString()))
                        val t = if (tl.moveToFirst()) tl.getString(0) ?: "" else ""
                        tl.close()
                        val ft = f + " " + rels[i] + " " + t
                        val cv = android.content.ContentValues().apply { put("fact_text", ft); put("recorded_at", nowMig); put("valid_at", nowMig) }
                        db.update("edges", cv, "id = ?", arrayOf(ids[i].toString()))
                        try { val fcv = android.content.ContentValues().apply { put("fact_text", ft) }; db.insert("fts_fact", null, fcv) } catch (e2: Exception) {}
                    } catch (e2: Exception) {}
                }
            } catch (e: Exception) {}
        }
        onCreate(db)
    }

    private fun getOrCreateNodeKotlin(type: String, label: String, source: String): Long {
        val clean = label.trim().take(200)
        if (clean.isBlank()) return -1
        // vc240 m14 (sisa): pencarian label case-insensitive (LOWER),
        // konsisten dengan pengecekan alias LIKE yang case-insensitive
        // untuk ASCII. Label yang tersimpan tetap memakai kapitalisasi
        // asli (tidak di-lowercase paksa) — hanya pencocokannya yang
        // dinormalisasi. Key fakta sudah dinormalisasi kelompok 1 dan
        // tidak diubah lagi di sini.
        val db = readableDatabase
        val cur = db.rawQuery("SELECT id FROM nodes WHERE LOWER(label) = LOWER(?) LIMIT 1", arrayOf(clean))
        val found = if (cur.moveToFirst()) cur.getLong(0) else -1L
        cur.close()
        if (found > 0) return found
        // vc198 Fase 3: alias — label yang pernah diputuskan SAMA dengan
        // node kanonis (via adjudikasi) mengarah ke node kanonis itu.
        // LIKE atas JSON array aliases_json; label ber-tanda kutip adalah
        // edge case yang tidak tertangani framing ini (dokumentasi).
        try {
            val esc = clean.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            val acur = db.rawQuery("SELECT id FROM nodes WHERE aliases_json LIKE ? ESCAPE '\\' LIMIT 1", arrayOf("%\"" + esc + "\"%"))
            val afound = if (acur.moveToFirst()) acur.getLong(0) else -1L
            acur.close()
            if (afound > 0) return afound
        } catch (e: Exception) {}
        val w = writableDatabase
        val values = android.content.ContentValues().apply {
            put("type", type); put("label", clean); put("source", source)
        }
        return w.insert("nodes", null, values)
    }

    private fun nodeByIdKotlin(id: Long): Node? {
        val db = readableDatabase
        val cur = db.rawQuery("SELECT id, type, label, source FROM nodes WHERE id = ?", arrayOf(id.toString()))
        val out = if (cur.moveToFirst()) Node(cur.getLong(0), cur.getString(1) ?: "", cur.getString(2) ?: "", cur.getString(3) ?: "") else null
        cur.close()
        return out
    }

    // vc198 Fase 1: fact_text + recorded_at/valid_at diisi saat insert;
    // episode_id menautkan edge ke episode sumbernya (provenance).
    // FTS disinkron manual (bukan external-content + trigger).
    private fun addEdgeKotlin(fromId: Long, toId: Long, rel: String, tag: String = "EXTRACTED", confidence: Double = 1.0, factText: String = "", episodeId: Long = -1): Long {
        if (fromId <= 0 || toId <= 0 || fromId == toId) return -1L
        val db = readableDatabase
        val cur = db.rawQuery("SELECT id FROM edges WHERE from_id = ? AND to_id = ? AND rel = ? LIMIT 1", arrayOf(fromId.toString(), toId.toString(), rel))
        val exists = cur.moveToFirst()
        cur.close()
        if (exists) return -2L
        var ft = factText
        if (ft.isBlank()) {
            val fl = nodeById(fromId)?.label ?: ""
            val tl = nodeById(toId)?.label ?: ""
            ft = fl + " " + rel + " " + tl
        }
        val nowEdge = System.currentTimeMillis()
        val w = writableDatabase
        val values = android.content.ContentValues().apply {
            put("from_id", fromId); put("to_id", toId); put("rel", rel); put("tag", tag); put("confidence", confidence)
            put("fact_text", ft); put("recorded_at", nowEdge); put("valid_at", nowEdge)
            if (episodeId > 0) put("episode_id", episodeId)
        }
        return try {
            val nid = w.insert("edges", null, values)
            if (nid > 0) syncFtsInsert(ft)
            nid
        } catch (e: Exception) {
            val fallback = android.content.ContentValues().apply {
                put("from_id", fromId); put("to_id", toId); put("rel", rel)
            }
            try {
                w.insert("edges", null, fallback)
                val rc = readableDatabase.rawQuery("SELECT id FROM edges WHERE from_id = ? AND to_id = ? AND rel = ? LIMIT 1", arrayOf(fromId.toString(), toId.toString(), rel))
                val rid = if (rc.moveToFirst()) rc.getLong(0) else -1L
                rc.close()
                rid
            } catch (e2: Exception) { -1L }
        }
    }

    private fun syncFtsInsert(factText: String) {
        if (factText.isBlank()) return
        try {
            val cv = android.content.ContentValues().apply { put("fact_text", factText) }
            writableDatabase.insert("fts_fact", null, cv)
        } catch (e: Exception) {}
    }

    private fun syncFtsDelete(factText: String) {
        if (factText.isBlank()) return
        try { writableDatabase.delete("fts_fact", "fact_text = ?", arrayOf(factText)) } catch (e: Exception) {}
    }

    private fun searchNodesKotlin(query: String, limit: Int = 20): List<Node> {
        val out = ArrayList<Node>()
        val q = query.trim()
        if (q.isBlank()) return out
        val db = readableDatabase
        // vc239 M7: prefix-search, bukan LIKE %q%. Wildcard di depan
        // membuat indeks idx_nodes_label tidak terpakai (full scan tiap
        // cari node). PERUBAHAN PERILAKU yang dicatat: pencarian
        // tengah-kata tidak lagi menemukan (mis. "akarta" tidak lagi
        // menemukan label "Jakarta"); label yang diawali query tetap
        // ditemukan, tanpa peka huruf besar/kecil untuk ASCII seperti dulu.
        val esc = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        val cur = db.rawQuery("SELECT id, type, label, source FROM nodes WHERE label LIKE ? ESCAPE '\\' ORDER BY id DESC LIMIT ?", arrayOf(esc + "%", limit.toString()))
        while (cur.moveToNext()) {
            out.add(Node(cur.getLong(0), cur.getString(1) ?: "", cur.getString(2) ?: "", cur.getString(3) ?: ""))
        }
        cur.close()
        return out
    }

    private fun neighborsKotlin(id: Long, limit: Int = 20, sinceMs: Long = 0L, untilMs: Long = Long.MAX_VALUE): List<Triple<Node, String, Node>> {
        val out = ArrayList<Triple<Node, String, Node>>()
        val db = readableDatabase
        // vc198 Fase 1: hanya edge aktif (invalid_at IS NULL).
        // vc205 T4: filter tanggal aktif mengecualikan recorded_at <= 0.
        val rangeOnNb = sinceMs > 0L || untilMs != Long.MAX_VALUE
        val cur = if (!rangeOnNb) db.rawQuery("SELECT e.to_id, e.rel FROM edges e WHERE e.from_id = ? AND e.invalid_at IS NULL LIMIT ?", arrayOf(id.toString(), limit.toString()))
        else db.rawQuery("SELECT e.to_id, e.rel FROM edges e WHERE e.from_id = ? AND e.invalid_at IS NULL AND e.recorded_at > 0 AND e.recorded_at >= ? AND e.recorded_at <= ? LIMIT ?", arrayOf(id.toString(), sinceMs.toString(), untilMs.toString(), limit.toString()))
        val rows = ArrayList<Pair<Long, String>>()
        while (cur.moveToNext()) rows.add(Pair(cur.getLong(0), cur.getString(1) ?: ""))
        cur.close()
        for ((toId, rel) in rows) {
            val to = nodeById(toId) ?: continue
            val from = nodeById(id) ?: continue
            out.add(Triple(from, rel, to))
        }
        return out
    }

    private fun countNodesKotlin(): Int {
        val db = readableDatabase
        val cur = db.rawQuery("SELECT COUNT(*) FROM nodes", null)
        val n = if (cur.moveToFirst()) cur.getInt(0) else 0
        cur.close()
        return n
    }

    private fun countEdgesKotlin(): Int {
        val db = readableDatabase
        val cur = db.rawQuery("SELECT COUNT(*) FROM edges", null)
        val n = if (cur.moveToFirst()) cur.getInt(0) else 0
        cur.close()
        return n
    }

    // v4.48.9.21: jalur antar dua node (BFS, maks 4 hop). Label dicari fuzzy.
    private fun pathKotlin(fromLabel: String, toLabel: String, sinceMs: Long = 0L, untilMs: Long = Long.MAX_VALUE): List<String> {
        val from = searchNodes(fromLabel, 1).firstOrNull() ?: return emptyList()
        val to = searchNodes(toLabel, 1).firstOrNull() ?: return emptyList()
        if (from.id == to.id) return listOf(from.label)
        val db = readableDatabase
        // vc239 M7: BFS ber-LIMIT per node — dulu path() memuat SEMUA
        // edge aktif ke memori sebelum BFS; kini tiap node yang diperluas
        // hanya menarik paling banyak 200 edge keluar (ORDER BY id,
        // deterministik) dan total node yang dikunjungi dibatasi 2000.
        // Kedalaman tetap maks 4 hop seperti dulu; filter tanggal sama.
        val rangeOnPath = sinceMs > 0L || untilMs != Long.MAX_VALUE
        val seen = HashSet<Long>(); seen.add(from.id)
        val prev = HashMap<Long, Pair<Long, String>>()
        val depthMap = HashMap<Long, Int>(); depthMap[from.id] = 0
        val queue = ArrayList<Long>(); queue.add(from.id)
        var head = 0; var found = false
        while (head < queue.size && !found && seen.size <= 2000) {
            val curId = queue[head++]
            val d = depthMap[curId] ?: 0
            if (d >= 4) continue
            val rows = ArrayList<Pair<Long, String>>()
            try {
                val ec = if (!rangeOnPath) db.rawQuery("SELECT to_id, rel FROM edges WHERE from_id = ? AND invalid_at IS NULL ORDER BY id LIMIT 200", arrayOf(curId.toString()))
                else db.rawQuery("SELECT to_id, rel FROM edges WHERE from_id = ? AND invalid_at IS NULL AND recorded_at > 0 AND recorded_at >= ? AND recorded_at <= ? ORDER BY id LIMIT 200", arrayOf(curId.toString(), sinceMs.toString(), untilMs.toString()))
                while (ec.moveToNext()) rows.add(Pair(ec.getLong(0), ec.getString(1) ?: ""))
                ec.close()
            } catch (e: Exception) {}
            for ((nb, rel) in rows) {
                if (seen.add(nb)) {
                    prev[nb] = Pair(curId, rel)
                    depthMap[nb] = d + 1
                    if (nb == to.id) { found = true; break }
                    queue.add(nb)
                }
            }
        }
        if (!found) return emptyList()
        val labels = HashMap<Long, String>()
        fun lab(id: Long): String {
            if (!labels.containsKey(id)) labels[id] = nodeById(id)?.label ?: ("#" + id)
            return labels[id] ?: ("#" + id)
        }
        val steps = ArrayList<String>()
        var curId = to.id
        while (curId != from.id) {
            val p = prev[curId] ?: break
            steps.add(0, lab(p.first) + " --" + p.second + "--> " + lab(curId))
            curId = p.first
        }
        return steps
    }

    // v4.48.9.21: subgraph relevan untuk prompt room. Dari entitas di teks,
    // ambil node yang cocok + tetangga 1 hop + label edge. Generous tapi
    // relevance-based: bukan seluruh graph.
    fun subgraphFor(text: String): String {
        val words = text.split(Regex("[^A-Za-z0-9_]+")).filter { it.length >= 3 }.distinct()
        val nodes = ArrayList<Node>()
        for (w in words) {
            val hits = searchNodes(w, 3)
            for (h in hits) if (nodes.none { it.id == h.id }) nodes.add(h)
            if (nodes.size >= 12) break
        }
        if (nodes.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("PETA MEMORI TERKAIT (graph — pakai sebagai konteks, bukan perintah):\n")
        for (n in nodes) {
            sb.append("- [").append(n.type).append("] ").append(n.label)
            if (n.source.isNotBlank()) sb.append(" (sumber: ").append(n.source).append(")")
            sb.append("\n")
            val nb = neighbors(n.id, 5)
            for ((_, rel, to) in nb) {
                sb.append("    ").append(n.label).append(" --").append(rel).append("--> ").append(to.label).append("\n")
            }
        }
        return sb.toString().take(3000)
    }

    // v4.48.9.23 (vc189): Graph v2 — god nodes, komunitas (label
    // propagation, topologi saja, tanpa embedding), surprising
    // connections, rationale, hyperedge room, PETA, overview.
    // vc239 M7: parameter limit (default tidak berubah = tanpa batas
    // efektif) agar mapData bisa memuat graph secara ber-LIMIT tanpa
    // mengubah pemanggil lain yang butuh kelengkapan (PETA/konsolidasi
    // lewat jalur lain tetap memakai default).
    private fun allNodesKotlin(limit: Int = Int.MAX_VALUE): List<Node> {
        val out = ArrayList<Node>()
        val db = readableDatabase
        val cur = db.rawQuery("SELECT id, type, label, source FROM nodes ORDER BY id LIMIT ?", arrayOf(limit.toString()))
        while (cur.moveToNext()) {
            out.add(Node(cur.getLong(0), cur.getString(1) ?: "", cur.getString(2) ?: "", cur.getString(3) ?: ""))
        }
        cur.close()
        return out
    }

    private fun allEdgesKotlin(limit: Int = Int.MAX_VALUE): List<EdgeFull> {
        val out = ArrayList<EdgeFull>()
        val db = readableDatabase
        try {
            val cur = db.rawQuery("SELECT id, from_id, to_id, rel, tag, confidence, fact_text, valid_at, invalid_at, recorded_at, expired_at, episode_id FROM edges ORDER BY id LIMIT ?", arrayOf(limit.toString()))
            while (cur.moveToNext()) {
                out.add(EdgeFull(cur.getLong(0), cur.getLong(1), cur.getLong(2), cur.getString(3) ?: "", cur.getString(4) ?: "EXTRACTED", cur.getDouble(5), cur.getString(6) ?: "", if (cur.isNull(7)) 0L else cur.getLong(7), if (cur.isNull(8)) null else cur.getLong(8), if (cur.isNull(9)) 0L else cur.getLong(9), if (cur.isNull(10)) null else cur.getLong(10), if (cur.isNull(11)) -1L else cur.getLong(11)))
            }
            cur.close()
        } catch (e: Exception) {
            val cur = db.rawQuery("SELECT id, from_id, to_id, rel FROM edges ORDER BY id LIMIT ?", arrayOf(limit.toString()))
            while (cur.moveToNext()) {
                out.add(EdgeFull(cur.getLong(0), cur.getLong(1), cur.getLong(2), cur.getString(3) ?: "", "EXTRACTED", 1.0))
            }
            cur.close()
        }
        return out
    }

    fun degreeOf(nodes: List<Node>, edges: List<EdgeFull>): Map<Long, Int> {
        val m = HashMap<Long, Int>()
        for (n in nodes) m[n.id] = 0
        for (e in edges) {
            if (m.containsKey(e.from)) m[e.from] = (m[e.from] ?: 0) + 1
            if (m.containsKey(e.to)) m[e.to] = (m[e.to] ?: 0) + 1
        }
        return m
    }

    fun godNodesFor(nodes: List<Node>, edges: List<EdgeFull>, limit: Int = 5): List<Pair<Node, Int>> {
        val deg = degreeOf(nodes, edges)
        return nodes.map { Pair(it, deg[it.id] ?: 0) }.filter { it.second > 0 }.sortedWith(compareByDescending<Pair<Node, Int>> { it.second }.thenBy { it.first.id }).take(limit)
    }

    fun communitiesFor(nodes: List<Node>, edges: List<EdgeFull>): List<List<Node>> {
        if (nodes.isEmpty()) return emptyList()
        val adj = HashMap<Long, ArrayList<Long>>()
        for (n in nodes) adj[n.id] = ArrayList()
        for (e in edges) {
            if (adj.containsKey(e.from) && adj.containsKey(e.to) && e.from != e.to) {
                if (!adj[e.from]!!.contains(e.to)) adj[e.from]!!.add(e.to)
                if (!adj[e.to]!!.contains(e.from)) adj[e.to]!!.add(e.from)
            }
        }
        val label = HashMap<Long, Long>()
        for (n in nodes) label[n.id] = n.id
        for (iter in 0 until 10) {
            var changed = false
            for (n in nodes) {
                val nb = adj[n.id] ?: continue
                if (nb.isEmpty()) continue
                val counts = HashMap<Long, Int>()
                for (x in nb) { val l = label[x] ?: continue; counts[l] = (counts[l] ?: 0) + 1 }
                if (counts.isEmpty()) continue
                val best = counts.entries.sortedWith(compareByDescending<Map.Entry<Long, Int>> { it.value }.thenBy { it.key }).first().key
                if (label[n.id] != best) { label[n.id] = best; changed = true }
            }
            if (!changed) break
        }
        val groups = HashMap<Long, ArrayList<Node>>()
        for (n in nodes) groups.getOrPut(label[n.id] ?: n.id) { ArrayList() }.add(n)
        return groups.values.sortedWith(compareByDescending<List<Node>> { it.size }.thenBy { it.firstOrNull()?.id ?: 0L })
    }

    fun surprisingFor(nodes: List<Node>, edges: List<EdgeFull>, comms: List<List<Node>>): List<Surp> {
        val commOf = HashMap<Long, Int>()
        for ((ci, grp) in comms.withIndex()) for (n in grp) commOf[n.id] = ci
        val byId = nodes.associateBy { it.id }
        val out = ArrayList<Surp>()
        for (e in edges) {
            val a = commOf[e.from]; val b = commOf[e.to]
            if (a == null || b == null || a == b) continue
            val fn = byId[e.from] ?: continue; val tn = byId[e.to] ?: continue
            val firstA = comms[a].firstOrNull()?.label ?: ""
            val firstB = comms[b].firstOrNull()?.label ?: ""
            out.add(Surp(fn.label, e.rel, tn.label, fn.label + " ada di komunitas '" + firstA + "', sedangkan " + tn.label + " ada di komunitas '" + firstB + "' — edge '" + e.rel + "' ini menjembatani dua topik berbeda."))
            if (out.size >= 5) break
        }
        return out
    }

    fun suggestedQuestionsFor(nodes: List<Node>, gods: List<Pair<Node, Int>>, comms: List<List<Node>>, surprising: List<Surp>, edges: List<EdgeFull>): List<String> {
        val out = ArrayList<String>()
        if (gods.size >= 2) out.add("Apa hubungan antara " + gods[0].first.label + " dan " + gods[1].first.label + "?")
        if (comms.isNotEmpty()) out.add("Apa saja yang ada di komunitas '" + (comms[0].firstOrNull()?.label ?: "") + "'?")
        val rat = edges.firstOrNull { it.rel == "rationale_for" }
        if (rat != null) {
            val byId = nodes.associateBy { it.id }
            val dec = byId[rat.to]?.label ?: ""
            if (dec.isNotBlank()) out.add("Kenapa " + dec + " diputuskan begitu?")
        }
        if (surprising.isNotEmpty() && comms.size >= 2) out.add("Koneksi apa yang menghubungkan komunitas '" + (comms[0].firstOrNull()?.label ?: "") + "' dan komunitas '" + (comms[1].firstOrNull()?.label ?: "") + "'?")
        val goal = nodes.firstOrNull { it.type == "goal" }
        if (goal != null) out.add("Tugas apa saja yang memengaruhi " + goal.label + "?")
        return out.take(5)
    }

    // Scope PETA per room: anggota grup room + tetangga 1 hop mereka.
    // Bila grup belum ada (belum pernah konsolidasi), pakai seluruh graph.
    fun scopedNodes(roomTitle: String, limit: Int = Int.MAX_VALUE): List<Node> {
        val all = allNodes(limit)
        if (roomTitle.isBlank()) return all
        val grp = all.firstOrNull { it.type == "grup" && it.label == "Room: " + roomTitle } ?: return all
        val members = neighbors(grp.id, 200).filter { it.second == "anggota" }.map { it.third }
        if (members.isEmpty()) return all
        val set = HashMap<Long, Node>()
        for (n in members) set[n.id] = n
        for (n in members) {
            for ((_, _, to) in neighbors(n.id, 20)) if (!set.containsKey(to.id)) set[to.id] = to
        }
        return set.values.sortedBy { it.id }
    }

    // vc245 GELOMBANG 4 (FT-M4): ada/tidaknya node grup room — dipakai
    // dialog PETA + label blok memori agar tidak lagi menampilkan peta
    // global seolah-olah peta room. Grup dibuat konsolidasi, BUKAN saat
    // room dibuat (pilihan status jujur, bukan node kosong di graph).
    fun groupExists(roomTitle: String): Boolean {
        if (roomTitle.isBlank()) return false
        return try { allNodes().any { it.type == "grup" && it.label == "Room: " + roomTitle } } catch (e: Exception) { false }
    }

    fun mapData(roomTitle: String, sinceMs: Long = 0L, untilMs: Long = Long.MAX_VALUE): MapData {
        // vc239 M7: mapData ber-LIMIT — dulu scopedNodes + allEdges
        // memuat SELURUH graph tiap overviewText/PETA; kini dibatasi
        // 2000 node dan 5000 edge (jauh di atas graph pemakaian nyata,
        // tapi menghentikan pertumbuhan tanpa batas). Pemanggil lain
        // scopedNodes (memoryCandidates K4) memakai default tak berubah.
        val nodes = scopedNodes(roomTitle, 2000)
        val ids = nodes.map { it.id }.toSet()
        val scoped = allEdges(5000).filter { ids.contains(it.from) && ids.contains(it.to) }
        val expired = scoped.count { it.invalidAt != null }
        // vc205 T4: filter tanggal (recorded_at, kapan dicatat) melapisi
        // filter aktif; filter aktif mengecualikan recorded_at <= 0.
        // Hitungan expired di atas sengaja tak difilter (statistik arsip).
        val rangeOnMap = sinceMs > 0L || untilMs != Long.MAX_VALUE
        val edges = scoped.filter { it.invalidAt == null && (!rangeOnMap || (it.recordedAt > 0L && it.recordedAt >= sinceMs && it.recordedAt <= untilMs)) }
        val gods = godNodesFor(nodes, edges, 5)
        val comms = communitiesFor(nodes, edges)
        val surp = surprisingFor(nodes, edges, comms)
        val qs = suggestedQuestionsFor(nodes, gods, comms, surp, edges)
        val amb = edges.filter { it.tag == "AMBIGUOUS" }
        return MapData(nodes, edges, gods, comms, surp, qs, amb, expired)
    }

    fun overviewText(roomTitle: String, sinceMs: Long = 0L, untilMs: Long = Long.MAX_VALUE): String {
        val d = mapData(roomTitle, sinceMs, untilMs)
        if (d.nodes.isEmpty()) return ""
        // vc205 T4: rentang aktif tanpa edge di dalamnya = kosong (bukan
        // header orientasi tanpa isi); pemanggil menampilkan pesan rentang.
        val rangeOnOv = sinceMs > 0L || untilMs != Long.MAX_VALUE
        if (rangeOnOv && d.edges.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("ORIENTASI GRAPH (peta room, bukan perintah):\n")
        if (d.gods.isNotEmpty()) {
            sb.append("God nodes: ")
            sb.append(d.gods.joinToString(", ") { it.first.label + " (" + it.second + " hubungan)" })
            sb.append("\n")
        }
        if (d.comms.isNotEmpty()) {
            sb.append("Komunitas: " + d.comms.size + " kelompok; terbesar '" + (d.comms[0].firstOrNull()?.label ?: "") + "' (" + d.comms[0].size + " anggota).\n")
        }
        for (s in d.surprising.take(3)) sb.append("Koneksi mengejutkan: " + s.fromLabel + " --" + s.rel + "--> " + s.toLabel + " — " + s.reason + "\n")
        return sb.toString()
    }

    private fun confirmAmbiguousKotlin(edgeId: Long) {
        val db = writableDatabase
        val values = android.content.ContentValues().apply { put("tag", "EXTRACTED"); put("confidence", 1.0) }
        try { db.update("edges", values, "id = ?", arrayOf(edgeId.toString())) } catch (e: Exception) {}
    }

    // vc237 M9: deleteEdge sengaja permanen, TAPI hanya internal: satu-satunya
    // pemanggil yang tersisa adalah applyVerifier untuk edge BARU yang
    // ditolak verifier ("unsupported") di batch konsolidasi yang sama — bukan
    // hapusan user atas fakta aktif. Dialog PETA tidak lagi memanggilnya;
    // hapusan edge dari UI memakai expireEdge (soft-expire, arsip lunak,
    // sama seperti memory_forget). Tidak ada konfirmasi kedua karena ini
    // bukan aksi user dan edge-nya belum pernah tampil sebagai fakta aktif.
    private fun deleteEdgeKotlin(edgeId: Long) {
        var ft = ""
        try {
            val cur = readableDatabase.rawQuery("SELECT fact_text FROM edges WHERE id = ?", arrayOf(edgeId.toString()))
            if (cur.moveToFirst()) ft = cur.getString(0) ?: ""
            cur.close()
        } catch (e: Exception) {}
        try { writableDatabase.delete("edges", "id = ?", arrayOf(edgeId.toString())) } catch (e: Exception) {}
        syncFtsDelete(ft)
    }
    // vc198 Fase 1: episode — satu baris per turn/pesan (provenance).
    // Dedup exact (session_id, text) atas teks yang dicap 4000 char.
    // Dipanggil dari hook non-blocking (Automation.appendMessage,
    // MemoryDb.addHistory) dan dari konsolidasi (sinkron, batch kecil).
    // vc240 m5: teks episode dicap 500 char — episode hanya dipakai
    // sebagai label sumber di retrieval, bukan dibaca kembali; teks
    // lengkap sudah ada di history (2000 char per pesan).
    private fun getOrCreateEpisodeKotlin(sessionId: String, role: String, text: String, source: String): Long {
        val clean = text.take(500)
        try {
            val db = readableDatabase
            val cur = db.rawQuery("SELECT id FROM episodes WHERE session_id = ? AND text = ? LIMIT 1", arrayOf(sessionId, clean))
            val found = if (cur.moveToFirst()) cur.getLong(0) else -1L
            cur.close()
            if (found > 0) return found
        } catch (e: Exception) {}
        return try {
            val cv = android.content.ContentValues().apply {
                put("session_id", sessionId); put("role", role); put("text", clean); put("source", source); put("ts", System.currentTimeMillis())
            }
            writableDatabase.insert("episodes", null, cv)
        } catch (e: Exception) { -1L }
    }

    // vc236 M24: baca episode chat 1-1 untuk konsolidasi (urut id ASC;
    // offset = jumlah episode yang sudah diproses pada run sebelumnya).
    // Episode bersifat append-only (dedup exact session+teks), sehingga
    // hitungan mark tidak pernah mundur; minOf di pemanggil menjaga bila
    // tabel menyusut.
    private fun countEpisodesKotlin(sessionId: String): Int {
        return try {
            val cur = readableDatabase.rawQuery("SELECT COUNT(*) FROM episodes WHERE session_id = ?", arrayOf(sessionId))
            val n = if (cur.moveToFirst()) cur.getInt(0) else 0
            cur.close(); n
        } catch (e: Exception) { 0 }
    }

    private fun episodesForSessionKotlin(sessionId: String, offset: Int, limit: Int): List<EpisodeRow> {
        val out = ArrayList<EpisodeRow>()
        try {
            val cur = readableDatabase.rawQuery("SELECT id, role, text, source FROM episodes WHERE session_id = ? ORDER BY id ASC LIMIT ? OFFSET ?", arrayOf(sessionId, limit.toString(), offset.toString()))
            while (cur.moveToNext()) out.add(EpisodeRow(cur.getLong(0), cur.getString(1) ?: "", cur.getString(2) ?: "", cur.getString(3) ?: ""))
            cur.close()
        } catch (e: Exception) {}
        return out
    }

    // vc237 M13: hapus episode satu session_id yang lebih tua dari cutoff
    // (dipakai archiveOldHistory untuk chat1-1 supaya episode tidak
    // menggantung tanpa baris history sumbernya).
    private fun deleteEpisodesBeforeKotlin(sessionId: String, beforeTs: Long): Int {
        return try { writableDatabase.delete("episodes", "session_id=? AND ts<?", arrayOf(sessionId, beforeTs.toString())) } catch (e: Exception) { 0 }
    }

    // vc198 Fase 1: expire = arsip lunak. Baris TIDAK dihapus; retrieval
    // aktif memfilter invalid_at IS NULL. PETA menampilkan hitungan arsip.
    // vc240 m8: "Aktifkan lagi" untuk edge ter-expire di PETA —
    // membalik expireEdge (invalid_at dan expired_at kembali NULL) agar
    // "arsip" tidak berarti nonaktif permanen tanpa jalan pulih.
    private fun unexpireEdgeKotlin(edgeId: Long) {
        try {
            val cv = android.content.ContentValues().apply { putNull("invalid_at"); putNull("expired_at") }
            writableDatabase.update("edges", cv, "id = ?", arrayOf(edgeId.toString()))
        } catch (e: Exception) {}
    }

    private fun expireEdgeKotlin(edgeId: Long) {
        try {
            val nowExp = System.currentTimeMillis()
            val cv = android.content.ContentValues().apply { put("invalid_at", nowExp); put("expired_at", nowExp) }
            writableDatabase.update("edges", cv, "id = ?", arrayOf(edgeId.toString()))
        } catch (e: Exception) {}
    }

    // vc233 K1b: cek kontradiksi graph untuk edge baru DI LUAR konsolidasi
    // room (yang sudah benar lewat applyVerifier dan tidak diubah). Aturan
    // deterministik: edge aktif dengan subjek (from_id) + relasi yang SAMA
    // tapi objek berbeda dianggap nilai lama yang digantikan nilai baru —
    // padanan graph dari CONFLICT_REPLACE fakta — jadi di-soft-expire
    // (pola expireEdge, baris tidak dihapus) SEBELUM edge baru ditambah.
    // Edge triple persis tidak disentuh di sini (addEdge mengembalikan -2).
    // Relasi banyak-nilai yang memang paralel (mis. satu subjek menyukai
    // banyak objek) tetap berisiko ikut ter-expire bila memakai relasi
    // yang sama — pemanggil memory_add hanya untuk fakta eksplisit manual.
    private fun supersedeConflictingEdgesKotlin(fromId: Long, toId: Long, rel: String): Int {
        var n = 0
        try {
            val cur = readableDatabase.rawQuery("SELECT id, to_id FROM edges WHERE from_id = ? AND rel = ? AND invalid_at IS NULL", arrayOf(fromId.toString(), rel))
            val targets = ArrayList<Pair<Long, Long>>()
            while (cur.moveToNext()) targets.add(cur.getLong(0) to cur.getLong(1))
            cur.close()
            for ((eid, oldTo) in targets) {
                if (oldTo != toId) { expireEdge(eid); n++ }
            }
        } catch (e: Exception) {}
        return n
    }

    // vc198 Fase 1: cari fakta via FTS5. Melempar bila FTS tidak tersedia
    // atau tanpa hasil — pemanggil fallback ke LIKE (searchNodes).
    private fun searchFactsKotlin(query: String, limit: Int = 20, sinceMs: Long = 0L, untilMs: Long = Long.MAX_VALUE): List<EdgeFull> {
        val tokens = query.split(Regex("[^A-Za-z0-9_]+")).filter { it.length >= 2 }.distinct()
        if (tokens.isEmpty()) return emptyList()
        val matchQ = tokens.joinToString(" OR ") { it + "*" }
        val db = readableDatabase
        val cur = db.rawQuery("SELECT fact_text FROM fts_fact WHERE fts_fact MATCH ? LIMIT ?", arrayOf(matchQ, (limit * 3).toString()))
        val facts = ArrayList<String>()
        while (cur.moveToNext()) { val f = cur.getString(0) ?: ""; if (f.isNotBlank()) facts.add(f) }
        cur.close()
        if (facts.isEmpty()) return emptyList()
        val out = ArrayList<EdgeFull>()
        for (f in facts) {
            val ec = db.rawQuery("SELECT id, from_id, to_id, rel, tag, confidence, fact_text, valid_at, invalid_at, recorded_at, expired_at, episode_id FROM edges WHERE fact_text = ? AND invalid_at IS NULL LIMIT 1", arrayOf(f))
            if (ec.moveToFirst()) {
                // vc205 T4: filter tanggal di dalam loop kandidat (bukan
                // pasca-filter) agar recall dalam rentang tetap terjaga.
                val recAt = if (ec.isNull(9)) 0L else ec.getLong(9)
                val rangeOnSf = sinceMs > 0L || untilMs != Long.MAX_VALUE
                if (!rangeOnSf || (recAt > 0L && recAt >= sinceMs && recAt <= untilMs)) out.add(EdgeFull(ec.getLong(0), ec.getLong(1), ec.getLong(2), ec.getString(3) ?: "", ec.getString(4) ?: "EXTRACTED", ec.getDouble(5), ec.getString(6) ?: "", if (ec.isNull(7)) 0L else ec.getLong(7), if (ec.isNull(8)) null else ec.getLong(8), recAt, if (ec.isNull(10)) null else ec.getLong(10), if (ec.isNull(11)) -1L else ec.getLong(11)))
            }
            ec.close()
            if (out.size >= limit) break
        }
        return out
    }
    // vc198 Fase 2: cari edge berdasarkan id (untuk lookup kontradiksi).
    private fun edgeByIdKotlin(edgeId: Long): EdgeFull? {
        return try {
            val cur = readableDatabase.rawQuery("SELECT id, from_id, to_id, rel, tag, confidence, fact_text, valid_at, invalid_at, recorded_at, expired_at, episode_id FROM edges WHERE id = ? LIMIT 1", arrayOf(edgeId.toString()))
            val out = if (cur.moveToFirst()) EdgeFull(cur.getLong(0), cur.getLong(1), cur.getLong(2), cur.getString(3) ?: "", cur.getString(4) ?: "EXTRACTED", cur.getDouble(5), cur.getString(6) ?: "", if (cur.isNull(7)) 0L else cur.getLong(7), if (cur.isNull(8)) null else cur.getLong(8), if (cur.isNull(9)) 0L else cur.getLong(9), if (cur.isNull(10)) null else cur.getLong(10), if (cur.isNull(11)) -1L else cur.getLong(11)) else null
            cur.close()
            out
        } catch (e: Exception) { null }
    }

    // vc198 Fase 2: ubah tag edge. confidence null = tidak diubah.
    private fun setEdgeTagKotlin(edgeId: Long, tag: String, confidence: Double? = null) {
        try {
            val cv = android.content.ContentValues().apply {
                put("tag", tag)
                if (confidence != null) put("confidence", confidence)
            }
            writableDatabase.update("edges", cv, "id = ?", arrayOf(edgeId.toString()))
        } catch (e: Exception) {}
    }

    // vc198 Fase 2: edge aktif yang menyentuh salah satu label (untuk
    // daftar kontradiksi di prompt verifier). JOIN label, cap 30.
    private fun activeEdgesTouchingKotlin(labels: Set<String>): List<EdgeFull> {
        if (labels.isEmpty()) return emptyList()
        val labs = labels.take(24)
        val ph = labs.joinToString(",") { "?" }
        val out = ArrayList<EdgeFull>()
        try {
            val cur = readableDatabase.rawQuery("SELECT e.id, e.from_id, e.to_id, e.rel, e.tag, e.confidence, e.fact_text, e.valid_at, e.invalid_at, e.recorded_at, e.expired_at, e.episode_id FROM edges e JOIN nodes nf ON nf.id = e.from_id JOIN nodes nt ON nt.id = e.to_id WHERE e.invalid_at IS NULL AND (nf.label IN (" + ph + ") OR nt.label IN (" + ph + ")) ORDER BY e.id DESC LIMIT 30", (labs + labs).toTypedArray())
            while (cur.moveToNext()) {
                out.add(EdgeFull(cur.getLong(0), cur.getLong(1), cur.getLong(2), cur.getString(3) ?: "", cur.getString(4) ?: "EXTRACTED", cur.getDouble(5), cur.getString(6) ?: "", if (cur.isNull(7)) 0L else cur.getLong(7), if (cur.isNull(8)) null else cur.getLong(8), if (cur.isNull(9)) 0L else cur.getLong(9), if (cur.isNull(10)) null else cur.getLong(10), if (cur.isNull(11)) -1L else cur.getLong(11)))
            }
            cur.close()
        } catch (e: Exception) {}
        return out
    }

    // vc198 Fase 2: terapkan hasil verifier. Melempar bila JSON tidak bisa
    // diparse — pemanggil menangkap dan MELEWATI verifier (konsolidasi
    // tidak gagal). EXTRACTED tidak pernah masuk sini: newEdges hanya
    // berisi edge INFERRED baru dari batch relations (lihat MainActivity).
    fun applyVerifier(answer: String, newEdges: List<NewEdge>): String {
        val start = answer.indexOf("{"); val end = answer.lastIndexOf("}")
        if (start < 0 || end <= start) throw IllegalArgumentException("tidak ada JSON verifier")
        val obj = org.json.JSONObject(answer.substring(start, end + 1))
        val results = obj.optJSONArray("results") ?: throw IllegalArgumentException("tidak ada array results")
        val byKey = newEdges.associateBy { it.fromLabel + "|" + it.rel + "|" + it.toLabel }
        var ok = 0; var part = 0; var del = 0; var exp = 0
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            val ne = byKey[r.optString("edge_key")] ?: continue
            when (r.optString("verdict")) {
                "unsupported" -> { deleteEdge(ne.id); del++ }
                "partial" -> { setEdgeTag(ne.id, "AMBIGUOUS"); part++ }
                "supported" -> { ok++ }
                else -> { }
            }
            val contra = r.optString("contradicts")
            if (contra.startsWith("old:")) {
                val oldId = contra.removePrefix("old:").toLongOrNull()
                if (oldId != null && oldId != ne.id) {
                    val old = edgeById(oldId)
                    if (old != null && old.invalidAt == null) { expireEdge(oldId); exp++ }
                }
            }
        }
        return ok.toString() + " didukung, " + part + " sebagian (AMBIGUOUS), " + del + " dibuang, " + exp + " lama di-expire."
    }
    // vc198 Fase 3: cari node berdasar label persis (untuk deteksi node
    // baru sebelum getOrCreateNode, dipakai koleksi kandidat resolusi).
    private fun nodeByLabelKotlin(label: String): Node? {
        val clean = label.trim().take(200)
        if (clean.isBlank()) return null
        return try {
            val cur = readableDatabase.rawQuery("SELECT id, type, label, source FROM nodes WHERE LOWER(label) = LOWER(?) LIMIT 1", arrayOf(clean))
            val out = if (cur.moveToFirst()) Node(cur.getLong(0), cur.getString(1) ?: "", cur.getString(2) ?: "", cur.getString(3) ?: "") else null
            cur.close()
            out
        } catch (e: Exception) { null }
    }

    fun normLabel(label: String): String {
        return label.lowercase().trim().replace(Regex("[^a-z0-9 ]+"), " ").replace(Regex("\\s+"), " ").trim()
    }

    // vc198 Fase 3: kandidat fuzzy untuk label baru — via LIKE per token
    // + filter kemiripan normalisasi (substring atau token bersama).
    // TIDAK merge otomatis; hasil masuk adjudikasi LLM per batch.
    fun fuzzyCandidates(label: String): List<Node> {
        val norm = normLabel(label)
        if (norm.isBlank()) return emptyList()
        val tokens = norm.split(" ").filter { it.length >= 3 }.distinct()
        if (tokens.isEmpty()) return emptyList()
        val out = ArrayList<Node>()
        for (tok in tokens) {
            for (n in searchNodes(tok, 5)) {
                if (n.label.trim().take(200) == label.trim().take(200)) continue
                if (out.none { it.id == n.id }) out.add(n)
            }
        }
        return out.filter { c ->
            val cn = normLabel(c.label)
            cn.isNotBlank() && (cn.contains(norm) || norm.contains(cn) || cn.split(" ").intersect(tokens.toSet()).isNotEmpty())
        }.take(10)
    }

    private fun addAliasKotlin(nodeId: Long, alias: String) {
        val clean = alias.trim().take(200)
        if (clean.isBlank() || nodeId <= 0) return
        try {
            val db = readableDatabase
            val cur = db.rawQuery("SELECT aliases_json FROM nodes WHERE id = ? LIMIT 1", arrayOf(nodeId.toString()))
            val raw = if (cur.moveToFirst()) cur.getString(0) ?: "[]" else "[]"
            cur.close()
            val arr = try { org.json.JSONArray(raw) } catch (e: Exception) { org.json.JSONArray() }
            for (i in 0 until arr.length()) if (arr.optString(i) == clean) return
            arr.put(clean)
            val cv = android.content.ContentValues().apply { put("aliases_json", arr.toString()) }
            writableDatabase.update("nodes", cv, "id = ?", arrayOf(nodeId.toString()))
        } catch (e: Exception) {}
    }

    private fun episodeSourceKotlin(episodeId: Long): String {
        if (episodeId <= 0) return "graph"
        return try {
            val cur = readableDatabase.rawQuery("SELECT source FROM episodes WHERE id = ? LIMIT 1", arrayOf(episodeId.toString()))
            val out = if (cur.moveToFirst()) cur.getString(0) ?: "graph" else "graph"
            cur.close()
            if (out.isBlank()) "graph" else out
        } catch (e: Exception) { "graph" }
    }

    // vc198 Fase 3: edge keluar aktif satu node (dengan id + fact_text +
    // temporal), untuk traversal 2 hop [MEMORI]. neighbors() 1 hop tool
    // TIDAK diubah.
    private fun outEdgesKotlin(id: Long, limit: Int = 20): List<EdgeFull> {
        val out = ArrayList<EdgeFull>()
        try {
            val cur = readableDatabase.rawQuery("SELECT id, from_id, to_id, rel, tag, confidence, fact_text, valid_at, invalid_at, recorded_at, expired_at, episode_id FROM edges WHERE from_id = ? AND invalid_at IS NULL LIMIT ?", arrayOf(id.toString(), limit.toString()))
            while (cur.moveToNext()) {
                out.add(EdgeFull(cur.getLong(0), cur.getLong(1), cur.getLong(2), cur.getString(3) ?: "", cur.getString(4) ?: "EXTRACTED", cur.getDouble(5), cur.getString(6) ?: "", if (cur.isNull(7)) 0L else cur.getLong(7), if (cur.isNull(8)) null else cur.getLong(8), if (cur.isNull(9)) 0L else cur.getLong(9), if (cur.isNull(10)) null else cur.getLong(10), if (cur.isNull(11)) -1L else cur.getLong(11)))
            }
            cur.close()
        } catch (e: Exception) {}
        return out
    }

    // vc198 Fase 3: kandidat fakta untuk blok [MEMORI] — FTS5 atas teks
    // + traversal 2 hop dari node benih. HANYA edge aktif.
    // vc234 K4: roomTitle kini DIPAKAI. Bila node grup "Room: <title>"
    // ada (dibuat konsolidasi room), kandidat difilter ke subgraph room
    // itu (pola scopedNodes yang sama dengan mapData: kedua ujung edge
    // harus anggota subgraph). Bila grup belum ada, scopedNodes
    // mengembalikan seluruh graph dan kandidat tetap global — perilaku
    // lama yang terdokumentasi di scopedNodes, bukan filter diam-diam;
    // sumber per fakta tetap tampil lewat memoryBlock.
    fun memoryCandidates(text: String, roomTitle: String, limit: Int = 20): List<Fact> {
        val out = ArrayList<Fact>()
        val seenEdges = HashSet<Long>()
        val seenFacts = HashSet<String>()
        val scopedIds: Set<Long>? = if (roomTitle.isBlank()) null else try {
            val nodes = scopedNodes(roomTitle)
            val grpExists = nodes.any { it.type == "grup" && it.label == "Room: " + roomTitle }
            if (grpExists) nodes.map { it.id }.toSet() else null
        } catch (e: Exception) { null }
        fun addEdgeFact(e: EdgeFull): Boolean {
            if (scopedIds != null && !(scopedIds.contains(e.from) && scopedIds.contains(e.to))) return false
            if (e.id > 0 && !seenEdges.add(e.id)) return false
            var ft = e.factText
            if (ft.isBlank()) {
                val fl = nodeById(e.from)?.label ?: ""
                val tl = nodeById(e.to)?.label ?: ""
                ft = fl + " " + e.rel + " " + tl
            }
            if (ft.isBlank() || !seenFacts.add(ft)) return false
            out.add(Fact(ft, e.recordedAt, episodeSource(e.episodeId), e.tag, e.confidence))
            return true
        }
        try {
            for (e in searchFacts(text, limit)) addEdgeFact(e)
        } catch (e: Exception) {}
        val words = text.split(Regex("[^A-Za-z0-9_]+")).filter { it.length >= 3 }.distinct()
        val seeds = ArrayList<Node>()
        for (w in words) {
            for (h in searchNodes(w, 3)) if (seeds.none { it.id == h.id }) seeds.add(h)
            if (seeds.size >= 12) break
        }
        val hop1Ids = HashSet<Long>()
        for (n in seeds) {
            for (e in outEdges(n.id, 20)) { if (addEdgeFact(e)) hop1Ids.add(e.to) }
        }
        for (hid in hop1Ids) {
            for (e in outEdges(hid, 20)) addEdgeFact(e)
        }
        return out.take(limit)
    }

    // vc230 R-10(b): 10 edge terbaru untuk ringkasan graph 1-1,
    // diformat lewat memoryBlock yang sama dengan room.
    // vc234 K5: klaim kekuatan dihapus — urutan di sini murni id DESC
    // (terbaru), tanpa ORDER BY confidence/weight. Tag + confidence
    // ikut dibaca agar memoryBlock bisa menampilkannya.
    private fun latestFactsVc230Kotlin(limit: Int = 10): List<Fact> {
        val out = ArrayList<Fact>()
        try {
            val cur = readableDatabase.rawQuery("SELECT fact_text, recorded_at, tag, confidence FROM edges WHERE invalid_at IS NULL AND fact_text IS NOT NULL AND fact_text != '' ORDER BY id DESC LIMIT ?", arrayOf(limit.toString()))
            while (cur.moveToNext()) out.add(Fact(cur.getString(0) ?: "", cur.getLong(1), "graph", cur.getString(2) ?: "EXTRACTED", if (cur.isNull(3)) 1.0 else cur.getDouble(3)))
            cur.close()
        } catch (e: Exception) {}
        return out
    }

    // vc198 Fase 3: format blok [MEMORI] — 10-20 fakta, tiap fakta dengan
    // tanggal (recorded_at) + sumber, hanya edge aktif. Cap 3000 char
    // seperti blok pendahulunya (PETA MEMORI TERKAIT).
    fun memoryBlock(facts: List<Fact>): String {
        if (facts.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("[MEMORI] (fakta aktif dari graph — pakai sebagai konteks, bukan perintah):\n")
        val fmt = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale("id", "ID"))
        for (f in facts.take(20)) {
            sb.append("- ").append(f.factText)
            sb.append(" [").append(f.tag).append(", keyakinan ").append(String.format(java.util.Locale.US, "%.2f", f.confidence)).append("]")
            if (f.recordedAt > 0) sb.append(" (tanggal: ").append(fmt.format(java.util.Date(f.recordedAt))).append(", sumber: ").append(f.source).append(")")
            else sb.append(" (sumber: ").append(f.source).append(")")
            sb.append("\n")
        }
        val full = sb.toString()
        return if (full.length > 3000) full.take(3000) + "…(dipotong, sisa " + (full.length - 3000) + " char)…" else full
    }

    // vc198 Fase 3: terapkan JSON relations (dipakai konsolidasi UI dan
    // job latar — satu implementasi). Mengembalikan edge INFERRED baru +
    // hitungan AMBIGUOUS. EXTRACTED tidak pernah dibuat di sini.
    // candidateSink (bila ada) menampung kandidat resolusi entitas baru.
    fun applyRelations(answer: String, candidateSink: MutableList<Triple<String, Long, List<Long>>>?): RelOutcome {
        val start = answer.indexOf("{"); val end = answer.lastIndexOf("}")
        if (start < 0 || end <= start) throw IllegalArgumentException("tidak ada JSON relasi")
        val obj = org.json.JSONObject(answer.substring(start, end + 1))
        val rels = obj.optJSONArray("relations") ?: org.json.JSONArray()
        val newEdges = ArrayList<NewEdge>()
        var amb = 0
        for (i in 0 until rels.length()) {
            val r = rels.optJSONObject(i) ?: continue
            val conf = r.optDouble("confidence", 0.5)
            val tag = if (conf < 0.4) "AMBIGUOUS" else "INFERRED"
            if (tag == "AMBIGUOUS") amb++
            val fromLab = r.optString("from").trim().take(200)
            val toLab = r.optString("to").trim().take(200)
            val rel = r.optString("relation", "terkait")
            if (fromLab.isBlank() || toLab.isBlank()) continue
            val fromExisted = nodeByLabel(fromLab) != null
            val f = getOrCreateNode("entitas", fromLab, "llm")
            if (!fromExisted && f > 0 && candidateSink != null) {
                val c = fuzzyCandidates(fromLab).filter { it.id != f }
                if (c.isNotEmpty()) candidateSink.add(Triple(fromLab, f, c.map { it.id }))
            }
            val toExisted = nodeByLabel(toLab) != null
            val t2 = getOrCreateNode("entitas", toLab, "llm")
            if (!toExisted && t2 > 0 && candidateSink != null) {
                val c = fuzzyCandidates(toLab).filter { it.id != t2 }
                if (c.isNotEmpty()) candidateSink.add(Triple(toLab, t2, c.map { it.id }))
            }
            if (f > 0 && t2 > 0) {
                val eid = addEdge(f, t2, rel, tag, conf)
                if (eid > 0 && tag == "INFERRED") newEdges.add(NewEdge(eid, fromLab, rel, toLab))
            }
        }
        return RelOutcome(newEdges, amb)
    }

    // vc198 Fase 3: terapkan adjudikasi resolusi. canonical_id HARUS salah
    // satu kandidat yang ditawarkan — bila tidak, diabaikan (LLM tidak
    // boleh menciptakan merge). Same -> alias di node kanonis; edge TIDAK
    // dipindahkan di build ini (ditunda). Different/ragu -> node baru
    // (sudah dibuat saat ekstraksi) dibiarkan.
    // vc240 M11 (pilihan: PINDAHKAN edge, bukan sembunyikan alias):
    // saat adjudikasi memutuskan label baru SAMA dengan node kanonis,
    // semua edge node alias dialihkan ke node kanonis (from dan to);
    // bila triple hasil pengalihan sudah ada, edge lama dihapus agar
    // tidak ganda. Alias tetap ditambahkan seperti sebelumnya, dan
    // fact_text dihitung ulang dari label kanonis agar traversal dan
    // PETA tidak lagi menampilkan node duplikat sebagai entitas terpisah.
    private fun rewireNodeEdgesKotlin(oldId: Long, canonId: Long) {
        if (oldId <= 0 || canonId <= 0 || oldId == canonId) return
        try {
            val db = readableDatabase
            val cur = db.rawQuery("SELECT id, from_id, to_id, rel FROM edges WHERE from_id = ? OR to_id = ?", arrayOf(oldId.toString(), oldId.toString()))
            val rows = ArrayList<Triple<Long, Long, Long>>()
            val rels = HashMap<Long, String>()
            while (cur.moveToNext()) { rows.add(Triple(cur.getLong(0), cur.getLong(1), cur.getLong(2))); rels[cur.getLong(0)] = cur.getString(3) ?: "" }
            cur.close()
            for ((eid, f, t) in rows) {
                val nf = if (f == oldId) canonId else f
                val nt = if (t == oldId) canonId else t
                if (nf == nt) { writableDatabase.delete("edges", "id = ?", arrayOf(eid.toString())); continue }
                val rel = rels[eid] ?: ""
                val dup = readableDatabase.rawQuery("SELECT id FROM edges WHERE from_id = ? AND to_id = ? AND rel = ? LIMIT 1", arrayOf(nf.toString(), nt.toString(), rel))
                val hasDup = dup.moveToFirst()
                dup.close()
                if (hasDup) { writableDatabase.delete("edges", "id = ?", arrayOf(eid.toString())); continue }
                val fl = nodeById(nf)?.label ?: ""
                val tl = nodeById(nt)?.label ?: ""
                val cv = android.content.ContentValues().apply { put("from_id", nf); put("to_id", nt); put("fact_text", fl + " " + rel + " " + tl) }
                writableDatabase.update("edges", cv, "id = ?", arrayOf(eid.toString()))
            }
        } catch (e: Exception) {}
    }

    fun applyAdjudications(answer: String, candidates: List<Triple<String, Long, List<Long>>>): String {
        val start = answer.indexOf("{"); val end = answer.lastIndexOf("}")
        if (start < 0 || end <= start) throw IllegalArgumentException("tidak ada JSON adjudikasi")
        val obj = org.json.JSONObject(answer.substring(start, end + 1))
        val decisions = obj.optJSONArray("decisions") ?: throw IllegalArgumentException("tidak ada array decisions")
        val byLabel = candidates.associateBy { it.first }
        var added = 0
        for (i in 0 until decisions.length()) {
            val d = decisions.optJSONObject(i) ?: continue
            if (d.isNull("canonical_id")) continue
            val canon = d.optLong("canonical_id", -1)
            if (canon <= 0) continue
            val cand = byLabel[d.optString("label")] ?: continue
            if (!cand.third.contains(canon)) continue
            if (canon == cand.second) continue
            addAlias(canon, cand.first)
            rewireNodeEdges(cand.second, canon)
            added++
        }
        return added.toString() + " alias ditambahkan."
    }

    // vc198 Fase 3: builder prompt bersama (UI + job latar) agar teks
    // prompt tidak divergen antar jalur.
    fun relationsPrompt(transcript: String): String {
        return "Ekstrak hubungan dari diskusi berikut sebagai JSON SATU BARIS: {\"relations\":[{\"from\":\"...\",\"relation\":\"...\",\"to\":\"...\",\"confidence\":0.5}]}. Hanya JSON, tanpa teks lain.\n\n" + transcript
    }

    fun verifierPrompt(newEdges: List<NewEdge>, oldListText: String, transcript: String): String {
        val newList = newEdges.joinToString("\n") { it.fromLabel + " | " + it.rel + " | " + it.toLabel }
        return "Verifikasi hubungan BARU berikut terhadap transkrip diskusi. Untuk tiap hubungan baru jawab verdict: supported (didukung transkrip), partial (sebagian didukung / meragukan), unsupported (tidak didukung). Bila hubungan baru BERTENTANGAN dengan hubungan LAMA yang masih aktif, isi contradicts dengan id lama (format old:<id>), bila tidak null. Kembalikan JSON SATU BARIS saja: {\"results\":[{\"edge_key\":\"<from>|<rel>|<to>\",\"verdict\":\"supported|partial|unsupported\",\"contradicts\":null}]}. Hanya JSON, tanpa teks lain.\n\nHUBUNGAN BARU:\n" + newList + "\n\nHUBUNGAN LAMA AKTIF (untuk cek kontradiksi):\n" + oldListText + "\n\nTRANSKRIP:\n" + transcript
    }

    fun adjudicationPrompt(cands: List<Triple<String, Long, List<Long>>>): String {
        val sb = StringBuilder()
        sb.append("Tentukan apakah label BARU berikut merujuk ke entitas yang SAMA dengan salah satu kandidat yang sudah ada, atau BERBEDA. Kembalikan JSON SATU BARIS saja: {\"decisions\":[{\"label\":\"<label baru>\",\"canonical_id\":123}]}. canonical_id = id kandidat yang sama, atau null bila berbeda/ragu. Hanya JSON, tanpa teks lain.\n\nKANDIDAT:\n")
        for ((lab, nid, ids) in cands.take(30)) {
            val parts = ArrayList<String>()
            for (cid in ids) { val cl = nodeById(cid)?.label ?: ("#" + cid); parts.add(cid.toString() + " \"" + cl + "\"") }
            sb.append("BARU \"").append(lab).append("\" (node ").append(nid).append("): kandidat: ").append(parts.joinToString(", ")).append("\n")
        }
        return sb.toString()
    }
    // ---- Slice 7: jalur Rust (seam rusqlite); gagal -> *Kotlin ----
    private fun nodeFromRow(row: List<Any?>): Node = Node(
        SqliteSeam.rowLong(row, 0), SqliteSeam.rowStr(row, 1),
        SqliteSeam.rowStr(row, 2), SqliteSeam.rowStr(row, 3)
    )

    private fun edgeFullFromRow(row: List<Any?>): EdgeFull = EdgeFull(
        SqliteSeam.rowLong(row, 0), SqliteSeam.rowLong(row, 1),
        SqliteSeam.rowLong(row, 2), SqliteSeam.rowStr(row, 3),
        SqliteSeam.rowStr(row, 4).ifBlank { "EXTRACTED" },
        SqliteSeam.rowDouble(row, 5), SqliteSeam.rowStr(row, 6),
        SqliteSeam.rowLongOrNull(row, 7) ?: 0L,
        SqliteSeam.rowLongOrNull(row, 8),
        SqliteSeam.rowLongOrNull(row, 9) ?: 0L,
        SqliteSeam.rowLongOrNull(row, 10),
        SqliteSeam.rowLongOrNull(row, 11) ?: -1L
    )

    private val EDGE_SELECT = "SELECT id, from_id, to_id, rel, tag, confidence, fact_text, " +
        "valid_at, invalid_at, recorded_at, expired_at, episode_id FROM edges"

    fun getOrCreateNode(type: String, label: String, source: String): Long {
        if (ensureRust()) {
            val clean = label.trim().take(200)
            if (clean.isBlank()) return -1
            val q1 = SqliteSeam.query(
                dbPath,
                "SELECT id FROM nodes WHERE LOWER(label) = LOWER(?) LIMIT 1", listOf(clean)
            )
            if (q1 == null) {
                rustGraphDb = false
                return getOrCreateNodeKotlin(type, label, source)
            }
            if (q1.isNotEmpty()) return SqliteSeam.rowLong(q1[0], 0)
            val esc = clean.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            val q2 = SqliteSeam.query(
                dbPath,
                "SELECT id FROM nodes WHERE aliases_json LIKE ? ESCAPE '\\' LIMIT 1",
                listOf("%\"" + esc + "\"%")
            )
            if (q2 == null) {
                rustGraphDb = false
                return getOrCreateNodeKotlin(type, label, source)
            }
            if (q2.isNotEmpty()) return SqliteSeam.rowLong(q2[0], 0)
            val ins = SqliteSeam.exec(
                dbPath,
                "INSERT INTO nodes(type, label, source) VALUES(?,?,?)",
                listOf(type, clean, source)
            )
            if (ins != null) return ins.lastId
            rustGraphDb = false
        }
        return getOrCreateNodeKotlin(type, label, source)
    }

    fun nodeById(id: Long): Node? {
        if (ensureRust()) {
            val rows = SqliteSeam.query(
                dbPath,
                "SELECT id, type, label, source FROM nodes WHERE id = ?", listOf(id.toString())
            )
            if (rows != null) {
                return if (rows.isEmpty()) null else nodeFromRow(rows[0])
            }
            rustGraphDb = false
        }
        return nodeByIdKotlin(id)
    }

    fun addEdge(fromId: Long, toId: Long, rel: String, tag: String = "EXTRACTED", confidence: Double = 1.0, factText: String = "", episodeId: Long = -1): Long {
        if (ensureRust()) {
            if (fromId <= 0 || toId <= 0 || fromId == toId) return -1L
            val q = SqliteSeam.query(
                dbPath,
                "SELECT id FROM edges WHERE from_id = ? AND to_id = ? AND rel = ? LIMIT 1",
                listOf(fromId.toString(), toId.toString(), rel)
            )
            if (q == null) {
                rustGraphDb = false
                return addEdgeKotlin(fromId, toId, rel, tag, confidence, factText, episodeId)
            }
            if (q.isNotEmpty()) return -2L
            var ft = factText
            if (ft.isBlank()) {
                val fl = nodeById(fromId)?.label ?: ""
                val tl = nodeById(toId)?.label ?: ""
                ft = fl + " " + rel + " " + tl
            }
            val nowEdge = System.currentTimeMillis()
            // INSERT ... + FTS sync dalam satu transaksi (padanan insert +
            // syncFtsInsert Kotlin; bila tx gagal -> fallback Kotlin yang
            // menjalankan jalur fallback minimalnya).
            val steps = ArrayList<Pair<String, List<Any?>>>()
            if (episodeId > 0) {
                steps.add(
                    "INSERT INTO edges(from_id, to_id, rel, tag, confidence, fact_text, recorded_at, valid_at, episode_id) VALUES(?,?,?,?,?,?,?,?,?)" to
                        listOf(fromId, toId, rel, tag, confidence, ft, nowEdge, nowEdge, episodeId)
                )
            } else {
                steps.add(
                    "INSERT INTO edges(from_id, to_id, rel, tag, confidence, fact_text, recorded_at, valid_at) VALUES(?,?,?,?,?,?,?,?)" to
                        listOf(fromId, toId, rel, tag, confidence, ft, nowEdge, nowEdge)
                )
            }
            if (ft.isNotBlank()) {
                steps.add(
                    "INSERT INTO fts_fact(fact_text) VALUES(?)" to listOf(ft)
                )
            }
            val res = SqliteSeam.tx(dbPath, steps)
            if (res != null && res.isNotEmpty()) return res[0].lastId
            rustGraphDb = false
        }
        return addEdgeKotlin(fromId, toId, rel, tag, confidence, factText, episodeId)
    }

    fun searchNodes(query: String, limit: Int = 20): List<Node> {
        if (ensureRust()) {
            val q = query.trim()
            if (q.isBlank()) return ArrayList()
            val esc = q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            val rows = SqliteSeam.query(
                dbPath,
                "SELECT id, type, label, source FROM nodes WHERE label LIKE ? ESCAPE '\\' ORDER BY id DESC LIMIT ?",
                listOf(esc + "%", limit.toString())
            )
            if (rows != null) return rows.map { nodeFromRow(it) }
            rustGraphDb = false
        }
        return searchNodesKotlin(query, limit)
    }

    fun neighbors(id: Long, limit: Int = 20, sinceMs: Long = 0L, untilMs: Long = Long.MAX_VALUE): List<Triple<Node, String, Node>> {
        if (ensureRust()) {
            val rangeOnNb = sinceMs > 0L || untilMs != Long.MAX_VALUE
            val rows = if (!rangeOnNb) {
                SqliteSeam.query(
                    dbPath,
                    "SELECT e.to_id, e.rel FROM edges e WHERE e.from_id = ? AND e.invalid_at IS NULL LIMIT ?",
                    listOf(id.toString(), limit.toString())
                )
            } else {
                SqliteSeam.query(
                    dbPath,
                    "SELECT e.to_id, e.rel FROM edges e WHERE e.from_id = ? AND e.invalid_at IS NULL AND e.recorded_at > 0 AND e.recorded_at >= ? AND e.recorded_at <= ? LIMIT ?",
                    listOf(id.toString(), sinceMs.toString(), untilMs.toString(), limit.toString())
                )
            }
            if (rows != null) {
                val out = ArrayList<Triple<Node, String, Node>>()
                for (r in rows) {
                    val to = nodeById(SqliteSeam.rowLong(r, 0)) ?: continue
                    val from = nodeById(id) ?: continue
                    out.add(Triple(from, SqliteSeam.rowStr(r, 1), to))
                }
                return out
            }
            rustGraphDb = false
        }
        return neighborsKotlin(id, limit, sinceMs, untilMs)
    }

    private fun countRust(sql: String): Int? {
        val rows = SqliteSeam.query(dbPath, sql, emptyList())
        if (rows != null && rows.isNotEmpty()) return SqliteSeam.rowInt(rows[0], 0)
        return null
    }

    fun countNodes(): Int {
        if (ensureRust()) {
            val n = countRust("SELECT COUNT(*) FROM nodes")
            if (n != null) return n
            rustGraphDb = false
        }
        return countNodesKotlin()
    }

    fun countEdges(): Int {
        if (ensureRust()) {
            val n = countRust("SELECT COUNT(*) FROM edges")
            if (n != null) return n
            rustGraphDb = false
        }
        return countEdgesKotlin()
    }

    fun path(fromLabel: String, toLabel: String, sinceMs: Long = 0L, untilMs: Long = Long.MAX_VALUE): List<String> {
        if (ensureRust()) {
            val from = searchNodes(fromLabel, 1).firstOrNull() ?: return emptyList()
            val to = searchNodes(toLabel, 1).firstOrNull() ?: return emptyList()
            if (from.id == to.id) return listOf(from.label)
            val rangeOnPath = sinceMs > 0L || untilMs != Long.MAX_VALUE
            val seen = HashSet<Long>(); seen.add(from.id)
            val prev = HashMap<Long, Pair<Long, String>>()
            val depthMap = HashMap<Long, Int>(); depthMap[from.id] = 0
            val queue = ArrayList<Long>(); queue.add(from.id)
            var head = 0; var found = false
            while (head < queue.size && !found && seen.size <= 2000) {
                val curId = queue[head++]
                val d = depthMap[curId] ?: 0
                if (d >= 4) continue
                val rows = if (!rangeOnPath) {
                    SqliteSeam.query(
                        dbPath,
                        "SELECT to_id, rel FROM edges WHERE from_id = ? AND invalid_at IS NULL ORDER BY id LIMIT 200",
                        listOf(curId.toString())
                    )
                } else {
                    SqliteSeam.query(
                        dbPath,
                        "SELECT to_id, rel FROM edges WHERE from_id = ? AND invalid_at IS NULL AND recorded_at > 0 AND recorded_at >= ? AND recorded_at <= ? ORDER BY id LIMIT 200",
                        listOf(curId.toString(), sinceMs.toString(), untilMs.toString())
                    )
                }
                if (rows == null) {
                    rustGraphDb = false
                    return pathKotlin(fromLabel, toLabel, sinceMs, untilMs)
                }
                for (r in rows) {
                    val nb = SqliteSeam.rowLong(r, 0)
                    val rel = SqliteSeam.rowStr(r, 1)
                    if (seen.add(nb)) {
                        prev[nb] = Pair(curId, rel)
                        depthMap[nb] = d + 1
                        if (nb == to.id) { found = true; break }
                        queue.add(nb)
                    }
                }
            }
            if (!found) return emptyList()
            val labels = HashMap<Long, String>()
            fun lab(id: Long): String {
                if (!labels.containsKey(id)) labels[id] = nodeById(id)?.label ?: ("#" + id)
                return labels[id] ?: ("#" + id)
            }
            val steps = ArrayList<String>()
            var curId = to.id
            while (curId != from.id) {
                val p = prev[curId] ?: break
                steps.add(0, lab(p.first) + " --" + p.second + "--> " + lab(curId))
                curId = p.first
            }
            return steps
        }
        return pathKotlin(fromLabel, toLabel, sinceMs, untilMs)
    }

    fun allNodes(limit: Int = Int.MAX_VALUE): List<Node> {
        if (ensureRust()) {
            val rows = SqliteSeam.query(
                dbPath,
                "SELECT id, type, label, source FROM nodes ORDER BY id LIMIT ?",
                listOf(limit.toString())
            )
            if (rows != null) return rows.map { nodeFromRow(it) }
            rustGraphDb = false
        }
        return allNodesKotlin(limit)
    }

    fun allEdges(limit: Int = Int.MAX_VALUE): List<EdgeFull> {
        if (ensureRust()) {
            val rows = SqliteSeam.query(
                dbPath, "$EDGE_SELECT ORDER BY id LIMIT ?", listOf(limit.toString())
            )
            if (rows != null) return rows.map { edgeFullFromRow(it) }
            // Fallback kolom (DB v1 tanpa kolom temporal) — padanan catch
            // Kotlin: SELECT 4 kolom saja.
            val rows4 = SqliteSeam.query(
                dbPath,
                "SELECT id, from_id, to_id, rel FROM edges ORDER BY id LIMIT ?",
                listOf(limit.toString())
            )
            if (rows4 != null) {
                return rows4.map {
                    EdgeFull(
                        SqliteSeam.rowLong(it, 0), SqliteSeam.rowLong(it, 1),
                        SqliteSeam.rowLong(it, 2), SqliteSeam.rowStr(it, 3),
                        "EXTRACTED", 1.0
                    )
                }
            }
            rustGraphDb = false
        }
        return allEdgesKotlin(limit)
    }

    fun confirmAmbiguous(edgeId: Long) {
        if (ensureRust()) {
            val r = SqliteSeam.exec(
                dbPath,
                "UPDATE edges SET tag = ?, confidence = ? WHERE id = ?",
                listOf("EXTRACTED", 1.0, edgeId.toString())
            )
            if (r != null) return
            rustGraphDb = false
        }
        confirmAmbiguousKotlin(edgeId)
    }

    fun deleteEdge(edgeId: Long) {
        if (ensureRust()) {
            val q = SqliteSeam.query(
                dbPath, "SELECT fact_text FROM edges WHERE id = ?", listOf(edgeId.toString())
            )
            if (q == null) {
                rustGraphDb = false
                deleteEdgeKotlin(edgeId)
                return
            }
            val ft = if (q.isEmpty()) "" else SqliteSeam.rowStr(q[0], 0)
            val steps = ArrayList<Pair<String, List<Any?>>>()
            steps.add("DELETE FROM edges WHERE id = ?" to listOf(edgeId.toString()))
            if (ft.isNotBlank()) {
                steps.add("DELETE FROM fts_fact WHERE fact_text = ?" to listOf(ft))
            }
            val res = SqliteSeam.tx(dbPath, steps)
            if (res != null) return
            rustGraphDb = false
        }
        deleteEdgeKotlin(edgeId)
    }

    fun getOrCreateEpisode(sessionId: String, role: String, text: String, source: String): Long {
        if (ensureRust()) {
            val clean = text.take(500)
            val q = SqliteSeam.query(
                dbPath,
                "SELECT id FROM episodes WHERE session_id = ? AND text = ? LIMIT 1",
                listOf(sessionId, clean)
            )
            if (q == null) {
                rustGraphDb = false
                return getOrCreateEpisodeKotlin(sessionId, role, text, source)
            }
            if (q.isNotEmpty()) return SqliteSeam.rowLong(q[0], 0)
            val ins = SqliteSeam.exec(
                dbPath,
                "INSERT INTO episodes(session_id, role, text, source, ts) VALUES(?,?,?,?,?)",
                listOf(sessionId, role, clean, source, System.currentTimeMillis())
            )
            if (ins != null) return ins.lastId
            rustGraphDb = false
        }
        return getOrCreateEpisodeKotlin(sessionId, role, text, source)
    }

    fun countEpisodes(sessionId: String): Int {
        if (ensureRust()) {
            val rows = SqliteSeam.query(
                dbPath,
                "SELECT COUNT(*) FROM episodes WHERE session_id = ?", listOf(sessionId)
            )
            if (rows != null && rows.isNotEmpty()) return SqliteSeam.rowInt(rows[0], 0)
            rustGraphDb = false
        }
        return countEpisodesKotlin(sessionId)
    }

    fun episodesForSession(sessionId: String, offset: Int, limit: Int): List<EpisodeRow> {
        if (ensureRust()) {
            val rows = SqliteSeam.query(
                dbPath,
                "SELECT id, role, text, source FROM episodes WHERE session_id = ? ORDER BY id ASC LIMIT ? OFFSET ?",
                listOf(sessionId, limit.toString(), offset.toString())
            )
            if (rows != null) {
                return rows.map {
                    EpisodeRow(
                        SqliteSeam.rowLong(it, 0), SqliteSeam.rowStr(it, 1),
                        SqliteSeam.rowStr(it, 2), SqliteSeam.rowStr(it, 3)
                    )
                }
            }
            rustGraphDb = false
        }
        return episodesForSessionKotlin(sessionId, offset, limit)
    }

    fun deleteEpisodesBefore(sessionId: String, beforeTs: Long): Int {
        if (ensureRust()) {
            val r = SqliteSeam.exec(
                dbPath,
                "DELETE FROM episodes WHERE session_id=? AND ts<?",
                listOf(sessionId, beforeTs.toString())
            )
            if (r != null) return r.changes.toInt()
            rustGraphDb = false
        }
        return deleteEpisodesBeforeKotlin(sessionId, beforeTs)
    }

    fun unexpireEdge(edgeId: Long) {
        if (ensureRust()) {
            val r = SqliteSeam.exec(
                dbPath,
                "UPDATE edges SET invalid_at = NULL, expired_at = NULL WHERE id = ?",
                listOf(edgeId.toString())
            )
            if (r != null) return
            rustGraphDb = false
        }
        unexpireEdgeKotlin(edgeId)
    }

    fun expireEdge(edgeId: Long) {
        if (ensureRust()) {
            val nowExp = System.currentTimeMillis()
            val r = SqliteSeam.exec(
                dbPath,
                "UPDATE edges SET invalid_at = ?, expired_at = ? WHERE id = ?",
                listOf(nowExp, nowExp, edgeId.toString())
            )
            if (r != null) return
            rustGraphDb = false
        }
        expireEdgeKotlin(edgeId)
    }

    fun supersedeConflictingEdges(fromId: Long, toId: Long, rel: String): Int {
        if (ensureRust()) {
            val rows = SqliteSeam.query(
                dbPath,
                "SELECT id, to_id FROM edges WHERE from_id = ? AND rel = ? AND invalid_at IS NULL",
                listOf(fromId.toString(), rel)
            )
            if (rows == null) {
                rustGraphDb = false
                return supersedeConflictingEdgesKotlin(fromId, toId, rel)
            }
            var n = 0
            for (r in rows) {
                val eid = SqliteSeam.rowLong(r, 0)
                val oldTo = SqliteSeam.rowLong(r, 1)
                if (oldTo != toId) {
                    expireEdge(eid)
                    n++
                }
            }
            return n
        }
        return supersedeConflictingEdgesKotlin(fromId, toId, rel)
    }

    fun searchFacts(query: String, limit: Int = 20, sinceMs: Long = 0L, untilMs: Long = Long.MAX_VALUE): List<EdgeFull> {
        if (ensureRust()) {
            val tokens = query.split(Regex("[^A-Za-z0-9_]+")).filter { it.length >= 2 }.distinct()
            if (tokens.isEmpty()) return emptyList()
            val matchQ = tokens.joinToString(" OR ") { it + "*" }
            val fts = SqliteSeam.query(
                dbPath,
                "SELECT fact_text FROM fts_fact WHERE fts_fact MATCH ? LIMIT ?",
                listOf(matchQ, (limit * 3).toString())
            )
            if (fts == null) {
                rustGraphDb = false
                return searchFactsKotlin(query, limit, sinceMs, untilMs)
            }
            val facts = ArrayList<String>()
            for (r in fts) {
                val f = SqliteSeam.rowStr(r, 0)
                if (f.isNotBlank()) facts.add(f)
            }
            if (facts.isEmpty()) return emptyList()
            val out = ArrayList<EdgeFull>()
            val rangeOnSf = sinceMs > 0L || untilMs != Long.MAX_VALUE
            for (f in facts) {
                val ec = SqliteSeam.query(
                    dbPath,
                    "$EDGE_SELECT WHERE fact_text = ? AND invalid_at IS NULL LIMIT 1",
                    listOf(f)
                )
                if (ec == null || ec.isEmpty()) {
                    if (ec == null) {
                        rustGraphDb = false
                        return searchFactsKotlin(query, limit, sinceMs, untilMs)
                    }
                    continue
                }
                val e = edgeFullFromRow(ec[0])
                if (!rangeOnSf || (e.recordedAt > 0L && e.recordedAt >= sinceMs && e.recordedAt <= untilMs)) {
                    out.add(e)
                }
                if (out.size >= limit) break
            }
            return out
        }
        return searchFactsKotlin(query, limit, sinceMs, untilMs)
    }

    fun edgeById(edgeId: Long): EdgeFull? {
        if (ensureRust()) {
            val rows = SqliteSeam.query(
                dbPath, "$EDGE_SELECT WHERE id = ? LIMIT 1", listOf(edgeId.toString())
            )
            if (rows != null) {
                return if (rows.isEmpty()) null else edgeFullFromRow(rows[0])
            }
            rustGraphDb = false
        }
        return edgeByIdKotlin(edgeId)
    }

    fun setEdgeTag(edgeId: Long, tag: String, confidence: Double? = null) {
        if (ensureRust()) {
            val r = if (confidence != null) {
                SqliteSeam.exec(
                    dbPath,
                    "UPDATE edges SET tag = ?, confidence = ? WHERE id = ?",
                    listOf(tag, confidence, edgeId.toString())
                )
            } else {
                SqliteSeam.exec(
                    dbPath,
                    "UPDATE edges SET tag = ? WHERE id = ?",
                    listOf(tag, edgeId.toString())
                )
            }
            if (r != null) return
            rustGraphDb = false
        }
        setEdgeTagKotlin(edgeId, tag, confidence)
    }

    fun activeEdgesTouching(labels: Set<String>): List<EdgeFull> {
        if (ensureRust()) {
            if (labels.isEmpty()) return emptyList()
            val labs = labels.take(24)
            val ph = labs.joinToString(",") { "?" }
            val args = ArrayList<Any?>()
            args.addAll(labs)
            args.addAll(labs)
            val rows = SqliteSeam.query(
                dbPath,
                "SELECT e.id, e.from_id, e.to_id, e.rel, e.tag, e.confidence, e.fact_text, e.valid_at, e.invalid_at, e.recorded_at, e.expired_at, e.episode_id FROM edges e JOIN nodes nf ON nf.id = e.from_id JOIN nodes nt ON nt.id = e.to_id WHERE e.invalid_at IS NULL AND (nf.label IN (" + ph + ") OR nt.label IN (" + ph + ")) ORDER BY e.id DESC LIMIT 30",
                args
            )
            if (rows != null) return rows.map { edgeFullFromRow(it) }
            rustGraphDb = false
        }
        return activeEdgesTouchingKotlin(labels)
    }

    fun nodeByLabel(label: String): Node? {
        if (ensureRust()) {
            val clean = label.trim().take(200)
            if (clean.isBlank()) return null
            val rows = SqliteSeam.query(
                dbPath,
                "SELECT id, type, label, source FROM nodes WHERE LOWER(label) = LOWER(?) LIMIT 1",
                listOf(clean)
            )
            if (rows != null) {
                return if (rows.isEmpty()) null else nodeFromRow(rows[0])
            }
            rustGraphDb = false
        }
        return nodeByLabelKotlin(label)
    }

    fun addAlias(nodeId: Long, alias: String) {
        if (ensureRust()) {
            val clean = alias.trim().take(200)
            if (clean.isBlank() || nodeId <= 0) return
            val q = SqliteSeam.query(
                dbPath,
                "SELECT aliases_json FROM nodes WHERE id = ? LIMIT 1",
                listOf(nodeId.toString())
            )
            if (q == null) {
                rustGraphDb = false
                addAliasKotlin(nodeId, alias)
                return
            }
            val raw = if (q.isEmpty()) "[]" else SqliteSeam.rowStr(q[0], 0)
            val arr = try {
                org.json.JSONArray(raw)
            } catch (e: Exception) {
                org.json.JSONArray()
            }
            for (i in 0 until arr.length()) if (arr.optString(i) == clean) return
            arr.put(clean)
            val r = SqliteSeam.exec(
                dbPath,
                "UPDATE nodes SET aliases_json = ? WHERE id = ?",
                listOf(arr.toString(), nodeId.toString())
            )
            if (r != null) return
            rustGraphDb = false
        }
        addAliasKotlin(nodeId, alias)
    }

    fun episodeSource(episodeId: Long): String {
        if (ensureRust()) {
            if (episodeId <= 0) return "graph"
            val rows = SqliteSeam.query(
                dbPath,
                "SELECT source FROM episodes WHERE id = ? LIMIT 1",
                listOf(episodeId.toString())
            )
            if (rows != null) {
                val out = if (rows.isEmpty()) "graph" else SqliteSeam.rowStr(rows[0], 0)
                return if (out.isBlank()) "graph" else out
            }
            rustGraphDb = false
        }
        return episodeSourceKotlin(episodeId)
    }

    fun outEdges(id: Long, limit: Int = 20): List<EdgeFull> {
        if (ensureRust()) {
            val rows = SqliteSeam.query(
                dbPath,
                "$EDGE_SELECT WHERE from_id = ? AND invalid_at IS NULL LIMIT ?",
                listOf(id.toString(), limit.toString())
            )
            if (rows != null) return rows.map { edgeFullFromRow(it) }
            rustGraphDb = false
        }
        return outEdgesKotlin(id, limit)
    }

    fun latestFactsVc230(limit: Int = 10): List<Fact> {
        if (ensureRust()) {
            val rows = SqliteSeam.query(
                dbPath,
                "SELECT fact_text, recorded_at, tag, confidence FROM edges WHERE invalid_at IS NULL AND fact_text IS NOT NULL AND fact_text != '' ORDER BY id DESC LIMIT ?",
                listOf(limit.toString())
            )
            if (rows != null) {
                return rows.map {
                    Fact(
                        SqliteSeam.rowStr(it, 0), SqliteSeam.rowLong(it, 1), "graph",
                        SqliteSeam.rowStr(it, 2).ifBlank { "EXTRACTED" },
                        SqliteSeam.rowDoubleOrNull(it, 3) ?: 1.0
                    )
                }
            }
            rustGraphDb = false
        }
        return latestFactsVc230Kotlin(limit)
    }

    fun rewireNodeEdges(oldId: Long, canonId: Long) {
        if (ensureRust()) {
            if (oldId <= 0 || canonId <= 0 || oldId == canonId) return
            val rows = SqliteSeam.query(
                dbPath,
                "SELECT id, from_id, to_id, rel FROM edges WHERE from_id = ? OR to_id = ?",
                listOf(oldId.toString(), oldId.toString())
            )
            if (rows == null) {
                rustGraphDb = false
                rewireNodeEdgesKotlin(oldId, canonId)
                return
            }
            for (r in rows) {
                val eid = SqliteSeam.rowLong(r, 0)
                val f = SqliteSeam.rowLong(r, 1)
                val t = SqliteSeam.rowLong(r, 2)
                val rel = SqliteSeam.rowStr(r, 3)
                val nf = if (f == oldId) canonId else f
                val nt = if (t == oldId) canonId else t
                if (nf == nt) {
                    SqliteSeam.exec(dbPath, "DELETE FROM edges WHERE id = ?", listOf(eid.toString()))
                    continue
                }
                val dup = SqliteSeam.query(
                    dbPath,
                    "SELECT id FROM edges WHERE from_id = ? AND to_id = ? AND rel = ? LIMIT 1",
                    listOf(nf.toString(), nt.toString(), rel)
                )
                if (dup == null) {
                    rustGraphDb = false
                    rewireNodeEdgesKotlin(oldId, canonId)
                    return
                }
                if (dup.isNotEmpty()) {
                    SqliteSeam.exec(dbPath, "DELETE FROM edges WHERE id = ?", listOf(eid.toString()))
                    continue
                }
                val fl = nodeById(nf)?.label ?: ""
                val tl = nodeById(nt)?.label ?: ""
                SqliteSeam.exec(
                    dbPath,
                    "UPDATE edges SET from_id = ?, to_id = ?, fact_text = ? WHERE id = ?",
                    listOf(nf, nt, fl + " " + rel + " " + tl, eid.toString())
                )
            }
            return
        }
        rewireNodeEdgesKotlin(oldId, canonId)
    }

}

// v4.48.9.23 (vc189): tipe Graph v2 (top-level).
data class EdgeFull(val id: Long, val from: Long, val to: Long, val rel: String, val tag: String, val confidence: Double, val factText: String = "", val validAt: Long = 0L, val invalidAt: Long? = null, val recordedAt: Long = 0L, val expiredAt: Long? = null, val episodeId: Long = -1L)
data class NewEdge(val id: Long, val fromLabel: String, val rel: String, val toLabel: String)
data class Fact(val factText: String, val recordedAt: Long, val source: String, val tag: String = "EXTRACTED", val confidence: Double = 1.0)
data class RelOutcome(val newEdges: List<NewEdge>, val amb: Int)
data class EpisodeRow(val id: Long, val role: String, val text: String, val source: String)
data class Surp(val fromLabel: String, val rel: String, val toLabel: String, val reason: String)
data class MapData(val nodes: List<GraphDb.Node>, val edges: List<EdgeFull>, val gods: List<Pair<GraphDb.Node, Int>>, val comms: List<List<GraphDb.Node>>, val surprising: List<Surp>, val questions: List<String>, val ambiguous: List<EdgeFull>, val expired: Int = 0)
