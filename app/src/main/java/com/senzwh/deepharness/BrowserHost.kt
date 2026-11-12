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
 */
object AgentBrowser {

    @Volatile var impl: ((String, JSONObject) -> String)? = null

    fun run(name: String, args: JSONObject): String {
        val f = impl ?: return "Browser agen belum siap — buka dulu tab Web di aplikasi."
        return try { f(name, args) } catch (e: Exception) { "Browser agen error: " + (e.message ?: e.javaClass.simpleName) }
    }

    /** Snapshot DOM: beri nomor ref ke elemen interaktif yang terlihat,
     *  kembalikan JSON {url,title,nodes:[{ref,tag,type,label,cx,cy,pw}],text}. */
    const val SNAPSHOT_JS: String = """
(function(){
  var els = document.querySelectorAll('a,button,input,textarea,select,[role="button"]');
  var out = []; var n = 0;
  els.forEach(function(e){
    var r = e.getBoundingClientRect();
    if (r.width < 4 || r.height < 4) return;
    var st = window.getComputedStyle(e);
    if (st.visibility === 'hidden' || st.display === 'none') return;
    if (r.bottom < 0 || r.top > window.innerHeight) return;
    n++; e.setAttribute('data-hx-ref', '' + n);
    var isPw262 = e.type === 'password';
    var label = isPw262 ? '[PASSWORD]' : (e.innerText || e.getAttribute('aria-label') || e.placeholder || e.name || '').trim().replace(/\s+/g,' ').slice(0,60);
    out.push({ref:n, tag:e.tagName.toLowerCase(), type:(e.getAttribute('type')||''), label:label, cx:Math.round(r.left + r.width/2), cy:Math.round(r.top + r.height/2), pw: e.type === 'password'});
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

    /** Format hasil snapshot JSON menjadi teks ringkas untuk agen. */
    fun formatSnapshot(json: String): String {
        val o = try { JSONObject(json) } catch (e: Exception) { return "Snapshot gagal dibaca: " + json.take(200) }
        val sb = StringBuilder()
        sb.append("🌐 ").append(o.optString("title", "")).append("\n").append(o.optString("url", "")).append("\n")
        val nodes = o.optJSONArray("nodes")
        if (nodes != null && nodes.length() > 0) {
            sb.append("ELEMEN INTERAKTIF:\n")
            for (i in 0 until nodes.length()) {
                val nd = nodes.optJSONObject(i) ?: continue
                sb.append("[#").append(nd.optInt("ref")).append("] <").append(nd.optString("tag"))
                val ty = nd.optString("type")
                if (ty.isNotBlank()) sb.append(":").append(ty)
                sb.append("> \"").append(if (nd.optBoolean("pw")) "[PASSWORD]" else nd.optString("label")).append("\"")
                if (nd.optBoolean("pw")) sb.append(" [PASSWORD]")
                sb.append("\n")
            }
        }
        val text = o.optString("text", "")
        if (text.isNotBlank()) sb.append("ISI TEKS:\n").append(text)
        return sb.toString().take(6000)
    }
}
