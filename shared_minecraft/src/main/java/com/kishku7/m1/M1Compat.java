package com.kishku7.m1;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import com.mojang.blaze3d.pipeline.RenderTarget;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Cross-version fault-line bridges so one source compiles + runs on 1.20 -> 26.3.
 *  Reflection-bridge convention (resolves on the mojmap runtimes: MC 26+, NeoForge, Forge/FG6 1.20.1+).
 *  Pre-26 Fabric (intermediary runtime) uses the Cog-generated twin of this class instead -- see
 *  _codegen/cog_sources/M1Compat.java + _codegen/compat.py. Keep the two method surfaces identical. */
public final class M1Compat {
    private M1Compat() {}

    // 26.1: Minecraft.screen (field). 26.2+: Minecraft.gui.screen().
    public static Screen screen(Minecraft mc) {
        try {
            Field guiF = Minecraft.class.getField("gui");
            Object gui = guiF.get(mc);
            Method m = gui.getClass().getMethod("screen");
            return (Screen) m.invoke(gui);
        } catch (Exception ignored) {}
        try {
            Field f = Minecraft.class.getField("screen");
            return (Screen) f.get(mc);
        } catch (Exception e) {
            return null;
        }
    }

    // 26.1: Minecraft.setScreen(Screen). 26.2+: Minecraft.gui.setScreen(Screen).
    public static void setScreen(Minecraft mc, Screen screen) {
        try {
            Field guiF = Minecraft.class.getField("gui");
            Object gui = guiF.get(mc);
            for (Method m : gui.getClass().getMethods()) {
                if (m.getName().equals("setScreen") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isAssignableFrom(Screen.class)) {
                    m.invoke(gui, screen);
                    return;
                }
            }
        } catch (NoSuchFieldException ignored) {
        } catch (Exception e) {
            throw new RuntimeException("M1Compat.setScreen via gui failed", e);
        }
        try {
            Method m = Minecraft.class.getMethod("setScreen", Screen.class);
            m.invoke(mc, screen);
        } catch (Exception e) {
            throw new RuntimeException("M1Compat.setScreen failed", e);
        }
    }

    // 26.1: EntityType.ZOMBIE (field). 26.2+: BuiltInRegistries.ENTITY_TYPE.getValue(EntityTypeIds.ZOMBIE).
    public static EntityType<?> zombie() {
        try {
            Field f = EntityType.class.getField("ZOMBIE");
            return (EntityType<?>) f.get(null);
        } catch (NoSuchFieldException ignored) {
        } catch (Exception e) {
            throw new RuntimeException("M1Compat.zombie field failed", e);
        }
        try {
            Class<?> ids = Class.forName("net.minecraft.world.entity.EntityTypeIds");
            Object key = ids.getField("ZOMBIE").get(null);
            Class<?> reg = Class.forName("net.minecraft.core.registries.BuiltInRegistries");
            Object entityReg = reg.getField("ENTITY_TYPE").get(null);
            for (Method m : entityReg.getClass().getMethods()) {
                if (m.getName().equals("getValue") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isAssignableFrom(ResourceKey.class)) {
                    return (EntityType<?>) m.invoke(entityReg, key);
                }
            }
            throw new RuntimeException("M1Compat.zombie: no getValue(ResourceKey)");
        } catch (Exception e) {
            throw new RuntimeException("M1Compat.zombie registry failed", e);
        }
    }

    // 26.1: Minecraft.getMainRenderTarget(). 26.2+: render target moved to
    // GameRenderer -> Minecraft.gameRenderer.mainRenderTarget().
    public static RenderTarget mainRenderTarget(Minecraft mc) {
        try {
            Method m = Minecraft.class.getMethod("getMainRenderTarget");
            return (RenderTarget) m.invoke(mc);
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            throw new RuntimeException("M1Compat.mainRenderTarget direct failed", e);
        }
        try {
            Field grF = Minecraft.class.getField("gameRenderer");
            Object gr = grF.get(mc);
            Method m = gr.getClass().getMethod("mainRenderTarget");
            return (RenderTarget) m.invoke(gr);
        } catch (Exception e) {
            throw new RuntimeException("M1Compat.mainRenderTarget via gameRenderer failed", e);
        }
    }

