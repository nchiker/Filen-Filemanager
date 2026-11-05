#!/usr/bin/env python3
"""Post-process UniFFI Kotlin output.

UniFFI emits sealed-class variants such as `data class File(val v1: File) : AnyFile()`. Inside the
sealed class, a type name equal to ANY variant name (own or sibling) then resolves to that nested
variant instead of the top-level SDK type, and the generated code does not compile. Fully qualify
those parameter types, using the file's imports to find the right package.
"""
import re, sys, pathlib

SEALED = re.compile(r"^sealed class (\w+)[^\n]*\{\n(.*?)^\}\n", re.S | re.M)
VARIANT = re.compile(r"data class (\w+)\(([^)]*)\)")

def patch(path: pathlib.Path) -> int:
    src = path.read_text()
    pkg = re.search(r"^package ([\w.]+)", src, re.M).group(1)
    imports = {m.group(2): m.group(1) + "." + m.group(2)
               for m in re.finditer(r"^import ([\w.]+)\.(\w+)$", src, re.M)}
    n = 0

    def fix_block(m):
        nonlocal n
        body = m.group(2)
        names = set(re.findall(r"^\s+(?:data class|object|class) (\w+)", body, re.M))
        def fix_variant(v):
            nonlocal n
            params = v.group(2)
            def q(t):
                nonlocal n
                n += 1
                return imports.get(t, f"{pkg}.{t}")
            new = re.sub(r"(:\s*)(\w+)(?![\w.(])",
                         lambda x: x.group(1) + (q(x.group(2)) if x.group(2) in names else x.group(2)),
                         params)
            return f"data class {v.group(1)}({new})"
        return m.group(0).replace(body, VARIANT.sub(fix_variant, body), 1)

    out = SEALED.sub(fix_block, src)
    path.write_text(out)
    return n

if __name__ == "__main__":
    for p in sys.argv[1:]:
        print(f"{p}: qualified {patch(pathlib.Path(p))} shadowed type references")
