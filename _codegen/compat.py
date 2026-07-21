# M1-Legacy cross-version drift brain (Cog).
#
# Sibling of the modern M1 _codegen/compat.py, but for the LEGACY line (pre-Flattening MC on
# Legacy Fabric). It supplies the version-specific bodies for the single MC-facing facade
# Platform.java. Everything else (M1Server, ScreenOps, DTOs) is MC-agnostic and lives in
# cog_sources/shared -- copied verbatim per cell, no cog.
#
# Runtime-mapping rule (why DIRECT, not reflection): Legacy Fabric runs INTERMEDIARY names at
# runtime, so reflecting MC members by their yarn string name would miss. We emit DIRECT compiled
# access (loom remaps yarn->intermediary), plus an accesswidener for the few protected seams. See
# LegacyFabric/<ver>/src/main/resources/m1.accesswidener.
#
# To add a legacy version: add a cell to matrix.json and, where its GUI shape differs from 1.8.9,
# branch the body functions below on V(mcver). ASCII only (HARD rule); bodies use plain -> in text.

def V(ver):
    """'1.8.9' -> (1,8,9); '1.12.2' -> (1,12,2). Strips any -suffix, pads to 3."""
    core = ver.split("-")[0]
    parts = [int(x) for x in core.split(".")]
    while len(parts) < 3:
        parts.append(0)
    return tuple(parts)

# ---- drift boundaries ----
# Legacy Fabric's Yarn mapping (build 604 for 1.8.9) uses MODERN-style names: Screen / ButtonWidget
# / TextFieldWidget / MinecraftClient. The 1.8.9 bodies below are written against those. When a
# second legacy version is added whose yarn names differ, gate here.
def yarn_modern_gui(v):
    return v >= (1, 8, 9)   # 1.8.9 Legacy-Fabric yarn uses Screen/ButtonWidget/TextFieldWidget names

# ---- imports Platform needs (MC classes only; java.* stay plain in the file) ----
def platform_imports(ver):
    v = V(ver)
    if yarn_modern_gui(v):
        return ["net.minecraft.client.MinecraftClient",
                "net.minecraft.client.gui.screen.Screen",
                "net.minecraft.client.gui.widget.ButtonWidget",
                "net.minecraft.client.gui.widget.TextFieldWidget"]
    raise Exception("no Platform imports for mcver=" + ver)

# ---- Platform method bodies (each returns the lines placed between the cog markers) ----

def p_dispatch(ver):   # onMainThread(Runnable r)
    if yarn_modern_gui(V(ver)):
        # MinecraftClient implements ThreadExecutor; submit(Runnable) runs it on the render thread
        # next tick (the client drains its task queue each frame) -- the mc.execute equivalent.
        return ["        MinecraftClient.getInstance().submit(r);"]
    raise Exception("no p_dispatch for " + ver)

def p_screen(ver):     # Object screen()
    if yarn_modern_gui(V(ver)):
        return ["        return MinecraftClient.getInstance().currentScreen;"]
    raise Exception("no p_screen for " + ver)

def p_screen_size(ver):  # int[] screenSize(Object s)
    if yarn_modern_gui(V(ver)):
        return ["        Screen sc = (Screen) s;",
                "        return new int[]{ sc.width, sc.height };"]
    raise Exception("no p_screen_size for " + ver)

def p_buttons(ver):    # List<WidgetInfo> buttons(Object s)
    if yarn_modern_gui(V(ver)):
        return ["        Screen sc = (Screen) s;",
                "        List<WidgetInfo> out = new ArrayList<WidgetInfo>();",
                "        if (sc.buttons != null) {",                      # AW-widened
                "            for (ButtonWidget b : sc.buttons) {",
                "                if (b == null) continue;",
                "                String label = b.message == null ? b.getClass().getSimpleName() : b.message;",
                "                out.add(new WidgetInfo(b.id, label, b.x, b.y, b.getWidth(), b.height, b.active, b.visible));",  # b.height AW-widened
                "            }",
                "        }",
                "        return out;"]
    raise Exception("no p_buttons for " + ver)

