#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) 2026 Mika authors
"""Assemble the runtime that ships inside the APK: the Alpine minirootfs plus pinned packages, as one .tar.xz.

Nothing runs apk: packages are unpacked the way apk lays them out, and /lib/apk/db/installed is written
from their signed metadata, so apk inside the runtime sees a consistent database.

Usage:
  build-runtime.py --resolve   re-resolve the package closure from the signed v3.22 APKINDEX, rewrite runtime/sources.lock
  build-runtime.py             build from runtime/sources.lock; write the asset, BundledRuntime.kt and runtime/packages.json
  build-runtime.py --check     build into a temp dir and compare with the committed BundledRuntime.kt; exit 1 on mismatch

Every download is https only and verified: the minirootfs by SHA-256; each .apk by SHA-256 from the lock,
by its own RSA signature (keys from the pinned minirootfs), by its control checksum (the APKINDEX `C:` value),
and its data part by the `datahash` in its signed .PKGINFO. --resolve also checks the APKINDEX signature.
Needs python3 >= 3.9 and openssl (for RSA signature checks).
"""

import argparse
import base64
import hashlib
import io
import json
import lzma
import os
import re
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
import zlib

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
HERE = os.path.join(ROOT, "tools", "sandbox", "runtime")
LOCK = os.path.join(HERE, "sources.lock")
MANIFEST = os.path.join(ROOT, "app", "src", "main", "assets", "runtime", "packages.json")
ASSET = os.path.join(ROOT, "app", "src", "main", "assets", "runtime", "rootfs.tar.xz")
KOTLIN = os.path.join(ROOT, "app", "src", "main", "java", "com", "wangzan101", "mika", "cap", "sandbox", "BundledRuntime.kt")
CLIENT = os.path.join(ROOT, "app", "src", "main", "assets", "sandbox", "mika")
CACHE = os.path.join(ROOT, "build", "sandbox-runtime", "dl")
CDN = "https://dl-cdn.alpinelinux.org/alpine"
ARCH = "aarch64"
# What `apk add` would be asked for. Keep in sync with core SANDBOX_PACKAGES (a JVM test checks it).
WANT = ["python3", "nodejs", "py3-pillow", "py3-pypdf"]
# Install scripts and triggers that a real `apk add` would run on the phone, pinned by SHA-256 of the script, and why
# the runtime is already right without running them. Any other install script or trigger stops the build.
SCRIPTS_NOT_RUN = {
    "icu-data-en/.post-install": ("1b60012707d8e5c6735bbe4620a08335e019618b776462da11fe2574d4a3dba4",
                                  "only prints a hint about icu-data-full"),
    # update-ca-certificates rebuilds /etc/ssl/certs/ca-certificates.crt; the minirootfs already has that file
    # from ca-certificates-bundle, built from the same Mozilla list at the same version
    "ca-certificates/.trigger": ("1bf77c8eae3220b1bd7dd86561d0899315f51d72257c1aeb4066072ed4304fe5",
                                 "same CA list as ca-certificates-bundle"),
}


def die(msg):
    print(f"build-runtime: {msg}", file=sys.stderr)
    sys.exit(1)


def fetch(url, dest):
    if not url.startswith("https://"):
        die(f"only https downloads allowed: {url}")
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    tmp = dest + ".part"
    with urllib.request.urlopen(url, timeout=120) as r, open(tmp, "wb") as f:
        while True:
            b = r.read(1 << 20)
            if not b:
                break
            f.write(b)
    os.replace(tmp, dest)


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def cached(url, name, want_sha=None):
    """Download into the cache unless a copy with the right SHA-256 is there; return the bytes."""
    path = os.path.join(CACHE, name)
    if not (want_sha and os.path.isfile(path) and sha256(open(path, "rb").read()) == want_sha):
        fetch(url, path)
    data = open(path, "rb").read()
    if want_sha and sha256(data) != want_sha:
        os.remove(path)
        die(f"SHA-256 mismatch for {name}: {sha256(data)} (expected {want_sha})")
    return data


