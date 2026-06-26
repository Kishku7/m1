package com.kishku7.m1;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.EntityType;
import net.minecraft.resources.ResourceKey;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** Cross-26.x fault-line bridges so one source compiles + runs on 26.1 -> 26.3.
 *  Same reflection-bridge convention as BvCompat / ScreenCompat (no Cog). */
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
}
