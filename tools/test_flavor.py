#!/usr/bin/env python3
"""Test tanpa-JVM untuk logika flavor Pure di build.py.

Memastikan daftar REPLACEMENTS_PURE benar-benar menghapus identitas lama
(paket `com.senzwh.deepharness`, brand `Senz`, `S&Harness`, dll.) dari
sampel teks — penjaga regresi agar build Pure tidak bocor identitas.

Jalankan: python3 tools/test_flavor.py
"""
import os
import re
import sys

PROJ = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, PROJ)

import build  # noqa: E402  (modul build.py; aman diimpor — tidak menjalankan main())

failed = 0


def check(name, cond, detail=""):
    global failed
    if cond:
        print("  ok   " + name)
    else:
        failed += 1
        print("  FAIL " + name + ((" — " + detail) if detail else ""))


def apply_all(text):
    for _tag, pattern, repl, is_regex in build.REPLACEMENTS_PURE:
        if is_regex:
            text = re.sub(pattern, repl, text)
        else:
            text = text.replace(pattern, repl)
    return text


print("== test_flavor ==")
check("REPLACEMENTS_PURE tidak kosong", len(build.REPLACEMENTS_PURE) > 0,
      str(len(build.REPLACEMENTS_PURE)))

tags = [t[0] for t in build.REPLACEMENTS_PURE]
check("tag unik", len(tags) == len(set(tags)),
      str([t for t in tags if tags.count(t) > 1]))

sample = (
    "package com.senzwh.deepharness.priv\n"
    "import com.senzwh.deepharness.MainActivity\n"
    "S&Harness Senz Asisten HP Senz Hai Senz hei senz\n"
    "DeepSeek Harness Tugas dari Harness Senz Harness Senz\n"
)
out = apply_all(sample)
check("paket .priv diganti", "com.senzwh.deepharness.priv" not in out)
check("paket utama diganti", "com.senzwh.deepharness" not in out)
check("S&Harness hilang", "S&Harness" not in out)
check("'Senz' hilang", "Senz" not in out)
check("'senz' hilang", "senz" not in out)
check("'HP Senz' hilang", "HP Senz" not in out)
check("hasil memakai paket pure", "com.harness.app" in out, out[:80])

# Pola verify (0f) di build.py memakai regex untuk identitas lama; pastikan
# nama paket lama tidak lolos dari penggantian pada sampel.
check("tidak ada sisa 'deepharness'", "deepharness" not in out.lower(), out)

print("SEMUA LULUS" if failed == 0 else "GAGAL: %d cek" % failed)
sys.exit(1 if failed else 0)
