package com.kishku7.m1;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Per-server config. Installing the mod IS the opt-in consent; this lets an owner turn INDIVIDUAL
 * capabilities off. Read from {@code config/m1server.json} relative to the server run directory
 * (loader-agnostic; works for dedicated and integrated/singleplayer servers). The file is WRITTEN ON
 * FIRST START with every feature set to true, so an admin has a complete, self-documenting file to edit.
 *
 * A disabled feature has two effects, both handled at the command boundary ({@link M1SrvCommand}):
 *   1. A direct {@code /m1srv q ...} for that feature returns the literal {@link #DISABLED_MARKER}
 *      so the M1 client recognises the data as unavailable rather than treating it as a real value.
 *   2. Because the M1 client's connect-time auto-grab goes through the SAME command path, a disabled
 *      feature is never auto-pushed to M1 clients on reload/restart -- it just yields the marker.
 */
public final class M1SrvConfig {

    /** Payload returned for any query whose feature is turned off. Machine-recognisable + human-readable. */
    public static final String DISABLED_MARKER = "<Feature is Disabled by Admin>";

    /** Master switch. false = every feature is treated as disabled regardless of the per-feature flags. */
    public boolean enabled = true;

    /** Per-feature on/off switches. Every field defaults to true (full envelope). */
    public Features features = new Features();

    /**
     * One boolean per capability. Field names ARE the feature keys used by {@link M1SrvCommand}.
     * The two {@code locate} sub-queries (structure/biome) share the single {@code locate} switch.
     */
    public static final class Features {
        public boolean seed = true;
        public boolean spawn = true;
        public boolean worldborder = true;
        public boolean difficulty = true;
        public boolean time = true;
        public boolean weather = true;
        public boolean gamerules = true;
        public boolean players = true;
        public boolean locate = true;
        public boolean lookingAt = true;
        public boolean recipe = true;
        public boolean advancement = true;
        public boolean serverInfo = true;
        public boolean playerDirection = true;
    }

    private static M1SrvConfig instance;

    public static M1SrvConfig get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    /** True if the given feature key is permitted: master switch on AND the per-feature flag on. */
    public boolean featureEnabled(String key) {
        if (!enabled) {
            return false;
        }
        switch (key) {
            case "seed":            return features.seed;
            case "spawn":           return features.spawn;
            case "worldborder":     return features.worldborder;
            case "difficulty":      return features.difficulty;
            case "time":            return features.time;
            case "weather":         return features.weather;
            case "gamerules":       return features.gamerules;
            case "players":         return features.players;
            case "locate":          return features.locate;
            case "lookingAt":       return features.lookingAt;
            case "recipe":          return features.recipe;
            case "advancement":     return features.advancement;
            case "serverInfo":      return features.serverInfo;
            case "playerDirection": return features.playerDirection;
            default:                return true; // unknown/future keys are allowed (forward-compatible)
        }
    }

    private static M1SrvConfig load() {
        Path path = Paths.get("config", "m1server.json");
        try {
            if (Files.exists(path)) {
                M1SrvConfig loaded = new Gson().fromJson(Files.readString(path), M1SrvConfig.class);
                if (loaded == null) {
                    return new M1SrvConfig();
                }
                if (loaded.features == null) {
                    loaded.features = new Features(); // tolerate a hand-edited file that dropped the block
                }
                return loaded;
            }
            M1SrvConfig defaults = new M1SrvConfig();
            Files.createDirectories(path.getParent());
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(defaults));
            return defaults;
        } catch (Exception e) {
            // Never let a bad config break the server; fall back to the default (full, enabled) envelope.
            return new M1SrvConfig();
        }
    }
}
