package com.kishku7.m1;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bank Vault (BV) storage integration -- zero compile dependency on the bank-vault mod.
 *
 * Since BV 1.4.0 the PREFERRED transport is the machine-readable automation surface
 * (com.kishku7.bankvault.api.VaultApi in-JVM, or the hidden "/bank api ..." command over chat)
 * bridged by {@link VaultNet}: snapshot/list/count/find/withdraw/deposit return a single
 * "BV|op|OK|..." ASCII line that is relayed on the socket verbatim. For BV &lt; 1.4.0 (or when
 * the api times out) the legacy paths below remain as fallbacks:
 *
 *   contents  -- reads the vault screen's synced entry list (BankVaultScreen.entries, filled by
 *                VaultSyncPayload) while the vault screen is open; records to StorageMemory.
 *   deposit   -- vanilla QUICK_MOVE clicks on the player slots of the open BankVaultMenu
 *                (menu 0..26 = main inv rows, 27..35 = hotbar; shift-click deposits per
 *                BankVaultMenu.quickMoveStack). "rows" deposits ONLY the main rows -- the
 *                HOTBAR IS THE KEEP-LIST (guided deposit: keep what you use in the hotbar).
 *   withdraw  -- the /bank withdraw <count> <item> command (server-side, arrives in inventory).
 *   trinket guard -- BV binds live trinket slots into the vault menu and Trinkets Updated has an
 *                intermittent eject-on-close bug: snapshot trinket slots while open; after close,
 *                report (and let VaultGuardAction re-equip) anything that landed in the inventory.
 *
 * The vault is player-bound (not block-bound) server-side, but M1 keeps the interaction physical
 * where the GUI is involved: mark the vault block, walk to it, use it, work the screen.
 */
public final class VaultOps {

    private static final String SCREEN_CLASS = "com.kishku7.bankvault.client.BankVaultScreen";
    private static final String MENU_CLASS = "com.kishku7.bankvault.inventory.BankVaultMenu";

    /** Last marked vault position (for storage memory + the guard's re-open). */
    private static volatile BlockPos vaultPos;
    /** Trinket snapshot taken while the vault screen is open: menu slot index -> item id. */
    private static final Map<Integer, String> trinketSnapshot = new LinkedHashMap<>();

    private VaultOps() {
    }

    // ---------- open-screen detection + reflection reads ----------

    static boolean isVaultScreen(Minecraft mc) {
        Screen s = M1Compat.screen(mc);
        return s != null && SCREEN_CLASS.equals(s.getClass().getName());
    }

    static boolean isVaultMenu(AbstractContainerMenu menu) {
        return menu != null && MENU_CLASS.equals(menu.getClass().getName());
    }

    /** Vault contents from the open screen's synced entries: item key -> count. */
    static Map<String, Long> readEntries(Minecraft mc) throws Exception {
        Screen s = M1Compat.screen(mc);
        if (s == null || !SCREEN_CLASS.equals(s.getClass().getName())) {
            return null;
        }
        Field f = s.getClass().getDeclaredField("entries");
        f.setAccessible(true);
        List<?> entries = (List<?>) f.get(s);
        Map<String, Long> out = new LinkedHashMap<>();
        if (entries == null || entries.isEmpty()) {
            return out;
        }
        Method key = null;
        Method count = null;
        for (Object e : entries) {
            if (key == null) {
                key = e.getClass().getMethod("key");
                count = e.getClass().getMethod("count");
            }
            out.merge((String) key.invoke(e), (Long) count.invoke(e), Long::sum);
        }
        return out;
    }

    /** First trinket slot index of the open vault menu (reflection on BV's static), or -1. */
    static int trinketFirst(AbstractContainerMenu menu) {
        try {
            return menu.getClass().getField("TRINKET_FIRST").getInt(null);
        } catch (Throwable t) {
            return -1;
        }
    }

    static int trinketCount(AbstractContainerMenu menu) {
        try {
            return menu.getClass().getField("trinketSlotCount").getInt(menu);
        } catch (Throwable t) {
            return 0;
        }
    }