def p_click(ver):      # String click(Object s, int id)
    if yarn_modern_gui(V(ver)):
        return ["        Screen sc = (Screen) s;",
                "        if (sc.buttons == null) return \"ERR screen has no buttons\";",
                "        for (ButtonWidget b : sc.buttons) {",
                "            if (b != null && b.id == id) {",
                "                if (!b.visible) return \"ERR button \" + id + \" not visible\";",
                "                if (!b.active) return \"ERR button \" + id + \" not active\";",
                "                int cx = b.x + b.getWidth() / 2;",
                "                int cy = b.y + b.height / 2;",
                "                sc.mouseClicked(cx, cy, 0);",            # AW-widened; 0 = left (LWJGL2)
                "                sc.mouseReleased(cx, cy, 0);",           # AW-widened
                "                return \"OK clicked \" + id + \" (\\\"\" + (b.message == null ? \"\" : b.message) + \"\\\")\";",
                "            }",
                "        }",
                "        return \"ERR no button with id \" + id;"]
    raise Exception("no p_click for " + ver)

def p_textfields(ver):  # List<TfInfo> textFields(Object s)
    if yarn_modern_gui(V(ver)):
        return ["        Screen sc = (Screen) s;",
                "        List<TfInfo> out = new ArrayList<TfInfo>();",
                "        Class<?> c = sc.getClass();",
                "        while (c != null && c != Object.class) {",
                "            for (Field f : c.getDeclaredFields()) {",
                "                if (TextFieldWidget.class.isAssignableFrom(f.getType())) {",
                "                    try {",
                "                        f.setAccessible(true);",
                "                        Object v = f.get(sc);",
                "                        if (v instanceof TextFieldWidget) {",
                "                            TextFieldWidget tf = (TextFieldWidget) v;",
                "                            out.add(new TfInfo(f.getName(), tf.getText(), tf.isFocused(), tf.isVisible()));",
                "                        }",
                "                    } catch (Throwable ignored) {}",
                "                }",
                "            }",
                "            c = c.getSuperclass();",
                "        }",
                "        return out;"]
    raise Exception("no p_textfields for " + ver)

def p_set_text(ver):   # String setText(Object s, String id, String text)
    if yarn_modern_gui(V(ver)):
        return ["        Screen sc = (Screen) s;",
                "        Class<?> c = sc.getClass();",
                "        while (c != null && c != Object.class) {",
                "            for (Field f : c.getDeclaredFields()) {",
                "                if (TextFieldWidget.class.isAssignableFrom(f.getType()) && f.getName().equals(id)) {",
                "                    try {",
                "                        f.setAccessible(true);",
                "                        Object v = f.get(sc);",
                "                        if (v instanceof TextFieldWidget) {",
                "                            TextFieldWidget tf = (TextFieldWidget) v;",
                "                            tf.setFocused(true);",
                "                            tf.setText(\"\");",
                "                            tf.write(text == null ? \"\" : text);",
                "                            return \"OK typed into \" + id;",
                "                        }",
                "                    } catch (Throwable t) { return \"ERR \" + t.getClass().getSimpleName(); }",
                "                }",
                "            }",
                "            c = c.getSuperclass();",
                "        }",
                "        return \"ERR no text field named \" + id;"]
    raise Exception("no p_set_text for " + ver)

def p_key(ver):        # String key(Object s, String name)
    if yarn_modern_gui(V(ver)):
        # LWJGL2 key codes (org.lwjgl.input.Keyboard): ESCAPE=1 RETURN=28 TAB=15 BACK=14
        # UP=200 DOWN=208 LEFT=203 RIGHT=205. Drive the screen's keyPressed(char, code).
        return ["        Screen sc = (Screen) s;",
                "        int code; char ch = 0;",
                "        String n = name == null ? \"\" : name.toLowerCase();",
                "        if (n.equals(\"escape\")) { code = 1; }",
                "        else if (n.equals(\"enter\") || n.equals(\"return\")) { code = 28; ch = '\\r'; }",
                "        else if (n.equals(\"tab\")) { code = 15; ch = '\\t'; }",
                "        else if (n.equals(\"backspace\")) { code = 14; ch = 8; }",
                "        else if (n.equals(\"up\")) { code = 200; }",
                "        else if (n.equals(\"down\")) { code = 208; }",
                "        else if (n.equals(\"left\")) { code = 203; }",
                "        else if (n.equals(\"right\")) { code = 205; }",
                "        else { return \"ERR unknown key name: \" + name; }",
                "        sc.keyPressed(ch, code);",                        # AW-widened
                "        return \"OK key \" + name;"]
    raise Exception("no p_key for " + ver)

