package com.kishku7.m1;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
//[[[cog
//import sys; sys.path.insert(0, codegen); import compat
//if compat.spawn_reason_enum(compat.V(mcver)):
//    cog.outl("import net.minecraft.world.entity.EntitySpawnReason;")
//]]]
import net.minecraft.world.entity.EntitySpawnReason;
//[[[end]]]

/**
 * Cross-version proxy-mob spawn facade (Cog). pre-1.21.2 the only Level-create is the 1-arg
 * create(Level) (no spawn-reason); 1.21.2+ adds create(Level, EntitySpawnReason). The un-ticked path
 * proxy doesn't care about the reason, so pre-1.21.2 just calls create(level). Caller casts to Mob.
 * DIRECT per version. _codegen/compat.py.
 */
public final class SpawnCompat {
    private SpawnCompat() {}

    public static Object createNatural(EntityType<?> type, Level level) {
        //[[[cog
        //for ln in compat.spawn_natural(mcver): cog.outl(ln)
        //]]]
        return type.create(level, EntitySpawnReason.NATURAL);
        //[[[end]]]
    }
}
