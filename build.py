#!/usr/bin/env python3
"""Build manual APK DeepSeek Harness (zero dependency AndroidX): aapt2 + kotlinc + d8 + apksigner."""
import argparse, os, re, shutil, subprocess, sys, zipfile

HOME = os.path.expanduser("~")
# PROJ: lokasi repo ini (otomatis = direktori build.py; bisa di-override
# lewat env HARNESS_PROJ). Dulu hard-coded ke ~/workspace/deepseek-harness.
PROJ = os.environ.get("HARNESS_PROJ", os.path.dirname(os.path.abspath(__file__)))
BUILD = f"{PROJ}/build-manual"
SDK = f"{HOME}/android-sdk"
BT = f"{SDK}/build-tools/34.0.0"
ANDROID_JAR = f"{SDK}/platforms/android-34/android.jar"
KOTLINC = f"{HOME}/kotlin-dist/kotlinc/bin/kotlinc"
STDLIB = f"{HOME}/kotlin-dist/kotlinc/lib/kotlin-stdlib.jar"
JH = f"{HOME}/jdk-21.0.12.1+1"
# ---- Flavor pure-original (Fase E2, PURE-VARIANT-SPEC.md §4) ----
# REPLACEMENTS_PURE: daftar (tag, pola, pengganti, is_regex), URUT.
# Literal = str.replace (case-sensitive); regex = re.subn (word-boundary).
REPLACEMENTS_PURE = [
    ("R1", "com.senzwh.deepharness.priv", "com.harness.app.priv", False),
    ("R2", "com.senzwh.deepharness", "com.harness.app", False),
    ("R11", "[DARURAT] Senz mengirim sinyal darurat dari S&Harness", "[DARURAT] Pengguna mengirim sinyal darurat dari Harness", False),
    ("R3b", "S&amp;Harness", "Harness", False),
    ("R4", "DeepSeek Harness", "Harness", False),
    ("R3", "S&Harness", "Harness", False),
    ("R10", "HP Senz", "HP ini", False),
    ("R5", "Senz Asisten", "Harness Asisten", False),
    ("R12", "Tugas dari Harness Senz", "Tugas dari Harness", False),
    ("R13", "Harness Senz", "Harness", False),
    ("R9", "Ngomong aja, Senz\u2026", "Ngomong aja\u2026", False),
    ("R8a", "Hai Senz", "Hai Asisten", False),
    ("R8b", "hai senz", "hai asisten", False),
    ("R8c", "hei senz", "hei asisten", False),
    ("R8d", "halo senz", "halo asisten", False),
    ("R8e", "ok senz", "ok asisten", False),
    ("R8f", "oke senz", "oke asisten", False),
    ("R16", '"harness@senz"', '"harness@device"', False),
    ("R18", '"Jhon menjangkaumu lewat tailnet ini"', '"Operator menjangkaumu lewat tailnet ini"', False),
    ("R14a", "ask_jhon", "ask_operator", False),
    ("R14b", "check_jhon", "check_operator", False),
    ("R14c", "JHON", "OPERATOR", False),
    ("R14d", "Jhon", "Operator", False),
    ("R14e", "jhon", "operator", False),
    ("R7", "Senz", "Asisten", False),
    ("R6", "senz", "asisten", False),
    ("R17", "Sendy", "user", False),
    ("R19a", '"path_mode" to "resmi"', '"path_mode" to "mandiri"', False),
    ("R19b", 'else "resmi"', 'else "mandiri"', False),
    ("R20a", r"\b4099\b", "4100", True),
    ("R20b", r"\b4197\b", "4199", True),
    ("R20c", r"\b4198\b", "4200", True),
    ("R20d", "~/harness-oc", "~/harness-pure-oc", False),
    ("R20e", "44-harness-oc.sh", "44-harness-pure-oc.sh", False),
    ("R21", "/sdcard/S&Harness", "/sdcard/Harness", False),
]