if __name__ == "__main__":
    for ver in ["1.8.9"]:
        print("== %s ==" % ver)
        print("  screen :", p_screen(ver)[0].strip())
        print("  click  :", p_click(ver)[8].strip())
        print("  key    :", p_key(ver)[-2].strip())

def p_screenshot(ver):  # String screenshot(String name)
    if yarn_modern_gui(V(ver)):
        return [
          "        if (name != null && (name.contains(\"/\") || name.contains(\"..\"))) return \"ERR bad name\";",
          "        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();",
          "        net.minecraft.text.Text t;",
          "        if (name != null && !name.isEmpty()) {",
          "            t = net.minecraft.client.util.ScreenshotUtils.saveScreenshot(mc.runDirectory, name, mc.width, mc.height, mc.getFramebuffer());",
          "        } else {",
          "            t = net.minecraft.client.util.ScreenshotUtils.saveScreenshot(mc.runDirectory, mc.width, mc.height, mc.getFramebuffer());",
          "        }",
          "        return t == null ? \"ERR screenshot returned null\" : t.asUnformattedString();"]
    raise Exception("no p_screenshot for " + ver)

def p_where(ver):
    if yarn_modern_gui(V(ver)):
        return [
          "        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();",
          "        net.minecraft.entity.player.ClientPlayerEntity p = mc.player;",
          "        if (p == null) return \"ERR not in world\";",
          "        return String.format(java.util.Locale.ROOT, \"pos %.2f,%.2f,%.2f yaw=%.1f pitch=%.1f dim=%d\", p.x, p.y, p.z, p.yaw, p.pitch, p.dimension);"]
    raise Exception("no p_where for " + ver)

def p_state(ver):
    if yarn_modern_gui(V(ver)):
        return [
          "        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();",
          "        net.minecraft.entity.player.ClientPlayerEntity p = mc.player;",
          "        if (p == null) return \"ERR not in world\";",
          "        net.minecraft.world.level.LevelInfo.GameMode gm = mc.interactionManager != null ? mc.interactionManager.getCurrentGameMode() : null;",
          "        return String.format(java.util.Locale.ROOT, \"health=%.1f/%.1f food=%d xpLevel=%d gamemode=%s\", p.getHealth(), p.getMaxHealth(), p.getHungerManager().getFoodLevel(), p.experienceLevel, gm==null?\"?\":gm.getName());"]
    raise Exception("no p_state for " + ver)

def p_inv(ver):
    if yarn_modern_gui(V(ver)):
        return [
          "        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();",
          "        net.minecraft.entity.player.ClientPlayerEntity p = mc.player;",
          "        if (p == null) return \"ERR not in world\";",
          "        net.minecraft.entity.player.PlayerInventory inv = p.inventory;",
          "        StringBuilder sb = new StringBuilder();",
          "        sb.append(\"selectedSlot=\").append(inv.selectedSlot);",
          "        for (int i = 0; i < inv.main.length; i++) {",
          "            net.minecraft.item.ItemStack st = inv.main[i];",
          "            if (st == null) continue;",
          "            sb.append(\"\\nslot \").append(i).append(\" \").append(st.getItem().getTranslationKey()).append(\" x\").append(st.count).append(\" dmg=\").append(st.getDamage());",
          "        }",
          "        return sb.toString();"]
    raise Exception("no p_inv for " + ver)

