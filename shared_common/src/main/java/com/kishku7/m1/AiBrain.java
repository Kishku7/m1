package com.kishku7.m1;

import java.io.InputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Ships the AI_Brain docs inside the jar and extracts them to the user's config on init.
 *
 * MC-agnostic: pure file IO + checksums, with a small reflection shim for the loader config dir
 * and a manifest-parse for the mod version (both stable across MC 1.20-26.3 / all loaders).
 * Full contract: AI_Brain distribution design in projects/m1.md.
 *
 * Layout: config/M1_AI_Brain/&lt;MAJOR.MINOR&gt;/ (create-once per minor) + a root .checksum manifest.
 * 00-99 are mod-owned defaults; 100_User_Overrides.md is the user layer; user-added files are kept.
 */
public final class AiBrain {

    private static final String RES       = "/m1_ai_brain/";
    private static final String OVERRIDES = "100_User_Overrides.md";
    private static final String INDEX     = "00_Index.md";

    /** Set by install() so M1Server can advertise the brief + any warning in its greeting. */
    public static volatile String indexPath = "";
    public static volatile String warning   = "";

    private AiBrain() {}

    public static synchronized void install() {
        try {
            Path cfg = configDir();
            if (cfg == null) { M1Server.log("AI_Brain: no config dir; skipped"); return; }
            String minor = minorVersion();
            Path root    = cfg.resolve("M1_AI_Brain");
            Path verDir  = root.resolve(minor);
            List<String> files = manifest();
            if (files.isEmpty()) { M1Server.log("AI_Brain: empty manifest; skipped"); return; }

            Map<String,String> sums = readChecksums(root);   // "<minor>/<file>" -> sha256

            if (!Files.isDirectory(verDir)) {
                // create-once: first launch on this minor line
                Files.createDirectories(verDir);
                for (String f : files) {
                    byte[] data = resource(RES + f);
                    if (data == null) continue;
                    Files.write(verDir.resolve(f), data);
                    sums.put(minor + "/" + f, sha(data));
                }
                carryForward(root, minor, verDir, files, sums);   // overrides + user-added from prior
                prunePristine(root, minor, files, sums);          // delete fully-default old dirs
                writeChecksums(root, sums);
                M1Server.log("AI_Brain: installed " + minor + " (" + files.size() + " files) at " + verDir);
            }

            indexPath = verDir.resolve(INDEX).toString();
            warning   = detectModified(verDir, minor, files, sums);
            if (!warning.isEmpty()) M1Server.log("AI_Brain: " + warning);
        } catch (Throwable t) {
            M1Server.log("AI_Brain install failed: " + t);
        }
    }

    /** START handshake: a dual-audience brief -- the AI's index-file path + the human's HELP/RAW tip. */
    public static String startBrief() {
        StringBuilder b = new StringBuilder("M1 operating brief:\n");
        if (!indexPath.isEmpty())
            b.append("  AI agents: read this file first -> ").append(indexPath).append("\n");
        else
            b.append("  AI agents: operating brief not available (extraction skipped)\n");
        b.append("  Humans: type HELP for the command list, and RAW OFF for cleaner output.");
        if (!warning.isEmpty()) b.append("\n").append(warning);
        return b.toString();
    }

    // --- carry-forward + cleanup -------------------------------------------------- //

