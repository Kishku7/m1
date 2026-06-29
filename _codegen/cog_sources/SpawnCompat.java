package com.kishku7.m1;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
//[[[cog
//import sys; sys.path.insert(0, codegen); import compat
//if compat.spawn_reason_enum(compat.V(mcver)):
//    cog.outl("import net.minecraft.world.entity.EntitySpawnReason;")
//else:
//    cog.outl("import net.minecraft.world.entity.MobSpawnType;")
//]]]
//[[[end]]]

/**
 * Cross-version proxy-mob spawn facade (Cog). The spawn-reason enum was renamed MobSpawnType ->
 * EntitySpawnReason at 1.21.2; EntityType.create(Level, <reason>) takes whichever exists. Caller casts
 * the result to Mob. DIRECT per version. _codegen/compat.py.
 */
public final class SpawnCompat {
    private SpawnCompat() {}

    public static Object createNatural(EntityType<?> type, Level level) {
        //[[[cog
        //for ln in compat.spawn_natural(mcver): cog.outl(ln)
        //]]]
        //[[[end]]]
    }
}
