//! Template JS `DsAgent.kt` — diekstrak byte-identik dari sumber
//! Kotlin oleh skrip `/tmp/probe329/extract_ds_js.py` (bukan
//! ketik ulang manual). P15 membandingkan tiap template Rust vs
//! literal Kotlin di runtime; bila ada selisih satu byte pun,
//! P15 merah. JANGAN edit manual — jalankan ulang ekstraktor.

#![forbid(unsafe_code)]

pub(crate) const ATTACH_JS_TEMPLATE: &str = r"(function(){try{var inp=document.querySelector('input[type=file]');if(!inp){return 'NO_INPUT';}var bin=atob('B64HERE');var arr=new Uint8Array(bin.length);for(var i=0;i<bin.length;i++){arr[i]=bin.charCodeAt(i);}var file=new File([arr],'NAMEHERE',{type:'MIMEHERE'});var dt=new DataTransfer();dt.items.add(file);inp.files=dt.files;inp.dispatchEvent(new Event('change',{bubbles:true}));inp.dispatchEvent(new Event('input',{bubbles:true}));return 'ATTACHED';}catch(e){return 'ERR:'+e.message;}})()";

pub(crate) const SEND_JS_TEMPLATE: &str = r#"(function(){
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
                    })()"#;

pub(crate) const PROBE_JS: &str = r"(function(){
              if (document.querySelector('textarea')) return 'READY';
              if (location.href.indexOf('sign_in') >= 0 || location.href.indexOf('sign_up') >= 0) return 'LOGIN';
              var b = (document.body && document.body.innerText) || '';
              if (b.indexOf('Log in') >= 0 && b.indexOf('Sign up') >= 0) return 'LOGIN';
              return 'LOADING';
            })()";

pub(crate) const PAGE_TEXT_JS: &str = r"document.title + '\n' + ((document.body && document.body.innerText) ? document.body.innerText : '')";

pub(crate) const LAST_STATE_JS: &str = r#"(function(){
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
            })()"#;
