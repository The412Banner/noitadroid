"""The builds of noita.exe the recompiler takes, told apart by their sha256.

Both are Noita of Jan 25 2025 with the same data and the same msvcp120.dll. GOG's 20250125-1640 talks to GOG Galaxy
(runtime/galaxy.c), the Steam build to steam_api.dll (runtime/steam.c); both are stubbed offline. The rest of the
program is the same game, but from about 0x441400 on the Steam build's code sits at other addresses.

The speed data was recorded on GOG's build and names its addresses: the inline caches (tools/icache_sites.txt), the
prefetches (tools/prefetch.py), the indirect call targets (tools/icall_targets.txt) and the PGO profile
(tools/noitadroid.profdata). tools/map_build.py maps them onto another build's addresses, into files named after it
(tools/icache_sites_steam.txt, tools/prefetch_steam.txt, ...): speed_file and prefetch give the build's own. A build
without them is built without: the same game, slower.
"""
import hashlib
import os
from dataclasses import dataclass
from functools import cache
from pathlib import Path


@dataclass(frozen=True)
class Build:
    name: str
    label: str
    exe_sha256: str
    entry: int


BUILDS = (
    Build("gog", "GOG build 20250125-1640", "f0f5ffd1c64558663ac7cffaf689f4899732a56494c3530b806bf3357d094363", 0xdfadb0),
    Build("steam", "Steam build of Jan 25 2025", "808d2a0ab51ea0b46e9ad2aeb3327a4b0ce3feae04f32ba26326bf585b5779bd", 0xdfd290),
)
TOOLS = Path(__file__).resolve().parent
MSVCP120_SHA256 = "87c42ca155473e4e71857d03497c8cbc28fa8ff7f2c8d72e8a1f39b71078f608"


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def by_hash(exe_sha256: str) -> Build | None:
    return next((b for b in BUILDS if b.exe_sha256 == exe_sha256), None)


@cache
def of(game_dir: Path) -> Build | None:
    """The build of game_dir/noita.exe, or None for a build this doesn't know (or no noita.exe)."""
    exe = Path(game_dir) / "noita.exe"
    return by_hash(sha256(exe)) if exe.is_file() else None


def current() -> Build | None:
    """The build in the game folder the build scripts read (NOITA_DIR, else build/game: tools/pe.py)."""
    return of(Path(os.environ.get("NOITA_DIR") or Path(__file__).parent.parent / "build/game"))


def speed_file(name: str, build: Build | None = None) -> Path | None:
    """The speed data file `name` as GOG's build has it (icache_sites.txt, icall_targets.txt, noitadroid.profdata) for
    build (default: current()): GOG's own, or tools/map_build.py's <stem>_<build><suffix>. None when there's none."""
    b = build or current()
    if b is None:
        return None
    p = TOOLS / name
    if b.name != "gog":
        p = p.with_name(f"{p.stem}_{b.name}{p.suffix}")
    return p if p.exists() else None


@cache
def _prefetch(name: str) -> dict:
    from prefetch import PREFETCH
    if name == "gog":
        return PREFETCH
    p = TOOLS / f"prefetch_{name}.txt"  # "<address> <GOG address>" lines (tools/map_build.py)
    out = {}
    for line in p.read_text().splitlines() if p.exists() else ():
        w = line.split("#")[0].split()
        if len(w) == 2 and int(w[1], 16) in PREFETCH:
            out[int(w[0], 16)] = PREFETCH[int(w[1], 16)]
    return out


def prefetch(build: Build | None = None) -> dict:
    """tools/prefetch.py's prefetches at build's addresses (default: current()): {address: C before it}."""
    b = build or current()
    return _prefetch(b.name) if b else {}
