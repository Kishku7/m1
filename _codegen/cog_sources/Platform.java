package com.kishku7.m1;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
//[[[cog
//import sys; sys.path.insert(0, codegen); import compat
//for imp in compat.platform_imports(mcver): cog.outl("import %s;" % imp)
//]]]
//[[[end]]]

/**
 * MC-facing GUI + threading facade (Cog, one body-set per legacy MC version).
 * Bodies are emitted by _codegen/compat.py and DIRECT-compiled (loom remaps to the intermediary
 * runtime); the protected Screen/ButtonWidget seams are opened by m1.accesswidener.
 *
 * Everything callable from the MC-agnostic core (M1Server, ScreenOps) goes through here, so a new
 * legacy version is added by branching compat.py -- no change to the shared code.
 */
public final class Platform {
    private Platform() {}

    /** Run r on the client/render thread. */
    public static void onMainThread(Runnable r) {
        //[[[cog
        //for ln in compat.p_dispatch(mcver): cog.outl(ln)
        //]]]
        //[[[end]]]
    }

    /** The current screen, or null if none (in-world). Opaque Object to keep the core agnostic. */
    public static Object screen() {
        //[[[cog
        //for ln in compat.p_screen(mcver): cog.outl(ln)
        //]]]
        //[[[end]]]
    }

    /** {width, height} of the given screen. */
    public static int[] screenSize(Object s) {
        //[[[cog
        //for ln in compat.p_screen_size(mcver): cog.outl(ln)
        //]]]
        //[[[end]]]
    }

    /** Enumerate the screen's buttons as version-agnostic WidgetInfo. */
    public static List<WidgetInfo> buttons(Object s) {
        //[[[cog
        //for ln in compat.p_buttons(mcver): cog.outl(ln)
        //]]]
        //[[[end]]]
    }

    /** Click the button with the given id (coordinate-synthesized). Returns a status string. */
    public static String click(Object s, int id) {
        //[[[cog
        //for ln in compat.p_click(mcver): cog.outl(ln)
        //]]]
        //[[[end]]]
    }

    /** Enumerate the screen's text fields (discovered by declared-field type). */
    public static List<TfInfo> textFields(Object s) {
        //[[[cog
        //for ln in compat.p_textfields(mcver): cog.outl(ln)
        //]]]
        //[[[end]]]
    }

    /** Set the text of the named text field. Returns a status string. */
    public static String setText(Object s, String id, String text) {
        //[[[cog
        //for ln in compat.p_set_text(mcver): cog.outl(ln)
        //]]]
        //[[[end]]]
    }

    /** Drive a GUI key by name (escape/enter/tab/backspace/up/down/left/right). Returns status. */
    public static String key(Object s, String name) {
        //[[[cog
        //for ln in compat.p_key(mcver): cog.outl(ln)
        //]]]
        //[[[end]]]
    }
    /** Save a screenshot (optional name). Returns the game save message / path. */
    public static String screenshot(String name) {
        //[[[cog
        //for ln in compat.p_screenshot(mcver): cog.outl(ln)
        //]]]
        //[[[end]]]
    }    /** Player position + facing (in-world). */
    public static String where() {
        //[[[cog
        //for ln in compat.p_where(mcver): cog.outl(ln)
        //]]]
        //[[[end]]]
    }

    /** Player vitals + gamemode (in-world). */
    public static String state() {
        //[[[cog
        //for ln in compat.p_state(mcver): cog.outl(ln)
        //]]]
        //[[[end]]]
    }

    /** Player inventory (in-world). */
    public static String inv() {
        //[[[cog
        //for ln in compat.p_inv(mcver): cog.outl(ln)
        //]]]
        //[[[end]]]
    }    /** Open the player inventory screen (in-world). */
    public static String openInventory() {
        //[[[cog
        //for ln in compat.p_openinv(mcver): cog.outl(ln)
        //]]]
        //[[[end]]]
    }

    /** Enumerate the open container screen's slots. */
    public static String slots() {
        //[[[cog
        //for ln in compat.p_slots(mcver): cog.outl(ln)
        //]]]
        //[[[end]]]
    }

    /** Click a container slot (button 0=left 1=right; mode 0=pickup 1=quickmove 2=swap...). */
    public static String slotClick(int slotId, int button, int mode) {
        //[[[cog
        //for ln in compat.p_slotclick(mcver): cog.outl(ln)
        //]]]
        //[[[end]]]
    }

    /** Enumerate selection-list entries (world/server lists). id = list index. */
    public static List<WidgetInfo> listEntries(Object s) {
        //[[[cog
        //for ln in compat.p_list_entries(mcver): cog.outl(ln)
        //]]]
        //[[[end]]]
    }

    /** Select+activate a list entry by index (join world / connect server). */
    public static String selectEntry(Object s, int index) {
        //[[[cog
        //for ln in compat.p_select_entry(mcver): cog.outl(ln)
        //]]]
        //[[[end]]]
    }

    /** Hold a movement keybind (forward/back/left/right/jump/sneak/sprint) for ms, then release. */
    public static String move(String dir, int ms) {
        //[[[cog
        //for ln in compat.p_move(mcver): cog.outl(ln)
        //]]]
        //[[[end]]]
    }

    /** Set the player's facing (yaw, pitch). */
    public static String look(float yaw, float pitch) {
        //[[[cog
        //for ln in compat.p_look(mcver): cog.outl(ln)
        //]]]
        //[[[end]]]
    }

    /** Release every held movement key. */
    public static String stopKeys() {
        //[[[cog
        //for ln in compat.p_stopkeys(mcver): cog.outl(ln)
        //]]]
        //[[[end]]]
    }

    /** Hold the attack key for ms (pre-1.9 mining / spam-attack). */
    public static String attack(int ms) {
        //[[[cog
        //for ln in compat.p_attack(mcver): cog.outl(ln)
        //]]]
        //[[[end]]]
    }

    /** Hold the use key for ms (interact / place). */
    public static String use(int ms) {
        //[[[cog
        //for ln in compat.p_use(mcver): cog.outl(ln)
        //]]]
        //[[[end]]]
    }

    /** Report the crosshair hit (entity or block). */
    public static String target() {
        //[[[cog
        //for ln in compat.p_target(mcver): cog.outl(ln)
        //]]]
        //[[[end]]]
    }

    /** Break the crosshair block headlessly (direct per-tick progress; bypasses inGameHasFocus). */
    public static String mine(int ms) {
        //[[[cog
        //for ln in compat.p_mine(mcver): cog.outl(ln)
        //]]]
        //[[[end]]]
    }

    /** List entities within radius of the player. */
    public static String nearby(double radius) {
        //[[[cog
        //for ln in compat.p_nearby(mcver): cog.outl(ln)
        //]]]
        //[[[end]]]
    }
}

