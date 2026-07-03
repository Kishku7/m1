package com.kishku7.m1;

import java.lang.reflect.Method;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Travelers Backpack integration, CODE-LEVEL, all via reflection (zero compile dep on TB/Trinkets,
 * exactly like VaultOps vs Bank Vault). Master, 2026-07-02: a backpack has no use-to-wear, so it
 * must be equipped at the code level, not by GUI.
 *
 * Discovered from the shipped jars (travelersbackpack-fabric-26.1.2-11.2.6):
 *   com.tiviacz.travelersbackpack.attachment.AttachmentUtils
 *     static void      equipBackpack(Player, ItemStack)     -- wears it into the Trinkets back slot
 *     static boolean   isWearingBackpack(Player)
 *     static ItemStack getWearingBackpack(Player)
 *     static BackpackWrapper getBackpackWrapper(Player)      -- wrapper for the worn backpack
 *   com.tiviacz.travelersbackpack.inventory.BackpackWrapper
 *     ItemStackHandler getStorage()  (ItemStackHandler EXTENDS net.minecraft.world.SimpleContainer)
 * TB owns the Trinkets slot routing, so M1 never touches the Trinkets API.
 *
 * Commands (ScreenOps): pack on | pack contents [filter] | pack put <item|all|junk> | pack take
 * <item> [n]. Batch put/take iterate the container in one call so a stash is quick.
 */
final class BackpackOps {

    private static final String ATTACH = "com.tiviacz.travelersbackpack.attachment.AttachmentUtils";
    private static final String ITEM = "com.tiviacz.travelersbackpack.item.TravelersBackpackItem";

    private static Boolean present;
    private static Method mEquip;
    private static Method mIsWearing;
    private static Method mGetWorn;
    private static Method mGetWrapper;
    private static Method mGetStorage;
    private static Method mTrinketsGetAttach;   // TrinketsApi.getAttachment(LivingEntity)
    private static Method mGetSlotAccessSI;      // TrinketAttachment.getSlotAccess(String,int)
    private static Method mSlotGet;              // TrinketSlotAccess.get()
    private static Method mAllEquipped;          // TrinketAttachment.allEquipped(boolean)
    private static Method mGetInventories;       // TrinketAttachment.getInventories() -> Map<String,TrinketInventory>
    private static Method mInvSlotAccess;        // TrinketInventory.getOrCreateSlotAccess(int)
    private static Method mInvSlotType;          // TrinketInventory.slotType()
    private static Method mTypeId;               // SlotType.getId()
    private static Method mTypeGroup;            // SlotType.group()
    private static Method mSlotSet;              // TrinketSlotAccess.set(ItemStack) -> boolean
    private static Method mFromStack;            // BackpackWrapper.fromStack(ItemStack)
    private static Method mWrapStorage;          // BackpackWrapper.getStorage()
    private static Method mWrapStack;            // BackpackWrapper.getBackpackStack()

    private BackpackOps() {}

