package com.kishku7.m1;

import com.mojang.datafixers.util.Pair;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;
import java.util.stream.Collectors;

//[[[cog
//import sys; sys.path.insert(0, codegen); import compat
//]]]
//[[[end]]]

/**
 * Read-only world/server lookups, run with server authority on the (non-op) M1 player's behalf.
 * Every method is a pure READ. Version drift (1.20.0 .. 26.x) is Cog-generated from _codegen/compat.py;
 * non-drifting methods are plain Java. This file is the cog_source master; each build cell generates its
 * own gen/Queries.java via scripts/cog-gen.ps1.
 */
//[[[cog
//if loader == "forge": cog.outl('@SuppressWarnings({"removal", "deprecation"})  // Forge mappings flag some vanilla APIs (ResourceLocation(String), BuiltInRegistries.BLOCK/ENTITY_TYPE on older lines) as deprecated/for-removal; these ARE the correct vanilla APIs. Fabric/NeoForge do not flag them. No-op on newer Forge lines.')
//]]]
//[[[end]]]
public final class Queries {
    private Queries() {}

    /** Server-side interaction reach used for the looked-at raycast (blocks). */
    private static final double LOOK_REACH = 5.0D;

    /** Cap on any NBT payload so a single M1S| chat line stays reasonable. */
    private static final int NBT_MAX = 1200;

    /** The level the querying player is in; overworld if the source is the console. (drift: ServerPlayer level accessor) */
    private static ServerLevel level(CommandSourceStack src) {
        //[[[cog
        //for ln in compat.q_level(mcver): cog.outl(ln)
        //]]]
        //[[[end]]]
    }

    public static String seed(CommandSourceStack src) {
        return Long.toString(level(src).getSeed());
    }

    public static String spawn(CommandSourceStack src) {
        //[[[cog
        //for ln in compat.q_spawn(mcver): cog.outl(ln)
        //]]]
        //[[[end]]]
    }

    public static String worldborder(CommandSourceStack src) {
        WorldBorder border = level(src).getWorldBorder();
        return "center=" + (long) border.getCenterX() + "," + (long) border.getCenterZ()
                + " size=" + (long) border.getSize();
    }

    public static String difficulty(CommandSourceStack src) {
        return src.getServer().getWorldData().getDifficulty().getSerializedName();
    }

    public static String time(CommandSourceStack src) {
        //[[[cog
        //for ln in compat.q_time(mcver): cog.outl(ln)
        //]]]
        //[[[end]]]
    }

    public static String weather(CommandSourceStack src) {
        ServerLevel lvl = level(src);
        return "raining=" + lvl.isRaining() + " thundering=" + lvl.isThundering();
    }

    public static String gamerules(CommandSourceStack src) {
        //[[[cog
        //for ln in compat.q_gamerules(mcver): cog.outl(ln)
        //]]]
        //[[[end]]]
    }

    public static String players(CommandSourceStack src) {
        MinecraftServer server = src.getServer();
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        String names = players.stream()
                .map(p -> p.getName().getString())
                .collect(Collectors.joining(","));
        return "count=" + players.size() + " names=" + (names.isEmpty() ? "-" : names);
    }

    /** Nearest structure matching a structure TAG id (e.g. "minecraft:village"), from the source position. */
    public static String locateStructure(CommandSourceStack src, String idStr) {
        //[[[cog
        //for ln in compat.q_locate_structure(mcver): cog.outl(ln)
        //]]]
        //[[[end]]]
    }

    /** Closest biome matching a biome id (e.g. "minecraft:desert"), from the source position. */
    public static String locateBiome(CommandSourceStack src, String idStr) {
        //[[[cog
        //for ln in compat.q_locate_biome(mcver): cog.outl(ln)
        //]]]
        //[[[end]]]
    }