    /** Snapshot the trinket slots of the open vault menu (call while open). */
    static void snapshotTrinkets(Minecraft mc) {
        trinketSnapshot.clear();
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (!isVaultMenu(menu)) {
            return;
        }
        int first = trinketFirst(menu);
        int n = trinketCount(menu);
        if (first < 0 || n <= 0) {
            return;
        }
        for (int i = first; i < first + n && i < menu.slots.size(); i++) {
            ItemStack st = menu.slots.get(i).getItem();
            if (!st.isEmpty()) {
                trinketSnapshot.put(i, Crafting.itemId(st));
            }
        }
    }

    /** Snapshot taken at/inside the last vault session (slot -> item id path). */
    static Map<Integer, String> lastTrinketSnapshot() {
        return trinketSnapshot;
    }

    static BlockPos markedPos() {
        return vaultPos;
    }

    // ---------- socket command ----------

    static final String HELP = """
            vault snapshot         bank totals: items/kinds/capacity/upgrades/members (BV 1.4.0 api)
            vault list [page]      full bank listing, 50 keys/page (api; keys = plain id or id#hash)
            vault count <key>      exact count of a key; plain id also lists id#hash variants (api)
            vault find <item>      live bank search (api; falls back to storage memory on old BV)
            vault withdraw <item> [n]   withdraw (api-parsed BV| result; either arg order;
                                        plain /bank withdraw fallback for BV < 1.4.0)
            vault deposit hand [n]      deposit the held stack (api)
            vault deposit <id> [n]      deposit by plain item id (api; GUI quick-move when the
                                        vault screen is open; n omitted/0 = everything)
            vault deposit rows     GUI: deposit ALL main inventory rows (hotbar = keep-list)
            vault deposit all      GUI: deposit main rows AND hotbar (screen must be open)
            vault mark [x y z]     remember the vault block position (default: crosshair target)
            vault status           is a vault open; entry/trinket counts; marked pos
            vault contents [f]     list open-screen contents (filter f), record to storage memory
            vault memory           storage memory summary for this world""";

    public static String command(Minecraft mc, String rest) {
        if (mc.player == null) {
            return "vault: not in world";
        }
        String s = (rest == null) ? "" : rest.trim();
        String[] parts = s.split("\\s+", 2);
        String sub = parts.length == 0 || parts[0].isEmpty() ? "status" : parts[0].toLowerCase(Locale.ROOT);
        String args = (parts.length > 1) ? parts[1].trim() : "";
        try {
            switch (sub) {
                case "help":
                    return HELP;
                case "mark":
                    return mark(mc, args);
                case "status":
                    return status(mc);
                case "contents":
                    return contents(mc, args);
                case "snapshot": {
                    String r = VaultNet.call(mc, "snapshot", "");
                    return r != null ? r : "vault snapshot: BV api unavailable (needs BV 1.4.0+)";
                }
                case "list": {
                    int page = 1;
                    if (!args.isEmpty()) {
                        if (!isInt(args)) {
                            return "ERR usage: vault list [page]";
                        }
                        page = Math.max(1, Integer.parseInt(args));
                    }
                    String r = VaultNet.call(mc, "list", String.valueOf(page), page);
                    return r != null ? r
                            : "vault list: BV api unavailable (needs BV 1.4.0+; try 'vault contents' at the open vault)";
                }
                case "count": {
                    if (args.isEmpty()) {
                        return "ERR usage: vault count <key>";
                    }
                    String r = VaultNet.call(mc, "count", args, args);
                    return r != null ? r : "vault count: BV api unavailable (needs BV 1.4.0+)";
                }
                case "withdraw":
                    return withdraw(mc, args);
                case "deposit":
                    return deposit(mc, args);
                case "find": {
                    if (args.isEmpty()) {
                        return "ERR usage: vault find <item>";
                    }
                    String r = VaultNet.call(mc, "find", args, args);
                    if (r != null) {
                        return r;
                    }
                    List<String> hits = StorageMemory.find(mc, args);  // legacy: last-seen memory
                    return hits.isEmpty() ? "vault find: nothing remembered matching '" + args + "'"
                            : String.join("\n", hits);
                }
                case "memory":
                    return StorageMemory.summary(mc);
                default:
                    return "ERR vault: unknown subcommand '" + sub + "'\n" + HELP;
            }
        } catch (Exception e) {
            return "ERR vault " + sub + ": " + e;
        }
    }