    // ---- 1.21.9 re-intermediation wave (ResourceLocation->Identifier, ResourceKey.location()->
    //      identifier(), the net.minecraft.client.input package, the PIERCING_WEAPON data component).
    //      Reflection resolves on all mojmap runtimes (new name 1.21.9+, old name pre-1.21.9); the
    //      Fabric cells use the Cog twin. ----

    // ResourceKey.identifier() (1.21.9+) or location() (pre-1.21.9) -> the ResourceLocation/Identifier.
    private static Object keyLoc(ResourceKey<?> key) {
        for (String n : new String[] {"identifier", "location"}) {
            try {
                Method m = ResourceKey.class.getMethod(n);
                return m.invoke(key);
            } catch (NoSuchMethodException ignored) {
            } catch (Exception e) {
                throw new RuntimeException("M1Compat.keyLoc(" + n + ") failed", e);
            }
        }
        throw new RuntimeException("M1Compat.keyLoc: no identifier()/location() on ResourceKey");
    }

    /** Full id string of a ResourceKey's value (e.g. "minecraft:overworld"). */
    public static String keyId(ResourceKey<?> key) {
        Object loc = keyLoc(key);
        return loc == null ? "?" : loc.toString();
    }

    /** Path segment of a ResourceKey's value (e.g. "sharpness"). */
    public static String keyPath(ResourceKey<?> key) {
        Object loc = keyLoc(key);
        if (loc == null) {
            return "?";
        }
        try {
            Method m = loc.getClass().getMethod("getPath");
            return (String) m.invoke(loc);
        } catch (Exception e) {
            return loc.toString();
        }
    }

    /** Build an item TagKey from a namespaced id, across the ResourceLocation->Identifier rename. */
    @SuppressWarnings("unchecked")
    public static TagKey<Item> itemTag(String ns, String path) {
        try {
            Class<?> idClass;
            try {
                idClass = Class.forName("net.minecraft.resources.Identifier");
            } catch (ClassNotFoundException e) {
                idClass = Class.forName("net.minecraft.resources.ResourceLocation");
            }
            Object loc;
            try {
                loc = idClass.getMethod("fromNamespaceAndPath", String.class, String.class).invoke(null, ns, path);
            } catch (NoSuchMethodException preFactory) {
                loc = idClass.getConstructor(String.class, String.class).newInstance(ns, path);
            }
            Method create = TagKey.class.getMethod("create", ResourceKey.class, idClass);
            return (TagKey<Item>) create.invoke(null, Registries.ITEM, loc);
        } catch (Exception e) {
            throw new RuntimeException("M1Compat.itemTag failed", e);
        }
    }

    // The PIERCING_WEAPON data component (spear) exists only on 1.21.9+. Fully reflective so this class
    // still compiles on versions that predate the whole component system (< 1.20.5). Returns the
    // component value, or null when absent (pre-1.21.9 = no spear).
    private static Object piercingComponent(ItemStack s) {
        try {
            Class<?> dc = Class.forName("net.minecraft.core.component.DataComponents");
            Object comp = dc.getField("PIERCING_WEAPON").get(null);
            Class<?> dct = Class.forName("net.minecraft.core.component.DataComponentType");
            Method has = ItemStack.class.getMethod("has", dct);
            if (!(Boolean) has.invoke(s, comp)) {
                return null;
            }
            Method get = ItemStack.class.getMethod("get", dct);
            return get.invoke(s, comp);
        } catch (Throwable t) {
            return null;
        }
    }