    static boolean present() {
        if (present == null) {
            try {
                Class<?> a = Class.forName(ATTACH);
                mEquip = a.getMethod("equipBackpack", Player.class, ItemStack.class);
                mIsWearing = a.getMethod("isWearingBackpack", Player.class);
                mGetWorn = a.getMethod("getWearingBackpack", Player.class);
                mGetWrapper = a.getMethod("getBackpackWrapper", Player.class);
                mGetStorage = mGetWrapper.getReturnType().getMethod("getStorage");
                // Direct Trinkets read (authoritative worn stack) -- bypasses TB's isWearingBackpack gate
                Class<?> tapi = Class.forName("eu.pb4.trinkets.api.TrinketsApi");
                mTrinketsGetAttach = tapi.getMethod("getAttachment", net.minecraft.world.entity.LivingEntity.class);
                Class<?> tatt = Class.forName("eu.pb4.trinkets.api.TrinketAttachment");
                mGetSlotAccessSI = tatt.getMethod("getSlotAccess", String.class, int.class);
                Class<?> tsa = Class.forName("eu.pb4.trinkets.api.TrinketSlotAccess");
                mSlotGet = tsa.getMethod("get");
                mAllEquipped = tatt.getMethod("allEquipped", boolean.class);
                mGetInventories = tatt.getMethod("getInventories");
                Class<?> tinv = Class.forName("eu.pb4.trinkets.api.TrinketInventory");
                mInvSlotAccess = tinv.getMethod("getOrCreateSlotAccess", int.class);
                mInvSlotType = tinv.getMethod("slotType");
                Class<?> stype = Class.forName("eu.pb4.trinkets.api.SlotType");
                mTypeId = stype.getMethod("getId");
                mTypeGroup = stype.getMethod("group");
                mSlotSet = tsa.getMethod("set", ItemStack.class);
                Class<?> wrap = Class.forName("com.tiviacz.travelersbackpack.inventory.BackpackWrapper");
                mFromStack = wrap.getMethod("fromStack", ItemStack.class);
                mWrapStorage = wrap.getMethod("getStorage");
                mWrapStack = wrap.getMethod("getBackpackStack");
                present = true;
            } catch (Throwable t) {
                present = false;
            }
        }
        return present;
    }

    private static final net.minecraft.tags.TagKey<net.minecraft.world.item.Item> BACK_TAG =
            net.minecraft.tags.TagKey.create(net.minecraft.core.registries.Registries.ITEM,
                    net.minecraft.resources.Identifier.fromNamespaceAndPath("trinkets", "chest/back"));

    static boolean isBackpackItem(ItemStack s) {
        if (s == null || s.isEmpty()) {
            return false;
        }
        // The trinkets:chest/back tag is TB's authoritative "is a wearable backpack" marker
        // (covers all tiers + fun variants like "bat" whose id has no "backpack" substring).
        if (s.is(BACK_TAG)) {
            return true;
        }
        String ns = BuiltInRegistries.ITEM.getKey(s.getItem()).getNamespace();
        return "travelersbackpack".equals(ns) && (isType(s, ITEM)
                || BuiltInRegistries.ITEM.getKey(s.getItem()).getPath().contains("backpack"));
    }

    // ---------- pack on ----------

