#!/usr/bin/env python3
"""Parse a Gradle/javac log into per-file error lists and assign owner groups.

Usage: python compile_errors.py <gradle_log> [--json out.json]
"""
import json
import re
import sys
from collections import defaultdict

OWNERS = [
    # (path substring after me/cortex/voxy/, group) - first match wins, most specific first
    ("client/mixin/minecraft/AccessorLightTexture", "G3"),
    ("client/mixin/minecraft/AccessorTextureAtlas", "G3"),
    ("client/mixin/minecraft/AccessorGameRenderer", "G3"),
    ("client/mixin/minecraft/Accessor", "G4"),
    ("client/mixin/minecraft/session/", "G1"),
    ("client/mixin/minecraft/util/", "G1"),
    ("client/mixin/minecraft/MixinRenderSystem", "G1"),
    ("client/mixin/minecraft/MixinClientLevel", "G2"),
    ("client/mixin/minecraft/MixinClientChunkCache", "G2"),
    ("client/mixin/minecraft/", "G3"),
    ("client/mixin/sodium/", "G5"),
    ("client/mixin/iris/", "G6"),
    ("client/mixin/worldgen/", "G7"),
    ("client/mixin/flashback/", "G1"),
    ("client/mixin/nvidium/", "G1"),
    ("client/compat/VoxyIrisMixinPlugin", "G6"),
    ("client/compat/VoxyWorldgenMixinPlugin", "G7"),
    ("client/compat/", "G1"),
    ("client/config/VoxyConfig.java", "G1"),
    ("client/config/", "G5"),
    ("client/core/model/", "G4"),
    ("client/core/util/IrisUtil", "G6"),
    ("client/core/IrisVoxyRenderPipeline", "G6"),
    ("client/core/", "G3"),
    ("client/iris/", "G6"),
    ("client/lod/", "G7"),
    ("client/LodResync", "G7"),
    ("client/DebugEntries", "G3"),
    ("client/VoxyDebugScreenEntry", "G3"),
    ("client/RenderStatistics", "G3"),
    ("client/TimingStatistics", "G3"),
    ("client/ICheekyClientChunkCache", "G2"),
    ("client/", "G1"),
    ("commonImpl/network/", "G7"),
    ("commonImpl/mixin/", "G2"),
    ("commonImpl/importers/", "G2"),
    ("commonImpl/IWorldGetIdentifier", "G2"),
    ("commonImpl/", "G1"),
    ("common/config/storage/", "G7"),
    ("common/config/section/", "G7"),
    ("common/config/Serialization", "G1"),
    ("common/config/", "G0"),
    ("common/world/service/", "G2"),
    ("common/world/other/", "G2"),
    ("common/world/WorldUpdater", "G2"),
    ("common/world/WorldEngine", "G2"),
    ("common/world/", "G0"),
    ("common/voxelization/", "G2"),
    ("common/util/cpu/", "G1"),
    ("common/util/ThreadUtils", "G1"),
    ("common/util/", "G0"),
    ("common/thread/", "G0"),
    ("common/Logger", "G1"),
    ("common/StorageConfigUtil", "G1"),
    ("common/DebugUtils", "G2"),
    ("common/WorldConfigStorage", "G2"),
    ("me/cortex/voxy/Voxy.java", "G1"),
]

ERR_RE = re.compile(r"^(?P<file>[A-Za-z]:[^:\n]+\.java|/[^:\n]+\.java):(?P<line>\d+): (?P<kind>error|warning): (?P<msg>.*)$")


def owner(path: str) -> str:
    p = path.replace("\\", "/")
    idx = p.find("me/cortex/voxy/")
    rel = p[idx + len("me/cortex/voxy/"):] if idx >= 0 else p
    for sub, g in OWNERS:
        if rel.startswith(sub) or ("/" + sub) in ("/" + rel):
            return g
    return "G0"


def main():
    log = sys.argv[1]
    out = None
    if "--json" in sys.argv:
        out = sys.argv[sys.argv.index("--json") + 1]
    errors = defaultdict(list)
    lines = open(log, encoding="utf-8", errors="replace").read().splitlines()
    i = 0
    while i < len(lines):
        m = ERR_RE.match(lines[i].strip())
        if m and m.group("kind") == "error":
            ctx = []
            j = i + 1
            while j < len(lines) and j < i + 6 and not ERR_RE.match(lines[j].strip()) and not lines[j].startswith(">"):
                ctx.append(lines[j].rstrip())
                j += 1
            errors[m.group("file").replace("\\", "/")].append({
                "line": int(m.group("line")),
                "msg": m.group("msg").strip(),
                "context": [c for c in ctx if c.strip()][:4],
            })
        i += 1
    by_group = defaultdict(dict)
    for f, errs in errors.items():
        by_group[owner(f)][f] = errs
    total = sum(len(v) for v in errors.values())
    print(f"TOTAL ERRORS: {total} in {len(errors)} files")
    for g in sorted(by_group):
        n = sum(len(v) for v in by_group[g].values())
        print(f"  {g}: {n} errors in {len(by_group[g])} files")
        for f in sorted(by_group[g]):
            print(f"     {f.split('me/cortex/voxy/')[-1]} ({len(by_group[g][f])})")
    if out:
        json.dump({"total": total, "byGroup": by_group}, open(out, "w"), indent=1)


if __name__ == "__main__":
    main()
