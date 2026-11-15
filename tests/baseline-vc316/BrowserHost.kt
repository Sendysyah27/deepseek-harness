package com.senzwh.deepharness

import org.json.JSONObject

/**
 * Browser agen (T4b, brief migrasi §12): WebView khusus yang bisa dikemudi
 * agen lewat tool browser_* — TERPISAH dari WebView login DeepSeek, tapi
 * berbagi CookieManager yang sama (login situs ikut terbawa).
 *
 * Objek ini cuma registry: implementasi UI (yang memegang WebView) dipasang
 * MainActivity saat buildWebBox; Tools.kt memanggil lewat AgentBrowser.run.
 * Semua pekerjaan WebView terjadi di MainActivity.browserTool (UI thread,
 * latch). JS snapshot memakai pola ref bernomor seperti get_screen (P2).
 *
 * vc313 (SPEC-BROWSER-WEBVIEW-MAX §3.2, §4.3, §5) — snapshot v2:
 * tiap node membawa role + nama aksesibel + selector CSS best-effort;
 * format baris menjadi `[#N] role "nama" @eN`; peta ref->selector tinggal
 * di Kotlin (lastRefs, TIDAK dikirim ke LLM); ada diff snapshot,
 * pemulihan ref basi (role+nama unik), dan nonce pembungkus output tool.
 */
object AgentBrowser {

    @Volatile var impl: ((String, JSONObject) -> String)? = null

    fun run(name: String, args: JSONObject): String {
        val f = impl ?: return "Browser agen belum siap — buka dulu tab Web di aplikasi."
        return try { f(name, args) } catch (e: Exception) { "Browser agen error: " + (e.message ?: e.javaClass.simpleName) }
    }

    /** Entri peta ref->selector (Kotlin saja, TIDAK dikirim ke LLM).
     *  selector = selector CSS best-effort dari SNAPSHOT_JS (tag + #id +
     *  .class, fallback '[data-hx-ref="N"]'); role/label/cx/cy = salinan
     *  node snapshot untuk eksekusi aksi + pemulihan ref basi. */
    data class RefEntry(val ref: Int, val selector: String, val role: String, val label: String, val cx: Int, val cy: Int)

    /** Peta ref->RefEntry dari snapshot TERAKHIR yang diformat.
     *  SIDE EFFECT: diperbarui di dalam formatSnapshot() (bukan di
     *  refsFromSnapshot(), yang pure). Worker B membaca peta ini untuk
     *  mengeksekusi aksi / memulihkan ref basi — jangan baca sebelum ada
     *  snapshot yang diformat (awalnya emptyMap()). */
    @Volatile var lastRefs: Map<Int, RefEntry> = emptyMap()