    static String equip(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) {
            return "pack: not in world";
        }
        if (!present()) {
            return "pack: Travelers Backpack not installed on this profile";
        }
        try {
            net.minecraft.world.entity.player.Player checkP = serverPlayer(mc);
            if (checkP == null) { checkP = p; }
            if (storageOf(checkP) != null) {
                return "pack: already wearing a backpack";
            }
            // find a backpack in the hotbar/inventory
            int src = -1;
            for (int i = 0; i < p.getInventory().getContainerSize(); i++) {
                if (isBackpackItem(p.getInventory().getItem(i))) {
                    src = i;
                    break;
                }
            }
            if (src < 0) {
                return "pack: no backpack in inventory to wear";
            }
            ItemStack bp = p.getInventory().getItem(src).copy();
            final int srcSlot = src;
            net.minecraft.client.server.IntegratedServer srv = mc.getSingleplayerServer();
            if (srv != null) {
                // SERVER-AUTHORITATIVE equip: TB.equipBackpack writes the Trinkets slot + syncs,
                // so it must run on the server player on the server thread (a client-only call is
                // reverted on the next sync -- the "did not take" we saw live).
                java.util.UUID uuid = p.getUUID();
                java.util.concurrent.CompletableFuture<String> fut = new java.util.concurrent.CompletableFuture<>();
                srv.execute(() -> {
                    try {
                        net.minecraft.server.level.ServerPlayer sp = srv.getPlayerList().getPlayer(uuid);
                        if (sp == null) { fut.complete("pack: server player not found"); return; }
                        // Set the trinket BACK slot directly (group "chest", slot id "back"),
                        // then remove the source. More reliable than TB.equipBackpack.
                        Object att = mTrinketsGetAttach.invoke(null, sp);
                        Object slot = backSlotAccess(att);
                        if (slot == null) { fut.complete("pack: no trinket back slot found"); return; }
                        boolean ok = (Boolean) mSlotSet.invoke(slot, bp.copy());
                        if (ok) {
                            sp.getInventory().setItem(srcSlot, ItemStack.EMPTY);
                            fut.complete("OK wearing " + BuiltInRegistries.ITEM.getKey(bp.getItem()).getPath());
                        } else {
                            fut.complete("pack: trinket slot rejected the backpack (slot full?)");
                        }
                    } catch (Throwable t) {
                        M1Server.log("BackpackOps.equip(server): " + t);
                        fut.complete("pack: equip failed (" + t.getClass().getSimpleName() + ")");
                    }
                });
                try { return fut.get(2, java.util.concurrent.TimeUnit.SECONDS); }
                catch (Throwable t) { return "pack: equip timed out"; }
            }
            // no integrated server (real MP): best-effort client call
            mEquip.invoke(null, p, bp);
            if ((Boolean) mIsWearing.invoke(null, p)) {
                p.getInventory().setItem(src, ItemStack.EMPTY);
                return "OK wearing " + BuiltInRegistries.ITEM.getKey(bp.getItem()).getPath();
            }
            return "pack: equip did not take (MP server did not accept the client equip)";
        } catch (Throwable t) {
            M1Server.log("BackpackOps.equip: " + t);
            return "pack: equip failed (" + t.getClass().getSimpleName() + ")";
        }
    }

    // ---------- shared: the worn (or held) backpack's storage container ----------

    private static Container storage(Minecraft mc) {
        // Read the worn backpack from the SERVER player: the Trinkets slot is server-authoritative,
        // so the client getWearingBackpack returns empty even while worn. In SP the integrated
        // server player has the real stack + wrapper. (All backpack ops go through this.)
        net.minecraft.world.entity.player.Player who = serverPlayer(mc);
        if (who == null) {
            who = mc.player; // MP fallback: best-effort client read
        }
        try {
            if (!(Boolean) mIsWearing.invoke(null, who)) {
                return null;
            }
            Object wrapper = mGetWrapper.invoke(null, who);
            Object handler = mGetStorage.invoke(wrapper);
            return (handler instanceof Container c) ? c : null; // ItemStackHandler extends SimpleContainer
        } catch (Throwable t) {
            M1Server.log("BackpackOps.storage: " + t);
            return null;
        }
    }

    /** The integrated-server player matching the client player (SP only), or null. */
    static net.minecraft.world.entity.player.Player serverPlayer(Minecraft mc) {
        try {
            net.minecraft.client.server.IntegratedServer srv = mc.getSingleplayerServer();
            if (srv == null || mc.player == null) {
                return null;
            }
            return srv.getPlayerList().getPlayer(mc.player.getUUID());
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Run a job on the integrated-server thread and block briefly for its String result. ALL
     * server-state touches (worn-backpack read + container mutation) MUST go through here: the
     * socket thread races the server otherwise (the "not wearing after equip" bug). Returns the
     * job result, or a fallback string on timeout / no-server.
     */
    private static String onServer(Minecraft mc, java.util.function.Function<net.minecraft.server.level.ServerPlayer, String> job) {
        net.minecraft.client.server.IntegratedServer srv = mc.getSingleplayerServer();
        if (srv == null || mc.player == null) {
            return null; // MP / no integrated server
        }
        java.util.UUID uuid = mc.player.getUUID();
        java.util.concurrent.CompletableFuture<String> fut = new java.util.concurrent.CompletableFuture<>();
        srv.execute(() -> {
            try {
                net.minecraft.server.level.ServerPlayer sp = srv.getPlayerList().getPlayer(uuid);
                fut.complete(sp == null ? "pack: server player not found" : job.apply(sp));
            } catch (Throwable t) {
                M1Server.log("BackpackOps.onServer: " + t);
                fut.complete("pack: server op failed (" + t.getClass().getSimpleName() + ")");
            }
        });
        try {
            return fut.get(2, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Throwable t) {
            return "pack: server op timed out";
        }
    }

    /** The TrinketSlotAccess for the backpack back-slot (group chest, id back), or null. */
    private static Object backSlotAccess(Object att) throws Exception {
        if (att == null) { return null; }
        Object invs = mGetInventories.invoke(att); // Map<String,TrinketInventory>
        if (!(invs instanceof java.util.Map<?, ?> map)) { return null; }
        Object fallback = null;
        for (Object inv : map.values()) {
            Object type = mInvSlotType.invoke(inv);
            String id = String.valueOf(mTypeId.invoke(type));
            String grp = String.valueOf(mTypeGroup.invoke(type));
            if ("back".equals(id)) {
                return mInvSlotAccess.invoke(inv, 0);
            }
            if ("chest".equals(grp) && fallback == null) {
                fallback = mInvSlotAccess.invoke(inv, 0);
            }
        }
        return fallback;
    }

    /** The worn backpack storage container for a specific (server) player. Reads the trinkets
     *  "back" slot DIRECTLY (authoritative), bypassing TB's isWearingBackpack sync gate. */
    private static Container storageOf(net.minecraft.world.entity.player.Player who) {
        try {
            // (1) Trinkets worn stack (authoritative -- allEquipped, slot-name independent).
            ItemStack worn = wornStack(who);
            if (worn != null && !worn.isEmpty()) {
                Object w = mFromStack.invoke(null, worn);
                Object h = mWrapStorage.invoke(w);
                if (h instanceof Container c) {
                    return c;
                }
            }
            // (2) fallback: TB native worn wrapper (enableTrinkets=false configs).
            Object wrapper = mGetWrapper.invoke(null, who);
            if (wrapper != null) {
                Object stk = mWrapStack.invoke(wrapper);
                if (stk instanceof ItemStack bs && !bs.isEmpty()) {
                    Object handler = mWrapStorage.invoke(wrapper);
                    if (handler instanceof Container c) {
                        return c;
                    }
                }
            }
            return null;
        } catch (Throwable t) {
            M1Server.log("BackpackOps.storageOf: " + t);
            return null;
        }
    }

    /** The backpack ItemStack worn in ANY trinket slot, or EMPTY. Iterates allEquipped (slot-name
     *  independent) and returns the first equipped stack that is a backpack. */
    static ItemStack wornStack(net.minecraft.world.entity.player.Player who) {
        try {
            Object att = mTrinketsGetAttach.invoke(null, who);
            if (att == null) {
                return ItemStack.EMPTY;
            }
            Object list = mAllEquipped.invoke(att, Boolean.TRUE);
            if (list instanceof java.util.List<?> l) {
                for (Object slot : l) {
                    Object s = mSlotGet.invoke(slot);
                    if (s instanceof ItemStack st && !st.isEmpty() && isBackpackItem(st)) {
                        return st;
                    }
                }
            }
            return ItemStack.EMPTY;
        } catch (Throwable t) {
            M1Server.log("BackpackOps.wornStack: " + t);
            return ItemStack.EMPTY;
        }
    }

    /** Diagnostic: report what each accessor sees for the worn backpack (server thread). */
    static String debug(Minecraft mc) {
        if (!present()) { return "pack debug: TB/Trinkets classes not resolved"; }
        String r = onServer(mc, sp -> {
            StringBuilder b = new StringBuilder("pack debug (server player):\n");
            try { b.append("  isWearingBackpack=").append(mIsWearing.invoke(null, sp)).append("\n"); }
            catch (Throwable t) { b.append("  isWearingBackpack ERR ").append(t).append("\n"); }
            try { Object w = mGetWorn.invoke(null, sp);
                  b.append("  getWearingBackpack=").append(w instanceof ItemStack s ? (s.isEmpty()?"EMPTY":BuiltInRegistries.ITEM.getKey(s.getItem()).getPath()) : w).append("\n"); }
            catch (Throwable t) { b.append("  getWearingBackpack ERR ").append(t).append("\n"); }
            try { Object att = mTrinketsGetAttach.invoke(null, sp);
                  b.append("  trinketAttachment=").append(att==null?"null":"present").append("\n");
                  if (att != null) {
                      Object list = mAllEquipped.invoke(att, Boolean.TRUE);
                      int n = (list instanceof java.util.List<?> l) ? l.size() : -1;
                      b.append("  allEquipped.size=").append(n).append("\n");
                      if (list instanceof java.util.List<?> l) {
                          for (Object slot : l) {
                              Object s = mSlotGet.invoke(slot);
                              if (s instanceof ItemStack st && !st.isEmpty()) {
                                  b.append("    equipped: ").append(BuiltInRegistries.ITEM.getKey(st.getItem()).getPath())
                                   .append(" isBackpack=").append(isBackpackItem(st)).append("\n");
                              }
                          }
                      }
                  } }
            catch (Throwable t) { b.append("  trinket read ERR ").append(t).append("\n"); }
            try { Object wr = mGetWrapper.invoke(null, sp);
                  Object stk = wr==null?null:mWrapStack.invoke(wr);
                  b.append("  nativeWrapper.stack=").append(stk instanceof ItemStack s ? (s.isEmpty()?"EMPTY":BuiltInRegistries.ITEM.getKey(s.getItem()).getPath()) : stk).append("\n"); }
            catch (Throwable t) { b.append("  nativeWrapper ERR ").append(t).append("\n"); }
            return b.toString().trim();
        });
        return r != null ? r : "pack debug: no integrated server";
    }

    // ---------- pack contents ----------

    static String contents(Minecraft mc, String filter) {
        if (!present()) {
            return "pack: Travelers Backpack not installed";
        }
        String q = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        String r = onServer(mc, sp -> contentsBody(storageOf(sp), q));
        return r != null ? r : "pack contents: not available (no integrated server)";
    }

    private static String contentsBody(Container st, String q) {
        if (st == null) {
            return "pack contents: not wearing a backpack (pack on first)";
        }
        StringBuilder b = new StringBuilder();
        int kinds = 0;
        int total = 0;
        for (int i = 0; i < st.getContainerSize(); i++) {
            ItemStack s = st.getItem(i);
            if (s.isEmpty()) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
            if (!q.isEmpty() && !id.contains(q)) {
                continue;
            }
            kinds++;
            total += s.getCount();
            if (kinds <= 60) {
                b.append("  ").append(s.getCount()).append("x ").append(id).append("\n");
            }
        }
        String head = "backpack: " + kinds + " kinds, " + total + " items"
                + (q.isEmpty() ? "" : " (filter '" + q + "')") + "\n";
        return kinds == 0 ? "backpack: empty" + (q.isEmpty() ? "" : " (filter '" + q + "')")
                : (head + b).trim();
    }

    // ---------- pack put <item|all|junk> (batch) ----------

    static String put(Minecraft mc, String arg) {
        if (!present()) {
            return "pack: Travelers Backpack not installed";
        }
        String a = arg == null ? "" : arg.trim().toLowerCase(Locale.ROOT);
        String r = onServer(mc, sp -> putBody(sp, storageOf(sp), a));
        return r != null ? r : "pack put: not available (no integrated server)";
    }

    private static String putBody(net.minecraft.world.entity.player.Player p, Container st, String a) {
        if (st == null) {
            return "pack put: not wearing a backpack (pack on first)";
        }
        int moved = 0;
        // iterate main inventory (0..35); never move worn armor/offhand
        for (int i = 0; i < 36; i++) {
            ItemStack s = p.getInventory().getItem(i);
            if (s.isEmpty() || isBackpackItem(s)) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
            boolean want = a.equals("all")
                    || (a.equals("junk") && InventoryOps.isJunk(s))
                    || (!a.isEmpty() && !a.equals("all") && !a.equals("junk") && id.contains(a));
            if (!want) {
                continue;
            }
            ItemStack remainder = insert(st, s.copy());
            if (remainder.getCount() < s.getCount()) {
                moved += s.getCount() - remainder.getCount();
                p.getInventory().setItem(i, remainder.isEmpty() ? ItemStack.EMPTY : remainder);
            }
        }
        return moved == 0 ? "pack put: nothing matched / backpack full"
                : "OK pack put: " + moved + " item(s) stored";
    }

    // ---------- pack take <item> [n] (batch) ----------

    static String take(Minecraft mc, String arg) {
        if (!present()) {
            return "pack: Travelers Backpack not installed";
        }
        String r = onServer(mc, sp -> takeBody(sp, storageOf(sp), arg));
        return r != null ? r : "pack take: not available (no integrated server)";
    }

    private static String takeBody(net.minecraft.world.entity.player.Player p, Container st, String arg) {
        if (st == null) {
            return "pack take: not wearing a backpack (pack on first)";
        }
        String[] t = arg.trim().split("\\s+");
        if (t.length == 0 || t[0].isEmpty()) {
            return "ERR usage: pack take <item> [count]";
        }
        String item = t[0].toLowerCase(Locale.ROOT);
        int want = Integer.MAX_VALUE;
        if (t.length > 1) {
            try {
                want = Integer.parseInt(t[1]);
            } catch (NumberFormatException e) {
                return "ERR usage: pack take <item> [count]";
            }
        }
        int taken = 0;
        for (int i = 0; i < st.getContainerSize() && taken < want; i++) {
            ItemStack s = st.getItem(i);
            if (s.isEmpty()) {
                continue;
            }
            String id = BuiltInRegistries.ITEM.getKey(s.getItem()).getPath();
            if (!id.contains(item)) {
                continue;
            }
            int n = Math.min(s.getCount(), want - taken);
            ItemStack out = s.copy();
            out.setCount(n);
            if (p.getInventory().add(out)) {
                s.shrink(n);
                st.setItem(i, s.isEmpty() ? ItemStack.EMPTY : s);
                taken += n;
            } else {
                break; // player inventory full
            }
        }
        st.setChanged();
        return taken == 0 ? "pack take: no '" + item + "' in the backpack"
                : "OK pack take: " + taken + "x " + item + " to inventory";
    }

    /** Insert a stack into the first fitting slots of the container; returns the remainder. */
    private static ItemStack insert(Container st, ItemStack stack) {
        // fill existing partial stacks of the same item, then empty slots
        for (int pass = 0; pass < 2 && !stack.isEmpty(); pass++) {
            for (int i = 0; i < st.getContainerSize() && !stack.isEmpty(); i++) {
                ItemStack in = st.getItem(i);
                if (pass == 0) {
                    if (in.isEmpty() || !ItemStack.isSameItemSameComponents(in, stack)) {
                        continue;
                    }
                    int room = Math.min(st.getMaxStackSize(), in.getMaxStackSize()) - in.getCount();
                    if (room <= 0) {
                        continue;
                    }
                    int add = Math.min(room, stack.getCount());
                    in.grow(add);
                    st.setItem(i, in);
                    stack.shrink(add);
                } else if (in.isEmpty()) {
                    st.setItem(i, stack.copy());
                    stack.setCount(0);
                }
            }
        }
        st.setChanged();
        return stack;
    }

    private static boolean isType(ItemStack s, String className) {
        try {
            return Class.forName(className).isInstance(s.getItem());
        } catch (Throwable t) {
            return false;
        }
    }
}
