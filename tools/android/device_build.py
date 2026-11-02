"""The game's build on the device: from the user's noita.exe and msvcp120.dll to libmain.so, as tools/build_all.py and
tools/android/build_game.py do on a PC (no ThinLTO). The app's BuildService runs it with the app's own toolchain
(tools/android/build_toolchain.py); it also runs on a PC with the NDK, for testing.

  python tools/android/device_build.py --game <folder with noita.exe> --out <libmain.so> [--jobs N] [--lift-jobs N]

It runs in a kit: a copy of the repo's tools, runtime, headers and prebuilt LuaJIT and gl4es, laid out as in the repo
(tools/android/build_app.py packs it). Its outputs go in the kit's build/. Every stage keeps what it finished, so a
build stopped for any reason goes on where it left off when it runs again in the same kit.

On the device the app sets NOITADROID_TOOLCHAIN and NOITADROID_TOOLCHAIN_DATA (tools/target.py), PYTHONHOME,
PYTHONPATH and LIBCAPSTONE_PATH.

For the app, on stdout: "ND-PROGRESS <permille> <stage> <done> <total>" lines, then "ND-DONE <seconds>" or
"ND-ERROR <stage> <message>" (and exit status 1). Everything else is log. SIGTERM stops the build and everything it
started.

Python's multiprocessing needs sem_open, which Android's libc doesn't have, so the lift's workers are forked here
(ForkPool): after the parent has loaded the module, so they share it copy-on-write.
"""
import argparse
import hashlib
import json
import os
import pickle
import selectors
import signal
import subprocess
import sys
import time
import traceback
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent

# The game build the recompiled code is for: GOG's 20250125-1640.
EXPECTED = {
    "noita.exe": "f0f5ffd1c64558663ac7cffaf689f4899732a56494c3530b806bf3357d094363",
    "msvcp120.dll": "87c42ca155473e4e71857d03497c8cbc28fa8ff7f2c8d72e8a1f39b71078f608",
}
# The app's native interface (NoitaActivity's natives, SDL's Java glue, the arguments): the app uses a libmain.so built
# for its own ABI only. Bump it when they change, so the app asks for a new build.
NATIVE_ABI = 2  # 2: NoitaActivity passes --game
MODULES = ("msvcp120", "noita")  # msvcp120 first: noita's register summaries use its exports'
# Stage weights for the progress bar: seconds on a Snapdragon G3x Gen 2 (AYANEO Pocket S).
STAGES = {"check": 2, "discover": 125, "bridges": 10, "regsum": 200, "lift": 130, "compile": 270, "link": 3}


class Progress:
    def __init__(self):
        self.done = 0  # weight of the finished stages
        self.stage = ""

    def start(self, stage: str):
        self.stage = stage
        self.update(0, 1)

    def update(self, done: int, total: int):
        part = STAGES[self.stage] * done / max(total, 1)
        permille = int(1000 * (self.done + part) / sum(STAGES.values()))
        print(f"ND-PROGRESS {permille} {self.stage} {done} {total}", flush=True)

    def finish(self):
        self.update(1, 1)
        self.done += STAGES[self.stage]


PROGRESS = Progress()


class ForkPool:
    """multiprocessing.Pool's map and imap_unordered without semaphores. The initializer runs once, here, before
    forking; each worker takes every n-th item and sends its pickled results back through a pipe, and a byte per
    finished item through another: the stage's progress is offset + finished items, of total (default: the items)."""
    jobs = 6
    offset = 0
    total = None

    def __init__(self, processes=None, initializer=None, initargs=()):
        if initializer:
            initializer(*initargs)

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False

    def map(self, fn, items):
        items = list(items)
        n = max(1, min(self.jobs, len(items)))
        prog_r, prog_w = os.pipe()
        kids = {}
        for w in range(n):
            r, wfd = os.pipe()
            pid = os.fork()
            if pid == 0:
                os.close(r)
                os.close(prog_r)
                code, data = 0, b""
                try:
                    out = []
                    for i in range(w, len(items), n):
                        out.append((i, fn(items[i])))
                        os.write(prog_w, b".")
                    data = pickle.dumps(out)
                except BaseException:
                    traceback.print_exc()
                    code = 1
                try:
                    with os.fdopen(wfd, "wb") as f:
                        f.write(data)
                finally:
                    os._exit(code)
            os.close(wfd)
            kids[r] = pid
        os.close(prog_w)
        sel = selectors.DefaultSelector()
        bufs = {r: bytearray() for r in kids}
        for fd in (*kids, prog_r):
            sel.register(fd, selectors.EVENT_READ)
        finished, open_fds = 0, len(kids) + 1
        while open_fds:
            for key, _ in sel.select():
                data = os.read(key.fd, 1 << 20)
                if not data:
                    sel.unregister(key.fd)
                    os.close(key.fd)
                    open_fds -= 1
                elif key.fd == prog_r:
                    finished += len(data)
                    PROGRESS.update(self.offset + finished, self.total or len(items))
                else:
                    bufs[key.fd] += data
        results, failed = [None] * len(items), False
        for r, pid in kids.items():
            if os.waitpid(pid, 0)[1]:
                failed = True
                continue
            for i, v in pickle.loads(bytes(bufs[r])):
                results[i] = v
        if failed:
            raise RuntimeError("a worker failed (its error is in the log)")
        return results

    def imap_unordered(self, fn, items):
        return iter(self.map(fn, items))


