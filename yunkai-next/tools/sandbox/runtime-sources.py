#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright (c) 2026 Mika authors
"""Collect the corresponding source for every copyleft package in the bundled runtime (for the GPL source pack).

Usage:
  runtime-sources.py OUT_DIR [--only ORIGIN ...]

Reads app/src/main/assets/runtime/packages.json (written by build-runtime.py). For each package whose license names
GPL, LGPL or MPL, takes its Alpine build recipe at the exact aports commit recorded in the package
(APKBUILD and local patches), plus every upstream source file listed in the recipe's sha512sums, and checks each
file against that SHA-512. Writes OUT_DIR/<origin>-<version>/ and OUT_DIR/SOURCES.txt (file, SHA-512, where from).

The recipe comes from the aports mirror on GitHub; upstream files come from Alpine's distfiles mirror.
"""

import argparse
import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.request

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
MANIFEST = os.path.join(ROOT, "app", "src", "main", "assets", "runtime", "packages.json")
RECIPES = "https://raw.githubusercontent.com/alpinelinux/aports"
COPYLEFT = re.compile(r"\b(L?GPL|MPL)")


def get(url):
    with urllib.request.urlopen(url, timeout=300) as r:
        return r.read()


def sha512sums(apkbuild):
    m = re.search(r'^sha512sums="(.*?)"', apkbuild, re.S | re.M)
    if not m:
        sys.exit(f"runtime-sources: no sha512sums in APKBUILD")
    out = []
    for line in m.group(1).strip().splitlines():
        parts = line.split()
        if len(parts) == 2:
            out.append((parts[1], parts[0]))
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("out")
    ap.add_argument("--only", nargs="*")
    a = ap.parse_args()
    m = json.load(open(MANIFEST, encoding="utf-8"))
    branch = "v" + ".".join(m["alpine"].split(".")[:2])
    distfiles = f"https://distfiles.alpinelinux.org/distfiles/{branch}"
    recipes = {}
    for p in m["packages"]:
        if COPYLEFT.search(p["license"]):
            recipes.setdefault((p["repo"], p["origin"], p["commit"]), []).append(p)
    index = []
    for (repo, origin, commit), pkgs in sorted(recipes.items(), key=lambda kv: kv[0][1]):
        if a.only and origin not in a.only:
            continue
        version = pkgs[0]["version"]
        d = os.path.join(a.out, f"{origin}-{version}")
        os.makedirs(d, exist_ok=True)
        base = f"{RECIPES}/{commit}/{repo}/{origin}"
        apkbuild = get(f"{base}/APKBUILD")
        open(os.path.join(d, "APKBUILD"), "wb").write(apkbuild)
        index.append(f"{origin}-{version}/APKBUILD  {hashlib.sha512(apkbuild).hexdigest()}  {base}/APKBUILD")
        for name, want in sha512sums(apkbuild.decode()):
            data, src = None, None
            for url in (f"{base}/{name}", f"{distfiles}/{name}"):
                try:
                    data, src = get(url), url
                except urllib.error.HTTPError:
                    continue
                if hashlib.sha512(data).hexdigest() == want:
                    break
                data = None
            if data is None:
                sys.exit(f"runtime-sources: {origin}: {name} not found with SHA-512 {want}")
            open(os.path.join(d, name), "wb").write(data)
            index.append(f"{origin}-{version}/{name}  {want}  {src}")
        names = ", ".join(sorted(f"{p['name']} {p['version']} ({p['license']})" for p in pkgs))
        index.append(f"# {origin}: aports {repo}/{origin} at {commit}; built packages: {names}")
        print(f"{origin}-{version}: ok")
    open(os.path.join(a.out, "SOURCES.txt"), "w", encoding="utf-8").write("\n".join(index) + "\n")


if __name__ == "__main__":
    main()