FLAVORS = {
    "senz": {
        "pkg": "com.senzwh.deepharness",
        "app_label": "S&Harness",
        "apk_name": "DeepSeekHarness-v{ver}.apk",
        "keystore": "debug.keystore",
        "keystore_dname": "CN=Android Debug,O=Android,C=US",
        "replacements": [],
        "splash": None,
    },
    "pure": {
        "pkg": "com.harness.app",
        "app_label": "Harness",
        "apk_name": "Harness-v{ver}-pure.apk",
        "keystore": "pure.keystore",
        "keystore_dname": "CN=Harness,O=Harness,C=US",
        "replacements": REPLACEMENTS_PURE,
        "splash": "flavor/pure/sh_splash.png",
    },
}

# Pola residual 0f (§4.4): HARUS 0 baris di salinan pure. Allowlist implisit:
# "DeepSeek"/"deepseek" berdiri sendiri BOLEH ada (nama mesin/provider) dan
# "Harness" boleh ada (nama baru) — keduanya TIDAK ada di daftar ini.
RESIDUAL_RES = [
    re.compile(r"senz", re.IGNORECASE),
    re.compile(r"snzwh", re.IGNORECASE),
    re.compile(r"sendy", re.IGNORECASE),
    re.compile(r"S&Harness", re.IGNORECASE),
    re.compile(r"DeepSeek Harness", re.IGNORECASE),
    re.compile(r"jhon", re.IGNORECASE),
]
# Port lama juga tidak boleh tersisa (word-boundary: 4109/4092 bukan match).
PORT_RES = [re.compile(r"\b4099\b"), re.compile(r"\b4197\b"), re.compile(r"\b4198\b")]


def resolve_flavor():
    ap = argparse.ArgumentParser()
    ap.add_argument("--flavor", choices=["senz", "pure"], default=None)
    a = ap.parse_args()
    if a.flavor:
        return a.flavor
    env = os.environ.get("FLAVOR", "").strip().lower()
    if env:
        if env not in FLAVORS:
            print(f"FLAVOR env tidak dikenal: {env!r} (harus senz|pure)")
            sys.exit(2)
        return env
    return "senz"


def apply_replacements_pure(src_dir):
    n_files = 0
    total = {}
    for dp, _, fns in os.walk(src_dir):
        for fn in fns:
            if not (fn.endswith(".kt") or fn.endswith(".xml")):
                continue
            fp = os.path.join(dp, fn)
            with open(fp, encoding="utf-8") as f:
                s = f.read()
            orig = s
            for tag, pat, rep, is_rx in REPLACEMENTS_PURE:
                if is_rx:
                    s, n = re.subn(pat, rep, s)
                else:
                    n = s.count(pat)
                    if n:
                        s = s.replace(pat, rep)
                if n:
                    total[tag] = total.get(tag, 0) + n
            if s != orig:
                with open(fp, "w", encoding="utf-8") as f:
                    f.write(s)
            n_files += 1
    print("  replacements pure (per tag): " + ", ".join(f"{t}={c}" for t, c in sorted(total.items()) if c))
    print(f"  {n_files} file .kt/.xml dipindai")
    return total


def verify_flavor_src(src_dir):
    hits = []
    for dp, _, fns in os.walk(src_dir):
        for fn in fns:
            if not (fn.endswith(".kt") or fn.endswith(".xml")):
                continue
            fp = os.path.join(dp, fn)
            with open(fp, encoding="utf-8", errors="replace") as f:
                for i, line in enumerate(f, 1):
                    reasons = []
                    if any(rx.search(line) for rx in RESIDUAL_RES):
                        reasons.append("residual")
                    if any(rx.search(line) for rx in PORT_RES):
                        reasons.append("port-lama")
                    if reasons:
                        hits.append(f"{os.path.relpath(fp, src_dir)}:{i}:{'/'.join(reasons)}:{line.strip()[:160]}")
    return hits