def gzip_streams(data):
    """Split concatenated gzip members; return their raw byte ranges (apk v2 packages are 2-3 members)."""
    out, pos = [], 0
    while pos < len(data):
        d = zlib.decompressobj(31)
        d.decompress(data[pos:])
        if not d.eof:
            die("truncated gzip member")
        end = len(data) - len(d.unused_data)
        out.append(data[pos:end])
        pos = end
    return out


def tar_members(gz_bytes):
    """Read one gzip member as a tar; return [(TarInfo, bytes|None)]. apk control/signature tars have no end marker."""
    raw = zlib.decompress(gz_bytes, 31)
    res = []
    with tarfile.open(fileobj=io.BytesIO(raw), mode="r:", ignore_zeros=False) as t:
        for m in t:
            res.append((m, t.extractfile(m).read() if m.isreg() else None))
    return res


def rsa_verify(keys, sig_name, sig, signed):
    """sig_name is `.SIGN.RSA.<key>` (SHA-1) or `.SIGN.RSA256.<key>` (SHA-256)."""
    m = re.fullmatch(r"\.SIGN\.(RSA|RSA256)\.(.+)", sig_name)
    if not m or m.group(2) not in keys:
        die(f"unknown signature {sig_name}")
    digest = "-sha1" if m.group(1) == "RSA" else "-sha256"
    with tempfile.TemporaryDirectory() as d:
        k, s, b = (os.path.join(d, x) for x in ("k", "s", "b"))
        open(k, "wb").write(keys[m.group(2)])
        open(s, "wb").write(sig)
        open(b, "wb").write(signed)
        r = subprocess.run(["openssl", "dgst", digest, "-verify", k, "-signature", s, b], capture_output=True)
    if r.returncode != 0:
        die(f"bad signature ({sig_name})")


def q1(digest):
    return "Q1" + base64.b64encode(digest).decode()


def parse_index(text):
    pkgs = []
    for blk in text.split("\n\n"):
        f = {}
        lines = []
        for line in blk.split("\n"):
            if len(line) > 1 and line[1] == ":":
                f.setdefault(line[0], line[2:])
                lines.append(line)
        if "P" in f:
            f["_lines"] = lines
            pkgs.append(f)
    return pkgs


def read_lock():
    alpine, pkgs = None, []
    for line in open(LOCK, encoding="utf-8"):
        parts = line.split()
        if not parts or parts[0].startswith("#"):
            continue
        if parts[0] == "alpine":
            alpine = {"version": parts[1], "url": parts[2], "sha256": parts[3]}
        elif parts[0] == "apk":
            pkgs.append({"repo": parts[1], "name": parts[2], "version": parts[3], "checksum": parts[4], "sha256": parts[5]})
    if not alpine:
        die("sources.lock has no alpine line")
    return alpine, pkgs


def branch(version):
    return "v" + ".".join(version.split(".")[:2])


def minirootfs(alpine):
    data = cached(alpine["url"], os.path.basename(alpine["url"]), alpine["sha256"])
    raw = zlib.decompress(data, 31)
    keys = {}
    with tarfile.open(fileobj=io.BytesIO(raw), mode="r:") as t:
        for m in t:
            if m.isreg() and m.name.lstrip("./").startswith("etc/apk/keys/"):
                keys[os.path.basename(m.name)] = t.extractfile(m).read()
    return raw, keys


def apk_url(alpine, p):
    return f"{CDN}/{branch(alpine['version'])}/{p['repo']}/{ARCH}/{p['name']}-{p['version']}.apk"


# ---------------------------------------------------------------- resolve

