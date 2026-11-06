"""The builds of noita.exe the recompiler takes, told apart by their sha256.

Both are Noita of Jan 25 2025 with the same data and the same msvcp120.dll. GOG's 20250125-1640 talks to GOG Galaxy
(runtime/galaxy.c), the Steam build to steam_api.dll (runtime/steam.c); both are stubbed offline. The rest of the
program is the same game, but from about 0x441400 on the Steam build's code sits at other addresses.

The speed data was recorded on GOG's build and names its addresses: the inline caches (tools/icache_sites.txt), the
prefetches (tools/prefetch.py), the indirect call targets (tools/icall_targets.txt) and the PGO profile
(tools/noitadroid.profdata). A build without its own (speed_data False) is built without them: the same game, slower.
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
    speed_data: bool


BUILDS = (
    Build("gog", "GOG build 20250125-1640", "f0f5ffd1c64558663ac7cffaf689f4899732a56494c3530b806bf3357d094363", 0xdfadb0, True),
    Build("steam", "Steam build of Jan 25 2025", "808d2a0ab51ea0b46e9ad2aeb3327a4b0ce3feae04f32ba26326bf585b5779bd", 0xdfd290,
          False),
)
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


def speed_data() -> bool:
    """Whether the build being made can use the speed data recorded on GOG's build."""
    b = current()
    return b is not None and b.speed_data