def prepare_flavor_src(flavor):
    """Langkah 0a-0f (§4.4) untuk pure; no-op untuk senz. Return
    BUILD/flavor-src (pure) atau None (senz = tree dipakai apa adanya)."""
    if not flavor["replacements"]:
        print("  flavor senz: tanpa salinan/rewrite (tree dipakai apa adanya)")
        return None
    src = f"{BUILD}/flavor-src"
    print("== 0a. salin app/src/main -> BUILD/flavor-src ==")
    shutil.copytree(f"{PROJ}/app/src/main", src)
    print("== 0b. rename direktori package ==")
    old_pkg_dir = f"{src}/java/com/senzwh/deepharness"
    new_pkg_dir = f"{src}/java/com/harness/app"
    os.makedirs(os.path.dirname(new_pkg_dir), exist_ok=True)
    if not os.path.isdir(old_pkg_dir):
        print("GAGAL 0b: direktori package tidak ditemukan:", old_pkg_dir)
        sys.exit(1)
    os.rename(old_pkg_dir, new_pkg_dir)
    shutil.rmtree(f"{src}/java/com/senzwh", ignore_errors=True)
    print("== 0c. terapkan REPLACEMENTS_PURE ==")
    apply_replacements_pure(src)
    print("== 0d. timpa Flavor.kt ==")
    fk = f"{new_pkg_dir}/Flavor.kt"
    if not os.path.exists(fk):
        print("GAGAL 0d: Flavor.kt tidak ada di salinan (E1 belum diterapkan di tree utama?)")
        sys.exit(1)
    with open(fk, encoding="utf-8") as f:
        fs = f.read()
    if fs.count("IS_PURE = false") != 1:
        print(f"GAGAL 0d: Flavor.kt: 'IS_PURE = false' muncul {fs.count('IS_PURE = false')}x (harus 1x)")
        sys.exit(1)
    fs = fs.replace("IS_PURE = false", "IS_PURE = true", 1)
    # APP_LABEL: 0c (R3) sudah menulis "S&Harness" -> "Harness" di file ini,
    # jadi kedua keadaan ini SAMA-SAMA benar; yang lain = gagal.
    if fs.count('const val APP_LABEL = "S&Harness"') == 1:
        fs = fs.replace('const val APP_LABEL = "S&Harness"', 'const val APP_LABEL = "Harness"', 1)
    elif fs.count('const val APP_LABEL = "Harness"') != 1:
        print("GAGAL 0d: Flavor.kt: APP_LABEL bukan 'S&Harness' maupun 'Harness' (1x)")
        sys.exit(1)
    with open(fk, "w", encoding="utf-8") as f:
        f.write(fs)
    print("== 0e. timpa splash pure ==")
    splash_src = os.path.join(PROJ, flavor["splash"])
    splash_dst = f"{src}/res/drawable-nodpi/sh_splash.png"
    if not os.path.exists(splash_src):
        print("GAGAL 0e: splash pure tidak ada:", splash_src, "(buat flavor/pure/sh_splash.png dulu; tanpa fallback ke splash S&Harness)")
        sys.exit(1)
    shutil.copyfile(splash_src, splash_dst)
    print("== 0e2. timpa ikon launcher pure (V-m5) ==")
    for d in ("mdpi", "hdpi", "xhdpi", "xxhdpi", "xxxhdpi"):
        icon_src = os.path.join(PROJ, "flavor", "pure", f"mipmap-{d}", "ic_launcher.png")
        icon_dst = f"{src}/res/mipmap-{d}/ic_launcher.png"
        if not os.path.exists(icon_src):
            print("GAGAL 0e2: ikon launcher pure tidak ada:", icon_src, "(buat flavor/pure/mipmap-<densitas>/ic_launcher.png dulu; tanpa fallback ke ikon S&Harness)")
            sys.exit(1)
        shutil.copyfile(icon_src, icon_dst)
    print("== 0f. verifikasi residual grep ==")
    hits = verify_flavor_src(src)
    if hits:
        print(f"GAGAL 0f: {len(hits)} baris residual di salinan pure:")
        for h in hits[:40]:
            print("  " + h)
        sys.exit(1)
    print("  residual 0f: 0 baris (allowlist: DeepSeek/deepseek berdiri sendiri + Harness)")
    return src
VERSION_CODE = 309
VERSION_NAME = "4.48.9.143"
ORT_JAR = f"{PROJ}/vendor/ort/classes.jar"
ORT_JNI = f"{PROJ}/vendor/ort/jni/arm64-v8a"
BC_JAR = f"{PROJ}/vendor/bc/bcprov.jar"
SPAKE_JARS = [f"{PROJ}/vendor/spake2/spake2-java-1.1.1.jar",
              f"{PROJ}/vendor/spake2/curve25519-elisabeth-0.1.0.jar",
              f"{PROJ}/vendor/spake2/ed25519-elisabeth-0.1.0.jar"]