def resolve():
    alpine, _ = read_lock()
    base_raw, keys = minirootfs(alpine)
    with tarfile.open(fileobj=io.BytesIO(base_raw), mode="r:") as t:
        base = parse_index(t.extractfile("./lib/apk/db/installed").read().decode())
    index = {}
    for repo in ("main", "community"):
        url = f"{CDN}/{branch(alpine['version'])}/{repo}/{ARCH}/APKINDEX.tar.gz"
        path = os.path.join(CACHE, f"APKINDEX-{repo}.tar.gz")
        fetch(url, path)
        sig, body = gzip_streams(open(path, "rb").read())[:2]
        (sm, sdata), = tar_members(sig)
        rsa_verify(keys, sm.name, sdata, body)
        text = dict((m.name, d) for m, d in tar_members(body))["APKINDEX"].decode()
        for p in parse_index(text):
            p["_repo"] = repo
            index.setdefault(p["P"], p)  # main wins over community
    provides = {}
    for p in index.values():
        for pr in p.get("p", "").split():
            provides.setdefault(re.split(r"[<>=~]", pr)[0], []).append(p)
    have = set()
    for p in base:
        have.add(p["P"])
        have.update(re.split(r"[<>=~]", pr)[0] for pr in p.get("p", "").split())
        if p["P"] in index and index[p["P"]]["V"] != p["V"]:
            die(f"minirootfs has {p['P']} {p['V']} but the index has {index[p['P']]['V']}: pin a newer minirootfs")
    chosen = []

    def add(p):
        chosen.append(p)
        have.add(p["P"])
        have.update(re.split(r"[<>=~]", pr)[0] for pr in p.get("p", "").split())
        for dep in p.get("D", "").split():
            need(dep)

    def need(dep):
        if dep.startswith("!"):
            return
        name = re.split(r"[<>=~]", dep)[0]
        if name in have:
            return
        cand = index.get(name) or sorted(provides.get(name, []), key=lambda c: c["P"])[:1]
        if isinstance(cand, list):
            cand = cand[0] if cand else None
        if cand is None:
            die(f"cannot resolve {dep}")
        if cand["P"] not in have:
            add(cand)

    for w in WANT:
        need(w)
    # install_if: apk adds these on its own when every condition is installed
    changed = True
    while changed:
        changed = False
        for p in index.values():
            cond = p.get("i", "").split()
            if cond and p["P"] not in have and all(re.split(r"[<>=~]", c)[0] in have for c in cond):
                add(p)
                changed = True

    lines = [
        "# Runtime bundled in the APK. Generated by build-runtime.py --resolve; edit only the alpine line by hand.",
        "# alpine <version> <minirootfs url> <sha256>",
        "# apk <repo> <name> <version> <APKINDEX checksum> <sha256 of the .apk>",
        f"alpine {alpine['version']} {alpine['url']} {alpine['sha256']}",
    ]
    for p in chosen:
        e = {"repo": p["_repo"], "name": p["P"], "version": p["V"]}
        data = cached(apk_url(alpine, e), f"{p['P']}-{p['V']}.apk")
        lines.append(f"apk {p['_repo']} {p['P']} {p['V']} {p['C']} {sha256(data)}")
    open(LOCK, "w", encoding="utf-8").write("\n".join(lines) + "\n")
    print(f"resolved {len(chosen)} packages into {os.path.relpath(LOCK, ROOT)}")


# ---------------------------------------------------------------- build

class Tree:
    """Ordered path -> (TarInfo, bytes). First position wins, so the output order stays stable."""

    def __init__(self):
        self.entries = {}
        self.owner = {}

    def put(self, info, data, owner=None):
        name = info.name.lstrip("./").rstrip("/")
        if not name:
            return
        info.name = name
        old = self.entries.get(name)
        if old and old[0].isdir() and info.isdir():
            return
        if old and owner and self.owner.get(name) not in (None, owner) and not info.isdir():
            die(f"{owner} and {self.owner[name]} both install /{name}")
        self.entries[name] = (info, data)
        if owner:
            self.owner[name] = owner