    private static String mark(Minecraft mc, String args) {
        if (!args.isEmpty()) {
            String[] t = args.split("\\s+");
            if (t.length < 3) {
                return "ERR usage: vault mark [x y z]";
            }
            try {
                vaultPos = new BlockPos(Integer.parseInt(t[0]), Integer.parseInt(t[1]), Integer.parseInt(t[2]));
            } catch (NumberFormatException e) {
                return "ERR usage: vault mark [x y z]";
            }
        } else if (mc.hitResult instanceof BlockHitResult bhr) {
            vaultPos = bhr.getBlockPos();
        } else {
            return "vault mark: not looking at a block (or pass x y z)";
        }
        return "OK vault marked at " + vaultPos.toShortString();
    }

    private static String status(Minecraft mc) {
        StringBuilder b = new StringBuilder();
        boolean open = isVaultScreen(mc);
        b.append("vault screen: ").append(open ? "OPEN" : "not open");
        if (open) {
            try {
                Map<String, Long> m = readEntries(mc);
                long total = 0;
                for (long v : m.values()) {
                    total += v;
                }
                b.append(" | ").append(m.size()).append(" kinds, ").append(total).append(" items");
            } catch (Exception e) {
                b.append(" | entries unreadable: ").append(e);
            }
            AbstractContainerMenu menu = mc.player.containerMenu;
            b.append(" | trinket slots: ").append(trinketCount(menu));
        }
        b.append(" | marked: ").append(vaultPos == null ? "none" : vaultPos.toShortString());
        b.append(" | api: ").append(VaultNet.inJvm() ? "in-jvm" : "chat-or-none");
        return b.toString();
    }

    private static String contents(Minecraft mc, String filter) throws Exception {
        if (!isVaultScreen(mc)) {
            return "vault contents: vault screen not open (walk to the vault and 'use' it; or try 'vault list')";
        }
        snapshotTrinkets(mc);   // any vault session refreshes the guard snapshot
        Map<String, Long> m = readEntries(mc);
        if (m == null || m.isEmpty()) {
            return "vault contents: (empty or not yet synced -- retry in a moment)";
        }
        // record to per-world storage memory (marked pos, else a player-pos placeholder)
        BlockPos rec = (vaultPos != null) ? vaultPos : mc.player.blockPosition();
        String dim = mc.level.dimension().identifier().toString();
        StorageMemory.record(mc, rec, dim, "bank_vault", m);

        String f = filter.toLowerCase(Locale.ROOT);
        List<Map.Entry<String, Long>> list = new ArrayList<>(m.entrySet());
        list.sort(Comparator.comparingLong((Map.Entry<String, Long> e) -> e.getValue()).reversed());
        StringBuilder b = new StringBuilder();
        long total = 0;
        int shown = 0;
        for (Map.Entry<String, Long> e : list) {
            total += e.getValue();
            if (!f.isEmpty() && !e.getKey().toLowerCase(Locale.ROOT).contains(f)) {
                continue;
            }
            if (shown < 60) {
                b.append(e.getKey()).append(" x ").append(e.getValue()).append('\n');
            }
            shown++;
        }
        String head = "vault: " + m.size() + " kinds, " + total + " items"
                + (f.isEmpty() ? "" : " (filter '" + filter + "': " + shown + " match)")
                + " -- recorded @ " + rec.toShortString() + "\n";
        if (shown > 60) {
            b.append("... and ").append(shown - 60).append(" more\n");
        }
        return (head + b).trim();
    }