def write_if_changed(path: Path, text: str):
    """Leaves the file (and its time) alone when it already says this, so its users aren't rebuilt."""
    if not path.exists() or path.read_text() != text:
        tmp = path.with_name(path.name + ".tmp")
        tmp.write_text(text)
        tmp.replace(path)


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for block in iter(lambda: f.read(1 << 20), b""):
            h.update(block)
    return h.hexdigest()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--game", required=True, type=Path, help="the folder with noita.exe")
    ap.add_argument("--out", required=True, type=Path, help="where libmain.so goes (and libmain.json beside it)")
    ap.add_argument("--jobs", type=int, default=os.cpu_count(), help="parallel compiles")
    ap.add_argument("--lift-jobs", type=int, default=min(6, os.cpu_count() or 1),
                    help="parallel lift workers (each can grow to 1-2 GB)")
    args = ap.parse_args()
    # Cancelling (SIGTERM from the app) stops the whole build: its compilers and workers are in this process group.
    os.setpgrp()
    signal.signal(signal.SIGTERM, lambda *_: os.killpg(0, signal.SIGKILL))
    os.environ["NOITADROID_TARGET"] = "android"  # before the build modules read it
    os.environ["NOITA_DIR"] = str(args.game)
    sys.path[:0] = [str(HERE.parent), str(HERE)]
    t0 = time.time()
    stage = "check"
    try:
        PROGRESS.start(stage)
        for name, want in EXPECTED.items():
            if not (args.game / name).exists():
                raise RuntimeError(f"{args.game / name} is missing")
            if sha256(args.game / name) != want:
                raise RuntimeError(f"{name} isn't GOG's build 20250125-1640")
        PROGRESS.finish()

        import build_all  # noqa: E402
        import build_game  # noqa: E402
        from lift import Program  # noqa: E402
        from pe import build_dir  # noqa: E402
        build_all.mp.Pool = ForkPool  # regsum's pool
        ForkPool.jobs = args.lift_jobs
        state = ROOT / "build/state"
        state.mkdir(parents=True, exist_ok=True)

        stage = "discover"
        PROGRESS.start(stage)
        for i, m in enumerate(MODULES):
            if not (state / f"discover-{m}").exists():
                subprocess.run([sys.executable, str(HERE.parent / "discover.py"), m], check=True, cwd=ROOT)
                (state / f"discover-{m}").touch()
            PROGRESS.update(i + 1, len(MODULES))
        PROGRESS.finish()

        stage = "bridges"  # SDL's and GL's, for Android; regsum reads their HOST declarations from build/gen_all
        PROGRESS.start(stage)
        build_game.GEN.mkdir(parents=True, exist_ok=True)
        build_game.generate_bridges()
        build_all.GEN.mkdir(parents=True, exist_ok=True)
        for name in ("sdl2_gen.c", "gl_gen.c"):
            write_if_changed(build_all.GEN / name, (build_game.GEN / name).read_text())
        PROGRESS.finish()

        stage = "regsum"
        PROGRESS.start(stage)
        starts_by_mod = {}
        for m in MODULES:
            prog = Program(m)
            prog.is_ordered(prog.t_lo)  # tools/memorder.py's analysis, cached, before the workers
            starts_by_mod[m] = build_all._module_starts(prog)
            del prog
        starts = sorted(a for s in starts_by_mod.values() for a in s)
        write_if_changed(build_all.GEN / "decls.h", "\n".join(f"void F_{a:08x}(CPU *restrict c);" for a in starts))
        sums = {}
        ForkPool.total = len(MODULES) * build_all.CHUNKS
        for i, m in enumerate(MODULES):
            ForkPool.offset = i * build_all.CHUNKS
            sums[m] = build_all.regsum_for(m)  # cached by tools/regsum.py
        PROGRESS.finish()

        stage = "lift"
        PROGRESS.start(stage)
        ic = build_all._load_ic(build_all.IC_SITES, set(starts)) if build_all.IC_SITES.exists() else {}
        jobs = []
        for m in MODULES:
            gen = build_dir(m) / "gen"
            gen.mkdir(parents=True, exist_ok=True)
            ms = starts_by_mod[m]
            jobs += [(m, k, ms[k::build_all.CHUNKS]) for k in range(build_all.CHUNKS)]
        lifted = 0
        ForkPool.total = len(jobs)
        for m in MODULES:
            todo = [j for j in jobs if j[0] == m and not (build_dir(m) / "gen" / f"chunk{j[1]:03}.ok").exists()]
            lifted += sum(1 for j in jobs if j[0] == m) - len(todo)
            PROGRESS.update(lifted, len(jobs))
            if todo:
                ForkPool.offset = lifted
                with ForkPool(initializer=build_all._init, initargs=(m, False, ic, "live", *sums[m], "off")) as pool:
                    pool.map(lift_chunk, todo)
                lifted += len(todo)
        table = ['#include "rt.h"', '#include "decls.h"',  # as tools/build_all.py writes it
                 "const FnEntry FN_TABLE[] = {" + ",".join(f"{{{a:#x}u,F_{a:08x}}}" for a in starts) + "};",
                 f"const int FN_COUNT = {len(starts)};"]
        ic_fns = sorted({t for ts in ic.values() for t in ts})
        table += ["const uint32_t IC_TARGETS[] = {" + ",".join([f"{t:#x}u" for t in ic_fns] + ["0"]) + "};",
                  f"const int IC_TARGET_COUNT = {len(ic_fns)};"]
        write_if_changed(build_all.GEN / "table.c", "\n".join(table))
        PROGRESS.finish()

        stage = "compile"
        PROGRESS.start(stage)
        build_game.OBJ.mkdir(parents=True, exist_ok=True)
        srcs = sorted(build_game.sources(), key=lambda s: -s.stat().st_size)  # the big chunks first
        newer_than = build_game.deps_mtime(" ".join(build_game.target.cc()))
        done = 0
        with ThreadPoolExecutor(max(1, args.jobs)) as ex:
            futures = [ex.submit(build_game.compile_one, (s, [], newer_than)) for s in srcs]
            for f in as_completed(futures):
                f.result()
                done += 1
                PROGRESS.update(done, len(srcs))
        objs = [build_game.obj_of(s) for s in build_game.sources()]
        PROGRESS.finish()

        stage = "link"
        PROGRESS.start(stage)
        args.out.parent.mkdir(parents=True, exist_ok=True)
        tmp = args.out.with_name(args.out.name + ".tmp")
        build_game.link(objs, build_all.LUAJIT_LIB, tmp, [])
        kit = (ROOT / "KIT_VERSION").read_text().strip() if (ROOT / "KIT_VERSION").exists() else "dev"
        info = {**{name: want for name, want in EXPECTED.items()}, "abi": NATIVE_ABI, "kit": kit, "built": int(time.time()),
                "sha256": sha256(tmp)}
        tmp.replace(args.out)
        args.out.with_suffix(".json").write_text(json.dumps(info, indent=1) + "\n")
        PROGRESS.finish()
    except BaseException as e:  # noqa: BLE001 - every failure is reported to the app
        traceback.print_exc()
        message = str(e).strip().splitlines()[-1] if str(e).strip() else type(e).__name__
        if isinstance(e, subprocess.CalledProcessError):
            message = f"{Path(str(e.cmd[0])).name} failed ({e.returncode})"
        print(f"ND-ERROR {stage} {message}", flush=True)
        sys.exit(1)
    print(f"ND-DONE {time.time() - t0:.0f}", flush=True)


def lift_chunk(job):
    """One chunk of C (tools/build_all.py's _lift_chunk), then its marker: a chunk with one is finished."""
    import build_all
    from pe import build_dir
    m, k, addrs = job
    build_all._lift_chunk((k, addrs))
    (build_dir(m) / "gen" / f"chunk{k:03}.ok").touch()


if __name__ == "__main__":
    main()