def p_openinv(ver):
    if yarn_modern_gui(V(ver)):
        return [
          "        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();",
          "        if (mc.player == null) return \"ERR not in world\";",
          "        mc.setScreen(new net.minecraft.client.gui.screen.ingame.SurvivalInventoryScreen(mc.player));",
          "        return \"OK opened inventory\";"]
    raise Exception("no p_openinv for " + ver)

def p_slots(ver):
    if yarn_modern_gui(V(ver)):
        return [
          "        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();",
          "        if (!(mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen)) return \"ERR no container screen open\";",
          "        net.minecraft.screen.ScreenHandler h = ((net.minecraft.client.gui.screen.ingame.HandledScreen) mc.currentScreen).screenHandler;",
          "        StringBuilder sb = new StringBuilder();",
          "        sb.append(\"syncId=\").append(h.syncId).append(\" slots=\").append(h.slots.size());",
          "        for (int i = 0; i < h.slots.size(); i++) {",
          "            net.minecraft.item.ItemStack st = h.slots.get(i).getStack();",
          "            if (st == null) continue;",
          "            sb.append(\"\\nslot \").append(i).append(\" \").append(st.getItem().getTranslationKey()).append(\" x\").append(st.count);",
          "        }",
          "        return sb.toString();"]
    raise Exception("no p_slots for " + ver)

def p_slotclick(ver):
    if yarn_modern_gui(V(ver)):
        return [
          "        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();",
          "        if (mc.player == null) return \"ERR not in world\";",
          "        if (!(mc.currentScreen instanceof net.minecraft.client.gui.screen.ingame.HandledScreen)) return \"ERR no container screen open\";",
          "        if (mc.interactionManager == null) return \"ERR no interaction manager\";",
          "        net.minecraft.screen.ScreenHandler h = ((net.minecraft.client.gui.screen.ingame.HandledScreen) mc.currentScreen).screenHandler;",
          "        mc.interactionManager.clickSlot(h.syncId, slotId, button, mode, mc.player);",
          "        return \"OK slot \" + slotId + \" button=\" + button + \" mode=\" + mode;"]
    raise Exception("no p_slotclick for " + ver)


def p_list_entries(ver):  # List<WidgetInfo> listEntries(Object s)  -- world/server selection lists
    if yarn_modern_gui(V(ver)):
        return [
          "        List<WidgetInfo> out = new ArrayList<WidgetInfo>();",
          "        try {",
          "            if (s instanceof net.minecraft.client.gui.screen.world.SelectWorldScreen) {",
          "                Class<?> c = s.getClass();",
          "                while (c != null && c != Object.class) {",
          "                    for (Field f : c.getDeclaredFields()) {",
          "                        if (java.util.List.class.isAssignableFrom(f.getType())) {",
          "                            f.setAccessible(true);",
          "                            Object v = f.get(s);",
          "                            if (v instanceof java.util.List) {",
          "                                java.util.List<?> lst = (java.util.List<?>) v;",
          "                                for (int i = 0; i < lst.size(); i++) {",
          "                                    Object e = lst.get(i);",
          "                                    if (e instanceof net.minecraft.world.level.storage.LevelSummary) {",
          "                                        net.minecraft.world.level.storage.LevelSummary ls = (net.minecraft.world.level.storage.LevelSummary) e;",
          "                                        out.add(new WidgetInfo(i, ls.getDisplayName() + \" (\" + ls.getFileName() + \")\", 0, 0, 0, 0, true, true));",
          "                                    }",
          "                                }",
          "                                if (!out.isEmpty()) return out;",
          "                            }",
          "                        }",
          "                    }",
          "                    c = c.getSuperclass();",
          "                }",
          "                return out;",
          "            }",
          "            if (s instanceof net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen) {",
          "                net.minecraft.client.option.ServerList sl = ((net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen) s).getServerList();",
          "                if (sl != null) {",
          "                    for (int i = 0; i < sl.size(); i++) {",
          "                        net.minecraft.client.network.ServerInfo si = sl.get(i);",
          "                        if (si == null) continue;",
          "                        out.add(new WidgetInfo(i, si.name + \" [\" + si.address + \"]\", 0, 0, 0, 0, true, true));",
          "                    }",
          "                }",
          "                return out;",
          "            }",
          "        } catch (Throwable ignored) {}",
          "        return out;"]
    raise Exception("no p_list_entries for " + ver)