    /** Snapshot DOM: beri nomor ref ke elemen interaktif yang terlihat,
     *  kembalikan JSON {url,title,nodes:[{ref,tag,type,label,cx,cy,pw,role,name,sel}],text}.
     *  Dipertahankan dari vc312: query elemen interaktif terlihat (ukuran
     *  >= 4px, tidak hidden/none, di viewport), penomoran data-hx-ref,
     *  label kata sandi = '[PASSWORD]', cap 90 node, teks maks 3200 karakter.
     *  Ditambah vc313: per node `role` (atribut role > tag + tipe input),
     *  `name` (nama aksesibel, urutan sumber sama seperti label), dan `sel`
     *  (selector CSS best-effort: tag + #id + .class, fallback
     *  '[data-hx-ref="N"]'). */
    const val SNAPSHOT_JS: String = """
(function(){
  var els = document.querySelectorAll('a,button,input,textarea,select,[role="button"]');
  var out = []; var n = 0;
  function roleOf(e){
    var ra = (e.getAttribute('role')||'').trim().toLowerCase();
    if (ra) return ra;
    var tag = e.tagName.toLowerCase();
    if (tag === 'a') return 'link';
    if (tag === 'button') return 'button';
    if (tag === 'textarea') return 'textbox';
    if (tag === 'select') return 'combobox';
    if (tag === 'input') {
      var t = (e.getAttribute('type')||'text').toLowerCase();
      if (t === 'checkbox') return 'checkbox';
      if (t === 'radio') return 'radio';
      if (t === 'submit' || t === 'button' || t === 'reset' || t === 'image') return 'button';
      if (t === 'range') return 'slider';
      return 'textbox';
    }
    return 'generic';
  }
  function cleanTok(s){ return (s||'').replace(/[^A-Za-z0-9_-]/g,''); }
  function selOf(e, tag, ref){
    var s = tag;
    var id = cleanTok(e.id);
    if (id) s += '#' + id;
    var cn = (typeof e.className === 'string') ? e.className : '';
    var parts = cn.trim().split(/\s+/).slice(0,2);
    for (var i=0;i<parts.length;i++){ var c = cleanTok(parts[i]); if (c) s += '.' + c; }
    if (!id && !cn.trim()) return '[data-hx-ref="' + ref + '"]';
    return s;
  }
  els.forEach(function(e){
    var r = e.getBoundingClientRect();
    if (r.width < 4 || r.height < 4) return;
    var st = window.getComputedStyle(e);
    if (st.visibility === 'hidden' || st.display === 'none') return;
    if (r.bottom < 0 || r.top > window.innerHeight) return;
    n++; e.setAttribute('data-hx-ref', '' + n);
    var isPw262 = e.type === 'password';
    var label = isPw262 ? '[PASSWORD]' : (e.innerText || e.getAttribute('aria-label') || e.placeholder || e.name || '').trim().replace(/\s+/g,' ').slice(0,60);
    var tag = e.tagName.toLowerCase();
    out.push({ref:n, tag:tag, type:(e.getAttribute('type')||''), label:label, cx:Math.round(r.left + r.width/2), cy:Math.round(r.top + r.height/2), pw: e.type === 'password', role: roleOf(e), name: label, sel: selOf(e, tag, n)});
  });
  var text = (document.body ? document.body.innerText : '').replace(/\n{3,}/g, '\n\n').slice(0, 3200);
  return JSON.stringify({url: location.href, title: document.title, nodes: out.slice(0, 90), text: text});
})()
"""

    fun tapJs(ref: Int): String = """
(function(){
  var e = document.querySelector('[data-hx-ref="$ref"]');
  if (!e) return 'REF_NOT_FOUND';
  e.scrollIntoView({block:'center'});
  // vc265 V-M3: kolom password TIDAK PERNAH membocorkan nilainya —
  // label tap memakai penanda yang sama dengan snapshot ([PASSWORD]).
  var isPwTap = e.type === 'password';
  var label = isPwTap ? '[PASSWORD]' : (e.innerText || e.value || e.getAttribute('aria-label') || '').trim().slice(0,60);
  e.click();
  return 'OK:' + label;
})()
"""

    fun typeJs(ref: Int, text: String): String {
        val q = JSONObject.quote(text)
        return """
(function(){
  var e = document.querySelector('[data-hx-ref="$ref"]');
  if (!e) return 'REF_NOT_FOUND';
  if (e.type === 'password') return 'PASSWORD_FIELD';
  e.scrollIntoView({block:'center'});
  e.focus();
  try {
    var proto = e.constructor === HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
    setter.call(e, $q);
  } catch (err) { e.value = $q; }
  e.dispatchEvent(new Event('input', {bubbles:true}));
  e.dispatchEvent(new Event('change', {bubbles:true}));
  return 'OK';
})()
"""
    }

    /** Role node snapshot (Kotlin, cerminan roleOf() di SNAPSHOT_JS — untuk
     *  node JSON lama yang belum punya field `role`).
     *  Aturan: atribut role menang; lalu a->link, button->button,
     *  textarea->textbox, select->combobox; input: checkbox->checkbox,
     *  radio->radio, submit/button/reset/image->button, range->slider,
     *  sisanya->textbox; lainnya->generic. */
    private fun nodeRole(nd: JSONObject): String {
        val ra = nd.optString("role").trim().lowercase()
        if (ra.isNotBlank()) return ra
        val tag = nd.optString("tag").lowercase()
        val ty = nd.optString("type").lowercase()
        return when {
            tag == "a" -> "link"
            tag == "button" -> "button"
            tag == "textarea" -> "textbox"
            tag == "select" -> "combobox"
            tag == "input" && ty == "checkbox" -> "checkbox"
            tag == "input" && ty == "radio" -> "radio"
            tag == "input" && (ty == "submit" || ty == "button" || ty == "reset" || ty == "image") -> "button"
            tag == "input" && ty == "range" -> "slider"
            tag == "input" -> "textbox"
            else -> "generic"
        }
    }

