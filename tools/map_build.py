"""Maps the speed data recorded on GOG's build of noita.exe onto another build of the same game (tools/game_build.py).

The Steam build of Jan 25 2025 is the same source built the same way, but its functions sit at other addresses from
about 0x441400 on. This matches each GOG function with the other build's: the same instructions, with the addresses
in them (calls, jumps, globals) left out, and jumps inside the function as offsets. A signature both builds have
once is a match; a repeated one (small helpers, thunks) is the candidate whose calls agree with the matches so far,
or the one at the distance its matched neighbours both have. A match goes when one of its direct calls or jumps
lands elsewhere than its twin's (on a matched function, at another address). An address in a
matched function maps to the same offset in its twin.

Mapped, into the other build's own files (the speed data's addresses only, nothing from the game):
  tools/icache_sites_<name>.txt    inline caches (tools/icache_sites.txt): site and targets mapped, else dropped
  tools/icall_targets_<name>.txt   indirect call targets (tools/icall_targets.txt), the same way
  tools/prefetch_<name>.txt        "<address> <GOG address>" for tools/prefetch.py's sites, when the instruction matches
  tools/noitadroid_<name>.profdata the PGO profile with its functions renamed (F_<GOG> -> F_<address>), others dropped;
                                   needs llvm-profdata (21, as the app's clang)

Inputs are each build's noita.exe and tools/discover.py's build/noita/discover.pkl for it:

  python tools/map_build.py <GOG noita.exe> <GOG discover.pkl> <other noita.exe> <other discover.pkl> --name steam
"""
import argparse
import bisect
import pickle
import re
import subprocess
import sys
import tempfile
from pathlib import Path

import pefile

ROOT = Path(__file__).resolve().parent.parent
TOOLS = ROOT / "tools"
HEX = re.compile(r"0x[0-9a-f]+")
CODE_REF = re.compile(r"^(call|jmp|j[a-z]+|loop[a-z]*|jecxz)$")


class Build:
    """One build's functions: start, end (the next start), signature, and direct references to other functions."""

    def __init__(self, exe: Path, discover: Path):
        pe = pefile.PE(str(exe), fast_load=True)
        base = pe.OPTIONAL_HEADER.ImageBase
        text = next(s for s in pe.sections if s.Name.rstrip(b"\0") == b".text")
        self.t_lo = base + text.VirtualAddress
        self.t_hi = self.t_lo + text.Misc_VirtualSize
        self.lo, self.hi = base, base + pe.OPTIONAL_HEADER.SizeOfImage
        d = pickle.load(open(discover, "rb"))
        insns = d["insns"]
        starts = set(d["calls"])
        for a, (_, m, o) in insns.items():  # tools/build_all.py's _module_starts: call targets too
            if m == "call" and o.startswith("0x"):
                starts.add(int(o, 16))
        self.starts = sorted(a for a in starts if self.t_lo <= a < self.t_hi)
        addrs = sorted(insns)
        self.sig, self.refs, self.offsets = {}, {}, {}
        for i, s in enumerate(self.starts):
            e = self.starts[i + 1] if i + 1 < len(self.starts) else self.t_hi
            j = bisect.bisect_left(addrs, s)
            items, refs, offs = [], [], []
            while j < len(addrs) and addrs[j] < e:
                a = addrs[j]
                size, m, o = insns[a]

                def norm(mm, s=s, e=e, a=a, m=m):
                    v = int(mm.group(0), 16)
                    if s <= v < e:
                        return f"L{v - s:x}"
                    if self.lo <= v < self.hi:
                        if CODE_REF.match(m) and self.t_lo <= v < self.t_hi:
                            refs.append((a - s, v))
                        return "A"
                    return mm.group(0)

                items.append((a - s, size, m, HEX.sub(norm, o)))
                offs.append(a - s)
                j += 1
            self.sig[s] = hash(tuple(items))
            self.refs[s] = refs
            self.offsets[s] = offs
        del insns, addrs, d

    def containing(self, a: int):
        i = bisect.bisect_right(self.starts, a) - 1
        return self.starts[i] if i >= 0 else None


def match(g: Build, o: Build) -> dict[int, int]:
    """{GOG function start: other build's function start}."""
    by_sig_g, by_sig_o = {}, {}
    for s, h in g.sig.items():
        by_sig_g.setdefault(h, []).append(s)
    for s, h in o.sig.items():
        by_sig_o.setdefault(h, []).append(s)
    m, used = {}, set()
    for h, gs in by_sig_g.items():
        os_ = by_sig_o.get(h)
        if os_ and len(gs) == 1 and len(os_) == 1:
            m[gs[0]] = os_[0]
            used.add(os_[0])

    def agree(s, t):
        """How many of s's calls and jumps to matched functions land where t's do; None if one doesn't."""
        rg, ro = g.refs[s], o.refs[t]
        if len(rg) != len(ro):
            return None
        known = 0
        for (og, vg), (oo, vo) in zip(rg, ro):
            if og != oo:
                return None
            cg = g.containing(vg)
            if cg in m:
                if m[cg] + (vg - cg) != vo:
                    return None
                known += 1
        return known

    def near(s):
        """The distance of the nearest matched functions before and after s, when they agree."""
        done = near.done
        i = bisect.bisect_left(done, s)
        d = {m[done[k]] - done[k] for k in (i - 1, i) if 0 <= k < len(done)}
        return d.pop() if len(d) == 1 else None

    # Repeated signatures: the one candidate whose calls agree with the matches so far, else the one at the
    # distance both neighbours have.
    for _ in range(8):
        near.done = sorted(m)
        added = 0
        for h, gs in by_sig_g.items():
            cands = [t for t in by_sig_o.get(h, ()) if t not in used]
            if not cands:
                continue
            for s in gs:
                if s in m or not cands:
                    continue
                scored = [(k, t) for t in cands if (k := agree(s, t)) is not None]
                best = max((k for k, _ in scored), default=0)
                top = [t for k, t in scored if k == best]
                pick = top[0] if best > 0 and len(top) == 1 else None
                if pick is None:
                    d = near(s)
                    if d is not None and s + d in {t for _, t in scored}:
                        pick = s + d
                if pick is not None:
                    m[s] = pick
                    used.add(pick)
                    cands.remove(pick)
                    added += 1
        if not added:
            break
    # A match goes when one of its calls or jumps contradicts the others (lands elsewhere than its twin's).
    while bad := [s for s, t in m.items() if agree(s, t) is None]:
        for s in bad:
            used.discard(m.pop(s))
    return m