    /**
     * NBT of the block or entity the M1 player is looking at (parity with reading a sign / opening a chest).
     * Looking at another PLAYER returns identity only -- reading other players' NBT stays outside the envelope.
     */
    public static String lookingAt(CommandSourceStack src) {
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            return "n/a:no_player";
        }
        ServerLevel lvl = level(src);
        HitResult hit = ProjectileUtil.getHitResultOnViewVector(player, e -> !e.isSpectator() && e.isPickable(), LOOK_REACH);
        if (hit.getType() == HitResult.Type.MISS) {
            return "none";
        }
        if (hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = ((BlockHitResult) hit).getBlockPos();
            BlockState state = lvl.getBlockState(pos);
            var blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
            StringBuilder sb = new StringBuilder();
            sb.append("block=").append(blockId)
                    .append(" pos=").append(pos.getX()).append(',').append(pos.getY()).append(',').append(pos.getZ());
            BlockEntity be = lvl.getBlockEntity(pos);
            if (be != null) {
                sb.append(" nbt=").append(clip(blockEntityNbt(be, lvl).toString()));
            }
            return sb.toString();
        }
        Entity entity = ((EntityHitResult) hit).getEntity();
        var typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        if (entity instanceof Player p) {
            return "entity=" + typeId + " player=" + p.getName().getString() + " nbt=excluded_player";
        }
        return "entity=" + typeId + " nbt=" + clip(entitySnapshot(entity, lvl).toString());
    }

    /** Whether a recipe id exists on the server, and (with a viewing player) whether it is unlocked. */
    public static String recipe(CommandSourceStack src, String idStr) {
        //[[[cog
        //for ln in compat.q_recipe(mcver): cog.outl(ln)
        //]]]
        //[[[end]]]
    }

    /** Whether an advancement id exists, and (with a viewing player) its completion state + percent. */
    public static String advancement(CommandSourceStack src, String idStr) {
        //[[[cog
        //for ln in compat.q_advancement(mcver): cog.outl(ln)
        //]]]
        //[[[end]]]
    }

    /** Server MOTD, tick performance (TPS + ms/tick), and player-slot occupancy. */
    public static String serverInfo(CommandSourceStack src) {
        //[[[cog
        //for ln in compat.q_serverinfo(mcver): cog.outl(ln)
        //]]]
        //[[[end]]]
    }

    /**
     * Relative compass DIRECTION (8-point) to each other online player -- direction only, NEVER distance,
     * matching what the vanilla 26.x player-locator bar already shows a human. Console (no viewer) -> n/a.
     */
    public static String playerDirection(CommandSourceStack src) {
        ServerPlayer self = src.getPlayer();
        if (self == null) {
            return "n/a:no_player";
        }
        double sx = self.getX();
        double sz = self.getZ();
        List<ServerPlayer> all = src.getServer().getPlayerList().getPlayers();
        StringJoiner sj = new StringJoiner(" ");
        int others = 0;
        for (ServerPlayer p : all) {
            if (p == self) {
                continue;
            }
            others++;
            sj.add(p.getName().getString() + "=" + compass8(p.getX() - sx, p.getZ() - sz));
        }
        String body = sj.toString();
        return "count=" + others + (body.isEmpty() ? "" : " " + body);
    }

    // ---- drift helpers (Cog) ------------------------------------------------------------------

    /** Block-entity NBT as a CompoundTag (drift: registries arg added at 1.20.5). */
    private static CompoundTag blockEntityNbt(BlockEntity be, ServerLevel lvl) {
        //[[[cog
        //for ln in compat.q_be_snapshot(mcver): cog.outl(ln)
        //]]]
        //[[[end]]]
    }

    /** Full entity NBT as a CompoundTag (drift: ValueOutput path from 1.21.6, CompoundTag below). */
    private static CompoundTag entitySnapshot(Entity entity, ServerLevel lvl) {
        //[[[cog
        //for ln in compat.q_entity_snapshot(mcver): cog.outl(ln)
        //]]]
        //[[[end]]]
    }

    // ---- non-drifting helpers -----------------------------------------------------------------

    /** 8-point compass from a horizontal delta; North = -Z, East = +X. No magnitude is exposed. */
    private static String compass8(double dx, double dz) {
        double deg = Math.toDegrees(Math.atan2(dx, -dz));
        if (deg < 0.0D) {
            deg += 360.0D;
        }
        String[] dirs = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return dirs[(int) Math.round(deg / 45.0D) % 8];
    }

    /** Cap a payload string so one chat line stays sane, noting how many chars were dropped. */
    private static String clip(String s) {
        return s.length() <= NBT_MAX ? s : s.substring(0, NBT_MAX) + "...(+" + (s.length() - NBT_MAX) + ")";
    }
}
