package com.kishku7.m1;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Builds the {@code /m1srv q <query>} command tree. Loader-agnostic (pure Brigadier + mojmap MC APIs);
 * each loader registers {@link #build()} through its own hook (Fabric CommandRegistrationCallback,
 * NeoForge RegisterCommandsEvent).
 *
 * Permission level 0 -> a non-op M1 player may run it, but every handler does a DIRECT server-authority
 * read, so no op grant is needed. The answer is pushed back as a system-chat line the M1 client captures:
 *   {@code M1S|<seq>|<query>|<payload>}
 *
 * Each query is gated by a per-feature switch (see {@link M1SrvConfig}). A query whose feature is turned
 * off returns {@link M1SrvConfig#DISABLED_MARKER} as its payload instead of running the handler.
 */
public final class M1SrvCommand {
    private M1SrvCommand() {}

    private static final AtomicLong SEQ = new AtomicLong();

    /** System-chat reply prefix the M1 client (M1SrvNet) captures. Wire: M1S|<seq>|<query>|<payload>. */
    private static final String REPLY_PREFIX = "M1S";

    public static LiteralArgumentBuilder<CommandSourceStack> build() {
        LiteralArgumentBuilder<CommandSourceStack> q = Commands.literal("q");
        q.then(leaf("seed", "seed", Queries::seed));
        q.then(leaf("spawn", "spawn", Queries::spawn));
        q.then(leaf("worldborder", "worldborder", Queries::worldborder));
        q.then(leaf("difficulty", "difficulty", Queries::difficulty));
        q.then(leaf("time", "time", Queries::time));
        q.then(leaf("weather", "weather", Queries::weather));
        q.then(leaf("gamerules", "gamerules", Queries::gamerules));
        q.then(leaf("players", "players", Queries::players));
        q.then(leaf("lookingat", "lookingAt", Queries::lookingAt));
        q.then(leaf("serverinfo", "serverInfo", Queries::serverInfo));
        q.then(leaf("playerdir", "playerDirection", Queries::playerDirection));
        q.then(argLeaf("recipe", "recipe", Queries::recipe));
        q.then(argLeaf("advancement", "advancement", Queries::advancement));
        q.then(Commands.literal("locate")
                .then(Commands.literal("structure")
                        .then(Commands.argument("id", StringArgumentType.greedyString())
                                .executes(ctx -> run(ctx, "locate_structure", "locate",
                                        s -> Queries.locateStructure(s, StringArgumentType.getString(ctx, "id"))))))
                .then(Commands.literal("biome")
                        .then(Commands.argument("id", StringArgumentType.greedyString())
                                .executes(ctx -> run(ctx, "locate_biome", "locate",
                                        s -> Queries.locateBiome(s, StringArgumentType.getString(ctx, "id")))))));
        return Commands.literal("m1srv").requires(src -> true).then(q);
    }

    /** A no-argument query leaf gated by {@code featureKey}. */
    private static LiteralArgumentBuilder<CommandSourceStack> leaf(
            String name, String featureKey, Function<CommandSourceStack, String> handler) {
        return Commands.literal(name).executes(ctx -> run(ctx, name, featureKey, handler));
    }

    /** A query leaf that takes a single greedy-string id argument (e.g. {@code recipe <id>}). */
    private static LiteralArgumentBuilder<CommandSourceStack> argLeaf(
            String name, String featureKey, java.util.function.BiFunction<CommandSourceStack, String, String> handler) {
        return Commands.literal(name).then(Commands.argument("id", StringArgumentType.greedyString())
                .executes(ctx -> run(ctx, name, featureKey,
                        s -> handler.apply(s, StringArgumentType.getString(ctx, "id")))));
    }

    private static int run(CommandContext<CommandSourceStack> ctx, String query, String featureKey,
                           Function<CommandSourceStack, String> handler) {
        String payload;
        if (!M1SrvConfig.get().featureEnabled(featureKey)) {
            payload = M1SrvConfig.DISABLED_MARKER;
        } else {
            try {
                payload = handler.apply(ctx.getSource());
            } catch (Exception e) {
                payload = "error:" + e.getClass().getSimpleName();
            }
        }
        String line = REPLY_PREFIX + "|" + SEQ.incrementAndGet() + "|" + query + "|" + payload;
        Component msg = Component.literal(line);
        ServerPlayer player = ctx.getSource().getPlayer();
        if (player != null) {
            player.sendSystemMessage(msg);
        } else {
            ctx.getSource().sendSuccess(() -> msg, false);
        }
        return 1;
    }
}