    private static void carryForward(Path root, String minor, Path verDir,
                                     List<String> files, Map<String,String> sums) throws IOException {
        String prev = latestPriorDir(root, minor);
        if (prev == null) return;
        Path prevDir = root.resolve(prev);

        // 1) user overrides: carry forward if the prior copy is non-default (differs from shipped blank)
        Path prevOv = prevDir.resolve(OVERRIDES);
        if (Files.isRegularFile(prevOv)) {
            String shippedBlank = sums.get(minor + "/" + OVERRIDES);
            String prevHash = sha(Files.readAllBytes(prevOv));
            if (shippedBlank != null && !prevHash.equals(shippedBlank)) {
                Files.copy(prevOv, verDir.resolve(OVERRIDES), StandardCopyOption.REPLACE_EXISTING);
                sums.put(minor + "/" + OVERRIDES, prevHash);
                M1Server.log("AI_Brain: carried user overrides forward from " + prev);
            }
        }
        // 2) user-added files (present in prior dir, not part of our library): move forward
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(prevDir)) {
            for (Path p : ds) {
                String name = p.getFileName().toString();
                if (files.contains(name) || !Files.isRegularFile(p)) continue;
                Path dst = verDir.resolve(name);
                if (!Files.exists(dst)) {
                    Files.move(p, dst);
                    M1Server.log("AI_Brain: carried user file '" + name + "' forward from " + prev);
                }
            }
        }
    }

    private static void prunePristine(Path root, String keepMinor,
                                      List<String> files, Map<String,String> sums) throws IOException {
        if (!Files.isDirectory(root)) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path dir : ds) {
                if (!Files.isDirectory(dir)) continue;
                String m = dir.getFileName().toString();
                if (m.equals(keepMinor) || !isVersion(m)) continue;
                if (dirIsPristine(dir, m, files, sums)) {
                    deleteDir(dir);
                    final String pfx = m + "/";
                    sums.keySet().removeIf(k -> k.startsWith(pfx));
                    M1Server.log("AI_Brain: pruned all-default version dir " + m);
                }
            }
        }
    }

    private static boolean dirIsPristine(Path dir, String m, List<String> files,
                                         Map<String,String> sums) throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (!Files.isRegularFile(p)) continue;
                String name = p.getFileName().toString();
                if (!files.contains(name)) return false;                       // a user-added file
                String want = sums.get(m + "/" + name);
                if (want == null || !want.equals(sha(Files.readAllBytes(p)))) return false;
            }
        }
        return true;
    }

    private static String detectModified(Path verDir, String minor, List<String> files,
                                         Map<String,String> sums) throws IOException {
        List<String> mod = new ArrayList<>();
        for (String f : files) {
            if (f.equals(OVERRIDES)) continue;            // user layer -- edits there are expected
            Path p = verDir.resolve(f);
            if (!Files.isRegularFile(p)) continue;
            String want = sums.get(minor + "/" + f);
            if (want != null && !want.equals(sha(Files.readAllBytes(p)))) mod.add(f);
        }
        if (mod.isEmpty()) return "";
        return "**WARNING: you edited default brain file(s) " + String.join(", ", mod)
             + " -- default edits are IGNORED. Move your instructions into " + OVERRIDES
             + " and delete your changes to those files manually.**";
    }

    // --- helpers ------------------------------------------------------------------ //

    private static List<String> manifest() {
        List<String> out = new ArrayList<>();
        byte[] data = resource(RES + "_manifest.txt");
        if (data == null) return out;
        for (String line : new String(data, StandardCharsets.UTF_8).split("\n")) {
            String s = line.trim();
            if (!s.isEmpty() && !s.startsWith("#")) out.add(s);
        }
        return out;
    }

    private static byte[] resource(String path) {
        try (InputStream in = AiBrain.class.getResourceAsStream(path)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) { return null; }
    }

    private static String minorVersion() {
        Matcher m = Pattern.compile("(\\d+)\\.(\\d+)").matcher(rawVersion());
        return m.find() ? (m.group(1) + "." + m.group(2)) : "0.0";
    }

    /** Mod version from whichever loader manifest is bundled (stable across loader versions). */
    private static String rawVersion() {
        for (String mf : new String[]{ "/fabric.mod.json", "/META-INF/neoforge.mods.toml", "/META-INF/mods.toml" }) {
            byte[] d = resource(mf);
            if (d == null) continue;
            Matcher m = Pattern.compile("version\\s*[:=]\\s*\"([0-9]+\\.[0-9]+[^\"]*)\"")
                               .matcher(new String(d, StandardCharsets.UTF_8));
            if (m.find()) return m.group(1);
        }
        return "0.0";
    }

    private static Path configDir() {
        // Fabric
        Path p = tryInstancePath("net.fabricmc.loader.api.FabricLoader", "getInstance", "getConfigDir");
        if (p != null) return p;
        // NeoForge / Forge: FMLPaths.CONFIGDIR.get()
        for (String cls : new String[]{ "net.neoforged.fml.loading.FMLPaths",
                                        "net.minecraftforge.fml.loading.FMLPaths" }) {
            try {
                Class<?> c = Class.forName(cls);
                Object configdir = c.getField("CONFIGDIR").get(null);
                Object path = c.getMethod("get").invoke(configdir);
                if (path instanceof Path) return (Path) path;
            } catch (Throwable ignored) { /* try next */ }
        }
        Path fb = Paths.get("config");                    // fallback: working-dir/config
        return Files.isDirectory(fb) ? fb : null;
    }

    private static Path tryInstancePath(String cls, String instanceMethod, String pathMethod) {
        try {
            Class<?> c = Class.forName(cls);
            Object inst = c.getMethod(instanceMethod).invoke(null);
            Object path = inst.getClass().getMethod(pathMethod).invoke(inst);
            return (path instanceof Path) ? (Path) path : null;
        } catch (Throwable t) { return null; }
    }

    private static String sha(byte[] data) {
        try {
            String norm = new String(data, StandardCharsets.UTF_8).replace("\r\n", "\n").replace("\r", "\n");
            byte[] h = MessageDigest.getInstance("SHA-256").digest(norm.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte x : h) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
            return sb.toString();
        } catch (Exception e) { return ""; }
    }

    private static Map<String,String> readChecksums(Path root) {
        Map<String,String> m = new TreeMap<>();
        Path f = root.resolve(".checksum");
        try {
            if (Files.isRegularFile(f)) {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    int tab = line.indexOf('\t');
                    if (tab > 0) m.put(line.substring(0, tab), line.substring(tab + 1));
                }
            }
        } catch (IOException ignored) { /* fail-safe: empty -> nothing treated as pristine */ }
        return m;
    }

    private static void writeChecksums(Path root, Map<String,String> sums) {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String,String> e : sums.entrySet()) lines.add(e.getKey() + "\t" + e.getValue());
        try { Files.createDirectories(root); Files.write(root.resolve(".checksum"), lines, StandardCharsets.UTF_8); }
        catch (IOException e) { M1Server.log("AI_Brain: cannot write .checksum: " + e); }
    }

    private static String latestPriorDir(Path root, String minor) throws IOException {
        String best = null;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path p : ds) {
                if (!Files.isDirectory(p)) continue;
                String m = p.getFileName().toString();
                if (m.equals(minor) || !isVersion(m)) continue;
                if (best == null || cmpVer(m, best) > 0) best = m;
            }
        }
        return best;
    }

    private static boolean isVersion(String s) { return s.matches("\\d+\\.\\d+"); }

    private static int cmpVer(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int xi = i < x.length ? Integer.parseInt(x[i]) : 0;
            int yi = i < y.length ? Integer.parseInt(y[i]) : 0;
            if (xi != yi) return Integer.compare(xi, yi);
        }
        return 0;
    }

    private static void deleteDir(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (Stream<Path> w = Files.walk(dir)) {
            w.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException ignored) { /* best effort */ }
            });
        }
    }
}
