"""Hapus atribut MethodParameters dari semua class file dalam sebuah jar.

Latar: d8 (build-tools 34) NPE saat membaca MethodParameters yang punya
parameter dengan name_index=0 (dihasilkan kotlinc untuk beberapa class).
Solusi: buang atributnya — tidak dibutuhkan saat runtime.

API: process_jar(src, dst)
CLI : python3 strip_methodparams.py in.jar out.jar
"""
import struct
import sys
import zipfile
import os

TARGET = "MethodParameters"


def strip_class(data: bytes) -> bytes:
    p = [0]

    def rd(n):
        v = data[p[0]:p[0] + n]
        p[0] += n
        return v

    def u16():
        return struct.unpack(">H", rd(2))[0]

    out = bytearray()
    out += rd(8)  # magic + minor + major
    cp_count = u16()
    out += struct.pack(">H", cp_count)

    utf8 = {}
    i = 1
    while i < cp_count:
        tag = data[p[0]]
        p[0] += 1
        out.append(tag)
        if tag == 1:  # CONSTANT_Utf8
            ln = struct.unpack_from(">H", data, p[0])[0]
            s = data[p[0] + 2:p[0] + 2 + ln].decode("utf-8", "replace")
            utf8[i] = s
            out += rd(2 + ln)
        elif tag in (7, 8, 16, 19, 20):
            out += rd(2)
        elif tag == 15:
            out += rd(3)
        elif tag in (3, 4):
            out += rd(4)
        elif tag in (5, 6):
            out += rd(8)
            i += 1  # long/double makan 2 slot
        elif tag in (9, 10, 11, 12, 17, 18):
            out += rd(4)
        else:
            raise ValueError(f"constant pool tag tak dikenal: {tag} (index {i})")
        i += 1

    out += rd(6)  # access_flags, this_class, super_class
    n_if = u16()
    out += struct.pack(">H", n_if)
    out += rd(2 * n_if)

    def copy_attrs():
        n = u16()
        kept = []
        for _ in range(n):
            name_idx = struct.unpack_from(">H", data, p[0])[0]
            ln = struct.unpack_from(">I", data, p[0] + 2)[0]
            raw = data[p[0]:p[0] + 6 + ln]
            p[0] += 6 + ln
            if utf8.get(name_idx) == TARGET:
                continue
            kept.append(raw)
        ob = struct.pack(">H", len(kept))
        for k in kept:
            ob += k
        return ob

    def copy_members():
        n = u16()
        ob = bytearray(struct.pack(">H", n))
        for _ in range(n):
            ob += rd(6)  # access, name_index, descriptor_index
            ob += copy_attrs()
        return bytes(ob)

    out += copy_members()  # fields
    out += copy_members()  # methods
    out += copy_attrs()    # class attributes
    assert p[0] == len(data), f"sisa byte tak terbaca: {len(data) - p[0]}"
    return bytes(out)


def process_jar(src: str, dst: str):
    with zipfile.ZipFile(src, "r") as zin:
        with zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
            for info in zin.infolist():
                raw = zin.read(info.filename)
                if info.filename.endswith(".class"):
                    try:
                        raw = strip_class(raw)
                    except Exception as e:
                        print(f"  WARN strip {info.filename}: {e}")
                zout.writestr(info, raw)


if __name__ == "__main__":
    process_jar(sys.argv[1], sys.argv[2])
    print("ok")
