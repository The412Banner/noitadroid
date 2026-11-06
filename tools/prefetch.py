"""Memory prefetches for the hot cell loops, by guest address: C that the lifter puts before the guest instruction.

On phones the cell simulation mostly waits for memory: each of its cells is a heap object, reached through a 512 x 512
table per chunk, and the first touch of one goes out to DRAM. The loops know which cells come next, so they ask for them
ahead of time. Prefetches have no effect on what the game does, and the table entries they read are always guest memory
that's mapped: the tables are on the game's heap, which has no holes (runtime/heap.h), and the worst read is a stale or
unrelated word, which a prefetch ignores.

runtime/cpu.h's pf_cell(slot) fetches the cache lines of the cell in that table entry. A table's rows are 512 entries,
2048 bytes. Measurements are in docs/ANDROID.md, "Performance".
"""

ROW = 2048

PREFETCH = {
    # GridWorldThreaded chunk update (F_007288a0), both directions along a row: a cell's update looks at the rows above
    # and below too.
    0x728d16: f"pf_cell(ecx-8); pf_cell(ecx-4+{ROW}); pf_cell(ecx-4-{ROW}); pf_cell(ecx-8+{ROW}); pf_cell(ecx-8-{ROW});",
    0x728dd0: f"pf_cell(ecx+8); pf_cell(ecx+4+{ROW}); pf_cell(ecx+4-{ROW}); pf_cell(ecx+8+{ROW}); pf_cell(ecx+8-{ROW});",
    # GridWorld::RenderTiles (F_00722cd0): cells to tile pixels, x going up.
    0x722f16: "pf_cell(edi+8);",
    # The main thread's scan along a column (F_0071e2f0), y going down.
    0x71e3b8: f"pf_cell(esi-{2 * ROW});",
    # The Box2D terrain's scan of a whole chunk (F_007396b0): x in esi, y << 9 in ebx.
    0x739710: "{ uint32_t n_ = esi + 3; if (n_ < 512) pf_cell(rd32(edx) + ((ebx | n_) << 2)); }",
}
