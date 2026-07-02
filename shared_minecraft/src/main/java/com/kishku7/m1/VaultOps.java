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
 * Bank Vault (BV) storage integration -- GUI-driven, ALL reflection, zero compile dependency on
 * the bank-vault mod. Works wherever BV is loaded (Fabric + NeoForge, mojmap runtime 26.x).
 *
 * How it drives BV (all existing player-facing entry points):
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
 * The vault is player-bound (not block-bound) server-side, but M1 keeps the interaction physical:
 * mark the vault block, walk to it, use it, work the screen.
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
            vault mark [x y z]     remember the vault block position (default: crosshair target)
            vault status           is a vault open; entry/trinket counts; marked pos
            vault contents [f]     list vault contents (filter f), record to storage memory
            vault withdraw <n> <item>   withdraw n of item (via /bank withdraw; member+)
            vault deposit rows     deposit ALL main inventory rows (hotbar = keep-list, untouched)
            vault deposit all      deposit main rows AND hotbar
            vault deposit <item>   deposit every player stack whose id contains <item>
            vault find <item>      search storage memory (last-seen) for an item
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
                case "withdraw":
                    return withdraw(mc, args);
                case "deposit":
                    return deposit(mc, args);
                case "find": {
                    if (args.isEmpty()) {
                        return "ERR usage: vault find <item>";
                    }
                    List<String> hits = StorageMemory.find(mc, args);
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
        return b.toString();
    }

    private static String contents(Minecraft mc, String filter) throws Exception {
        if (!isVaultScreen(mc)) {
            return "vault contents: vault screen not open (walk to the vault and 'use' it)";
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
        String[] t = args.split("\\s+", 2);
        if (t.length < 2) {
            return "ERR usage: vault withdraw <count> <item>";
        }
        int n;
        try {
            n = Integer.parseInt(t[0]);
        } catch (NumberFormatException e) {
            return "ERR usage: vault withdraw <count> <item>";
        }
        if (mc.getConnection() == null) {
            return "vault withdraw: no connection";
        }
        // /bank withdraw is BV's own player-facing command; result arrives as a chat line and
        // the items land directly in the inventory (or drop if full).
        mc.getConnection().sendCommand("bank withdraw " + n + " " + t[1].trim());
        return "OK sent /bank withdraw " + n + " " + t[1].trim() + " (check 'inv'; chat confirms)";
    }

    private static String deposit(Minecraft mc, String args) {
        if (!isVaultScreen(mc)) {
            return "vault deposit: vault screen not open";
        }
        AbstractContainerMenu menu = mc.player.containerMenu;
        if (!isVaultMenu(menu)) {
            return "vault deposit: open menu is not the vault";
        }
        snapshotTrinkets(mc);
        String a = args.toLowerCase(Locale.ROOT).trim();
        if (a.isEmpty()) {
            return "ERR usage: vault deposit rows|all|<item>";
        }
        int from;
        int to;
        String match = null;
        switch (a) {
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
                match = a;
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
        return "OK deposit " + a + ": quick-moved " + moved + " stack(s) (" + before
                + " items offered; anything left didn't fit or was refused)";
    }
}