def p_select_entry(ver):  # String selectEntry(Object s, int index)  -- join world / connect server
    if yarn_modern_gui(V(ver)):
        return [
          "        try {",
          "            if (s instanceof net.minecraft.client.gui.screen.world.SelectWorldScreen) {",
          "                ((net.minecraft.client.gui.screen.world.SelectWorldScreen) s).joinWorld(index);",
          "                return \"OK joining world \" + index;",
          "            }",
          "            if (s instanceof net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen) {",
          "                net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen ms = (net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen) s;",
          "                ms.selectEntry(index);",
          "                ms.connect();",
          "                return \"OK connecting to server \" + index;",
          "            }",
          "        } catch (Throwable t) { return \"ERR \" + t.getClass().getSimpleName(); }",
          "        return \"ERR screen has no selectable list\";"]
    raise Exception("no p_select_entry for " + ver)


def p_move(ver):  # String move(String dir, int ms)  -- hold a movement keybind; live tick applies it
    if yarn_modern_gui(V(ver)):
        return [
          "        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();",
          "        if (mc.player == null) return \"ERR not in world\";",
          "        net.minecraft.client.option.GameOptions o = mc.options;",
          "        net.minecraft.client.option.KeyBinding kb;",
          "        String dl = dir == null ? \"\" : dir.toLowerCase();",
          "        if (dl.equals(\"forward\")) kb = o.forwardKey;",
          "        else if (dl.equals(\"back\")) kb = o.backKey;",
          "        else if (dl.equals(\"left\")) kb = o.leftKey;",
          "        else if (dl.equals(\"right\")) kb = o.rightKey;",
          "        else if (dl.equals(\"jump\")) kb = o.jumpKey;",
          "        else if (dl.equals(\"sneak\")) kb = o.sneakKey;",
          "        else if (dl.equals(\"sprint\")) kb = o.sprintKey;",
          "        else return \"ERR unknown dir: \" + dir;",
          "        final int code = kb.getCode();",
          "        final int hold = ms <= 0 ? 500 : ms;",
          "        net.minecraft.client.option.KeyBinding.setKeyPressed(code, true);",
          "        new Thread(new Runnable() { public void run() {",
          "            try { Thread.sleep(hold); } catch (InterruptedException ie) {}",
          "            net.minecraft.client.MinecraftClient.getInstance().submit(new Runnable() { public void run() {",
          "                net.minecraft.client.option.KeyBinding.setKeyPressed(code, false);",
          "            } });",
          "        } }).start();",
          "        return \"OK move \" + dl + \" \" + hold + \"ms\";"]
    raise Exception("no p_move for " + ver)


def p_look(ver):  # String look(float yaw, float pitch)
    if yarn_modern_gui(V(ver)):
        return [
          "        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();",
          "        if (mc.player == null) return \"ERR not in world\";",
          "        float pp = pitch; if (pp > 90f) pp = 90f; if (pp < -90f) pp = -90f;",
          "        mc.player.yaw = yaw;",
          "        mc.player.pitch = pp;",
          "        mc.player.prevYaw = yaw;",
          "        mc.player.prevPitch = pp;",
          "        return String.format(java.util.Locale.ROOT, \"OK look yaw=%.1f pitch=%.1f\", yaw, pp);"]
    raise Exception("no p_look for " + ver)


def p_stopkeys(ver):  # String stopKeys()  -- release every held key
    if yarn_modern_gui(V(ver)):
        return [
          "        net.minecraft.client.option.KeyBinding.releaseAllKeys();",
          "        return \"OK released all keys\";"]
    raise Exception("no p_stopkeys for " + ver)