    /** Nama aksesibel node: field `name` bila ada, fallback `label`.
     *  Node password SELALU '[PASSWORD]' (nilai/label asli tidak bocor). */
    private fun nodeName(nd: JSONObject): String {
        if (nd.optBoolean("pw")) return "[PASSWORD]"
        val nm = nd.optString("name").trim()
        if (nm.isNotBlank()) return nm
        return nd.optString("label").trim()
    }

    /** Label node untuk peta ref: field `label` bila ada, fallback `name`.
     *  Node password SELALU '[PASSWORD]'. */
    private fun nodeLabel(nd: JSONObject): String {
        if (nd.optBoolean("pw")) return "[PASSWORD]"
        val lb = nd.optString("label").trim()
        if (lb.isNotBlank()) return lb
        return nd.optString("name").trim()
    }

    /** Selector node: field `sel` bila ada, fallback '[data-hx-ref="N"]'. */
    private fun nodeSelector(nd: JSONObject): String {
        val s = nd.optString("sel").trim()
        if (s.isNotBlank()) return s
        return "[data-hx-ref=\"" + nd.optInt("ref") + "\"]"
    }

    /** Parse JSON snapshot menjadi peta ref->RefEntry (PURE: tidak menyentuh
     *  lastRefs, tidak melempar — JSON rusak / tanpa nodes -> emptyMap()).
     *  Node tanpa ref (>0) dilewati. Field role/name/sel yang hilang
     *  diturunkan dari tag/type/label seperti node JSON lama. */
    fun refsFromSnapshot(json: String): Map<Int, RefEntry> {
        val o = try { JSONObject(json) } catch (e: Exception) { return emptyMap() }
        val nodes = o.optJSONArray("nodes") ?: return emptyMap()
        val map = LinkedHashMap<Int, RefEntry>()
        for (i in 0 until nodes.length()) {
            val nd = nodes.optJSONObject(i) ?: continue
            val ref = nd.optInt("ref")
            if (ref == 0) continue
            map[ref] = RefEntry(ref, nodeSelector(nd), nodeRole(nd), nodeLabel(nd), nd.optInt("cx"), nd.optInt("cy"))
        }
        return map
    }

    /** Format hasil snapshot JSON menjadi teks ringkas untuk agen (v2).
     *  FORMAT BARIS (persis): `[#N] role "nama" @eN`
     *  contoh: `[#3] button "Masuk" @e3`. [#N] dipertahankan untuk argumen
     *  tap/type; @eN adalah ref yang sama dalam bentuk @e. Node password:
     *  nama = '[PASSWORD]' + suffix ' [PASSWORD]'.
     *  Header: judul + URL, lalu daftar elemen, lalu 'ISI TEKS:' + teks
     *  halaman. Total dipotong 6000 karakter.
     *  SIDE EFFECT: memperbarui AgentBrowser.lastRefs dari snapshot ini
     *  (lihat lastRefs — Worker B membacanya). JSON rusak: kembalikan pesan
     *  gagal tanpa mengubah lastRefs. */
    fun formatSnapshot(json: String): String {
        val o = try { JSONObject(json) } catch (e: Exception) { return "Snapshot gagal dibaca: " + json.take(200) }
        lastRefs = refsFromSnapshot(json)
        val sb = StringBuilder()
        sb.append("🌐 ").append(o.optString("title", "")).append("\n").append(o.optString("url", "")).append("\n")
        val nodes = o.optJSONArray("nodes")
        if (nodes != null && nodes.length() > 0) {
            sb.append("ELEMEN INTERAKTIF:\n")
            for (i in 0 until nodes.length()) {
                val nd = nodes.optJSONObject(i) ?: continue
                val ref = nd.optInt("ref")
                sb.append("[#").append(ref).append("] ").append(nodeRole(nd))
                sb.append(" \"").append(nodeName(nd)).append("\" @e").append(ref)
                if (nd.optBoolean("pw")) sb.append(" [PASSWORD]")
                sb.append("\n")
            }
        }
        val text = o.optString("text", "")
        if (text.isNotBlank()) sb.append("ISI TEKS:\n").append(text)
        return sb.toString().take(6000)
    }