def norm(info, mtime=None):
    info.uid = info.gid = 0
    info.uname = info.gname = ""
    info.pax_headers = {}
    if mtime is not None:
        info.mtime = mtime
    return info


def unpack_apk(alpine, p, keys):
    data = cached(apk_url(alpine, p), f"{p['name']}-{p['version']}.apk", p["sha256"])
    parts = gzip_streams(data)
    if len(parts) != 3:
        die(f"{p['name']}: expected 3 gzip members, got {len(parts)}")
    sig, control, payload = parts
    (sm, sdata), = tar_members(sig)
    rsa_verify(keys, sm.name, sdata, control)
    if q1(hashlib.sha1(control).digest()) != p["checksum"]:
        die(f"{p['name']}: control checksum differs from the lock")
    ctl = dict((m.name, d) for m, d in tar_members(control))
    info = {}
    for line in ctl[".PKGINFO"].decode().splitlines():
        if " = " in line:
            k, v = line.split(" = ", 1)
            info.setdefault(k, []).append(v)
    if info.get("datahash", [""])[0] != sha256(payload):
        die(f"{p['name']}: data hash differs from its signed .PKGINFO")
    if info["pkgname"][0] != p["name"] or info["pkgver"][0] != p["version"]:
        die(f"{p['name']}: .PKGINFO names {info['pkgname'][0]} {info['pkgver'][0]}")
    # Upgrade/removal scripts and triggers go into scripts.tar for later apk runs. Install scripts and triggers are not
    # run here, so each one must be listed in SCRIPTS_NOT_RUN with its exact content.
    scripts = {n: d for n, d in ctl.items() if n.startswith(".") and n != ".PKGINFO"}
    for n in sorted(scripts):
        if n in (".pre-install", ".post-install", ".trigger"):
            pinned = SCRIPTS_NOT_RUN.get(f"{p['name']}/{n}")
            if not pinned or pinned[0] != sha256(scripts[n]):
                die(f"{p['name']} has {n} ({sha256(scripts[n])}); check what it does and list it in SCRIPTS_NOT_RUN")
    info["_scripts"] = scripts
    raw = zlib.decompress(payload, 31)
    members = []
    with tarfile.open(fileobj=io.BytesIO(raw), mode="r:") as t:
        for m in t:
            csum = m.pax_headers.get("APK-TOOLS.checksum.SHA1")
            members.append((m, t.extractfile(m).read() if m.isreg() else None, csum))
    return info, members


def installed_entry(index_lines, members):
    """One /lib/apk/db/installed record: the package's index header, then F:/M: per directory, R:/a:/Z: per file."""
    out = list(index_lines)
    dirs = {}
    order = []
    for m, _, csum in members:
        name = m.name.lstrip("./").rstrip("/")
        if m.isdir():
            if name not in dirs:
                dirs[name] = (m, [])
                order.append(name)
        else:
            parent = os.path.dirname(name)
            if parent not in dirs:
                dirs[parent] = (None, [])
                order.append(parent)
            dirs[parent][1].append((m, csum))
    for d in order:
        m, files = dirs[d]
        if d == "" and not files:
            continue
        out.append(f"F:{d}")
        if m is not None and (m.uid, m.gid, m.mode & 0o7777) != (0, 0, 0o755):
            out.append(f"M:{m.uid}:{m.gid}:{m.mode & 0o7777:o}")
        for f, csum in files:
            out.append(f"R:{os.path.basename(f.name)}")
            if (f.uid, f.gid, f.mode & 0o7777) != (0, 0, 0o644):
                out.append(f"a:{f.uid}:{f.gid}:{f.mode & 0o7777:o}")
            if csum:
                out.append("Z:" + q1(bytes.fromhex(csum)))
    return "\n".join(out) + "\n\n"