def _hold_key(keyexpr, label):
    return [
      "        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();",
      "        if (mc.player == null) return \"ERR not in world\";",
      "        final int code = " + keyexpr + ".getCode();",
      "        final int hold = ms <= 0 ? 150 : ms;",
      "        net.minecraft.client.option.KeyBinding.setKeyPressed(code, true);",
      "        net.minecraft.client.option.KeyBinding.onKeyPressed(code);",
      "        new Thread(new Runnable() { public void run() {",
      "            try { Thread.sleep(hold); } catch (InterruptedException ie) {}",
      "            net.minecraft.client.MinecraftClient.getInstance().submit(new Runnable() { public void run() {",
      "                net.minecraft.client.option.KeyBinding.setKeyPressed(code, false);",
      "            } });",
      "        } }).start();",
      "        return \"OK " + label + " \" + hold + \"ms\";"]

def p_attack(ver):  # String attack(int ms) -- direct attackEntity if a mob is targeted, else keybind swing
    if yarn_modern_gui(V(ver)):
        return [
          "        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();",
          "        if (mc.player == null) return \"ERR not in world\";",
          "        if (mc.targetedEntity != null && mc.interactionManager != null) {",
          "            mc.interactionManager.attackEntity(mc.player, mc.targetedEntity);",
          "            return \"OK attacked entity \" + mc.targetedEntity.getClass().getSimpleName();",
          "        }",
          "        if (mc.world != null && mc.interactionManager != null) {",
          "            net.minecraft.entity.Entity best = null; double bestD = 4.0;",
          "            for (net.minecraft.entity.Entity e : mc.world.loadedEntities) {",
          "                if (e == mc.player || !(e instanceof net.minecraft.entity.LivingEntity)) continue;",
          "                double d = e.distanceTo(mc.player);",
          "                if (d < bestD) { bestD = d; best = e; }",
          "            }",
          "            if (best != null) {",
          "                mc.interactionManager.attackEntity(mc.player, best);",
          "                return String.format(java.util.Locale.ROOT, \"OK attacked nearest %s d=%.2f\", best.getClass().getSimpleName(), bestD);",
          "            }",
          "        }",
          "        final int code = mc.options.attackKey.getCode();",
          "        final int hold = ms <= 0 ? 150 : ms;",
          "        net.minecraft.client.option.KeyBinding.setKeyPressed(code, true);",
          "        net.minecraft.client.option.KeyBinding.onKeyPressed(code);",
          "        new Thread(new Runnable() { public void run() {",
          "            try { Thread.sleep(hold); } catch (InterruptedException ie) {}",
          "            net.minecraft.client.MinecraftClient.getInstance().submit(new Runnable() { public void run() {",
          "                net.minecraft.client.option.KeyBinding.setKeyPressed(code, false);",
          "            } });",
          "        } }).start();",
          "        return \"OK attack swing \" + hold + \"ms\";"]
    raise Exception("no p_attack for " + ver)

def p_use(ver):  # String use(int ms) -- hold useKey (interact/place)
    if yarn_modern_gui(V(ver)):
        return _hold_key("mc.options.useKey", "use")
    raise Exception("no p_use for " + ver)

def p_target(ver):  # String target() -- report the crosshair hit (entity or block)
    if yarn_modern_gui(V(ver)):
        return [
          "        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();",
          "        if (mc.player == null) return \"ERR not in world\";",
          "        net.minecraft.entity.Entity te = mc.targetedEntity;",
          "        if (te != null) {",
          "            return String.format(java.util.Locale.ROOT, \"entity %s pos %.2f,%.2f,%.2f\", te.getClass().getSimpleName(), te.x, te.y, te.z);",
          "        }",
          "        net.minecraft.util.hit.BlockHitResult r = mc.result;",
          "        if (r != null && r.getBlockPos() != null) {",
          "            net.minecraft.util.math.BlockPos bp = r.getBlockPos();",
          "            return \"block \" + bp.getX() + \",\" + bp.getY() + \",\" + bp.getZ() + \" type=\" + r.type;",
          "        }",
          "        return \"none\";"]
    raise Exception("no p_target for " + ver)


