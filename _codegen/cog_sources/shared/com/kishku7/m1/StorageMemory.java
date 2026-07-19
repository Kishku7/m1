package com.kishku7.m1;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Per-world memory of storage locations (bank vaults now; chests later) and what was last seen
 * inside each. Persisted as JSON under {@code <gamedir>/m1-storage/<worldKey>.json} so it
 * survives sessions ("take inventory" refreshes it; reads may be stale by design -- they record
 * what was LAST SEEN).
 *
 * Keyed by "x,y,z,dimension". Each record: type ("bank_vault"/"chest"), items (id -> count),
 * seen (epoch ms).
 */
public final class StorageMemory {

    /** One remembered storage location. */
    public static final class Rec {
        public String type;
        public Map<String, Long> items = new LinkedHashMap<>();
        public long seen;
    }

    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Rec>>() { }.getType();

    private static Map<String, Rec> cache;
    private static String cacheKey;

    private StorageMemory() {
    }

    /** Stable id for the current world: server address, or the singleplayer level name. */
    static String worldKey(Minecraft mc) {
        try {
            if (mc.getCurrentServer() != null && mc.getCurrentServer().ip != null) {
                return sanitize("mp-" + mc.getCurrentServer().ip);
            }
        } catch (Throwable ignored) {
        }
        try {
            if (mc.getSingleplayerServer() != null) {
                return sanitize("sp-" + mc.getSingleplayerServer().getWorldData().getLevelName());
            }
        } catch (Throwable ignored) {
        }
        return "unknown-world";
    }

    private static String sanitize(String s) {
        return s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
    }

    private static File file(Minecraft mc) {
        File dir = new File(mc.gameDirectory, "m1-storage");
        if (!dir.exists() && !dir.mkdirs()) {
            // fall through; write will fail loudly and the caller reports it
        }
        return new File(dir, worldKey(mc) + ".json");
    }

    private static synchronized Map<String, Rec> load(Minecraft mc) {
        String key = worldKey(mc);
        if (cache != null && key.equals(cacheKey)) {
            return cache;
        }
        Map<String, Rec> m = null;
        File f = file(mc);
        if (f.exists()) {
            try (FileReader r = new FileReader(f)) {
                m = GSON.fromJson(r, MAP_TYPE);
            } catch (Exception ignored) {
            }
        }
        cache = (m != null) ? m : new LinkedHashMap<>();
        cacheKey = key;
        return cache;
    }

    private static synchronized void save(Minecraft mc) {
        if (cache == null) {
            return;
        }
        try (FileWriter w = new FileWriter(file(mc))) {
            GSON.toJson(cache, MAP_TYPE, w);
        } catch (Exception ignored) {
        }
    }

    static String posKey(BlockPos pos, String dim) {
        return pos.getX() + "," + pos.getY() + "," + pos.getZ() + "," + dim;
    }

    /** Record (or refresh) what a storage at pos currently holds. */
    public static synchronized void record(Minecraft mc, BlockPos pos, String dim, String type,
                                           Map<String, Long> items) {
        Map<String, Rec> m = load(mc);
        Rec r = new Rec();
        r.type = type;
        r.items = new LinkedHashMap<>(items);
        r.seen = System.currentTimeMillis();
        m.put(posKey(pos, dim), r);
        save(mc);
    }

    /** All remembered locations whose last-seen contents include an item id containing 'match'. */
    public static synchronized List<String> find(Minecraft mc, String match) {
        List<String> out = new ArrayList<>();
        String needle = match.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, Rec> e : load(mc).entrySet()) {
            for (Map.Entry<String, Long> it : e.getValue().items.entrySet()) {
                if (it.getKey().toLowerCase(Locale.ROOT).contains(needle)) {
                    out.add(e.getValue().type + " @ (" + e.getKey() + "): "
                            + it.getKey() + " x " + it.getValue());
                }
            }
        }
        return out;
    }

    /** Summary of everything remembered for this world. */
    public static synchronized String summary(Minecraft mc) {
        Map<String, Rec> m = load(mc);
        if (m.isEmpty()) {
            return "storage memory: empty for this world (" + worldKey(mc) + ")";
        }
        StringBuilder b = new StringBuilder("storage memory (" + worldKey(mc) + "):\n");
        for (Map.Entry<String, Rec> e : m.entrySet()) {
            long total = 0;
            for (long v : e.getValue().items.values()) {
                total += v;
            }
            b.append("  ").append(e.getValue().type).append(" @ (").append(e.getKey()).append("): ")
                    .append(e.getValue().items.size()).append(" kinds, ").append(total)
                    .append(" items, seen ").append((System.currentTimeMillis() - e.getValue().seen) / 60000)
                    .append("m ago\n");
        }
        return b.toString().trim();
    }
}
