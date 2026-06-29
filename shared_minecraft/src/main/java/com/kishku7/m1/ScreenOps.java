package com.kishku7.m1;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.pathfinder.Path;

import java.util.ArrayList;
import java.util.List;

/**
 * The observe + actuate engine. All methods run on the client main thread.
 * Screen ops (describe/click/type/slots) + world perception (look/scan/inv/where) + cmd.
 */
public final class ScreenOps {

    private static final String HELP =
        "screen:\n" +
        "  describe | screen    describe current screen + widgets\n" +
        "  click <id>           click widget <id>\n" +
        "  type <id> <text>     set an EditBox\n" +
        "  slots                open container slots\n" +
        "world:\n" +
        "  where | state        player pos/facing/health\n" +
        "  look                 what the crosshair is pointed at\n" +
        "  scan [r|<name>]      what you SEE: bounds + notable blocks + mobs + items (LOS, r<=32)\n" +
        "  inv                  inventory contents + held item\n" +
        "  cmd <command>        run a server command (needs cheats), no slash\n" +
        "  pause                open pause menu (then click Save and Quit to Title)\n" +
        "  face <dir|yaw [pitch]|x y z>  set facing\n" +
        "  moveto <x> <z>       pathfind + walk to x,z (routes around walls)\n" +
        "  goto <x> <y> <z>     pathfind + walk to coords\n" +
        "  move <dir> <n>       pathfind + walk n blocks that way\n" +
        "  stop                 stop moving\n" +
        "  mine [x y z]         mine the block you are looking at (or at x y z)\n" +
        "  worlds               list saved worlds (on the world-select screen)\n" +
        "  joinworld <idx>      load saved world #idx (re-enter)\n" +
        "  openinv / close      open inventory (2x2 grid) / close screen\n" +
        "  craft planks|sticks|table|axe   craft items (axe needs the crafting table open=3x3)\n" +
        "  hold <0-8> / equip <item>       select hotbar slot / move item to hand\n" +
        "  place                use/place held item on the block you are looking at\n" +
        "  slot <id> [btn] [pickup|quick|swap]   raw slot click\n" +
        "  openpack             open your worn Travelers Backpack (triggers its keybind)\n" +
        "  screenshot [name]    save a PNG of the current frame to screenshots/ (vanilla writer)\n" +
        "  upgrades             show pending auto-armor-upgrade messages\n" +
        "  autoupgrade on|off   toggle auto armor upgrading (default on)\n" +
        "  help                 this list";

    private ScreenOps() {}

    public static String dispatch(Minecraft mc, String line) {
        String[] parts = line.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String rest = parts.length > 1 ? parts[1].trim() : "";
        switch (cmd) {
            case "help":      return HELP;
            case "describe":
            case "screen":    return describe(mc);
            case "click":     return click(mc, rest);
            case "type":      return type(mc, rest);
            case "slots":     return slots(mc);
            case "where":
            case "state":     return where(mc);
            case "look":      return look(mc);
            case "scan":      return scan(mc, rest);
            case "inv":
            case "inventory": return inv(mc);
            case "cmd":       return runCmd(mc, rest);
            case "pause":     return pause(mc);
            case "face":      return face(mc, rest);
            case "move":      return move(mc, rest);
            case "moveto":    return moveto(mc, rest);
            case "goto":      return gotoCmd(mc, rest);
            case "stop":      return stopMove();
            case "mine":      return mine(mc, rest);
            case "worlds":    return worlds(mc);
            case "joinworld":
            case "join":      return joinWorldCmd(mc, rest);
            case "openinv":   return Crafting.openInv(mc);
            case "close":     return Crafting.close(mc);
            case "hold":      return Crafting.hold(mc, rest);
            case "equip":     return Crafting.equip(mc, rest);
            case "slot":      return Crafting.slotCmd(mc, rest);
            case "place":     return Crafting.place(mc);
            case "craft":     return Crafting.craft(mc, rest);
            case "openpack":  return openpack(mc);
            case "screenshot":
            case "shot":      return screenshot(mc, rest);
            case "autoupgrade": return autoupgrade(rest);
            case "upgrades":  return upgrades();
            default:          return "ERR unknown command: " + cmd + " (try help)";
        }
    }

    // ---------- screen ops ----------

    private static List<AbstractWidget> widgets(Screen s) {
        List<AbstractWidget> out = new ArrayList<>();
        for (GuiEventListener c : s.children()) collect(c, out);
        return out;
    }

    // Recursively gather every interactive AbstractWidget through the ContainerEventHandler tree
    // (tab bars, layouts, nested config panels). Stops at selection lists (their entries are
    // addressed separately via worlds/joinworld and list handling).
    private static void collect(GuiEventListener node, List<AbstractWidget> out) {
        if (node instanceof AbstractSelectionList) return;
        if (node instanceof AbstractWidget w) {
            if (out.size() < 400 && !out.contains(w)) out.add(w);
        }
        if (node instanceof ContainerEventHandler ceh) {
            for (GuiEventListener child : ceh.children()) collect(child, out);
        }
    }