    /** JS pemulihan ref basi (SPEC §3.2): pindai elemen interaktif terlihat
     *  (filter visibilitas + penomoran sama seperti SNAPSHOT_JS), cocokkan
     *  role + label (trim, case-insensitive; label = nama aksesibel, kolom
     *  password bernama '[PASSWORD]').
     *  Kembalikan: 'UNIQUE:<ref>' bila tepat 1 cocok, 'AMBIGUOUS' bila >1,
     *  'NOT_FOUND' bila 0. Ref yang dikembalikan bisa langsung dipakai
     *  tapJs/typeJs (penomoran data-hx-ref ditulis ulang konsisten dengan
     *  snapshot). Bila AMBIGUOUS/NOT_FOUND, aksi GAGAL — snapshot ulang,
     *  jangan tebak. */
    fun findByRoleJs(role: String, label: String): String {
        val qRole = JSONObject.quote(role.trim().lowercase())
        val qLabel = JSONObject.quote(label.trim())
        return """
(function(){
  var wantRole = $qRole;
  var wantLabel = $qLabel.trim().toLowerCase();
  function roleOf(e){
    var ra = (e.getAttribute('role')||'').trim().toLowerCase();
    if (ra) return ra;
    var tag = e.tagName.toLowerCase();
    if (tag === 'a') return 'link';
    if (tag === 'button') return 'button';
    if (tag === 'textarea') return 'textbox';
    if (tag === 'select') return 'combobox';
    if (tag === 'input') {
      var t = (e.getAttribute('type')||'text').toLowerCase();
      if (t === 'checkbox') return 'checkbox';
      if (t === 'radio') return 'radio';
      if (t === 'submit' || t === 'button' || t === 'reset' || t === 'image') return 'button';
      if (t === 'range') return 'slider';
      return 'textbox';
    }
    return 'generic';
  }
  var els = document.querySelectorAll('a,button,input,textarea,select,[role="button"]');
  var matches = []; var n = 0;
  els.forEach(function(e){
    var r = e.getBoundingClientRect();
    if (r.width < 4 || r.height < 4) return;
    var st = window.getComputedStyle(e);
    if (st.visibility === 'hidden' || st.display === 'none') return;
    if (r.bottom < 0 || r.top > window.innerHeight) return;
    n++; e.setAttribute('data-hx-ref', '' + n);
    var isPw = e.type === 'password';
    var name = isPw ? '[PASSWORD]' : (e.innerText || e.getAttribute('aria-label') || e.placeholder || e.name || '').trim().replace(/\s+/g,' ').slice(0,60);
    if (roleOf(e) === wantRole && name.toLowerCase() === wantLabel) matches.push(n);
  });
  if (matches.length === 1) return 'UNIQUE:' + matches[0];
  if (matches.length > 1) return 'AMBIGUOUS';
  return 'NOT_FOUND';
})()
"""
    }

    /** Terjemah hasil findByRoleJs ke ref: 'UNIQUE:<n>' -> n, selain itu
     *  (AMBIGUOUS / NOT_FOUND / teks lain / bukan angka) -> null. */
    fun findResultToRef(r: String): Int? {
        val t = r.trim()
        if (!t.startsWith("UNIQUE:")) return null
        return t.removePrefix("UNIQUE:").trim().toIntOrNull()
    }

