package com.senzwh.deepharness

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Kontrol chat DeepSeek web di WebView lewat injeksi JS.
 * Semua proteksi (PoW/WAF/captcha) diurus JS resmi DeepSeek; kita hanya
 * mengisi kolom chat, menekan kirim, dan membaca jawaban dari DOM.
 */
class DsAgent(private val webView: WebView) {

    private val main = Handler(Looper.getMainLooper())
    @Volatile var busy = false

    private fun eval(js: String, cb: (String) -> Unit) {
        webView.evaluateJavascript(js) { raw ->
            val v = DsAgentSeam.parseEvalResult(raw ?: "null") ?: evalParseKotlin(raw)
            cb(v)
        }
    }

    /** Slice 12 (vc329): parse hasil `evaluateJavascript` ala
     *  `JSONTokener.nextValue` — string -> dirinya, null -> "null",
     *  angka/bool -> toString, objek/array -> JSON compact, gagal
     *  parse -> "". Dipakai bila seam Rust tak-tersedia. */
    private fun evalParseKotlin(raw: String?): String {
        return try {
            val parsed = JSONTokener(raw ?: "null").nextValue()
            if (parsed is String) parsed else (parsed?.toString() ?: "")
        } catch (e: Exception) { "" }
    }

    /** Ambil judul + teks halaman web yang sedang tampil di WebView. */
    fun pageText(cb: (String) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { pageText(cb) }
            return
        }
        eval(DsAgentSeam.dsJs("page_text") ?: pageTextJsKotlin()) { r ->
            cb(r.take(7000))
        }
    }

    /** Slice 12 (vc329): template JS `pageText` — dipakai bila seam
     *  Rust tak-tersedia. */
    private fun pageTextJsKotlin(): String =
        "document.title + '\\n' + ((document.body && document.body.innerText) ? document.body.innerText : '')"

    fun analyzeImage(base64Jpeg: String, question: String, onDone: (String) -> Unit, onError: (String) -> Unit) =
        attachFile(base64Jpeg, "harness-photo.jpg", "image/jpeg", question, onDone, onError)

    /** Lampirkan file apa pun (base64) ke chat web DeepSeek lewat input file-nya,
     *  lalu kirim pertanyaan. Best-effort: tergantung DOM chat.deepseek.com. */
    fun attachFile(base64Data: String, fileName: String, mime: String, question: String, onDone: (String) -> Unit, onError: (String) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { attachFile(base64Data, fileName, mime, question, onDone, onError) }
            return
        }
        val js = DsAgentSeam.buildAttachJs(base64Data, fileName, mime) ?: attachJsKotlin(base64Data, fileName, mime)
        eval(js) { r ->
            if (r.startsWith("ERR") || r == "NO_INPUT") {
                onError("Lampiran gambar ke chat web gagal ($r). Pastikan tab Web menampilkan halaman chat DeepSeek yang sudah login.")
            } else {
                main.postDelayed({ sendAndAwait(question, false, false, {}, onDone, onError) }, 2500)
            }
        }
    }

    /** Slice 12 (vc329): template JS `attachFile` (placeholder
     *  B64HERE/NAMEHERE/MIMEHERE) — dipakai bila seam Rust
     *  tak-tersedia. */
    private fun attachJsKotlin(base64Data: String, fileName: String, mime: String): String {
        val js = "(function(){try{" +
            "var inp=document.querySelector('input[type=file]');" +
            "if(!inp){return 'NO_INPUT';}" +
            "var bin=atob('" + "B64HERE" + "');" +
            "var arr=new Uint8Array(bin.length);" +
            "for(var i=0;i<bin.length;i++){arr[i]=bin.charCodeAt(i);}" +
            "var file=new File([arr],'NAMEHERE',{type:'MIMEHERE'});" +
            "var dt=new DataTransfer();dt.items.add(file);" +
            "inp.files=dt.files;" +
            "inp.dispatchEvent(new Event('change',{bubbles:true}));" +
            "inp.dispatchEvent(new Event('input',{bubbles:true}));" +
            "return 'ATTACHED';}catch(e){return 'ERR:'+e.message;}})()"
        return js.replace("B64HERE", base64Data).replace("NAMEHERE", fileName).replace("MIMEHERE", mime)
    }

    fun probe(cb: (String) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { probe(cb) }; return }
        eval(DsAgentSeam.dsJs("probe") ?: probeJsKotlin()) { cb(it) }
    }

    /** Slice 12 (vc329): template JS `probe` — dipakai bila seam
     *  Rust tak-tersedia. */
    private fun probeJsKotlin(): String =
        """(function(){
              if (document.querySelector('textarea')) return 'READY';
              if (location.href.indexOf('sign_in') >= 0 || location.href.indexOf('sign_up') >= 0) return 'LOGIN';
              var b = (document.body && document.body.innerText) || '';
              if (b.indexOf('Log in') >= 0 && b.indexOf('Sign up') >= 0) return 'LOGIN';
              return 'LOADING';
            })()"""

    private fun lastState(cb: (Int, String) -> Unit) {
        eval(DsAgentSeam.dsJs("last_state") ?: lastStateJsKotlin()) { s ->
            try {
                val o = JSONObject(s)
                cb(o.optInt("c", 0), o.optString("t", ""))
            } catch (e: Exception) { cb(0, "") }
        }
    }

    /** Slice 12 (vc329): template JS `lastState` (baca markdown
     *  terakhir + walker manual) — dipakai bila seam Rust
     *  tak-tersedia. */
    private fun lastStateJsKotlin(): String =
        """(function(){
              var n = document.querySelectorAll('.ds-markdown');
              var t = '';
              if (n.length) {
                var clone = n[n.length-1].cloneNode(true);
                var kill = clone.querySelectorAll('button, [class*="banner"], [class*="code-block-header"]');
                for (var i = 0; i < kill.length; i++) kill[i].remove();
                // JANGAN pakai clone.innerText: node lepas dari DOM tidak punya layout,
                // jadi baris baru antar blok HILANG semua (teks nempel satu tembok).
                // Walker manual: elemen blok -> '\n', <li> di <ol> -> nomor urut.
                var BLOCKS = {P:1,DIV:1,LI:1,H1:1,H2:1,H3:1,H4:1,H5:1,H6:1,TR:1,PRE:1,BLOCKQUOTE:1,SECTION:1,ARTICLE:1,UL:1,OL:1,TABLE:1,HR:1};
                function ext(node) {
                  if (node.nodeType === 3) return node.nodeValue || '';
                  if (node.nodeType !== 1) return '';
                  var tag = node.tagName;
                  if (tag === 'BR') return '\n';
                  var s = '';
                  var kids = node.childNodes;
                  for (var i = 0; i < kids.length; i++) s += ext(kids[i]);
                  if (tag === 'LI') {
                    var p = node.parentNode;
                    var pre = '\u2022 ';
                    if (p && p.tagName === 'OL') {
                      var idx = 1; var sib = node.previousElementSibling;
                      while (sib) { if (sib.tagName === 'LI') idx++; sib = sib.previousElementSibling; }
                      pre = idx + '. ';
                    }
                    s = pre + s.replace(/^\s+/, '');
                  }
                  if (BLOCKS[tag]) s += '\n';
                  return s;
                }
                t = ext(clone);
                t = t.replace(/[ \t]+\n/g, '\n').replace(/\n{3,}/g, '\n\n').replace(/^\s+|\s+$/g, '');
              }
              return JSON.stringify({c: n.length, t: t});
            })()"""

    /** Kirim prompt dan tunggu jawaban selesai (stabil 3x polling), maks 180 detik.
     *  onPartial dipanggil tiap ada teks jawaban baru (streaming dari DOM). */
    fun sendAndAwait(prompt: String, thinking: Boolean, search: Boolean, onPartial: (String) -> Unit, onDone: (String) -> Unit, onError: (String) -> Unit) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post { sendAndAwait(prompt, thinking, search, onPartial, onDone, onError) }; return }
        if (busy) { onError("Masih ada jawaban yang diproses. Tunggu dulu."); return }
        probe { state ->
            if (state == "LOGIN") { onError("Belum login. Buka tab 'DeepSeek Web', login dulu pakai akun DeepSeek kamu, lalu kembali ke Chat."); return@probe }
            if (state != "READY") { onError("Halaman DeepSeek belum siap (status: $state). Buka tab 'DeepSeek Web' dan tunggu sampai kolom chat muncul."); return@probe }
            busy = true
            lastState { baseCount, baseText ->
                val quoted = DsAgentSeam.jsonQuote(prompt) ?: JSONObject.quote(prompt)
                eval(DsAgentSeam.buildSendJs(thinking, search, quoted) ?: sendJsKotlin(thinking, search, quoted)) { setRes ->
                    if (setRes != "SET") { busy = false; onError("Gagal mengisi kolom chat DeepSeek (respon: $setRes)."); return@eval }
                    main.postDelayed({
                        eval(
                            """(function(){
                              var ta = document.querySelector('textarea');
                              if (ta) {
                                ta.dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true}));
                              }
                              return 'ENTER_SENT';
                            })()"""
                        ) {}
                        // fallback klik tombol kirim bila Enter tidak mengirim
                        main.postDelayed({
                            lastState { c2, t2 ->
                                if (c2 == baseCount && t2 == baseText) {
                                    eval(
                                        """(function(){
                                          var els = Array.prototype.slice.call(document.querySelectorAll('div[role="button"], button'));
                                          var vis = els.filter(function(e){ var r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0; });
                                          if (vis.length) { vis[vis.length-1].click(); return 'CLICKED'; }
                                          return 'NO_BTN';
                                        })()"""
                                    ) {}
                                }
                            }
                        }, 1800)
                        pollAnswer(baseCount, baseText, 0, "", 0, onPartial, onDone, onError)
                    }, 700)
                }
            }
        }
    }

    /** Slice 12 (vc329): template JS `sendAndAwait` (isi kolom +
     *  toggle DeepThink/Search) — dipakai bila seam Rust tak-tersedia. */
    private fun sendJsKotlin(thinking: Boolean, search: Boolean, quoted: String): String =
        """(function(){
                      var ta = document.querySelector('textarea');
                      if (!ta) return 'NO_INPUT';
                      function setToggle(label, want) {
                        try {
                          var els = Array.prototype.slice.call(document.querySelectorAll('button, [role="button"]'));
                          for (var i = 0; i < els.length; i++) {
                            var t = (els[i].textContent || '').trim();
                            if (t === label) {
                              var cn = els[i].className || '';
                              var sel = (typeof cn === 'string' && (cn.indexOf('selected') >= 0 || cn.indexOf('active') >= 0)) || els[i].getAttribute('aria-pressed') === 'true' || els[i].getAttribute('data-state') === 'on';
                              if (sel !== want) els[i].click();
                              return;
                            }
                          }
                        } catch (e) {}
                      }
                      setToggle('DeepThink', $thinking);
                      setToggle('Search', $search);
                      ta.focus();
                      var setter = Object.getOwnPropertyDescriptor(window.HTMLTextAreaElement.prototype, 'value').set;
                      setter.call(ta, $quoted);
                      ta.dispatchEvent(new Event('input', {bubbles: true}));
                      ta.dispatchEvent(new Event('change', {bubbles: true}));
                      return 'SET';
                    })()"""

    private fun pollAnswer(baseCount: Int, baseText: String, tries: Int, lastText: String,
                           stable: Int, onPartial: (String) -> Unit,
                           onDone: (String) -> Unit, onError: (String) -> Unit) {
        if (tries > 150) { busy = false; onError("Timeout menunggu jawaban DeepSeek (3 menit). Mungkin server sibuk — coba lagi."); return }
        main.postDelayed({
            lastState { count, text ->
                val step = DsAgentSeam.pollStep(tries, baseCount, baseText, lastText, stable, count, text)
                if (step != null) {
                    when (step.outcome) {
                        "timeout" -> { busy = false; onError("Timeout menunggu jawaban DeepSeek (3 menit). Mungkin server sibuk — coba lagi.") }
                        "unchanged" -> pollAnswer(baseCount, baseText, tries + 1, lastText, 0, onPartial, onDone, onError)
                        "continue" -> {
                            if (step.firePartial) onPartial(text)
                            pollAnswer(baseCount, baseText, tries + 1, step.newLastText, step.newStable, onPartial, onDone, onError)
                        }
                        "done" -> {
                            if (step.firePartial) onPartial(text)
                            busy = false
                            onDone(text)
                        }
                        else -> pollAnswerKotlin(baseCount, baseText, tries, lastText, stable, count, text, onPartial, onDone, onError)
                    }
                } else {
                    pollAnswerKotlin(baseCount, baseText, tries, lastText, stable, count, text, onPartial, onDone, onError)
                }
            }
        }, 1200)
    }

    /** Slice 12 (vc329): satu langkah `pollAnswer` (stabil 3x) —
     *  dipakai bila seam Rust tak-tersedia. */
    private fun pollAnswerKotlin(baseCount: Int, baseText: String, tries: Int, lastText: String,
                                 stable: Int, count: Int, text: String,
                                 onPartial: (String) -> Unit,
                                 onDone: (String) -> Unit, onError: (String) -> Unit) {
        val changed = (count > baseCount) || (text != baseText && text.isNotBlank())
        if (!changed) {
            pollAnswer(baseCount, baseText, tries + 1, lastText, 0, onPartial, onDone, onError)
            return
        }
        if (text != lastText && text.isNotBlank()) onPartial(text)
        val newStable = if (text == lastText && text.isNotBlank()) stable + 1 else 0
        if (newStable >= 3) {
            busy = false
            onDone(text)
        } else {
            pollAnswer(baseCount, baseText, tries + 1, text, newStable, onPartial, onDone, onError)
        }
    }
}
