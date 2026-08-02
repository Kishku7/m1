package com.kishku7.m1;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.screens.worldselection.WorldSelectionList;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
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
        "  scan [r]             what you SEE: bounds + notable blocks + mobs + items (LOS, r<=32)\n" +
        "  scan <name|id> [rN] [page]   FIND EVERY match, by VOLUME not line-of-sight (so it sees\n" +
        "                       the back rows of a wall). Lists exact coords + facing + distance for\n" +
        "                       each, 50 per page: 'scan furnace' then 'scan furnace 2' for page 2.\n" +
        "                       rN sets radius (default 16, max 32). Use this to enumerate a bank of\n" +
        "                       chests/furnaces -- never walk-and-rescan, it silently skips things.\n" +
        "  inv                  inventory contents + held item\n" +
        "  cmd <command>        run a server command (needs cheats), no slash\n" +
        "  say <text>           send an in-game CHAT message (e.g. acknowledge your master)\n" +
        "  pause                open pause menu (then click Save and Quit to Title)\n" +
        "  face <dir|yaw [pitch]|x y z>  set facing\n" +
        "  moveto <x> <z>       pathfind + walk to x,z (routes around walls)\n" +
        "  goto <x> <y> <z>     pathfind + walk to coords\n" +
        "  move <dir> <n>       pathfind + walk n blocks that way\n" +
        "  stop                 stop moving\n" +
        "  mine [x y z]         mine the block you are looking at (or at x y z)\n" +
        "  mine hold [on|off]   HOLD mouse-1 down: you break whatever the crosshair hits -- steer\n" +
        "                       with face/moveto to tunnel continuously. 'stop' also releases it.\n" +
        "  mine area <x1 y1 z1> <x2 y2 z2> [nocollect]   clear a whole BOX as one job: top-down,\n" +
        "                       air gaps skipped free, repositions itself, [agent] progress lines,\n" +
        "                       then walks the drops in (nocollect to skip that).\n" +
        "  sign <x> <y> <z> <text>   WRITE a sign (split lines with | ). The sign must already be\n" +
        "                       placed. Queued as an action -- the open and the write must land in\n" +
        "                       different ticks. Verify with 'read x y z'.\n" +
        "  read [x y z]         READ A SIGN, by coord or crosshair. Reads the block entity, not a ray,\n" +
        "                       so it works on wall signs (a collider ray passes straight through them).\n" +
        "                       'scan sign' also returns each sign's text inline, which is usually faster.\n" +
        "  take output [radius] BULK: empty EVERY furnace in range -- walks them all, takes the OUTPUT\n" +
        "                       slot only, never touches input or fuel. One command instead of six per\n" +
        "                       furnace. Progress arrives as [agent] lines; stops early if you fill up.\n" +
        "  open <x> <y> <z>     open/use a container BY COORDINATE -- no aiming, no crosshair. Feed it\n" +
        "                       coords straight from 'scan'. Server reach still applies (~4.4): if it is\n" +
        "                       too far the reply says so with the distance. Then 'slots' as usual.\n" +
        "  worlds | servers | entries   list the ROWS of the on-screen selection list (saved worlds,\n" +
        "                       saved servers, LAN games...). Rows are NOT widgets -- they never show\n" +
        "                       up in 'describe' as [n] ids; they are listed separately as <n>.\n" +
        "  select <n>           select list row <n>; buttons needing a selection (Join Server, Play\n" +
        "                       Selected World) then go ACTIVE and can be clicked by their widget id\n" +
        "  joinworld <idx>      load saved world #idx (re-enter)\n" +
        "  openinv / close      open inventory (2x2 grid) / close screen\n" +
        "  craft planks|sticks|table|axe   craft items (axe needs the crafting table open=3x3)\n" +
        "  hold <0-8> / equip <item|all>   select hotbar slot / auto-equip by name (armor->slot, shield->offhand) or ALL\n" +
        "  organize hotbar / takeall / stash junk / moveitem <item> to <hb1-9|offhand|head|chest|legs|feet>\n" +
        "  pack on|contents [f]|put <item|all|junk>|take <item> [n]   wear + use a Travelers Backpack\n" +
        "  place                use/place held item on the block you are looking at\n" +
        "  slot <id> [btn] [pickup|quick|swap]   raw slot click\n" +
        "  drop [slot|<item>] [n|all]   throw item(s) on the ground, Q-equivalent (slot: hand|hb1-9|inv N|head|chest|legs|feet|offhand)\n" +
        "  examine [slot|<item>]        full item readout: id, custom name, enchantments, durability, tooltip, vault key\n" +
        "  openpack             open your worn Travelers Backpack (triggers its keybind)\n" +
        "  screenshot [name]    save a PNG of the current frame to screenshots/ (vanilla writer)\n" +
        "  upgrades             show pending auto-armor-upgrade messages\n" +
        "  autoupgrade on|off   toggle auto armor upgrading (default on)\n" +
        "  vault <sub>          Bank Vault storage: mark|status|contents|withdraw|deposit|find|memory ('vault help')\n" +
        "  cancraft <item>      recipe-book feasibility: craftable now? what's missing? (vault-aware)\n" +
        "  attack [target] [crit|normal]   engage a mob (nearest|<id>|crosshair); approaches then hits\n" +
        "  follow <player> [dist]          follow a player; 'stop' to end\n" +
        "  defend [on|off|status|auto|<p>] auto-defense reflex: if the master or I get attacked,\n" +
        "                                  engage the attacker, then resume (leashed to 16m)\n" +
        "  agent <sub>          queued action layer: status|ping|goto|moveto|patrol|look|mine|hold|equip|use|drop|jump|sneak|sprint|slot|openinv|close|sleep|recover|craft|vault|attack|follow|shield|defend|stop\n" +
        "  help                 this list\n" +
        "AI agents: type START for the AI_Brain index path (config/M1_AI_Brain/<ver>/00_Index.md); full command syntax is in 10_command_card.md.";

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
            case "m1srv":     return M1SrvNet.requestOne(mc, rest);
            case "say":       return say(mc, rest);
            case "master":  return master(rest);
            case "pause":     return pause(mc);
            case "face":      return face(mc, rest);
            case "move":      return move(mc, rest);
            case "moveto":    return moveto(mc, rest);
            case "goto":      return gotoCmd(mc, rest);
            case "stop":      return stopMove();
            case "nav":       return navCmd(rest);
            case "sleep":     return AgentRuntime.command("sleep");
            case "recover":   return AgentRuntime.command("recover");
            case "mine":      return mine(mc, rest);
            case "open":      return openAtCmd(mc, rest);
            case "take":      return takeCmd(rest);
            case "read":      return readCmd(mc, rest);
            case "sign":      return signCmd(mc, rest);
            case "worlds":
            case "servers":
            case "entries":   return worlds(mc);
            case "select":    return selectCmd(mc, rest);
            case "joinworld":
            case "join":      return joinWorldCmd(mc, rest);
            case "openinv":   return Crafting.openInv(mc);
            case "close":     return Crafting.close(mc);
            case "hold":      return Crafting.hold(mc, rest);
            case "equip":
                if (rest.trim().equalsIgnoreCase("all")) return InventoryOps.equipAll(mc);
                return InventoryOps.equipNamed(mc, rest);
            case "organize":  return InventoryOps.organizeHotbar(mc); // "organize hotbar"
            case "takeall":   return InventoryOps.takeAll(mc);
            case "stash":     return InventoryOps.stashJunk(mc);      // "stash junk"
            case "moveitem":  return InventoryOps.moveItem(mc, rest);
            case "pack": {
                String[] pt = rest.trim().split("\\s+", 2);
                String sub = pt.length > 0 ? pt[0].toLowerCase(java.util.Locale.ROOT) : "";
                String pa = pt.length > 1 ? pt[1] : "";
                switch (sub) {
                    case "on":       return BackpackOps.equip(mc);
                    case "contents": return BackpackOps.contents(mc, pa);
                    case "put":      return BackpackOps.put(mc, pa);
                    case "take":     return BackpackOps.take(mc, pa);
                    default: return "ERR usage: pack on|contents [f]|put <item|all|junk>|take <item> [n]";
                }
            }
            case "slot":      return Crafting.slotCmd(mc, rest);
            case "drop":
            case "throw":     return AgentRuntime.command("drop " + rest);
            case "examine":
            case "inspect":   return ItemInfo.examine(mc, rest);
            case "place":
            case "use":
            case "interact":  return Crafting.place(mc); // use/interact aliases (AI reached for them, session 702)
            case "craft":     return Crafting.craft(mc, rest);
            case "openpack":  return openpack(mc);
            case "screenshot":
            case "shot":      return screenshot(mc, rest);
            case "autoupgrade": return autoupgrade(rest);
            case "upgrades":  return upgrades();
            case "vault":     return VaultOps.command(mc, rest);
            case "cancraft":  return RecipeOps.canCraft(mc, rest.trim());
            case "attack":    return AgentRuntime.command("attack " + rest);
            case "follow":    return AgentRuntime.command("follow " + rest);
            case "defend":    return AgentRuntime.command("defend " + rest);
            case "agent":     return AgentRuntime.command(rest);
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
            // Associate a descriptive label so a controller can act on ambiguous controls without a
            // hard-coded layout: a control's PURPOSE is usually the text to its LEFT (same row), else
            // directly ABOVE. e.g. an "ON"/"OFF" toggle whose meaning lives in a neighbouring label.
            if (!isLabelWidget(w)) {
                String lbl = nearestLabel(w, ws);
                if (lbl != null && !lbl.isEmpty() && !msg.equalsIgnoreCase(lbl)) b.append(" label=\"").append(lbl).append("\"");
            }
            b.append("\n");
        }
        List<String> rows = listEntryLines(s);
        if (!rows.isEmpty()) {
            b.append("list rows: ").append(rows.size())
             .append("  (not widgets -- use 'select <n>', then click the now-active button)\n");
            for (int i = 0; i < rows.size(); i++) {
                b.append("  <").append(i).append("> ").append(rows.get(i)).append("\n");
            }
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
            b.append("  [").append(slot.index).append("] ")
                    .append(slotRole(p, slot)).append("  ")
                    .append(itemStr(slot.getItem())).append("\n");
        }
        return trim(b);
    }

    /**
     * Human/AI-readable ROLE of a slot (702b fix: the AI parked diamond tools in the 2x2 craft
     * grid because slot ids carried no meaning). Player-inventory slots label as hotbar/main/
     * armor/offhand; crafting grids and results are called out as NOT-storage.
     */
    private static String slotRole(LocalPlayer p, Slot slot) {
        if (slot.container == p.getInventory()) {
            int cs = slot.getContainerSlot();
            if (cs >= 0 && cs <= 8) return "hotbar-" + cs;
            if (cs >= 9 && cs <= 35) return "main";
            if (cs == 36) return "armor:feet";
            if (cs == 37) return "armor:legs";
            if (cs == 38) return "armor:chest";
            if (cs == 39) return "armor:head";
            if (cs == 40) return "offhand";
            return "inv";
        }
        String cn = slot.container.getClass().getSimpleName();
        if (cn.contains("Result")) return "CRAFT-RESULT(no storage)";
        if (cn.contains("Crafting")) return "CRAFT-GRID(no storage!)";
        if (cn.contains("Equipment") || cn.contains("Armor")) return "armor";
        if (cn.toLowerCase(java.util.Locale.ROOT).contains("trinket")
                || slot.getClass().getName().toLowerCase(java.util.Locale.ROOT).contains("trinket")) {
            return "TRINKET (worn accessory: backpack/elytra/etc)";
        }
        return "container";
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
        if (M1Compat.screen(mc) != null) {
            base += "  | screen OPEN: " + M1Compat.screen(mc).getClass().getSimpleName()
                    + " (movement holds while a screen is up -- 'close' to exit)";
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
    /** Targeted scan walks a VOLUME, so its default radius is smaller than the raycast one. */
    private static final int SCAN_FIND_DEFAULT_R = 16;
    /** Match rows per page (Master, 2026-08-01: "so you can get up to 50"). */
    private static final int SCAN_PAGE = 50;

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
        if (!arg.isEmpty() && ri == null) {
            String more = rest.trim().substring(arg.length()).trim();
            return scanFind(mc, p, arg.toLowerCase(), SCAN_FIND_DEFAULT_R, more);
        }
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

    /**
     * TARGETED SCAN -- "find me every X". Rewritten 2026-08-01 after a live chore (empty every
     * furnace in a bank) turned into a 40-command crawl.
     *
     * <p>Two things were wrong with the old one, both found by playing:
     * <ol>
     *   <li>It deduped by block id and reported only the NEAREST of each kind plus a bare
     *       {@code +more}. For a bank of 8 furnaces that is worse than useless -- you cannot
     *       enumerate what you cannot see, so the controller had to walk a few blocks and re-scan,
     *       over and over, and STILL silently skipped three furnaces because the hops jumped over
     *       them.</li>
     *   <li>It RAYCAST, so it was line-of-sight only: the back rows of a chest/furnace wall, or
     *       anything behind a block, simply did not exist.</li>
     * </ol>
     *
     * <p>Now it walks the actual block VOLUME (no rays, no line-of-sight), lists EVERY match with
     * exact coords, reports each block's {@code facing} where it has one (so the caller knows which
     * side to stand on rather than guessing), and PAGES the result 50 at a time
     * ({@code scan furnace 2} = page 2) so a big match set can be read without blowing the reply.
     *
     * <p>Syntax: {@code scan <name|id> [rN] [page]} -- {@code rN} sets the radius (default
     * {@value #SCAN_FIND_DEFAULT_R}, max {@value #SCAN_MAX_R}), a bare integer is the page number.
     */
    private static String scanFind(Minecraft mc, LocalPlayer p, String needle, int r, String rest) {
        int page = 1;
        for (String tok : rest.trim().split("\\s+")) {
            if (tok.isEmpty()) continue;
            String low = tok.toLowerCase();
            if (low.startsWith("r") && parseInt(low.substring(1)) != null) {
                r = Math.max(2, Math.min(SCAN_MAX_R, parseInt(low.substring(1)).intValue()));
                continue;
            }
            Integer n = parseInt(low);
            if (n != null) page = Math.max(1, n.intValue());
        }

        Vec3 eye = p.getEyePosition();
        BlockPos origin = BlockPos.containing(p.getX(), p.getY(), p.getZ());
        java.util.List<BlockPos> hits = new java.util.ArrayList<>();
        java.util.List<String> ids = new java.util.ArrayList<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        int rr = r * r;
        // NO explicit build-height clamp on purpose: the accessor was RENAMED across the supported
        // range (getMinBuildHeight/getMaxBuildHeight -> getMinY/getMaxY), and Level.getBlockState
        // already returns air for anything outside the build limits, so the bounds check buys
        // nothing and would cost a Cog branch or a facade on every cell.
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                int wy = origin.getY() + dy;
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dy * dy + dz * dz > rr) continue;
                    m.set(origin.getX() + dx, wy, origin.getZ() + dz);
                    BlockState st = mc.level.getBlockState(m);
                    if (st.isAir()) continue;
                    String id = blockShort(st);
                    if (!id.contains(needle)) continue;
                    hits.add(m.immutable());
                    ids.add(id);
                }
            }
        }

        // nearest first -- the caller almost always wants to work outward from where it stands
        Integer[] order = new Integer[hits.size()];
        for (int i = 0; i < order.length; i++) order[i] = Integer.valueOf(i);
        final java.util.List<BlockPos> hf = hits;
        java.util.Arrays.sort(order, java.util.Comparator.comparingDouble(
                i -> eye.distanceToSqr(Vec3.atCenterOf(hf.get(i.intValue())))));

        StringBuilder b = new StringBuilder();
        int total = hits.size();
        if (total == 0) {
            b.append("scan ").append(needle).append(": no blocks within ").append(r).append("\n");
        } else {
            int pages = (total + SCAN_PAGE - 1) / SCAN_PAGE;
            if (page > pages) page = pages;
            int from = (page - 1) * SCAN_PAGE;
            int to = Math.min(total, from + SCAN_PAGE);
            b.append(String.format(java.util.Locale.ROOT,
                    "scan %s: %d match%s within %d (volume, NOT line-of-sight) -- showing %d-%d, page %d of %d\n",
                    needle, total, total == 1 ? "" : "es", r, from + 1, to, page, pages));
            for (int i = from; i < to; i++) {
                int k = order[i].intValue();
                BlockPos bp = hits.get(k);
                BlockState st = mc.level.getBlockState(bp);
                String face = facingOf(st);
                double ddx = bp.getX() + 0.5 - p.getX();
                double ddy = bp.getY() + 0.5 - eye.y;
                double ddz = bp.getZ() + 0.5 - p.getZ();
                String sign = signTextAt(mc, bp);   // labels come back WITH the match, for free
                b.append(String.format(java.util.Locale.ROOT, "  %s (%d,%d,%d)%s dist=%.1f %s%s\n",
                        ids.get(k), bp.getX(), bp.getY(), bp.getZ(),
                        face == null ? "" : " facing=" + face,
                        Math.sqrt(ddx * ddx + ddy * ddy + ddz * ddz), bearing(ddx, ddy, ddz),
                        sign == null ? "" : "  \"" + sign + "\""));
            }
            if (pages > 1) {
                b.append("  (next: scan ").append(needle).append(' ')
                 .append(page < pages ? page + 1 : pages).append(")\n");
            }
        }

        double erad = (double) r * r;
        java.util.List<String> ents = new java.util.ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e == p) continue;
            boolean item = e instanceof ItemEntity;
            if (!item && !(e instanceof LivingEntity)) continue;
            String id = item ? itemPath((ItemEntity) e) : entPath(e);
            if (!id.contains(needle)) continue;
            Vec3 c = e.getBoundingBox().getCenter();
            double sq = c.distanceToSqr(eye);
            if (sq > erad) continue;
            ents.add(String.format(java.util.Locale.ROOT, "  %s%s (%.0f,%.0f,%.0f) id=%d dist=%.1f",
                    item ? "item:" : "", id, c.x, c.y, c.z, e.getId(), Math.sqrt(sq)));
        }
        if (!ents.isEmpty()) {
            b.append("entities: ").append(ents.size()).append("\n");
            for (String line : ents) b.append(line).append("\n");
        }
        return trim(b);
    }

    /**
     * A block's {@code facing} property value, or null when it has none. Read GENERICALLY off the
     * state's property set rather than by naming a block class, so it works for furnaces, chests,
     * dispensers, stairs, beds and anything else on every supported MC version.
     */
    private static String facingOf(BlockState st) {
        for (net.minecraft.world.level.block.state.properties.Property<?> pr : st.getProperties()) {
            if (pr.getName().equals("facing")) {
                Comparable<?> v = st.getValue(pr);
                if (v != null) return v.toString().toLowerCase(java.util.Locale.ROOT);
            }
        }
        return null;
    }

    private static String entPath(Entity e) {
        return BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
    }

    @SuppressWarnings("deprecation") // Forge 1.20.1-only: BuiltInRegistries access deprecated there (ForgeRegistries); vanilla registry is correct + cross-loader
    private static String itemPath(ItemEntity ie) {
        return BuiltInRegistries.ITEM.getKey(ie.getItem().getItem()).getPath();
    }

    @SuppressWarnings("deprecation") // Forge 1.20.1-only: BuiltInRegistries access deprecated there (ForgeRegistries); vanilla registry is correct + cross-loader
    static String blockShort(BlockState st) {
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
        b.append("held: ").append(ItemInfo.label(held)).append(" = ").append(itemStr(in.getItem(held))).append("\n");
        b.append("items (hbN = hotbar, idx = raw index; 'examine <slot>' for detail):\n");
        boolean any = false;
        for (int i = 0; i < in.getContainerSize(); i++) {
            ItemStack it = in.getItem(i);
            if (!it.isEmpty()) { b.append("  [").append(ItemInfo.label(i)).append("] ").append(itemStr(it)).append("\n"); any = true; }
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

    private static String say(Minecraft mc, String rest) {
        if (mc.player == null || mc.getConnection() == null) return "say: not in world";
        String t = rest.trim();
        if (t.isEmpty()) return "ERR usage: say <text>";
        if (t.length() > 256) t = t.substring(0, 256);
        mc.getConnection().sendChat(t);
        return "OK said: " + t;
    }

    /** "master" (query) | "master <name>" (set) | "master clear" (unset). The AI, not the mod,
     *  decides WHO becomes master (see ChatWatch); this verb is how it tells the mod. */
    private static String master(String rest) {
        String t = (rest == null) ? "" : rest.trim();
        if (t.isEmpty()) {
            String m = ChatWatch.master();
            return (m != null) ? "master: " + m : "master: (none set -- chat relay open to all players)";
        }
        if (t.equalsIgnoreCase("clear")) {
            ChatWatch.clearMaster();
            return "OK master cleared";
        }
        ChatWatch.setMaster(t);
        return "OK master set to " + t;
    }
    private static String pause(Minecraft mc) {
        if (mc.level == null) return "pause: not in world";
        // Tell the PauseScreen reflex to stand down: this pause is DELIBERATE. Without this the
        // reflex closes it on the very next tick and the graceful-exit path cannot be driven.
        ScreenWatch.allowPause(60_000L);
        M1Compat.setScreen(mc, new net.minecraft.client.gui.screens.PauseScreen(true));
        return "OK pause menu opened, and the auto-dismiss reflex is suppressed for 60s"
                + " (describe, then click 'Save and Quit to Title' / 'Disconnect')";
    }

    private static String face(Minecraft mc, String rest) {
        LocalPlayer p = mc.player;
        if (p == null) return "face: not in world";
        String[] t = rest.trim().split("\\s+");
        if (t.length == 0 || t[0].isEmpty()) return "ERR usage: face <dir|yaw [pitch]|x y z>";
        if (t[0].equalsIgnoreCase("up"))   { p.setXRot(-90f); return "OK facing up (pitch -90)"; }
        if (t[0].equalsIgnoreCase("down")) { p.setXRot(90f);  return "OK facing down (pitch 90)"; }
        Float cy = compassYaw(t[0]);
        if (cy != null) { p.setYRot(cy); return "OK facing " + t[0] + " (yaw " + cy + ")"; }
        try {
            if (t.length >= 3) {
                double x = Double.parseDouble(t[0]), y = Double.parseDouble(t[1]), z = Double.parseDouble(t[2]);
                // BLOCK COORDS AIM AT THE BLOCK'S CENTRE, NOT ITS CORNER (fixed 2026-08-01).
                // A block spans [x, x+1), so the raw integer is its minimum CORNER -- up to 0.87
                // blocks off in 3D. At 10+ blocks that is a rounding error and nobody noticed; at
                // 1-2 blocks, which is exactly where container work happens, it is TENS OF DEGREES
                // and the ray lands on the neighbouring block. Live symptom: aiming at a chest one
                // block away produced pitch 48-60 and opened the chest below/behind it, repeatedly.
                // Integers => a block reference (that is what `scan` hands back); anything with a
                // decimal point is honoured as an exact point.
                boolean blockRef = isIntToken(t[0]) && isIntToken(t[1]) && isIntToken(t[2]);
                if (blockRef) {
                    x += 0.5;
                    y += 0.5;
                    z += 0.5;
                }
                Vec3 eye = p.getEyePosition();
                double dx = x - eye.x, dy = y - eye.y, dz = z - eye.z;
                double h = Math.sqrt(dx * dx + dz * dz);
                float yw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                float pt = (float) (-Math.toDegrees(Math.atan2(dy, h)));
                p.setYRot(yw); p.setXRot(pt);
                return String.format("OK looking at (%.1f,%.1f,%.1f)%s yaw=%.1f pitch=%.1f",
                        x, y, z, blockRef ? " [block centre]" : "", yw, pt);
            }
            float yw = Float.parseFloat(t[0]); p.setYRot(yw);
            if (t.length >= 2) p.setXRot(Float.parseFloat(t[1]));
            return "OK yaw=" + yw;
        } catch (Exception e) { return "ERR usage: face <dir|yaw [pitch]|x y z>"; }
    }

    /** True when a token is a plain integer (no decimal point) -- i.e. a BLOCK reference. */
    private static boolean isIntToken(String t) {
        if (t == null || t.isEmpty()) return false;
        int i = (t.charAt(0) == '-' || t.charAt(0) == '+') ? 1 : 0;
        if (i >= t.length()) return false;
        for (; i < t.length(); i++) {
            if (!Character.isDigit(t.charAt(i))) return false;
        }
        return true;
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
        if (t.length >= 3) {
            // 3 coords given: the caller meant goto (702d: y was silently parsed as z)
            return gotoCmd(mc, rest);
        }
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

    static String startMove(Minecraft mc, LocalPlayer p, double tx, double ty, double tz, double stop, String label) {
        MoveControl.stop(); // clear any prior move on either engine
        // If the target cell is a solid block (e.g. the Bank Vault's iron blocks -- 702d), you can
        // never STAND there. Retarget to the nearest standable cell beside it so "walk to the vault"
        // works when the AI names the block itself.
        net.minecraft.core.BlockPos gp = net.minecraft.core.BlockPos.containing(tx, ty, tz);
        if (mc.level != null && !mc.level.getBlockState(gp).getCollisionShape(mc.level, gp).isEmpty()) {
            for (net.minecraft.core.Direction d : new net.minecraft.core.Direction[]{
                    net.minecraft.core.Direction.NORTH, net.minecraft.core.Direction.SOUTH,
                    net.minecraft.core.Direction.EAST, net.minecraft.core.Direction.WEST}) {
                net.minecraft.core.BlockPos side = gp.relative(d);
                boolean feetClear = mc.level.getBlockState(side).getCollisionShape(mc.level, side).isEmpty();
                boolean floor = !mc.level.getBlockState(side.below()).getCollisionShape(mc.level, side.below()).isEmpty();
                if (feetClear && floor) {
                    tx = side.getX() + 0.5;
                    ty = side.getY();
                    tz = side.getZ() + 0.5;
                    break;
                }
            }
        }
        int maxT = (int) (Math.hypot(tx - p.getX(), tz - p.getZ()) * 30) + 120;
        if (MoveControl.ownNav() && NavEngine.start(mc, tx, ty, tz, stop, maxT)) {
            return String.format("OK %s -> (%.1f,%.1f) own pather, segment 1 (%d wp). poll 'where'.",
                label, tx, tz, NavEngine.waypointCount());
        }
        Path path = PathOracle.compute(mc, tx, ty, tz, 1);
        if (path == null || path.getNodeCount() == 0)
            return String.format("no path to (%.1f,%.1f) -- re-scan and pick a closer/clearer point", tx, tz);
        MoveControl.startPath(path, tx, tz, stop, maxT);
        String partial = path.canReach() ? "" : " (partial, will re-route)";
        return String.format("OK %s -> (%.1f,%.1f) via %d waypoints%s. poll 'where'.",
            label, tx, tz, path.getNodeCount(), partial);
    }

    private static String stopMove() {
        AgentRuntime.command("stop"); // clear any agent plan (follow/goto/attack) + stop move/mine
        MoveControl.stop();
        MineControl.stop();
        return "OK stopped";
    }

    /** Engine toggle for the own pather (Master decision 2026-07-02): nav [own|vanilla|status]. */
    private static String navCmd(String rest) {
        String a = rest == null ? "" : rest.trim().toLowerCase();
        switch (a) {
            case "own":     MoveControl.setOwnNav(true);  return "OK nav engine = own (M1Pather)";
            case "vanilla": MoveControl.setOwnNav(false); return "OK nav engine = vanilla (PathOracle)";
            case "":
            case "status":
                return "nav engine=" + (MoveControl.ownNav() ? "own" : "vanilla")
                        + " active=" + NavEngine.isActive()
                        + " status=" + NavEngine.status()
                        + " segment=" + NavEngine.segmentNo();
            default: return "ERR usage: nav [own|vanilla|status]";
        }
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
        // Bulk forms (2026-08-01): one block per command made real excavation unusable, so both
        // "hold the button down" and "clear this whole box" run on the agent queue.
        if (t.length >= 1 && !t[0].isEmpty()) {
            String head = t[0].toLowerCase();
            if (head.equals("hold") || head.equals("area") || head.equals("box")) {
                return AgentRuntime.command("mine " + rest.trim());
            }
        }
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

    /**
     * Rows of the screen's selection list, as text. THE GUI GAP (Master, 2026-08-01): a selection
     * list's rows are NOT widgets -- they are list Entries -- so `describe` never showed them and
     * `worlds` only understood WorldListEntry. That made the multiplayer server list, and every
     * other list screen, completely invisible: the saved-server rows simply did not exist as far as
     * the controller could tell. Enumerate them GENERICALLY via Entry.getNarration(), which every
     * ObjectSelectionList.Entry implements, so this works for worlds, servers, LAN games, packs,
     * and anything else without naming a single version-specific entry class.
     */
    private static List<String> listEntryLines(Screen s) {
        List<String> out = new ArrayList<>();
        ObjectSelectionList<?> list = findList(s);
        if (list == null) return out;
        for (Object e : list.children()) {
            String n = "";
            if (e instanceof ObjectSelectionList.Entry<?> oe) {
                Component c = oe.getNarration();
                if (c != null) n = c.getString();
            }
            if (n.isEmpty()) n = e.getClass().getSimpleName();
            out.add(n.replace('\n', ' ').trim());
        }
        return out;
    }

    /** Select list row {@code idx}. Raw/unchecked: the list's element type is a self-referential
     *  generic, and we deliberately do not name any concrete entry class (version drift). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean selectEntry(ObjectSelectionList<?> list, int idx) {
        List<?> kids = list.children();
        if (idx < 0 || idx >= kids.size()) return false;
        // AbstractSelectionList.Entry is PROTECTED, so it can never be named here; ObjectSelectionList
        // .Entry is public and is a subtype of the erased parameter, which is what makes this compile
        // on every version without naming an inaccessible type.
        ((ObjectSelectionList) list).setSelected((ObjectSelectionList.Entry) kids.get(idx));
        return true;
    }

    /**
     * Sign text, read from the BLOCK ENTITY rather than from a ray.
     *
     * <p>Live bug (Master, 2026-08-01): a storage room's 12 wall signs -- which label every chest
     * column -- were completely unreadable. The server's {@code lookingat} ray uses a COLLIDER
     * context and wall signs have no collision box, so the ray goes straight through them and
     * reports the chest behind. The controller could see the signs existed (they show in a block
     * scan) but could never read a single one, and had to open chests one by one instead.
     *
     * <p>Reading the block entity sidesteps rays entirely. Both faces are returned when the back is
     * also written; blank lines are dropped.
     */
    private static String signTextAt(Minecraft mc, BlockPos bp) {
        if (mc.level == null) return null;
        net.minecraft.world.level.block.entity.BlockEntity be = mc.level.getBlockEntity(bp);
        if (!(be instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign)) return null;
        // Both the accessor and the return shape drift at 26.3 -- SignCompat owns that (Cog facade).
        String front = SignCompat.face(sign, true);
        String back = SignCompat.face(sign, false);
        if (front.isEmpty() && back.isEmpty()) return "(blank)";
        if (back.isEmpty()) return front;
        if (front.isEmpty()) return back;
        return front + " // " + back;
    }

    /** Package accessor so {@link SignWriteAction} can confirm a sign is really there. */
    static String signTextAtPublic(Minecraft mc, BlockPos bp) {
        return signTextAt(mc, bp);
    }

    /**
     * {@code sign <x> <y> <z> <line1>|<line2>|...} -- WRITE a sign.
     *
     * <p>QUEUED as an action on purpose. Doing it inline always failed silently: the right-click
     * and the update packet left in the SAME tick, so the update reached the server before it had
     * marked us the sign's designated editor, and a non-editor update is dropped with no error.
     * A socket command cannot wait for the round-trip -- its handler runs on the render thread, so
     * sleeping there would block the tick loop that processes the reply. See SignWriteAction.
     */
    private static String signCmd(Minecraft mc, String rest) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "sign: not in world";
        String[] t = rest.trim().split("\\s+", 4);
        if (t.length < 4) {
            return "ERR usage: sign <x> <y> <z> <text>   (split lines with | )";
        }
        Integer x = parseInt(t[0]);
        Integer y = parseInt(t[1]);
        Integer z = parseInt(t[2]);
        if (x == null || y == null || z == null) return "ERR usage: sign <x> <y> <z> <text>";

        java.util.List<String> lines = new ArrayList<>();
        for (String part : t[3].split("\\|", -1)) {
            String line = part.trim();
            if (line.length() > 15) line = line.substring(0, 15);
            lines.add(line);
            if (lines.size() == 4) break;
        }
        return AgentRuntime.queueSignWrite(
                new BlockPos(x.intValue(), y.intValue(), z.intValue()), lines);
    }

    /** {@code read [x y z]} -- sign text at a coordinate, or at the crosshair block. */
    private static String readCmd(Minecraft mc, String rest) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return "read: not in world";
        String[] t = rest.trim().split("\\s+");
        BlockPos bp = null;
        if (t.length >= 3 && !t[0].isEmpty()) {
            Integer x = parseInt(t[0]);
            Integer y = parseInt(t[1]);
            Integer z = parseInt(t[2]);
            if (x == null || y == null || z == null) return "ERR usage: read [x y z]";
            bp = new BlockPos(x.intValue(), y.intValue(), z.intValue());
        } else {
            HitResult hr = mc.hitResult;
            if (hr instanceof BlockHitResult bhr) bp = bhr.getBlockPos();
            else return "read: not looking at a block (aim at it, or 'read x y z')";
        }
        String txt = signTextAt(mc, bp);
        if (txt == null) {
            return "read: no sign at (" + bp.getX() + "," + bp.getY() + "," + bp.getZ() + ") -- it is "
                    + blockShort(mc.level.getBlockState(bp));
        }
        return "sign (" + bp.getX() + "," + bp.getY() + "," + bp.getZ() + "): " + txt;
    }

    /** Server interaction reach; past this the packet is rejected however well we aim. */
    private static final double USE_REACH = 4.4;

    /**
     * Interact with the block at an exact COORDINATE -- no crosshair, no aiming.
     *
     * <p>Why this exists (Master, 2026-08-01): every container chore was really an AIMING chore.
     * Opening a chest meant standing in exactly the right spot and landing a ray on it, and all
     * three parts of that were unreliable in practice -- {@code face x y z} threw 50-60 degree
     * pitch errors at close range and hit the neighbouring chest; {@code moveto} silently no-ops
     * any move under its 1-block stop radius and still reports "arrived"; and a chest block several
     * deep is only reachable from certain sides at all. Searching a 49-chest room cost ~150
     * commands and still did not find the target.
     *
     * <p>A synthesized {@link BlockHitResult} removes the ray from the equation entirely: `scan`
     * already hands back exact coordinates, so the caller can act on them directly. Proximity is
     * still real -- the SERVER enforces reach -- so an out-of-range target is reported honestly
     * with its distance rather than silently doing nothing.
     */
    static String useAt(Minecraft mc, BlockPos bp, String label) {
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) return label + ": not in world";
        Vec3 eye = p.getEyePosition();
        Vec3 center = Vec3.atCenterOf(bp);
        double dist = eye.distanceTo(center);
        if (dist > USE_REACH) {
            return String.format(java.util.Locale.ROOT,
                    "%s: (%d,%d,%d) is %.1f blocks away, past the %.1f server reach -- walk closer"
                    + " first (goto/moveto), then re-issue",
                    label, bp.getX(), bp.getY(), bp.getZ(), dist, USE_REACH);
        }
        if (mc.level.getBlockState(bp).isAir()) {
            return label + ": nothing at (" + bp.getX() + "," + bp.getY() + "," + bp.getZ() + ")";
        }
        // Look at it as a real player would -- some servers sanity-check the look vector -- then
        // hit the face that points back at us, so the click lands on a reachable side.
        MineControl.faceBlock(p, bp);
        Direction face = nearestFace(eye.x - center.x, eye.y - center.y, eye.z - center.z);
        BlockHitResult hit = new BlockHitResult(center, face, bp, false);
        mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
        p.swing(InteractionHand.MAIN_HAND);
        return "OK " + label + " " + blockShort(mc.level.getBlockState(bp))
                + " at (" + bp.getX() + "," + bp.getY() + "," + bp.getZ() + ")";
    }

    /**
     * Dominant-axis face for a direction vector. Hand-rolled on purpose: {@code Direction.getNearest}
     * changed signature across the supported range (double/float/Vec3 overloads come and go), and
     * the enum constants are the only part that is stable everywhere.
     */
    private static Direction nearestFace(double dx, double dy, double dz) {
        double ax = Math.abs(dx);
        double ay = Math.abs(dy);
        double az = Math.abs(dz);
        if (ax >= ay && ax >= az) return dx > 0 ? Direction.EAST : Direction.WEST;
        if (ay >= az) return dy > 0 ? Direction.UP : Direction.DOWN;
        return dz > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    /** {@code take output [radius]} -- bulk-collect furnace outputs (delegates to the agent queue). */
    private static String takeCmd(String rest) {
        String[] t = rest.trim().split("\\s+");
        if (t.length == 0 || t[0].isEmpty() || !t[0].equalsIgnoreCase("output")) {
            return "usage: take output [radius]   (empties every furnace in range, output slot only)";
        }
        return AgentRuntime.command("takeoutput" + (t.length > 1 ? " " + t[1] : ""));
    }

    /** {@code open <x> <y> <z>} -- open/use a container by coordinate. */
    private static String openAtCmd(Minecraft mc, String rest) {
        String[] t = rest.trim().split("\\s+");
        if (t.length < 3) return "ERR usage: open <x> <y> <z>   (coords come straight from 'scan')";
        Integer x = parseInt(t[0]);
        Integer y = parseInt(t[1]);
        Integer z = parseInt(t[2]);
        if (x == null || y == null || z == null) return "ERR usage: open <x> <y> <z>";
        String r = useAt(mc, new BlockPos(x.intValue(), y.intValue(), z.intValue()), "open");
        if (!r.startsWith("OK")) return r;
        return r + " -- run 'slots' to see its contents";
    }

    private static ObjectSelectionList<?> findList(Screen s) {
        for (GuiEventListener c : s.children()) {
            if (c instanceof ObjectSelectionList<?> l) return l;
        }
        return null;
    }

    /** {@code worlds} / {@code servers} / {@code entries} -- list the rows of WHATEVER selection
     *  list is on screen (saved worlds, saved servers, LAN games, ...). Generic on purpose. */
    private static String worlds(Minecraft mc) {
        Screen s = M1Compat.screen(mc);
        if (s == null) return "list: no screen open";
        ObjectSelectionList<?> list = findList(s);
        if (list == null) return "list: no selection list on " + s.getClass().getSimpleName();
        List<String> rows = listEntryLines(s);
        if (rows.isEmpty()) return "list: the selection list on " + s.getClass().getSimpleName()
                + " is empty (0 rows)";
        StringBuilder b = new StringBuilder("list rows: " + rows.size()
                + "  (use 'select <n>', then click the button that becomes active)\n");
        for (int i = 0; i < rows.size(); i++) {
            b.append("  <").append(i).append("> ").append(rows.get(i)).append("\n");
        }
        return trim(b);
    }

    /** {@code select <n>} -- select row n of the on-screen selection list. Buttons that depend on a
     *  selection (Join Server, Play Selected World, Edit, Delete) go ACTIVE once a row is selected;
     *  the reply re-describes so the caller sees which. */
    private static String selectCmd(Minecraft mc, String rest) {
        Screen s = M1Compat.screen(mc);
        if (s == null) return "select: no screen open";
        ObjectSelectionList<?> list = findList(s);
        if (list == null) return "select: no selection list on " + s.getClass().getSimpleName();
        Integer idx = parseInt(firstTok(rest));
        if (idx == null) return "ERR usage: select <row>  (see 'describe' / 'worlds' for rows)";
        List<String> rows = listEntryLines(s);
        if (!selectEntry(list, idx.intValue())) {
            return "select: no row " + idx + " (have 0.." + (rows.size() - 1) + ")";
        }
        return "OK selected <" + idx + "> " + (idx < rows.size() ? rows.get(idx) : "")
                + "\n--- now ---\n" + describe(mc);
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
        // mojmap runtimes (26+/NeoForge/Forge) report real names; keep them verbatim.
        if (!t.isEmpty() && !t.startsWith("class_")) return t;
        // pre-26 Fabric reports intermediary names (class_5676 ...) -> classify by instanceof, which
        // Loom remaps, so the controller still sees a readable type.
        if (w instanceof EditBox) return "EditBox";
        if (w instanceof CycleButton) return "CycleButton";
        if (w instanceof StringWidget) return "StringWidget";
        if (w instanceof AbstractButton) return "Button";
        return t.isEmpty() ? "Widget" : t;
    }

    // A display-only text widget (its string is a label for a neighbouring control, not a control).
    // Uses instanceof (Loom remaps the class reference to the runtime mapping) -- a getSimpleName()
    // string compare would FAIL on pre-26 Fabric where the runtime name is intermediary (class_7842).
    private static boolean isLabelWidget(AbstractWidget w) {
        return w instanceof StringWidget;
    }

    // The label that describes a control: nearest label-widget on the SAME ROW to its LEFT, else the
    // nearest label DIRECTLY ABOVE. Uses only stable geometry (no version-specific API) so it works on
    // every loader/version. Returns null when nothing plausible is adjacent.
    private static String nearestLabel(AbstractWidget w, List<AbstractWidget> all) {
        int wcy = w.getY() + w.getHeight() / 2;
        AbstractWidget best = null;
        int bestGap = Integer.MAX_VALUE;
        for (AbstractWidget c : all) {
            if (c == w || !isLabelWidget(c)) continue;
            int ccy = c.getY() + c.getHeight() / 2;
            if (Math.abs(ccy - wcy) <= 10 && (c.getX() + c.getWidth()) <= w.getX() + 2) {
                int gap = w.getX() - (c.getX() + c.getWidth());
                if (gap >= 0 && gap < bestGap) { bestGap = gap; best = c; }
            }
        }
        if (best == null) {
            int bestDy = Integer.MAX_VALUE;
            for (AbstractWidget c : all) {
                if (c == w || !isLabelWidget(c)) continue;
                boolean xOverlap = c.getX() < w.getX() + w.getWidth() && (c.getX() + c.getWidth()) > w.getX();
                int dy = w.getY() - (c.getY() + c.getHeight());
                if (xOverlap && dy >= 0 && dy < 16 && dy < bestDy) { bestDy = dy; best = c; }
            }
        }
        return (best != null && best.getMessage() != null) ? best.getMessage().getString() : null;
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
                keyboardType().getOrCreate(sym);
            if (!used.contains(k.getName())) return k;
        }
        return keyboardType().getOrCreate(313);
    }

    // KEYSYM (<=26.2) was renamed to KEYBOARD in the 26.3-snapshot-3 input refactor (SCANCODE also dropped).
    // Resolve by name so this compiles + runs on both without a version facade (rare path; 2-element enum scan).
    private static com.mojang.blaze3d.platform.InputConstants.Type keyboardType() {
        for (com.mojang.blaze3d.platform.InputConstants.Type t : com.mojang.blaze3d.platform.InputConstants.Type.values()) {
            String n = t.name();
            if (n.equals("KEYBOARD") || n.equals("KEYSYM")) return t;
        }
        return com.mojang.blaze3d.platform.InputConstants.Type.values()[0];
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
        // D20: name becomes a filesystem path under screenshots/; reject path-escaping input
        // (separators or "..") so a wire value cannot traverse out of the screenshots directory.
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            return "ERR screenshot: name must be a plain filename (no path separators or ..)";
        }
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

    @SuppressWarnings("deprecation") // Forge 1.20.1-only: BuiltInRegistries access deprecated there (ForgeRegistries); vanilla registry is correct + cross-loader
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