    /** True if the stack is a spear (PIERCING_WEAPON component; false on versions without it). */
    public static boolean isPiercingWeapon(ItemStack s) {
        return piercingComponent(s) != null;
    }

    /** Perform the 1.21.9+ spear stab if the main hand holds a piercing weapon; false otherwise. */
    public static boolean tryPiercingAttack(Minecraft mc, LocalPlayer p) {
        Object pw = piercingComponent(p.getMainHandItem());
        if (pw == null || mc.gameMode == null) {
            return false;
        }
        try {
            for (Method m : mc.gameMode.getClass().getMethods()) {
                if (m.getName().equals("piercingAttack") && m.getParameterCount() == 1) {
                    m.invoke(mc.gameMode, pw);
                    p.swing(InteractionHand.MAIN_HAND);
                    return true;
                }
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** Press a Button across the onPress() -> onPress(InputWithModifiers) change at 1.21.9. */
    public static void pressButton(Button b) {
        try {
            Method m = Button.class.getMethod("onPress");
            m.invoke(b);
            return;
        } catch (NoSuchMethodException ignored) {
        } catch (Exception e) {
            throw new RuntimeException("M1Compat.pressButton no-arg failed", e);
        }
        try {
            Class<?> iwm = Class.forName("net.minecraft.client.input.InputWithModifiers");
            Object proxy = Proxy.newProxyInstance(iwm.getClassLoader(), new Class<?>[] {iwm},
                (pr, meth, args) -> {
                    switch (meth.getName()) {
                        case "input": return 257;      // GLFW Enter
                        case "modifiers": return 0;
                        case "hashCode": return System.identityHashCode(pr);
                        case "equals": return pr == args[0];
                        case "toString": return "InputWithModifiers(Enter)";
                        default: return null;
                    }
                });
            Method m = Button.class.getMethod("onPress", iwm);
            m.invoke(b, proxy);
        } catch (Exception e) {
            throw new RuntimeException("M1Compat.pressButton InputWithModifiers failed", e);
        }
    }

    /** Level.isBrightOutside() (1.21.5+) vs isDay() (pre-1.21.5). */
    public static boolean isBrightOutside(net.minecraft.world.level.Level lvl) {
        for (String n : new String[] {"isBrightOutside", "isDay"}) {
            try {
                return (Boolean) lvl.getClass().getMethod(n).invoke(lvl);
            } catch (NoSuchMethodException ignored) {
            } catch (Exception e) {
                throw new RuntimeException("M1Compat.isBrightOutside(" + n + ")", e);
            }
        }
        return true;
    }

    /** Item's equipment slot: 1.21.2+ EQUIPPABLE component; pre via LivingEntity.getEquipmentSlotForItem. */
    public static EquipmentSlot equipSlot(Minecraft mc, ItemStack s) {
        try {
            Class<?> dc = Class.forName("net.minecraft.core.component.DataComponents");
            Object comp = dc.getField("EQUIPPABLE").get(null);
            Class<?> dct = Class.forName("net.minecraft.core.component.DataComponentType");
            Object eq = ItemStack.class.getMethod("get", dct).invoke(s, comp);
            if (eq == null) {
                return null;
            }
            return (EquipmentSlot) eq.getClass().getMethod("slot").invoke(eq);
        } catch (ClassNotFoundException | NoSuchFieldException pre) {
            // pre-1.21.2: no EQUIPPABLE component
        } catch (Exception e) {
            return null;
        }
        try {
            return (EquipmentSlot) mc.player.getClass()
                    .getMethod("getEquipmentSlotForItem", ItemStack.class).invoke(mc.player, s);
        } catch (Exception e) {
            return null;
        }
    }

    /** Food value (nutrition + saturation), 0 when not food. 1.20.5+ FOOD component; pre getFoodProperties(). */
    public static double foodValue(ItemStack s) {
        try {
            Class<?> dc = Class.forName("net.minecraft.core.component.DataComponents");
            Object comp = dc.getField("FOOD").get(null);
            Class<?> dct = Class.forName("net.minecraft.core.component.DataComponentType");
            Object f = ItemStack.class.getMethod("get", dct).invoke(s, comp);
            if (f == null) {
                return 0;
            }
            double n = ((Number) f.getClass().getMethod("nutrition").invoke(f)).doubleValue();
            double sat = ((Number) f.getClass().getMethod("saturation").invoke(f)).doubleValue();
            return n + sat;
        } catch (ClassNotFoundException | NoSuchFieldException pre) {
            // pre-1.20.5: no FOOD component
        } catch (Exception e) {
            return 0;
        }
        try {
            Object item = ItemStack.class.getMethod("getItem").invoke(s);
            Object f = item.getClass().getMethod("getFoodProperties").invoke(item);
            if (f == null) {
                return 0;
            }
            double n = ((Number) f.getClass().getMethod("getNutrition").invoke(f)).doubleValue();
            double sat = ((Number) f.getClass().getMethod("getSaturationModifier").invoke(f)).doubleValue();
            return n + sat;
        } catch (Exception e) {
            return 0;
        }
    }

    /** Enchantment listing "id level (\"Full Name\"), ...". 1.20.5+ ItemEnchantments component
     *  (Holder-arg 1.21+, Enchantment-arg 1.20.5-1.20.6); pre-1.20.5 via EnchantmentHelper. */
    public static String enchantmentsLine(ItemStack s, boolean stored) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        try {
            Class<?> dc = Class.forName("net.minecraft.core.component.DataComponents");
            Object comp = dc.getField(stored ? "STORED_ENCHANTMENTS" : "ENCHANTMENTS").get(null);
            Class<?> dct = Class.forName("net.minecraft.core.component.DataComponentType");
            Object e = ItemStack.class.getMethod("get", dct).invoke(s, comp);
            if (e == null) {
                return "";
            }
            Object keySet = e.getClass().getMethod("keySet").invoke(e);
            Class<?> enchClass = Class.forName("net.minecraft.world.item.enchantment.Enchantment");
            for (Object h : (Iterable<?>) keySet) {
                Object val = h.getClass().getMethod("value").invoke(h);
                int level = enchLevel(e, h, val);
                parts.add(enchId(h) + " " + level + fullnameSuffix(enchClass, h, val, level));
            }
            return String.join(", ", parts);
        } catch (ClassNotFoundException | NoSuchFieldException pre) {
            // pre-1.20.5: no ItemEnchantments component
        } catch (Exception ex) {
            return "";
        }
        try {
            Class<?> eh = Class.forName("net.minecraft.world.item.enchantment.EnchantmentHelper");
            Object map = eh.getMethod("getEnchantments", ItemStack.class).invoke(null, s);
            Object enchReg = Class.forName("net.minecraft.core.registries.BuiltInRegistries")
                    .getField("ENCHANTMENT").get(null);
            for (Object entry : ((java.util.Map<?, ?>) map).entrySet()) {
                java.util.Map.Entry<?, ?> me = (java.util.Map.Entry<?, ?>) entry;
                Object ench = me.getKey();
                int level = ((Number) me.getValue()).intValue();
                Object key = enchReg.getClass().getMethod("getKey", Object.class).invoke(enchReg, ench);
                String eid = (key == null) ? "?" : (String) key.getClass().getMethod("getPath").invoke(key);
                Object full = ench.getClass().getMethod("getFullname", int.class).invoke(ench, level);
                String fn = (String) full.getClass().getMethod("getString").invoke(full);
                parts.add(eid + " " + level + " (\"" + fn + "\")");
            }
        } catch (Exception ex) {
            return "";
        }
        return String.join(", ", parts);
    }

    private static int enchLevel(Object itemEnch, Object holder, Object val) {
        for (Object a : new Object[] {holder, val}) {
            for (Method m : itemEnch.getClass().getMethods()) {
                if (m.getName().equals("getLevel") && m.getParameterCount() == 1
                        && m.getParameterTypes()[0].isInstance(a)) {
                    try {
                        return ((Number) m.invoke(itemEnch, a)).intValue();
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return 0;
    }

    private static String enchId(Object holder) {
        try {
            Object opt = holder.getClass().getMethod("unwrapKey").invoke(holder);
            Object key = ((java.util.Optional<?>) opt).orElse(null);
            return key == null ? "?" : keyPath((ResourceKey<?>) key);
        } catch (Exception e) {
            return "?";
        }
    }

    private static String fullnameSuffix(Class<?> enchClass, Object holder, Object val, int level) {
        try {
            for (Method m : enchClass.getMethods()) {
                if (m.getName().equals("getFullname") && m.getParameterCount() == 2
                        && java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                    Class<?> p0 = m.getParameterTypes()[0];
                    Object a = p0.isInstance(holder) ? holder : (p0.isInstance(val) ? val : null);
                    if (a != null) {
                        Object c = m.invoke(null, a, level);
                        return " (\"" + c.getClass().getMethod("getString").invoke(c) + "\")";
                    }
                }
            }
            Object ic = val.getClass().getMethod("getFullname", int.class).invoke(val, level);
            return " (\"" + ic.getClass().getMethod("getString").invoke(ic) + "\")";
        } catch (Exception ignored) {
        }
        return "";
    }

    /** TB backpack with a sleeping bag attached (component-driven); false pre-1.20.5 (no components). */
    public static boolean hasAttachedSleepingBag(ItemStack s, String ns) {
        try {
            Object comps = ItemStack.class.getMethod("getComponents").invoke(s);
            Object reg = Class.forName("net.minecraft.core.registries.BuiltInRegistries")
                    .getField("DATA_COMPONENT_TYPE").get(null);
            Method getKey = firstMethod(reg, "getKey", 1);
            for (Object comp : (Iterable<?>) comps) {
                Object type = comp.getClass().getMethod("type").invoke(comp);
                Object id = getKey.invoke(reg, type);
                if (id == null) {
                    continue;
                }
                String namespace = (String) id.getClass().getMethod("getNamespace").invoke(id);
                String path = (String) id.getClass().getMethod("getPath").invoke(id);
                if (ns.equals(namespace) && path.contains("sleeping_bag")) {
                    Object v = comp.getClass().getMethod("value").invoke(comp);
                    return !(v instanceof Integer i) || i >= 0;
                }
            }
        } catch (Throwable t) {
            return false;
        }
        return false;
    }

    /** Custom display name Component, or null. 1.20.5+ CUSTOM_NAME component; pre hasCustomHoverName. */
    public static net.minecraft.network.chat.Component customName(ItemStack s) {
        try {
            Class<?> dc = Class.forName("net.minecraft.core.component.DataComponents");
            Object comp = dc.getField("CUSTOM_NAME").get(null);
            Class<?> dct = Class.forName("net.minecraft.core.component.DataComponentType");
            return (net.minecraft.network.chat.Component) ItemStack.class.getMethod("get", dct).invoke(s, comp);
        } catch (ClassNotFoundException | NoSuchFieldException pre) {
            // pre-1.20.5
        } catch (Exception e) {
            return null;
        }
        try {
            if (!(Boolean) ItemStack.class.getMethod("hasCustomHoverName").invoke(s)) {
                return null;
            }
            return (net.minecraft.network.chat.Component) ItemStack.class.getMethod("getHoverName").invoke(s);
        } catch (Exception e) {
            return null;
        }
    }

    /** True if the item carries a non-default component patch (or NBT tag pre-1.20.5). */
    public static boolean hasComponentPatch(ItemStack s) {
        try {
            Object patch = ItemStack.class.getMethod("getComponentsPatch").invoke(s);
            return !(Boolean) patch.getClass().getMethod("isEmpty").invoke(patch);
        } catch (Exception pre) {
            // pre-1.20.5
        }
        try {
            return Boolean.TRUE.equals(ItemStack.class.getMethod("hasTag").invoke(s));
        } catch (Exception e) {
            return false;
        }
    }

    /** Non-default component entries as "key=value" (or NBT keys pre-1.20.5). */
    public static java.util.List<String> componentPatchList(ItemStack s) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            Object patch = ItemStack.class.getMethod("getComponentsPatch").invoke(s);
            Object entrySet = patch.getClass().getMethod("entrySet").invoke(patch);
            Object reg = Class.forName("net.minecraft.core.registries.BuiltInRegistries")
                    .getField("DATA_COMPONENT_TYPE").get(null);
            Method getKey = firstMethod(reg, "getKey", 1);
            for (Object e : (Iterable<?>) entrySet) {
                java.util.Map.Entry<?, ?> me = (java.util.Map.Entry<?, ?>) e;
                String key = String.valueOf(getKey.invoke(reg, me.getKey()));
                Object val = me.getValue();
                String vs = (val instanceof java.util.Optional<?> o) ? o.map(String::valueOf).orElse("(removed)")
                        : String.valueOf(val);
                if (vs.length() > 60) {
                    vs = vs.substring(0, 57) + "...";
                }
                out.add(key + "=" + vs);
            }
            return out;
        } catch (Exception pre) {
            // pre-1.20.5
        }
        try {
            Object tag = ItemStack.class.getMethod("getTag").invoke(s);
            if (tag != null) {
                for (Object k : (Iterable<?>) tag.getClass().getMethod("getAllKeys").invoke(tag)) {
                    out.add(String.valueOf(k));
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** Advanced tooltip lines, across the 1.20.5 TooltipContext signature change. */
    @SuppressWarnings("unchecked")
    public static java.util.List<net.minecraft.network.chat.Component> tooltipLines(
            Minecraft mc, net.minecraft.world.entity.player.Player p, ItemStack s) {
        try {
            Class<?> tc = Class.forName("net.minecraft.world.item.Item$TooltipContext");
            Object ctx = tc.getMethod("of", net.minecraft.world.level.Level.class).invoke(null, mc.level);
            Object adv = Class.forName("net.minecraft.world.item.TooltipFlag").getField("ADVANCED").get(null);
            for (Method m : ItemStack.class.getMethods()) {
                if (m.getName().equals("getTooltipLines") && m.getParameterCount() == 3) {
                    return (java.util.List<net.minecraft.network.chat.Component>) m.invoke(s, ctx, p, adv);
                }
            }
        } catch (Throwable pre) {
            // pre-1.20.5
        }
        try {
            Object adv = Class.forName("net.minecraft.world.item.TooltipFlag").getField("ADVANCED").get(null);
            for (Method m : ItemStack.class.getMethods()) {
                if (m.getName().equals("getTooltipLines") && m.getParameterCount() == 2) {
                    return (java.util.List<net.minecraft.network.chat.Component>) m.invoke(s, p, adv);
                }
            }
        } catch (Throwable ignored) {
        }
        return new java.util.ArrayList<>();
    }

    /** Same item + same components (1.20.5+) / same tags (pre-1.20.5). */
    public static boolean sameItemSameComponents(ItemStack a, ItemStack b) {
        for (String n : new String[] {"isSameItemSameComponents", "isSameItemSameTags"}) {
            try {
                return (Boolean) ItemStack.class.getMethod(n, ItemStack.class, ItemStack.class).invoke(null, a, b);
            } catch (NoSuchMethodException ignored) {
            } catch (Exception e) {
                return false;
            }
        }
        return false;
    }

    private static Method firstMethod(Object o, String name, int argc) {
        for (Method m : o.getClass().getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == argc) {
                return m;
            }
        }
        return null;
    }
}
