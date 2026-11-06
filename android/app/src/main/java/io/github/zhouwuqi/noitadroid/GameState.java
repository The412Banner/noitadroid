package io.github.zhouwuqi.noitadroid;

import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * What the game is showing, for the on-screen controls: its own flags, read straight from its memory, whose addresses
 * are fixed as only one build of the game runs here (GameCheck). They come from noita.exe 20250125-1640:
 * - 0x12056f0 points to the game's menu while there is one, the main menu's or the pause menu's, and is 0 otherwise.
 * - 0x1205798 is the same for the game over screen; the function that clears the one clears the other.
 * - 0x1220630 is what the game's Lua function GameIsInventoryOpen returns.
 *
 * It reads them through /proc/self/mem, so that the game's code, which a user may have built on the device, stays as
 * it is.
 */
final class GameState {
    enum Screen { MENU, PLAY, INVENTORY }

    private static final long MEM = 0x1000004000L;  // MEM_HOST_BASE in runtime/compat/compat.h
    private static final long MENU = 0x12056f0, GAME_OVER = 0x1205798, INVENTORY_OPEN = 0x1220630;

    private RandomAccessFile mem;

    /** What's on the screen now. If the flags can't be read while the game runs, PLAY: the controls then stay. */
    Screen read() {
        if (NoitaActivity.nativeFrames() == 0) return Screen.MENU;  // its memory may not be set up yet
        try {
            if (mem == null) mem = new RandomAccessFile("/proc/self/mem", "r");
            mem.seek(MEM + MENU);
            if (mem.readInt() != 0) return Screen.MENU;
            mem.seek(MEM + GAME_OVER);
            if (mem.readInt() != 0) return Screen.MENU;
            mem.seek(MEM + INVENTORY_OPEN);
            return mem.read() != 0 ? Screen.INVENTORY : Screen.PLAY;
        } catch (IOException e) {
            return Screen.PLAY;
        }
    }
}