def index_header(p, info, payload_size):
    """The installed-db header for a package, rebuilt from its signed .PKGINFO (same fields apk writes)."""
    g = lambda k: info.get(k, [""])[0]
    h = [f"C:{p['checksum']}", f"P:{p['name']}", f"V:{p['version']}", f"A:{g('arch')}", f"S:{payload_size}",
         f"I:{g('size')}", f"T:{g('pkgdesc')}", f"U:{g('url')}", f"L:{g('license')}", f"o:{g('origin')}",
         f"m:{g('maintainer')}", f"t:{g('builddate')}", f"c:{g('commit')}"]
    if info.get("provider_priority"):
        h.append(f"k:{g('provider_priority')}")
    if info.get("depend"):
        h.append("D:" + " ".join(info["depend"]))
    if info.get("provides"):
        h.append("p:" + " ".join(info["provides"]))
    if info.get("install_if"):
        h.append("i:" + " ".join(info["install_if"]))
    if info.get("replaces"):
        h.append("r:" + " ".join(info["replaces"]))
    return h


def build(out_asset):
    alpine, pkgs = read_lock()
    base_raw, keys = minirootfs(alpine)
    tree = Tree()
    with tarfile.open(fileobj=io.BytesIO(base_raw), mode="r:") as t:
        db_info = t.getmember("./lib/apk/db/installed")
        epoch = db_info.mtime
        installed = t.extractfile(db_info).read().decode()
        for m in t.getmembers():
            tree.put(norm(m), t.extractfile(m).read() if m.isreg() else None)
    base_names = {p["P"] for p in parse_index(installed)}
    manifest = []
    db = installed.rstrip("\n") + "\n\n"
    extra_scripts = []
    with tarfile.open(fileobj=io.BytesIO(base_raw), mode="r:") as t:
        base_scripts = t.extractfile("./lib/apk/db/scripts.tar").read()
        triggers = t.extractfile("./lib/apk/db/triggers").read().decode()
    for p in pkgs:
        if p["name"] in base_names:
            die(f"{p['name']} is already in the minirootfs")
        info, members = unpack_apk(alpine, p, keys)
        for m, data, _ in members:
            tree.put(norm(m), data, owner=p["name"])
        size = len(cached(apk_url(alpine, p), f"{p['name']}-{p['version']}.apk", p["sha256"]))
        db += installed_entry(index_header(p, info, size), members)
        for sname, sdata in sorted(info["_scripts"].items()):
            extra_scripts.append((f"{p['name']}-{p['version']}.{p['checksum']}{sname}", sdata))
        if info.get("triggers"):
            triggers += f"{p['checksum']} {' '.join(info['triggers'][0].split())}\n"
        manifest.append({"name": p["name"], "version": p["version"], "license": info.get("license", [""])[0],
                         "origin": info.get("origin", [""])[0], "url": info.get("url", [""])[0], "repo": p["repo"],
                         "commit": info.get("commit", [""])[0]})
    for p in parse_index(installed):
        manifest.append({"name": p["P"], "version": p["V"], "license": p.get("L", ""), "origin": p.get("o", ""),
                         "url": p.get("U", ""), "repo": "main", "commit": p.get("c", "")})
    manifest.sort(key=lambda e: e["name"])

    def gen(path, data, mode=0o644):
        ti = tarfile.TarInfo(path)
        ti.size = len(data)
        ti.mode = mode
        tree.put(norm(ti, epoch), data)

    world = sorted(set(open_world(base_raw) + WANT))
    gen("lib/apk/db/installed", db.encode())
    gen("lib/apk/db/triggers", triggers.encode())
    sbuf = io.BytesIO()
    with tarfile.open(fileobj=io.BytesIO(base_scripts), mode="r:") as bt, \
            tarfile.open(fileobj=sbuf, mode="w:", format=tarfile.USTAR_FORMAT) as st:
        for m in bt.getmembers():
            st.addfile(m, bt.extractfile(m))
        for sname, sdata in extra_scripts:
            ti = norm(tarfile.TarInfo(sname), epoch)
            ti.size, ti.mode = len(sdata), 0o755
            st.addfile(ti, io.BytesIO(sdata))
    gen("lib/apk/db/scripts.tar", sbuf.getvalue())
    gen("etc/apk/world", ("\n".join(world) + "\n").encode())
    b = branch(alpine["version"])
    gen("etc/apk/repositories", f"{CDN}/{b}/main\n{CDN}/{b}/community\n".encode())
    gen("etc/hosts", b"127.0.0.1 localhost\n::1 localhost\n")
    gen("usr/local/bin/mika", open(CLIENT, "rb").read(), 0o755)

    buf = io.BytesIO()
    total = 0
    with tarfile.open(fileobj=buf, mode="w:", format=tarfile.PAX_FORMAT) as out:
        for name, (info, data) in tree.entries.items():
            if info.isreg():
                info.size = len(data)
                total += len(data)
                out.addfile(info, io.BytesIO(data))
            else:
                out.addfile(info)
    tar = buf.getvalue()
    xz = lzma.compress(tar, format=lzma.FORMAT_XZ, check=lzma.CHECK_CRC64, preset=9)
    os.makedirs(os.path.dirname(out_asset), exist_ok=True)
    open(out_asset, "wb").write(xz)
    return {
        "alpine": alpine["version"], "sha256": sha256(xz), "bytes": len(xz), "fileBytes": total,
        "entries": len(tree.entries), "packages": manifest,
    }