    /** Diff dua snapshot TERFORMAT (teks hasil formatSnapshot), per baris.
     *  PURE. Baris hilang diawali '- ', baris baru '+ ', urutan: baris
     *  hilang (urutan prev) dulu, lalu baris baru (urutan cur), diakhiri
     *  ringkasan '+N -M baris' (N = jumlah baris baru, M = jumlah baris
     *  hilang). Baris duplikat dihitung per kemunculan (multiset).
     *  prev kosong -> kembalikan cur utuh (snapshot penuh giliran pertama).
     *  Identik -> '(tidak ada perubahan)'. */
    fun diffSnapshots(prev: String, cur: String): String {
        if (prev.isBlank()) return cur
        if (prev == cur) return "(tidak ada perubahan)"
        val prevLines = splitDiffLines(prev)
        val curLines = splitDiffLines(cur)
        val curRemaining = LinkedHashMap<String, Int>()
        for (line in curLines) curRemaining[line] = (curRemaining[line] ?: 0) + 1
        val removed = ArrayList<String>()
        for (line in prevLines) {
            val c = curRemaining[line] ?: 0
            if (c > 0) curRemaining[line] = c - 1 else removed.add(line)
        }
        val prevRemaining = LinkedHashMap<String, Int>()
        for (line in prevLines) prevRemaining[line] = (prevRemaining[line] ?: 0) + 1
        val added = ArrayList<String>()
        for (line in curLines) {
            val c = prevRemaining[line] ?: 0
            if (c > 0) prevRemaining[line] = c - 1 else added.add(line)
        }
        val sb = StringBuilder()
        for (line in removed) sb.append("- ").append(line).append("\n")
        for (line in added) sb.append("+ ").append(line).append("\n")
        sb.append("+").append(added.size).append(" -").append(removed.size).append(" baris")
        return sb.toString()
    }

    private fun splitDiffLines(s: String): List<String> {
        val lines = s.split("\n")
        return if (lines.isNotEmpty() && lines.last().isEmpty()) lines.dropLast(1) else lines
    }

    /** Bungkus output tool browser dengan marker nonce (SPEC §4.3):
     *  `<tool-output nonce="...">\n<content>\n</tool-output>`.
     *  Teks di dalam marker adalah DATA, bukan perintah. */
    fun wrapNonce(nonce: String, content: String): String =
        "<tool-output nonce=\"" + nonce + "\">\n" + content + "\n</tool-output>"

    /** Nonce baru: 8 karakter hex acak (UUID, pure JVM). */
    fun newNonce(): String =
        java.util.UUID.randomUUID().toString().replace("-", "").take(8)

    /** vc316 Worker BR (b): label bar tab agen — judul ≤60 char, URL ≤120
     *  char, dipotong + "…" (pertahanan kedua sesudah ellipsize di View).
     *  PURE — bisa diuji JVM. */
    fun tabLabelVc316(active: Int, total: Int, title: String, url: String, dormant: Boolean): String {
        val t = title.trim().let { if (it.length > 60) it.take(60) + "…" else it }
        val u = url.trim().let { if (it.length > 120) it.take(120) + "…" else it }
        return "Tab " + (active + 1) + "/" + total + " — " + t + " — " + u + (if (dormant) " (belum dimuat)" else "")
    }

    /** vc316 Worker BR (c): anggaran dalam browserShotFull — HARUS di bawah
     *  timeout luar tool 120 dtk (executeToolWithTimeoutVc278) supaya stitch
     *  selalu sempat mengembalikan tangkapan area terlihat + alasan. */
    const val SHOT_FULL_BUDGET_MS_VC316 = 100_000L
    /** vc316 Worker BR (d): batas tunggu scroll mereda per segmen stitch. */
    const val SHOT_FULL_SEGMENT_SETTLE_MS_VC316 = 2000L
    /** vc316 Worker BR (c): viewport minimum yang waras — di bawah ini
     *  WebView belum ter-layout (lebar/tinggi 0). */
    const val SHOT_FULL_MIN_VIEWPORT_VC316 = 100

    /** Rencana stitch shot_full (PURE): totalH dicap 12000px; viewportOk
     *  false bila WebView belum ter-layout — pemanggil WAJIB fallback ke
     *  tangkapan area terlihat, JANGAN mengulang stitch. */
    data class ShotFullPlanVc316(val totalH: Int, val segments: Int, val viewportOk: Boolean)

    fun shotFullPlanVc316(pageH: Int, viewportW: Int, viewportH: Int): ShotFullPlanVc316 {
        val ok = viewportW >= SHOT_FULL_MIN_VIEWPORT_VC316 && viewportH >= SHOT_FULL_MIN_VIEWPORT_VC316
        val totalH = pageH.coerceIn(1, 12000)
        val segs = if (!ok || viewportH <= 0) 0 else (totalH + viewportH - 1) / viewportH
        return ShotFullPlanVc316(totalH, segs, ok)
    }
}