    private static String describe(Minecraft mc) {
        Screen s = M1Compat.screen(mc);
        if (s == null) {
            String w = (mc.level == null) ? "no world" : "in world";
            return "no screen open (" + w + "). use 'where' for player state.";
        }
        StringBuilder b = new StringBuilder();
        String title = s.getTitle() != null ? s.getTitle().getString() : "";
        b.append("screen: ").append(s.getClass().getSimpleName()).append("  title: \"").append(title).append("\"\n");
        List<AbstractWidget> ws = widgets(s);
        b.append("widgets: ").append(ws.size()).append("\n");
        for (int i = 0; i < ws.size(); i++) {
            AbstractWidget w = ws.get(i);
            String msg = w.getMessage() != null ? w.getMessage().getString() : "";
            b.append("  [").append(i).append("] ").append(widgetType(w))
             .append(" \"").append(msg).append("\"")
             .append(" pos=(").append(w.getX()).append(",").append(w.getY()).append(")")
             .append(" size=").append(w.getWidth()).append("x").append(w.getHeight())
             .append(" ").append(w.visible ? "V" : "-").append(w.active ? "A" : "-").append(w.isFocused() ? "F" : "-");
            if (w instanceof EditBox eb) b.append(" value=\"").append(eb.getValue()).append("\"");
            b.append("\n");
        }
        return trim(b);
    }

    private static String click(Minecraft mc, String rest) {
        Screen s = M1Compat.screen(mc);
        if (s == null) return "ERR no screen open";
        Integer id = parseInt(firstTok(rest));
        if (id == null) return "ERR usage: click <id>";
        List<AbstractWidget> ws = widgets(s);
        if (id < 0 || id >= ws.size()) return "ERR no widget " + id + " (have 0.." + (ws.size() - 1) + ")";
        AbstractWidget w = ws.get(id);
        double cx = w.getX() + w.getWidth() / 2.0, cy = w.getY() + w.getHeight() / 2.0;
        boolean handled = ScreenClickCompat.clickAt(s, cx, cy);
        String msg = w.getMessage() != null ? w.getMessage().getString() : "";
        return "OK click " + id + " \"" + msg + "\" handled=" + handled;
    }

    private static String type(Minecraft mc, String rest) {
        Screen s = M1Compat.screen(mc);
        if (s == null) return "ERR no screen open";
        String[] p = rest.split("\\s+", 2);
        Integer id = parseInt(p[0]);
        if (id == null) return "ERR usage: type <id> <text>";
        String text = p.length > 1 ? p[1] : "";
        List<AbstractWidget> ws = widgets(s);
        if (id < 0 || id >= ws.size()) return "ERR no widget " + id;
        if (ws.get(id) instanceof EditBox eb) { eb.setValue(text); return "OK type " + id + " -> \"" + text + "\""; }
        return "ERR widget " + id + " is " + ws.get(id).getClass().getSimpleName() + ", not an EditBox";
    }

