package com.kishku7.m1;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;

import java.lang.reflect.Method;

/**
 * Version facade for the container-click protocol -- one source, every MC version.
 *   26+    : MultiPlayerGameMode.handleContainerInput(int, int, int, ContainerInput, Player)
 *   pre-26 : MultiPlayerGameMode.handleInventoryMouseClick(int, int, int, ClickType, Player)
 * Both take a 4th-arg enum (ContainerInput / ClickType) carrying PICKUP / QUICK_MOVE / SWAP.
 * Resolved by reflection so business code never names a version-specific type.
 */
public final class ContainerCompat {
    private ContainerCompat() {}

    /** Loader/version-agnostic click intents (mapped to the per-version enum by name). */
    public enum Mode { PICKUP, QUICK_MOVE, SWAP }

    private static Method method;
    private static Class<?> enumType;
    private static boolean resolved;

    private static void resolve(MultiPlayerGameMode gm) {
        if (resolved) return;
        resolved = true;
        for (String name : new String[] { "handleContainerInput", "handleInventoryMouseClick" }) {
            for (Method m : gm.getClass().getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 5) {
                    method = m;
                    enumType = m.getParameterTypes()[3];
                    return;
                }
            }
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" }) // enumType is a runtime-resolved enum class
    private static Object constant(Mode mode) {
        return Enum.valueOf((Class<? extends Enum>) enumType, mode.name());
    }

    /** Drive one container-menu slot click (the desync-safe protocol). No-op if unresolved. */
    public static void click(Minecraft mc, int containerId, int slot, int button, Mode mode) {
        MultiPlayerGameMode gm = mc.gameMode;
        if (gm == null) return;
        resolve(gm);
        if (method == null) return;
        try {
            method.invoke(gm, containerId, slot, button, constant(mode), mc.player);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("container-click facade failed: " + e.getMessage(), e);
        }
    }
}