def p_mine(ver):  # String mine(int ms) -- direct block breaking + inline diagnostics
    if yarn_modern_gui(V(ver)):
        return [
          "        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();",
          "        if (mc.player == null || mc.world == null) return \"ERR not in world\";",
          "        final net.minecraft.client.network.ClientPlayerInteractionManager im = mc.interactionManager;",
          "        if (im == null) return \"ERR no interaction manager\";",
          "        net.minecraft.util.hit.BlockHitResult r = mc.result;",
          "        final net.minecraft.util.math.BlockPos pos;",
          "        final net.minecraft.util.math.Direction dir;",
          "        if (r != null && r.getBlockPos() != null && r.type != null && r.type.toString().equals(\"BLOCK\")) {",
          "            pos = r.getBlockPos(); dir = r.direction;",
          "        } else {",
          "            pos = new net.minecraft.util.math.BlockPos(mc.player.x, mc.player.y - 1.0, mc.player.z);",
          "            dir = net.minecraft.util.math.Direction.UP;",
          "        }",
          "        final int hold = ms <= 0 ? 3000 : ms;",
          "        String diag;",
          "        try {",
          "            net.minecraft.block.BlockState bs = mc.world.getBlockState(pos);",
          "            String bn = bs == null || bs.getBlock() == null ? \"null\" : bs.getBlock().getTranslationKey();",
          "            boolean air = mc.world.isAir(pos);",
          "            boolean ab = im.attackBlock(pos, dir);",
          "            boolean up1 = im.updateBlockBreakingProgress(pos, dir);",
          "            diag = \"block=\" + bn + \" air=\" + air + \" attackBlock=\" + ab + \" updateProgress=\" + up1;",
          "        } catch (Throwable t) {",
          "            return \"ERR mine-diag \" + t.getClass().getName() + \": \" + t.getMessage();",
          "        }",
          "        new Thread(new Runnable() { public void run() {",
          "            long end = System.currentTimeMillis() + hold;",
          "            while (System.currentTimeMillis() < end) {",
          "                net.minecraft.client.MinecraftClient.getInstance().submit(new Runnable() { public void run() {",
          "                    try { im.updateBlockBreakingProgress(pos, dir); } catch (Throwable ignored) {}",
          "                } });",
          "                try { Thread.sleep(50); } catch (InterruptedException ie) { break; }",
          "            }",
          "            net.minecraft.client.MinecraftClient.getInstance().submit(new Runnable() { public void run() {",
          "                im.cancelBlockBreaking();",
          "            } });",
          "        } }).start();",
          "        return \"OK mining \" + pos.getX() + \",\" + pos.getY() + \",\" + pos.getZ() + \" for \" + hold + \"ms [\" + diag + \"]\";"]
    raise Exception("no p_mine for " + ver)


def p_nearby(ver):  # String nearby(double radius) -- list entities near the player
    if yarn_modern_gui(V(ver)):
        return [
          "        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();",
          "        if (mc.player == null || mc.world == null) return \"ERR not in world\";",
          "        double R = radius <= 0 ? 8.0 : radius;",
          "        StringBuilder sb = new StringBuilder();",
          "        int n = 0;",
          "        for (net.minecraft.entity.Entity e : mc.world.loadedEntities) {",
          "            if (e == mc.player) continue;",
          "            double d = e.distanceTo(mc.player);",
          "            if (d > R) continue;",
          "            n++;",
          "            sb.append(\"\\n\").append(e.getClass().getSimpleName());",
          "            if (e instanceof net.minecraft.entity.LivingEntity) {",
          "                sb.append(\" hp=\").append(String.format(java.util.Locale.ROOT, \"%.1f\", ((net.minecraft.entity.LivingEntity) e).getHealth()));",
          "            }",
          "            sb.append(String.format(java.util.Locale.ROOT, \" pos %.1f,%.1f,%.1f d=%.2f\", e.x, e.y, e.z, d));",
          "        }",
          "        return \"entities within \" + R + \": \" + n + sb.toString();"]
    raise Exception("no p_nearby for " + ver)