    private static String slots(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || p.containerMenu == null) return "ERR no open container";
        AbstractContainerMenu m = p.containerMenu;
        StringBuilder b = new StringBuilder();
        b.append("menu: ").append(m.getClass().getSimpleName()).append("  slots: ").append(m.slots.size()).append("\n");
        for (Slot slot : m.slots) {
            b.append("  [").append(slot.index).append("] ").append(itemStr(slot.getItem())).append("\n");
        }
        return trim(b);
    }

    // ---------- world perception ----------

    private static String where(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            String sc = M1Compat.screen(mc) != null ? M1Compat.screen(mc).getClass().getSimpleName() : "none";
            return "no world loaded. screen=" + sc;
        }
        String base = String.format("pos=(%.2f, %.2f, %.2f) facing=%s yaw=%.1f pitch=%.1f health=%.1f food=%d dim=%s",
            p.getX(), p.getY(), p.getZ(), compassFromYaw(p.getYRot()), p.getYRot(), p.getXRot(),
            p.getHealth(), p.getFoodData().getFoodLevel(), mc.level.dimension());
        if (MoveControl.isActive()) {
            double d = Math.hypot(MoveControl.targetX() - p.getX(), MoveControl.targetZ() - p.getZ());
            base += String.format("  | moving -> (%.1f,%.1f) wp %d/%d dist %.1f",
                MoveControl.targetX(), MoveControl.targetZ(), MoveControl.waypointIdx(), MoveControl.waypointCount(), d);
        } else if (!MoveControl.status().equals("idle")) {
            base += "  | last move: " + MoveControl.status();
        }
        return base;
    }

    private static String look(Minecraft mc) {
        if (mc.player == null || mc.level == null) return "look: not in world";
        HitResult hr = mc.hitResult;
        if (hr == null || hr.getType() == HitResult.Type.MISS) return "look: nothing in reach";
        Vec3 eye = mc.player.getEyePosition();
        double dist = Math.sqrt(hr.getLocation().distanceToSqr(eye));
        if (hr instanceof BlockHitResult bhr) {
            BlockPos bp = bhr.getBlockPos();
            BlockState st = mc.level.getBlockState(bp);
            return String.format("look: block %s at (%d,%d,%d) face=%s dist=%.1f",
                blockId(st), bp.getX(), bp.getY(), bp.getZ(), bhr.getDirection(), dist);
        }
        if (hr instanceof EntityHitResult ehr) {
            return String.format("look: entity %s dist=%.1f", ehr.getEntity().getName().getString(), dist);
        }
        return "look: " + hr.getType();
    }

    // ---------- world perception (line-of-sight) ----------

    private static final int SCAN_DEFAULT_R = 32;
    private static final int SCAN_MAX_R = 32;

    private static final java.util.Set<String> FILLER = new java.util.HashSet<>(java.util.Arrays.asList(
        "stone", "cobblestone", "deepslate", "cobbled_deepslate", "dirt", "coarse_dirt", "grass_block",
        "podzol", "gravel", "sand", "red_sand", "sandstone", "red_sandstone", "andesite", "diorite",
        "granite", "tuff", "calcite", "netherrack", "bedrock", "end_stone", "basalt", "smooth_basalt",
        "blackstone", "clay", "mud", "snow", "snow_block", "ice", "packed_ice", "dripstone_block",
        "moss_block", "rooted_dirt", "short_grass", "tall_grass", "fern", "large_fern", "dead_bush"));

    private static String scan(Minecraft mc, String rest) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "scan: not in world";
        String arg = firstTok(rest);
        Integer ri = parseInt(arg);
        if (!arg.isEmpty() && ri == null) return scanFind(mc, p, arg.toLowerCase(), SCAN_DEFAULT_R);
        int r = SCAN_DEFAULT_R;
        if (ri != null) r = Math.max(2, Math.min(SCAN_MAX_R, ri));
        return scanFull(mc, p, r);
    }

    private static BlockHitResult ray(Minecraft mc, Vec3 eye, double ux, double uy, double uz, int r) {
        Vec3 to = eye.add(ux * r, uy * r, uz * r);
        return mc.level.clip(new ClipContext(eye, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, mc.player));
    }

    private static String axisHit(Minecraft mc, Vec3 eye, int ux, int uy, int uz, int r) {
        BlockHitResult h = ray(mc, eye, ux, uy, uz, r);
        if (h == null || h.getType() == HitResult.Type.MISS) return "open >" + r;
        BlockState st = mc.level.getBlockState(h.getBlockPos());
        int d = (int) Math.round(eye.distanceTo(h.getLocation()));
        return blockShort(st) + " " + d;
    }

    private static void accumulate(Minecraft mc, Vec3 eye, BlockHitResult h,
            java.util.Map<String, int[]> count, java.util.Map<String, BlockPos> nearest,
            java.util.Map<String, Double> nearestSq) {
        if (h == null || h.getType() == HitResult.Type.MISS) return;
        BlockPos bp = h.getBlockPos();
        BlockState st = mc.level.getBlockState(bp);
        String id = blockShort(st);
        if (FILLER.contains(id)) return;
        if (id.equals("air") || id.equals("cave_air") || id.equals("void_air")) return;
        double sq = eye.distanceToSqr(Vec3.atCenterOf(bp));
        int[] c = count.get(id);
        if (c == null) { count.put(id, new int[]{1}); nearest.put(id, bp); nearestSq.put(id, sq); }
        else { c[0]++; if (sq < nearestSq.get(id)) { nearest.put(id, bp); nearestSq.put(id, sq); } }
    }

    private static String scanFull(Minecraft mc, LocalPlayer p, int r) {
        Vec3 eye = p.getEyePosition();
        StringBuilder b = new StringBuilder();
        b.append(String.format("SCAN pos=(%.0f,%.0f,%.0f) facing=%s r=%d (line-of-sight)\n",
            p.getX(), p.getY(), p.getZ(), compassFromYaw(p.getYRot()).toUpperCase(), r));
        b.append("BOUNDS ");
        b.append(" N:").append(axisHit(mc, eye, 0, 0, -1, r));
        b.append("  S:").append(axisHit(mc, eye, 0, 0, 1, r));
        b.append("  E:").append(axisHit(mc, eye, 1, 0, 0, r));
        b.append("  W:").append(axisHit(mc, eye, -1, 0, 0, r));
        b.append("  UP:").append(axisHit(mc, eye, 0, 1, 0, r));
        b.append("  DOWN:").append(axisHit(mc, eye, 0, -1, 0, r));
        b.append("\n");
        java.util.Map<String, int[]> count = new java.util.LinkedHashMap<>();
        java.util.Map<String, BlockPos> nearest = new java.util.LinkedHashMap<>();
        java.util.Map<String, Double> nearestSq = new java.util.LinkedHashMap<>();
        double[] pitches = {-60, -40, -25, -12, 0, 12, 25, 40, 60};
        for (int yawDeg = 0; yawDeg < 360; yawDeg += 18) {
            double yr = Math.toRadians(yawDeg);
            for (double pd : pitches) {
                double pr = Math.toRadians(pd);
                accumulate(mc, eye, ray(mc, eye, Math.cos(pr) * Math.cos(yr), Math.sin(pr), Math.cos(pr) * Math.sin(yr), r),
                    count, nearest, nearestSq);
            }
        }
        accumulate(mc, eye, ray(mc, eye, 0, 1, 0, r), count, nearest, nearestSq);
        accumulate(mc, eye, ray(mc, eye, 0, -1, 0, r), count, nearest, nearestSq);
        b.append("VISIBLE ");
        if (nearest.isEmpty()) {
            b.append("(none)");
        } else {
            java.util.List<String> ids = new java.util.ArrayList<>(nearest.keySet());
            ids.sort(java.util.Comparator.comparingDouble(nearestSq::get));
            for (int i = 0; i < ids.size(); i++) {
                String id = ids.get(i);
                b.append(blockLine(p, eye, id, nearest.get(id), count.get(id)[0]));
                if (i < ids.size() - 1) b.append(" | ");
            }
        }
        b.append("\n");
        appendEntities(mc, p, eye, r, b);
        return trim(b);
    }

    private static String blockLine(LocalPlayer p, Vec3 eye, String id, BlockPos bp, int count) {
        double dx = bp.getX() + 0.5 - p.getX();
        double dy = bp.getY() + 0.5 - eye.y;
        double dz = bp.getZ() + 0.5 - p.getZ();
        int dist = (int) Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
        String more = count > 1 ? " +more" : "";
        return String.format("%s (%d,%d,%d) %s %d%s", id, bp.getX(), bp.getY(), bp.getZ(), bearing(dx, dy, dz), dist, more);
    }

    private static void appendEntities(Minecraft mc, LocalPlayer p, Vec3 eye, int r, StringBuilder b) {
        java.util.Map<String, int[]> mobCount = new java.util.LinkedHashMap<>();
        java.util.Map<String, Vec3> mobNear = new java.util.LinkedHashMap<>();
        java.util.Map<String, Double> mobSq = new java.util.LinkedHashMap<>();
        java.util.Map<String, int[]> itemCount = new java.util.LinkedHashMap<>();
        java.util.Map<String, Vec3> itemNear = new java.util.LinkedHashMap<>();
        java.util.Map<String, Double> itemSq = new java.util.LinkedHashMap<>();
        double rr = (double) r * r;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == p) continue;
            Vec3 c = e.getBoundingBox().getCenter();
            double sq = c.distanceToSqr(eye);
            if (sq > rr) continue;
            BlockHitResult h = mc.level.clip(new ClipContext(eye, c, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
            if (h != null && h.getType() != HitResult.Type.MISS && h.getLocation().distanceToSqr(eye) < sq - 0.5) continue;
            if (e instanceof ItemEntity ie) tallyEnt(itemPath(ie), c, sq, itemCount, itemNear, itemSq);
            else if (e instanceof LivingEntity) tallyEnt(entPath(e), c, sq, mobCount, mobNear, mobSq);
        }
        b.append("MOBS   ").append(entLines(eye, mobCount, mobNear, mobSq)).append("\n");
        b.append("ITEMS  ").append(entLines(eye, itemCount, itemNear, itemSq));
    }

    private static void tallyEnt(String id, Vec3 c, double sq, java.util.Map<String, int[]> count,
            java.util.Map<String, Vec3> near, java.util.Map<String, Double> nsq) {
        int[] k = count.get(id);
        if (k == null) { count.put(id, new int[]{1}); near.put(id, c); nsq.put(id, sq); }
        else { k[0]++; if (sq < nsq.get(id)) { near.put(id, c); nsq.put(id, sq); } }
    }

    private static String entLines(Vec3 eye, java.util.Map<String, int[]> count,
            java.util.Map<String, Vec3> near, java.util.Map<String, Double> nsq) {
        if (count.isEmpty()) return "(none)";
        java.util.List<String> ids = new java.util.ArrayList<>(count.keySet());
        ids.sort(java.util.Comparator.comparingDouble(nsq::get));
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            String id = ids.get(i);
            Vec3 c = near.get(id);
            double dx = c.x - eye.x, dy = c.y - eye.y, dz = c.z - eye.z;
            int dist = (int) Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
            int n = count.get(id)[0];
            b.append(String.format("%s%s (%.0f,%.0f,%.0f) %s %d", id, n > 1 ? " x" + n : "", c.x, c.y, c.z, bearing(dx, dy, dz), dist));
            if (i < ids.size() - 1) b.append(" | ");
        }
        return b.toString();
    }

    private static String scanFind(Minecraft mc, LocalPlayer p, String needle, int r) {
        Vec3 eye = p.getEyePosition();
        java.util.Map<String, int[]> count = new java.util.LinkedHashMap<>();
        java.util.Map<String, BlockPos> near = new java.util.LinkedHashMap<>();
        java.util.Map<String, Double> nsq = new java.util.LinkedHashMap<>();
        double[] pitches = {-60, -40, -25, -12, 0, 12, 25, 40, 60};
        for (int yawDeg = 0; yawDeg < 360; yawDeg += 12) {
            double yr = Math.toRadians(yawDeg);
            for (double pd : pitches) {
                double pr = Math.toRadians(pd);
                BlockHitResult h = ray(mc, eye, Math.cos(pr) * Math.cos(yr), Math.sin(pr), Math.cos(pr) * Math.sin(yr), r);
                if (h == null || h.getType() == HitResult.Type.MISS) continue;
                BlockState st = mc.level.getBlockState(h.getBlockPos());
                String id = blockShort(st);
                if (!id.contains(needle)) continue;
                double sq = eye.distanceToSqr(Vec3.atCenterOf(h.getBlockPos()));
                int[] c = count.get(id);
                if (c == null) { count.put(id, new int[]{1}); near.put(id, h.getBlockPos()); nsq.put(id, sq); }
                else { c[0]++; if (sq < nsq.get(id)) { near.put(id, h.getBlockPos()); nsq.put(id, sq); } }
            }
        }
        StringBuilder b = new StringBuilder();
        if (!near.isEmpty()) {
            java.util.List<String> ids = new java.util.ArrayList<>(near.keySet());
            ids.sort(java.util.Comparator.comparingDouble(nsq::get));
            for (String id : ids) b.append(blockLine(p, eye, id, near.get(id), count.get(id)[0])).append("\n");
        }
        double rr = (double) r * r;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == p) continue;
            boolean item = e instanceof ItemEntity;
            if (!item && !(e instanceof LivingEntity)) continue;
            String id = item ? itemPath((ItemEntity) e) : entPath(e);
            if (!id.contains(needle)) continue;
            Vec3 c = e.getBoundingBox().getCenter();
            double sq = c.distanceToSqr(eye);
            if (sq > rr) continue;
            BlockHitResult h = mc.level.clip(new ClipContext(eye, c, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
            if (h != null && h.getType() != HitResult.Type.MISS && h.getLocation().distanceToSqr(eye) < sq - 0.5) continue;
            double dx = c.x - eye.x, dy = c.y - eye.y, dz = c.z - eye.z;
            int dist = (int) Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz));
            b.append(String.format("%s (%.0f,%.0f,%.0f) %s %d\n", id, c.x, c.y, c.z, bearing(dx, dy, dz), dist));
        }
        if (b.length() == 0) return "scan " + needle + ": none visible within " + r;
        return "scan " + needle + ":\n" + trim(b);
    }

    private static String entPath(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
    }

    private static String itemPath(ItemEntity ie) {
        return BuiltInRegistries.ITEM.getKey(ie.getItem().getItem()).getPath();
    }

    private static String blockShort(BlockState st) {
        return BuiltInRegistries.BLOCK.getKey(st.getBlock()).getPath();
    }

    private static String compassShort(double dx, double dz) {
        double ang = Math.toDegrees(Math.atan2(dx, -dz));
        ang = (ang % 360 + 360) % 360;
        String[] d = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
        return d[(int) Math.round(ang / 45.0) % 8];
    }

    private static String bearing(double dx, double dy, double dz) {
        double h = Math.sqrt(dx * dx + dz * dz);
        if (h < 1.5 && Math.abs(dy) >= 1.0) return dy > 0 ? "UP" : "DOWN";
        return compassShort(dx, dz);
    }

    private static String inv(Minecraft mc) {
        LocalPlayer p = mc.player;
        if (p == null) return "inv: not in world";
        Inventory in = p.getInventory();
        StringBuilder b = new StringBuilder();
        int held = InventoryCompat.getSelected(in);
        b.append("held: hotbar[").append(held).append("] = ").append(itemStr(in.getItem(held))).append("\n");
        b.append("items:\n");
        boolean any = false;
        for (int i = 0; i < in.getContainerSize(); i++) {
            ItemStack it = in.getItem(i);
            if (!it.isEmpty()) { b.append("  [").append(i).append("] ").append(itemStr(it)).append("\n"); any = true; }
        }
        if (!any) b.append("  (empty)\n");
        return trim(b);
    }

    private static String runCmd(Minecraft mc, String rest) {
        if (mc.player == null || mc.getConnection() == null) return "cmd: not in world";
        if (rest.isEmpty()) return "ERR usage: cmd <command> (no leading slash)";
        String c = rest.startsWith("/") ? rest.substring(1) : rest;
        mc.getConnection().sendCommand(c);
        return "OK sent: /" + c;
    }

    private static String pause(Minecraft mc) {
        if (mc.level == null) return "pause: not in world";
        M1Compat.setScreen(mc, new net.minecraft.client.gui.screens.PauseScreen(true));
        return "OK pause menu opened (describe, then click 'Save and Quit to Title' to save+exit)";
    }

    private static String face(Minecraft mc, String rest) {
        LocalPlayer p = mc.player;
        if (p == null) return "face: not in world";
        String[] t = rest.trim().split("\\s+");
        if (t.length == 0 || t[0].isEmpty()) return "ERR usage: face <dir|yaw [pitch]|x y z>";
        Float cy = compassYaw(t[0]);
        if (cy != null) { p.setYRot(cy); return "OK facing " + t[0] + " (yaw " + cy + ")"; }
        try {
            if (t.length >= 3) {
                double x = Double.parseDouble(t[0]), y = Double.parseDouble(t[1]), z = Double.parseDouble(t[2]);
                Vec3 eye = p.getEyePosition();
                double dx = x - eye.x, dy = y - eye.y, dz = z - eye.z;
                double h = Math.sqrt(dx * dx + dz * dz);
                float yw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                float pt = (float) (-Math.toDegrees(Math.atan2(dy, h)));
                p.setYRot(yw); p.setXRot(pt);
                return String.format("OK looking at (%.1f,%.1f,%.1f) yaw=%.1f pitch=%.1f", x, y, z, yw, pt);
            }
            float yw = Float.parseFloat(t[0]); p.setYRot(yw);
            if (t.length >= 2) p.setXRot(Float.parseFloat(t[1]));
            return "OK yaw=" + yw;
        } catch (Exception e) { return "ERR usage: face <dir|yaw [pitch]|x y z>"; }
    }

    private static String move(Minecraft mc, String rest) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "move: not in world";
        String[] t = rest.trim().split("\\s+");
        if (t.length < 2) return "ERR usage: move <dir> <blocks>";
        Float yaw = compassYaw(t[0]);
        if (yaw == null) return "ERR unknown dir: " + t[0];
        double n;
        try { n = Double.parseDouble(t[1]); } catch (Exception e) { return "ERR blocks must be a number"; }
        double rad = Math.toRadians(yaw);
        double tx = p.getX() - Math.sin(rad) * n, tz = p.getZ() + Math.cos(rad) * n;
        return startMove(mc, p, tx, p.getY(), tz, 0.8, "move " + t[0]);
    }

    private static String moveto(Minecraft mc, String rest) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "moveto: not in world";
        String[] t = rest.trim().split("\\s+");
        if (t.length < 2) return "ERR usage: moveto <x> <z>";
        try {
            double x = Double.parseDouble(t[0]), z = Double.parseDouble(t[1]);
            return startMove(mc, p, x, p.getY(), z, 1.0, "moveto");
        } catch (Exception e) { return "ERR usage: moveto <x> <z>"; }
    }

    private static String gotoCmd(Minecraft mc, String rest) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "goto: not in world";
        String[] t = rest.trim().split("\\s+");
        if (t.length < 3) return "ERR usage: goto <x> <y> <z>";
        try {
            double x = Double.parseDouble(t[0]), y = Double.parseDouble(t[1]), z = Double.parseDouble(t[2]);
            return startMove(mc, p, x, y, z, 1.0, "goto");
        } catch (Exception e) { return "ERR usage: goto <x> <y> <z>"; }
    }

    private static String startMove(Minecraft mc, LocalPlayer p, double tx, double ty, double tz, double stop, String label) {
        Path path = PathOracle.compute(mc, tx, ty, tz, 1);
        if (path == null || path.getNodeCount() == 0)
            return String.format("no path to (%.1f,%.1f) -- re-scan and pick a closer/clearer point", tx, tz);
        MoveControl.startPath(path, tx, tz, stop, (int) (Math.hypot(tx - p.getX(), tz - p.getZ()) * 30) + 120);
        String partial = path.canReach() ? "" : " (partial, will re-route)";
        return String.format("OK %s -> (%.1f,%.1f) via %d waypoints%s. poll 'where'.",
            label, tx, tz, path.getNodeCount(), partial);
    }

    private static String stopMove() {
        MoveControl.stop();
        MineControl.stop();
        return "OK stopped";
    }

    private static Float compassYaw(String d) {
        switch (d.toLowerCase()) {
            case "south": return 0f;
            case "southwest": case "sw": return 45f;
            case "west": return 90f;
            case "northwest": case "nw": return 135f;
            case "north": return 180f;
            case "northeast": case "ne": return -135f;
            case "east": return -90f;
            case "southeast": case "se": return -45f;
            default: return null;
        }
    }

    private static String mine(Minecraft mc, String rest) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "mine: not in world";
        String[] t = rest.trim().split("\\s+");
        BlockPos target = null;
        if (t.length >= 3) {
            try { target = new BlockPos(Integer.parseInt(t[0]), Integer.parseInt(t[1]), Integer.parseInt(t[2])); }
            catch (Exception e) { return "ERR usage: mine [x y z]"; }
        } else {
            HitResult hr = mc.hitResult;
            if (hr instanceof BlockHitResult bhr) target = bhr.getBlockPos();
            else return "mine: not looking at a block (face it first, or 'mine x y z')";
        }
        if (mc.level.getBlockState(target).isAir()) return "mine: target is air";
        String id = blockId(mc.level.getBlockState(target));
        MineControl.start(target, 200);
        return "OK mining " + id + " at (" + target.getX() + "," + target.getY() + "," + target.getZ() + "). poll 'inv'/'look'.";
    }

    private static ObjectSelectionList<?> findList(Screen s) {
        for (GuiEventListener c : s.children()) {
            if (c instanceof ObjectSelectionList<?> l) return l;
        }
        return null;
    }

    private static String worlds(Minecraft mc) {
        Screen s = M1Compat.screen(mc);
        if (s == null) return "worlds: no screen open";
        ObjectSelectionList<?> list = findList(s);
        if (list == null) return "worlds: no selection list on " + s.getClass().getSimpleName();
        StringBuilder b = new StringBuilder("world entries:\n");
        int i = 0;
        for (Object e : list.children()) {
            if (e instanceof WorldSelectionList.WorldListEntry wle) {
                b.append("  [").append(i).append("] ").append(wle.getNarration().getString()).append("\n");
                i++;
            }
        }
        if (i == 0) b.append("  (no worlds)\n");
        return trim(b);
    }

    private static String joinWorldCmd(Minecraft mc, String rest) {
        Screen s = M1Compat.screen(mc);
        if (s == null) return "joinworld: no screen open";
        ObjectSelectionList<?> list = findList(s);
        if (list == null) return "joinworld: no world list (open Singleplayer first)";
        int idx = 0;
        Integer pp = parseInt(firstTok(rest));
        if (pp != null) idx = pp;
        int i = 0;
        for (Object e : list.children()) {
            if (e instanceof WorldSelectionList.WorldListEntry wle) {
                if (i == idx) { wle.joinWorld(); return "OK joining world #" + idx; }
                i++;
            }
        }
        return "joinworld: no world entry " + idx + " (have " + i + ")";
    }

    private static String widgetType(AbstractWidget w) {
        String t = w.getClass().getSimpleName();
        if (!t.isEmpty()) return t;
        if (w instanceof EditBox) return "EditBox";
        Class<?> sup = w.getClass().getSuperclass();
        return sup != null ? sup.getSimpleName() : "Widget";
    }

    private static String openpack(Minecraft mc) {
        if (mc.player == null) return "openpack: not in world";
        net.minecraft.client.KeyMapping bp = null;
        for (net.minecraft.client.KeyMapping km : mc.options.keyMappings) {
            if (km.getName().toLowerCase().contains("backpack")) { bp = km; break; }
        }
        if (bp == null) return "openpack: no backpack keybind found (" + mc.options.keyMappings.length + " keys)";
        // Isolate: temporarily bind the backpack mapping to a scratch key so ONLY it fires -- otherwise
        // KeyMapping.click(key) triggers EVERY mapping on that physical key (e.g. Xaero waypoints also on B).
        com.mojang.blaze3d.platform.InputConstants.Key orig =
            com.mojang.blaze3d.platform.InputConstants.getKey(bp.saveString());
        com.mojang.blaze3d.platform.InputConstants.Key scratch = freeScratchKey(mc);
        try {
            bp.setKey(scratch);
            net.minecraft.client.KeyMapping.resetMapping();
            net.minecraft.client.KeyMapping.click(scratch);
        } finally {
            bp.setKey(orig);
            net.minecraft.client.KeyMapping.resetMapping();
        }
        return "OK triggered " + bp.getName() + " in isolation -- poll 'describe' for the backpack screen";
    }

    // A KEYSYM not currently bound by any mapping (GLFW F13..F24), used as a conflict-free scratch key.
    private static com.mojang.blaze3d.platform.InputConstants.Key freeScratchKey(Minecraft mc) {
        java.util.Set<String> used = new java.util.HashSet<>();
        for (net.minecraft.client.KeyMapping km : mc.options.keyMappings) used.add(km.saveString());
        for (int sym = 302; sym <= 313; sym++) {
            com.mojang.blaze3d.platform.InputConstants.Key k =
                com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(sym);
            if (!used.contains(k.getName())) return k;
        }
        return com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(313);
    }

    // Trigger Minecraft's OWN screenshot writer. IMPORTANT: this MUST NOT run on / block the
    // render-main thread. Screenshot.grab's GPU readback completes via the command-encoder flush
    // that happens on the main thread DURING the render loop, and the PNG encode is queued on
    // Util.ioPool(); if we blocked the main thread waiting for that, the flush could never run
    // (deadlock -> 0-byte file, no callback). So M1Server dispatches this verb on the CONNECTION
    // thread, and here we only marshal the grab() call itself onto the main thread via mc.execute(),
    // then wait OFF-thread for the success/failure callback. No mixin, compositor-independent --
    // the frame comes straight from the game's framebuffer.
    static String screenshot(Minecraft mc, String rest) {
        String name = rest.trim();
        final String forceName;
        if (name.isEmpty()) {
            forceName = null;
        } else {
            forceName = name.toLowerCase().endsWith(".png") ? name : name + ".png";
        }
        java.io.File picDir = new java.io.File(mc.gameDirectory, Screenshot.SCREENSHOT_DIR);
        long before = newestPngMtime(picDir);
        final String[] captured = new String[1];
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        java.util.function.Consumer<net.minecraft.network.chat.Component> cb = msg -> {
            try { captured[0] = msg != null ? msg.getString() : null; } finally { latch.countDown(); }
        };
        // Marshal ONLY the grab onto the main thread; return control immediately so the render
        // loop keeps flushing GPU commands and the ioPool write can complete.
        mc.execute(() -> {
            try {
                if (forceName == null) {
                    ScreenshotCompat.grab(mc.gameDirectory, null, M1Compat.mainRenderTarget(mc), cb);
                } else {
                    ScreenshotCompat.grab(mc.gameDirectory, forceName, M1Compat.mainRenderTarget(mc), cb);
                }
            } catch (Throwable t) {
                captured[0] = "GRAB-ERR " + t.getClass().getSimpleName() + ": " + t.getMessage();
                latch.countDown();
            }
        });
        boolean done = false;
        try { done = latch.await(10, java.util.concurrent.TimeUnit.SECONDS); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        // Resolve the newest PNG that postdates our pre-grab snapshot (ground truth).
        java.io.File newest = newestPngNewerThan(picDir, before);
        if (newest == null) newest = newestPng(picDir);
        StringBuilder b = new StringBuilder();
        if (newest != null && newest.length() > 0 && newest.lastModified() >= before) {
            b.append("OK screenshot ").append(newest.getName())
             .append(" (").append(newest.length()).append(" bytes) path=").append(newest.getAbsolutePath());
        } else if (newest != null) {
            b.append("ERR screenshot: file ").append(newest.getName())
             .append(" is ").append(newest.length()).append(" bytes (callback=").append(captured[0])
             .append(" done=").append(done).append(")");
        } else {
            b.append("ERR screenshot: no PNG written (callback=").append(captured[0]).append(" done=").append(done).append(")");
        }
        return b.toString();
    }

    private static long newestPngMtime(java.io.File dir) {
        java.io.File f = newestPng(dir);
        return f != null ? f.lastModified() : 0L;
    }

    private static java.io.File newestPngNewerThan(java.io.File dir, long after) {
        if (dir == null || !dir.isDirectory()) return null;
        java.io.File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".png"));
        if (files == null) return null;
        java.io.File best = null;
        for (java.io.File f : files) {
            if (f.lastModified() < after) continue;
            if (best == null || f.lastModified() > best.lastModified()) best = f;
        }
        return best;
    }

    private static java.io.File newestPng(java.io.File dir) {
        if (dir == null || !dir.isDirectory()) return null;
        java.io.File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".png"));
        if (files == null || files.length == 0) return null;
        java.io.File best = null;
        for (java.io.File f : files) {
            if (best == null || f.lastModified() > best.lastModified()) best = f;
        }
        return best;
    }

    private static String autoupgrade(String rest) {
        String a = rest.trim().toLowerCase();
        if (a.equals("on") || a.equals("true")) { PickupUpgrade.setEnabled(true); return "OK auto-upgrade ON"; }
        if (a.equals("off") || a.equals("false")) { PickupUpgrade.setEnabled(false); return "OK auto-upgrade OFF"; }
        return "auto-upgrade is " + (PickupUpgrade.isEnabled() ? "ON" : "OFF") + " (usage: autoupgrade on|off)";
    }

    private static String upgrades() {
        String r = PickupUpgrade.drainReports();
        return r.isEmpty() ? "no upgrades pending" : r;
    }

    // ---------- helpers ----------

    private static String itemStr(ItemStack it) {
        return it.isEmpty() ? "empty" : (it.getCount() + "x " + it.getHoverName().getString());
    }

    private static String blockId(BlockState st) {
        return BuiltInRegistries.BLOCK.getKey(st.getBlock()).toString();
    }

    private static String compassFromYaw(float yaw) {
        float y = (yaw % 360 + 360) % 360;
        String[] dirs = {"south", "southwest", "west", "northwest", "north", "northeast", "east", "southeast"};
        return dirs[(int) Math.round(y / 45.0) % 8];
    }

    private static String compass(double dx, double dz) {
        double ang = Math.toDegrees(Math.atan2(dx, -dz));
        ang = (ang % 360 + 360) % 360;
        String[] dirs = {"north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest"};
        return dirs[(int) Math.round(ang / 45.0) % 8];
    }

    private static String firstTok(String s) {
        if (s == null || s.isEmpty()) return "";
        String[] t = s.split("\\s+");
        return t.length > 0 ? t[0] : "";
    }

    private static Integer parseInt(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return null; }
    }

    private static String trim(StringBuilder b) {
        if (b.length() > 0 && b.charAt(b.length() - 1) == '\n') b.setLength(b.length() - 1);
        return b.toString();
    }
}