CONSCRYPT_JAR = f"{PROJ}/vendor/conscrypt/classes.jar"
CONSCRYPT_SO = f"{PROJ}/vendor/conscrypt/jni/arm64-v8a/libconscrypt_jni.so"
XZ_JAR = f"{PROJ}/vendor/xz/xz.jar"
PROOT_JNI = f"{PROJ}/vendor/proot/jni/arm64-v8a"
EXTRA_JARS = [BC_JAR, CONSCRYPT_JAR, XZ_JAR] + SPAKE_JARS

def run(cmd, fail=""):
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        print(fail or "GAGAL:", cmd[0])
        print(r.stderr[-4000:]); print(r.stdout[-2000:])
        sys.exit(1)
    return r

def main():
    os.environ["JAVA_HOME"] = JH
    os.environ["PATH"] = f"{JH}/bin:" + os.environ.get("PATH", "")
    flavor_name = resolve_flavor()
    FLAVOR = FLAVORS[flavor_name]
    print(f"== flavor: {flavor_name} (pkg={FLAVOR['pkg']}) ==")
    if os.path.exists(BUILD):
        shutil.rmtree(BUILD)
    for d in ("compiled", "gen", "classes", "dex"):
        os.makedirs(f"{BUILD}/{d}", exist_ok=True)

    SRC_DIR = prepare_flavor_src(FLAVOR) or f"{PROJ}/app/src/main"
    print("== 0. aset (UI React pensiun sejak T6 — tanpa npm) ==")
    assets_dir = f"{SRC_DIR}/assets"
    os.makedirs(assets_dir, exist_ok=True)
    shutil.rmtree(f"{assets_dir}/ui", ignore_errors=True)
    print("  assets/ui dibersihkan (APK murni native)")

    print("== 1. aapt2 compile ==")
    run([f"{BT}/aapt2", "compile", "--dir", f"{SRC_DIR}/res",
         "-o", f"{BUILD}/compiled/app.zip"], "aapt2 compile GAGAL")

    print("== 2. aapt2 link ==")
    man = open(f"{SRC_DIR}/AndroidManifest.xml").read()
    man = man.replace("<manifest ",
                      f'<manifest package="{FLAVOR["pkg"]}" android:versionCode="{VERSION_CODE}" android:versionName="{VERSION_NAME}" ', 1)
    man_path = f"{BUILD}/AndroidManifest.xml"
    open(man_path, "w").write(man)
    run([f"{BT}/aapt2", "link", "-o", f"{BUILD}/base.apk", "--manifest", man_path,
         "--java", f"{BUILD}/gen", "--min-sdk-version", "26", "--target-sdk-version", "34",
         "-A", assets_dir, "--no-compress-regex", ".*\\.(onnx|tflite)$",
         "-I", ANDROID_JAR, f"{BUILD}/compiled/app.zip"], "aapt2 link GAGAL")
    print("  base.apk ok")

    print("== 3. kotlinc ==")
    kt_files = []
    for dp, _, fns in os.walk(f"{SRC_DIR}/java"):
        for fn in fns:
            if fn.endswith(".kt"):
                kt_files.append(os.path.join(dp, fn))
    r_java = []
    for dp, _, fns in os.walk(f"{BUILD}/gen"):
        for fn in fns:
            if fn == "R.java":
                r_java.append(os.path.join(dp, fn))
    print(f"  {len(kt_files)} kt + {len(r_java)} R.java")
    # Heap kompiler dinaikkan (v3.3.1): 26+ file bikin kotlinc OOM di heap bawaan.
    os.environ["KOTLINC_OPTS"] = "-Xmx3g"
    os.environ["JAVA_OPTS"] = "-Xmx3g"
    run([KOTLINC, "-J-Xmx3g", "-jvm-target", "17", "-no-stdlib", "-no-reflect",
         "-cp", ":".join([ANDROID_JAR, STDLIB, ORT_JAR] + EXTRA_JARS), "-d", f"{BUILD}/classes"] + kt_files + r_java,
        "kotlinc GAGAL")
    print("  kotlinc ok")

    print("== 4. strip MethodParameters di stdlib ==")
    sys.path.insert(0, f"{HOME}/workspace/senz-browser/tools")
    sys.path.insert(0, os.path.join(PROJ, "tools"))  # salinan repo (diutamakan)
    import strip_methodparams as smp
    stdlib_stripped = f"{BUILD}/kotlin-stdlib-stripped.jar"
    shutil.copy(STDLIB, stdlib_stripped)
    smp.process_jar(stdlib_stripped, stdlib_stripped + ".tmp")
    os.replace(stdlib_stripped + ".tmp", stdlib_stripped)
    # xz.jar (T4b2): MethodParameters bawaannya bikin d8 NPE — strip juga.
    xz_stripped = f"{BUILD}/xz-stripped.jar"
    shutil.copy(XZ_JAR, xz_stripped)
    smp.process_jar(xz_stripped, xz_stripped + ".tmp")
    os.replace(xz_stripped + ".tmp", xz_stripped)

    print("== 5. d8 ==")
    classes_jar = f"{BUILD}/app-classes.jar"
    with zipfile.ZipFile(classes_jar, "w", zipfile.ZIP_DEFLATED) as z:
        for dp, _, fns in os.walk(f"{BUILD}/classes"):
            for fn in fns:
                if fn.endswith(".class"):
                    fp = os.path.join(dp, fn)
                    z.write(fp, os.path.relpath(fp, f"{BUILD}/classes"))
    run([f"{BT}/d8", "--lib", ANDROID_JAR, "--min-api", "26",
         "--output", f"{BUILD}/dex", classes_jar, stdlib_stripped, ORT_JAR] +
        [x if x != XZ_JAR else xz_stripped for x in EXTRA_JARS], "d8 GAGAL")
    print("  dex:", os.listdir(f"{BUILD}/dex"))

    print("== 6. package + sign ==")
    unsigned = f"{BUILD}/app-unsigned.apk"
    shutil.copy(f"{BUILD}/base.apk", unsigned)
    with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as z:
        for fn in sorted(os.listdir(f"{BUILD}/dex")):
            if fn.endswith(".dex"):
                z.write(f"{BUILD}/dex/{fn}", fn)
        # ONNX Runtime arm64 (Tahap B) — diekstrak saat install (extractNativeLibs=true).
        for so in ("libonnxruntime.so", "libonnxruntime4j_jni.so"):
            z.write(os.path.join(ORT_JNI, so), f"lib/arm64-v8a/{so}")
        # Conscrypt mandiri (S3) — JNI untuk TLS 1.3 + exportKeyingMaterial.
        z.write(CONSCRYPT_SO, "lib/arm64-v8a/libconscrypt_jni.so")
        # proot Ubuntu (T4b2) — binary sebagai lib*.so agar diekstrak ke
        # nativeLibraryDir (lolos W^X targetSdk 34); dieksekusi dari sana.
        for so in ("libproot.so", "libprootloader.so", "libtalloc.so", "libandroid-shmem.so"):
            z.write(os.path.join(PROOT_JNI, so), f"lib/arm64-v8a/{so}")
    run([f"{BT}/zipalign", "-f", "4", unsigned, f"{BUILD}/app-aligned.apk"], "zipalign GAGAL")
    ks = f"{PROJ}/{FLAVOR['keystore']}"
    if not os.path.exists(ks):
        run([f"{JH}/bin/keytool", "-genkeypair", "-keystore", ks, "-alias", "androiddebugkey",
             "-keyalg", "RSA", "-keysize", "2048", "-validity", "10950",
             "-storepass", "android", "-keypass", "android",
             "-dname", FLAVOR["keystore_dname"]], "keytool GAGAL")
    final = f"{PROJ}/{FLAVOR['apk_name'].format(ver=VERSION_NAME)}"
    run([f"{BT}/apksigner", "sign", "--ks", ks, "--ks-pass", "pass:android",
         "--key-pass", "pass:android", "--out", final, f"{BUILD}/app-aligned.apk"], "sign GAGAL")
    r = run([f"{BT}/apksigner", "verify", "--print-certs", final])
    print("  verify:", (r.stdout.splitlines() or ["?"])[0])
    print(f"SELESAI: {final} ({os.path.getsize(final)/1024:.0f} KB)")

if __name__ == "__main__":
    main()