def open_world(base_raw):
    with tarfile.open(fileobj=io.BytesIO(base_raw), mode="r:") as t:
        return t.extractfile("./etc/apk/world").read().decode().split()


def version_id(r):
    return f"{r['alpine']}-{r['sha256'][:12]}"


def kotlin(r):
    return f"""package com.wangzan101.mika.sandbox

// Generated by tools/sandbox/build-runtime.py; do not edit.

/** 随安装包自带的运行环境(assets/{os.path.relpath(ASSET, os.path.join(ROOT, 'app', 'src', 'main', 'assets'))})。 */
object BundledRuntime {{
    const val ASSET = "runtime/rootfs.tar.xz"
    const val VERSION = "{version_id(r)}"
    const val SHA256 = "{r['sha256']}"
    const val BYTES = {r['bytes']}L
    /** 解出的普通文件总字节数与条目数:解包时的上限按它们定,多一点余量。 */
    const val FILE_BYTES = {r['fileBytes']}L
    const val ENTRIES = {r['entries']}
}}
"""


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    g = ap.add_mutually_exclusive_group()
    g.add_argument("--resolve", action="store_true")
    g.add_argument("--check", action="store_true")
    a = ap.parse_args()
    if a.resolve:
        resolve()
        return
    if a.check:
        with tempfile.TemporaryDirectory() as d:
            r = build(os.path.join(d, "rootfs.tar.xz"))
        committed = open(KOTLIN, encoding="utf-8").read() if os.path.isfile(KOTLIN) else ""
        if kotlin(r) != committed:
            die(f"rebuilt runtime {r['sha256']} differs from BundledRuntime.kt")
        if os.path.isfile(ASSET) and sha256(open(ASSET, "rb").read()) != r["sha256"]:
            die("assets/runtime/rootfs.tar.xz differs from the rebuild")
        print(f"ok {version_id(r)} ({r['bytes']} bytes)")
        return
    r = build(ASSET)
    open(KOTLIN, "w", encoding="utf-8").write(kotlin(r))
    json.dump({"version": version_id(r), "alpine": r["alpine"], "packages": r["packages"]},
              open(MANIFEST, "w", encoding="utf-8"), indent=1, ensure_ascii=False)
    open(MANIFEST, "a").write("\n")
    print(f"built {version_id(r)}: {r['bytes']} bytes compressed, {r['fileBytes']} bytes of files, {r['entries']} entries")


if __name__ == "__main__":
    main()
