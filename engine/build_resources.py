"""Builds adblock-rust resources from uBlock Origin's scriptlet modules without running them.

Reads engine/ubo/*.js as text: every top-level `function name(...) {...}` is cut out with a JavaScript-aware
brace matcher, and every `registerScriptlet(fn, {...})` / `builtinScriptlets.push({...})` gives the scriptlet's
name, aliases, dependencies and whether it needs a trusted list. Brave's own resources.json is merged in.

Usage: python -I build_resources.py <ubo dir> <brave resources.json> <out.json>
"""
import base64
import json
import os
import re
import sys

TRUSTED = 1  # permission bit; the uBlock Origin lists are compiled with it (compile.rs)
REGEX_PRECEDERS = set("(,=:[!&|?{};+-*%<>~^") | {""}


def skip_string(src, i):
    quote = src[i]
    i += 1
    while i < len(src):
        c = src[i]
        if c == "\\":
            i += 2
            continue
        if quote == "`" and c == "$" and src.startswith("${", i):
            i = match_braces(src, i + 1) + 1
            continue
        if c == quote:
            return i + 1
        i += 1
    raise ValueError("unterminated string")


def skip_regex(src, i):
    i += 1
    in_class = False
    while i < len(src):
        c = src[i]
        if c == "\\":
            i += 2
            continue
        if c == "[":
            in_class = True
        elif c == "]":
            in_class = False
        elif c == "/" and not in_class:
            i += 1
            while i < len(src) and src[i].isalpha():
                i += 1
            return i
        elif c == "\n":
            raise ValueError("newline in regex")
        i += 1
    raise ValueError("unterminated regex")


def prev_significant(src, i):
    j = i - 1
    while j >= 0 and src[j] in " \t\r\n":
        j -= 1
    if j < 0:
        return ""
    # a keyword before '/' (return /x/, typeof /x/) means a regex
    m = re.search(r"([A-Za-z_$][\w$]*)$", src[: j + 1])
    if m and m.group(1) in ("return", "typeof", "case", "in", "of", "delete", "void", "throw", "new"):
        return "("
    return src[j]


def match_braces(src, i):
    """src[i] is '{' (or '('/'['); returns the index of its matching closer."""
    pairs = {"{": "}", "(": ")", "[": "]"}
    stack = [pairs[src[i]]]
    i += 1
    while i < len(src):
        c = src[i]
        if c in "'\"`":
            i = skip_string(src, i)
            continue
        if c == "/" and src.startswith("//", i):
            i = src.index("\n", i)
            continue
        if c == "/" and src.startswith("/*", i):
            i = src.index("*/", i) + 2
            continue
        if c == "/" and prev_significant(src, i) in REGEX_PRECEDERS:
            i = skip_regex(src, i)
            continue
        if c in pairs:
            stack.append(pairs[c])
        elif c in ")]}":
            if c != stack.pop():
                raise ValueError(f"mismatched {c} at {i}")
            if not stack:
                return i
        i += 1
    raise ValueError("unbalanced")


FUNC_RE = re.compile(r"^(?:export\s+)?(?:async\s+)?function\s+([A-Za-z_$][\w$]*)\s*\(", re.M)
CLASS_RE = re.compile(r"^(?:export\s+)?class\s+([A-Za-z_$][\w$]*)[^{]*\{", re.M)


def functions(src):
    out = {}
    # Helper classes (ArglistParser, JSONPath, ...) are dependencies: included as written, never called.
    for m in CLASS_RE.finditer(src):
        start = m.start() + (len("export ") if src.startswith("export", m.start()) else 0)
        out[m.group(1)] = src[start : match_braces(src, m.end() - 1) + 1]
    for m in FUNC_RE.finditer(src):
        name = m.group(1)
        start = m.start() + (len("export ") if src.startswith("export", m.start()) else 0)
        params_end = match_braces(src, m.end() - 1)
        body = src.index("{", params_end)
        end = match_braces(src, body)
        out[name] = src[start : end + 1]
    return out


def parse_details(text):
    """The object literal of a registration, read field by field."""
    d = {}
    m = re.search(r"\bname\s*:\s*(['\"])(.+?)\1", text)
    d["name"] = m.group(2) if m else None
    m = re.search(r"\bfn\s*:\s*([A-Za-z_$][\w$]*)", text)
    d["fn"] = m.group(1) if m else None
    m = re.search(r"\baliases\s*:\s*\[(.*?)\]", text, re.S)
    d["aliases"] = re.findall(r"['\"]([^'\"]+)['\"]", m.group(1)) if m else []
    m = re.search(r"\bdependencies\s*:\s*\[(.*?)\]", text, re.S)
    deps = []
    if m:
        for tok in re.findall(r"['\"][^'\"]+['\"]|[A-Za-z_$][\w$]*", m.group(1)):
            deps.append(("str", tok[1:-1]) if tok[0] in "'\"" else ("ident", tok))
    d["dependencies"] = deps
    d["requiresTrust"] = bool(re.search(r"\brequiresTrust\s*:\s*true", text))
    return d


def main(ubo_dir, brave_json, out_path):
    funcs, regs = {}, []
    for fname in sorted(os.listdir(ubo_dir)):
        if not fname.endswith(".js"):
            continue
        src = open(os.path.join(ubo_dir, fname), encoding="utf-8").read()
        funcs.update(functions(src))
        for m in re.finditer(r"registerScriptlet\(\s*([A-Za-z_$][\w$]*)\s*,\s*\{", src):
            obj_end = match_braces(src, m.end() - 1)
            d = parse_details(src[m.end() - 1 : obj_end + 1])
            d["fn"] = m.group(1)
            regs.append(d)
        for m in re.finditer(r"builtinScriptlets\.push\(\s*\{", src):
            obj_end = match_braces(src, m.end() - 1)
            regs.append(parse_details(src[m.end() - 1 : obj_end + 1]))

    ident_to_name = {r["fn"]: r["name"] for r in regs if r["fn"] and r["name"]}
    out, seen, trusted, skipped = [], set(), 0, []
    for r in regs:
        name, fn = r["name"], r["fn"]
        if not name or fn not in funcs or name in seen:
            skipped.append(name or fn)
            continue
        deps = []
        for kind, val in r["dependencies"]:
            dep = val if kind == "str" else ident_to_name.get(val)
            if dep:
                deps.append(dep)
        seen.add(name)
        trusted += r["requiresTrust"]
        out.append({
            "name": name,
            "aliases": [a for a in r["aliases"] if a != name],
            "kind": {"mime": "application/javascript"},
            "content": base64.b64encode(funcs[fn].encode("utf-8")).decode("ascii"),
            "dependencies": deps,
            "permission": TRUSTED if r["requiresTrust"] else 0,
        })
    names = {r["name"] for r in out}
    missing = sorted({d for r in out for d in r["dependencies"] if d not in names})
    brave = json.load(open(brave_json, encoding="utf-8"))
    for r in brave:
        if r["name"] not in names:
            out.append(r)
    json.dump(out, open(out_path, "w", encoding="utf-8"))
    print(f"{len(out)} resources: {len(out) - len(brave)} from uBlock Origin ({trusted} trusted), "
          f"{len(brave)} from Brave; skipped {len(skipped)}; missing deps {missing}")


if __name__ == "__main__":
    main(*sys.argv[1:4])