def mapper(g: Build, o: Build, m: dict[int, int]):
    """GOG address -> the other build's, or None (msvcp120 and other modules keep theirs)."""
    def f(a: int):
        if not (g.lo <= a < g.hi):
            return a
        s = g.containing(a)
        if s is None or s not in m or not (g.t_lo <= a < g.t_hi):
            return None
        if (a - s) not in set(g.offsets[s]) and a != s:
            return None
        return m[s] + (a - s)
    return f


def map_pairs(path: Path, out: Path, f, header: str):
    """'site target [target...]  # comment' lines: the site and each target mapped; a line without both goes."""
    kept = dropped = 0
    lines = [header]
    for line in path.read_text().splitlines():
        code, _, comment = line.partition("#")
        words = code.split()
        if not words:
            if line.startswith("#"):
                lines.append("# (from GOG's) " + line[1:].strip())
            continue
        site, *ts = (int(w, 16) for w in words)
        ms = f(site)
        mts = [x for x in (f(t) for t in ts) if x is not None]
        if ms is None or not mts:
            dropped += 1
            continue
        kept += 1
        lines.append(" ".join(f"{x:#x}" for x in (ms, *mts)) + (f"  #{comment}" if comment else ""))
    out.write_text("\n".join(lines) + "\n")
    return kept, dropped


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("gog_exe", type=Path)
    ap.add_argument("gog_discover", type=Path)
    ap.add_argument("exe", type=Path)
    ap.add_argument("discover", type=Path)
    ap.add_argument("--name", required=True, help="the build's name in tools/game_build.py (steam)")
    ap.add_argument("--profdata", default="llvm-profdata", help="llvm-profdata (21)")
    args = ap.parse_args()
    g, o = Build(args.gog_exe, args.gog_discover), Build(args.exe, args.discover)
    m = match(g, o)
    print(f"functions: GOG {len(g.starts)}, {args.name} {len(o.starts)}, matched {len(m)} "
          f"({100 * len(m) / len(g.starts):.1f}% of GOG's)")
    f = mapper(g, o, m)
    head = f"# mapped from GOG's build by tools/map_build.py ({len(m)} of {len(g.starts)} functions matched)"
    for name in ("icache_sites", "icall_targets"):
        kept, dropped = map_pairs(TOOLS / f"{name}.txt", TOOLS / f"{name}_{args.name}.txt", f, head)
        print(f"{name}: {kept} kept, {dropped} dropped")

    sys.path.insert(0, str(TOOLS))
    from prefetch import PREFETCH
    lines = [head]
    for a in sorted(PREFETCH):
        b = f(a)
        if b is not None:
            lines.append(f"{b:#x} {a:#x}")
    (TOOLS / f"prefetch_{args.name}.txt").write_text("\n".join(lines) + "\n")
    print(f"prefetch: {len(lines) - 1} of {len(PREFETCH)} sites")

    prof = TOOLS / "noitadroid.profdata"
    with tempfile.TemporaryDirectory() as tmp:
        text = Path(tmp) / "gog.proftext"
        subprocess.run([args.profdata, "merge", "--text", str(prof), "-o", str(text)], check=True)
        out, kept, dropped, skip = [], 0, 0, False
        fn = re.compile(r"^F_([0-9a-f]{8})$")
        records = text.read_text().split("\n\n")
        for rec in records:
            lines = rec.split("\n")
            k = next((i for i, x in enumerate(lines) if x and x[0] not in "#:"), None)  # the name, after the header
            mm = fn.match(lines[k]) if k is not None else None
            if mm:
                a = int(mm.group(1), 16)
                if g.lo <= a < g.hi:
                    if a not in m:  # whole functions only
                        if k:
                            out.append("\n".join(lines[:k]))  # the file's header stays
                        dropped += 1
                        continue
                    lines[k] = f"F_{m[a]:08x}"
                    kept += 1
            out.append("\n".join(lines))
        renamed = Path(tmp) / f"{args.name}.proftext"
        renamed.write_text("\n\n".join(out))
        subprocess.run([args.profdata, "merge", "--binary", str(renamed), "-o",
                        str(TOOLS / f"noitadroid_{args.name}.profdata")], check=True)
    print(f"profile: {kept} lifted functions kept, {dropped} dropped")


if __name__ == "__main__":
    main()
