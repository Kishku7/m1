package com.kishku7.m1;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

import java.lang.reflect.Method;

/**
 * Version facade for spawning the un-ticked path-proxy mob. The spawn-reason enum was renamed
 * MobSpawnType -> EntitySpawnReason; EntityType.create(Level, <reason>) takes whichever exists.
 * Resolved reflectively (the NATURAL constant exists on both).
 */
public final class SpawnCompat {
    private SpawnCompat() {}

    private static boolean resolved;
    private static Method create;
    private static Object natural;

    private static void resolve() {
        if (resolved) return;
        resolved = true;
        for (Method m : EntityType.class.getMethods()) {
            if (!m.getName().equals("create") || m.getParameterCount() != 2) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p[0] == Level.class && p[1].isEnum()) { create = m; natural = constant(p[1]); break; }
        }
    }

    @SuppressWarnings({ "unchecked", "rawtypes" }) // spawn-reason enum resolved at runtime
    private static Object constant(Class<?> enumType) {
        return Enum.valueOf((Class<? extends Enum>) enumType, "NATURAL");
    }

    /** Create the proxy mob with spawn-reason NATURAL; null if unresolved. Caller casts to Mob. */
    public static Object createNatural(EntityType<?> type, Level level) {
        resolve();
        if (create == null) return null;
        try { return create.invoke(type, level, natural); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("spawn facade: " + e.getMessage(), e); }
    }
}