    private static String withdraw(Minecraft mc, String args) {
        // Accept BOTH argument orders (the 702b confusion): "withdraw <count> <item>" and
        // "withdraw <item> [count]". Item may be a plain id or an "id#hash" special key
        // (component-bearing stack -- BV withdraws it with components intact as of 1.3.0).
        String[] t = args.trim().split("\\s+");
        if (t.length == 0 || t[0].isEmpty()) {
            return "ERR usage: vault withdraw <item> [count]  (or <count> <item>)";
        }
        int n = 1;
        String item;
        if (t.length == 1) {
            item = t[0];
        } else if (isInt(t[0])) {
            n = Integer.parseInt(t[0]);
            item = t[1];
        } else if (isInt(t[t.length - 1])) {
            n = Integer.parseInt(t[t.length - 1]);
            item = t[0];
        } else {
            item = t[0];
        }
        if (n < 1) {
            return "ERR count must be >= 1";
        }
        if (mc.getConnection() == null) {
            return "vault withdraw: no connection";
        }
        // BV 1.4.0+: machine-readable result (BV|withdraw|OK|key|taken=n, AMBIG variant list,
        // or BV|withdraw|ERR|reason). Relayed verbatim -- items arrive in the inventory.
        String r = VaultNet.call(mc, "withdraw", n + " " + item, item, n);
        if (r != null) {
            return r;
        }
        // Legacy BV < 1.4.0: fire the player-facing command; result is a plain chat line.
        mc.getConnection().sendCommand("bank withdraw " + n + " " + item);
        return "OK sent /bank withdraw " + n + " " + item + " (check 'inv'; chat confirms)";
    }

    private static boolean isInt(String s) {
        try {
            Integer.parseInt(s);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private static String deposit(Minecraft mc, String args) {
        String a = args.toLowerCase(Locale.ROOT).trim();
        if (a.isEmpty()) {
            return "ERR usage: vault deposit rows|all|hand|<item> [n]";
        }
        // GUI bulk modes always drive the open screen (rows keeps the hotbar as the keep-list).
        if (a.equals("rows") || a.equals("all")) {
            return depositGui(mc, a);
        }
        // api modes: "hand [n]", "<n> hand", "<id> [n]", "<n> <id>"; n omitted/0 = everything.
        String[] t = a.split("\\s+");
        int n = 0;
        String what;
        if (t.length == 1) {
            what = t[0];
        } else if (isInt(t[0])) {
            n = Integer.parseInt(t[0]);
            what = t[1];
        } else if (isInt(t[t.length - 1])) {
            n = Integer.parseInt(t[t.length - 1]);
            what = t[0];
        } else {
            what = t[0];
        }
        if (n < 0) {
            return "ERR count must be >= 0";
        }
        // With the vault screen open, keep the proven GUI quick-move for item deposits (it also
        // refreshes the trinket guard snapshot). "hand" is api-only.
        if (!"hand".equals(what) && isVaultScreen(mc)) {
            return depositGui(mc, what);
        }
        String r = VaultNet.call(mc, "deposit", n + " " + what, what, n);
        if (r != null) {
            return r;
        }
        if (isVaultScreen(mc)) {
            return depositGui(mc, what);
        }
        return "vault deposit: BV api unavailable (needs BV 1.4.0+; or open the vault screen for GUI deposit)";
    }

    /** Legacy GUI deposit -- QUICK_MOVE clicks on the open BankVaultMenu's player slots. */
    private static String depositGui(Minecraft mc, String mode) {
        if (!isVaultScreen(mc)) {
            return "vault deposit: vault screen not open";
        }
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (!isVaultMenu(menu)) {
            return "vault deposit: open menu is not the vault";
        }
        snapshotTrinkets(mc);
        int from;
        int to;
        String match = null;
        switch (mode) {
            case "rows":            // main inventory rows only -- hotbar (menu 27..35) is the keep-list
                from = 0;
                to = 26;
                break;
            case "all":             // rows + hotbar
                from = 0;
                to = 35;
                break;
            default:                // by item id substring, anywhere in the 36 player slots
                from = 0;
                to = 35;
                match = mode;
                break;
        }
        int moved = 0;
        long before = 0;
        for (int i = from; i <= to; i++) {
            ItemStack st = menu.slots.get(i).getItem();
            if (st.isEmpty()) {
                continue;
            }
            if (match != null && !Crafting.itemId(st).contains(match)) {
                continue;
            }
            before += st.getCount();
            ContainerCompat.click(mc, menu.containerId, i, 0, ContainerCompat.Mode.QUICK_MOVE);
            moved++;
        }
        if (moved == 0) {
            return "vault deposit: nothing to deposit" + (match != null ? " matching '" + match + "'" : "");
        }
        return "OK deposit " + mode + ": quick-moved " + moved + " stack(s) (" + before
                + " items offered; anything left didn't fit or was refused)";
    }
}
