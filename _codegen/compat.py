# M1 cross-version drift brain (Cog).
#
# Why this exists: M1's version drift was first solved with reflection-by-mojmap-name *Compat
# facades. Those work where the RUNTIME is mojmap (MC 26+ all loaders; NeoForge; Forge/FG6 1.20.1+),
# but FAIL on pre-26 FABRIC -- there the runtime is INTERMEDIARY (field_xxxx/method_xxxx), so a
# mojmap string lookup misses (M1Compat.screen returned null on 1.20.6 -> describe broke). DIRECT
# compiled access works on every runtime (Loom remaps mojmap->intermediary on Fabric; native on
# mojmap loaders). So we drop to (b) Cog per the fall-through (facade -> Cog -> segregated): each
# drifting access is emitted as DIRECT code, version-selected here, by a thin Cog block in the
# *Compat class. The build runs `cog -r -D mcver=<ver> -D loader=<loader>` per cell.
#
# ver string examples: "1.20", "1.20.6", "1.21.5", "26.1", "26.2", "26.3". loader in {fabric,neoforge,forge}.
# ASCII only (HARD rule). Bodies reference the *Compat method's own parameter names.

def V(ver):
    """Parse an M1 build version string to a comparable int tuple. '26.2'->(26,2), '1.21.5'->(1,21,5).
    Strips any '-snapshot'/'-pre'/'-rc' suffix."""
    core = ver.split("-")[0]
    return tuple(int(x) for x in core.split("."))

# ---- drift boundaries (verified this session unless marked TODO) ----
def is26(v):            return v[0] == 26
def gui_screen(v):      return v >= (26, 2)          # 26.2 moved screen/setScreen onto Minecraft.gui
def gameRenderer_rt(v): return v >= (26, 2)          # 26.2 moved the render target onto GameRenderer
def zombie_registry(v): return v >= (26, 2)          # 26.2 dropped EntityType.ZOMBIE field for registry getValue
def selected_accessor(v): return v >= (1, 21, 5)     # 1.21.5 made Inventory.selected private -> get/setSelectedSlot()
def mouse_event(v):     return v >= (1, 21, 9)       # MouseButtonEvent record + mouseClicked(ev,bool): 1.21.9+ (re-intermediation) THROUGH 26
def container_input(v): return v[0] == 26            # handleContainerInput+ContainerInput @26 vs handleInventoryMouseClick+ClickType
def shot_int_arg(v):    return v >= (1, 21, 8)       # 5-arg grab(...,int downscale,...): 1.21.8+ THROUGH 26
def spawn_reason_enum(v):
    # MobSpawnType -> EntitySpawnReason. CONFIRMED via deobf: MobSpawnType through 1.21.1; EntitySpawnReason
    # from 1.21.2 (the 1.21.2 API-churn version). 26.x all use EntitySpawnReason.
    return v >= (1, 21, 2)
def has_required_path_length(v):
    # PathNavigation.setRequiredPathLength CONFIRMED via deobf: absent through 1.21.1; present from 1.21.2.
    # (Same 1.21.2 boundary as the spawn-reason rename.) On older versions, omit -> default path length.
    return v >= (1, 21, 2)

# ---- imports the shared *Compat files need, by version (emitted into a cog imports block) ----
def imports(loader, ver):
    v = V(ver); out = []
    if mouse_event(v):
        out += ["net.minecraft.client.input.MouseButtonEvent",
                "net.minecraft.client.input.MouseButtonInfo"]
    out += ["net.minecraft.world.inventory.ContainerInput"] if container_input(v) \
        else ["net.minecraft.world.inventory.ClickType"]
    if zombie_registry(v):
        out += ["net.minecraft.core.registries.BuiltInRegistries",
                "net.minecraft.world.entity.EntityTypeIds"]
    out += ["net.minecraft.world.entity.EntitySpawnReason"] if spawn_reason_enum(v) \
        else ["net.minecraft.world.entity.MobSpawnType"]
    return sorted(set(out))

# ---- per-method DIRECT bodies (each returns the lines that go between the cog markers) ----

def screen_get(ver):          # M1Compat.screen(Minecraft mc) -> Screen
    return ["return mc.gui.screen();"] if gui_screen(V(ver)) else ["return mc.screen;"]

def screen_set(ver):          # M1Compat.setScreen(Minecraft mc, Screen screen)
    return ["mc.gui.setScreen(screen);"] if gui_screen(V(ver)) else ["mc.setScreen(screen);"]

def render_target(ver):       # M1Compat.mainRenderTarget(Minecraft mc) -> RenderTarget
    return ["return mc.gameRenderer.mainRenderTarget();"] if gameRenderer_rt(V(ver)) \
        else ["return mc.getMainRenderTarget();"]

def zombie(ver):              # M1Compat.zombie() -> EntityType<?>
    if zombie_registry(V(ver)):
        return ["return BuiltInRegistries.ENTITY_TYPE.getValue(EntityTypeIds.ZOMBIE);"]
    return ["return EntityType.ZOMBIE;"]

def selected_get(ver):        # InventoryCompat.getSelected(Inventory inv) -> int
    return ["return inv.getSelectedSlot();"] if selected_accessor(V(ver)) else ["return inv.selected;"]

def selected_set(ver):        # InventoryCompat.setSelected(Inventory inv, int slot)
    return ["inv.setSelectedSlot(slot);"] if selected_accessor(V(ver)) else ["inv.selected = slot;"]

def container_click(ver):     # ContainerCompat.click(mc, containerId, slot, button, Mode mode)
    # Mode is M1's enum {PICKUP,QUICK_MOVE,SWAP}; map to the per-era enum by name.
    v = V(ver)
    enum = "ContainerInput" if container_input(v) else "ClickType"
    meth = "handleContainerInput" if container_input(v) else "handleInventoryMouseClick"
    return ["if (mc.gameMode == null) return;",
            "mc.gameMode.%s(containerId, slot, button, %s.valueOf(mode.name()), mc.player);" % (meth, enum)]

def screen_click(ver):        # ScreenClickCompat.clickAt(Screen s, double x, double y) -> boolean
    if mouse_event(V(ver)):
        return ["MouseButtonEvent ev = new MouseButtonEvent(x, y, new MouseButtonInfo(0, 0));",
                "boolean handled = s.mouseClicked(ev, false);",
                "s.mouseReleased(ev);",
                "return handled;"]
    return ["boolean handled = s.mouseClicked(x, y, 0);",
            "s.mouseReleased(x, y, 0);",
            "return handled;"]

def spawn_natural(ver):       # SpawnCompat.createNatural(EntityType<?> type, Level level) -> Object (cast Mob by caller)
    # Spawn-create signature (deobf-confirmed): pre-1.21.2 the only create-with-Level is the 1-arg
    # create(Level) (NO spawn-reason param); 1.21.2+ adds create(Level, EntitySpawnReason). For an
    # un-ticked path-proxy mob the reason is irrelevant, so pre-1.21.2 just calls create(level).
    if spawn_reason_enum(V(ver)):
        return ["return type.create(level, EntitySpawnReason.NATURAL);"]
    return ["return type.create(level);"]

def required_path_length(ver):  # PathNavCompat.setRequiredPathLength(PathNavigation nav, float value)
    return ["nav.setRequiredPathLength(value);"] if has_required_path_length(V(ver)) \
        else ["// setRequiredPathLength absent on this version -- default path length"]

def screenshot_grab(ver):     # ScreenshotCompat.grab(File dir, String name, RenderTarget target, Consumer<Component> cb)
    if shot_int_arg(V(ver)):
        return ["if (name == null) { net.minecraft.client.Screenshot.grab(dir, target, cb); return; }",
                "net.minecraft.client.Screenshot.grab(dir, name, target, 1, cb);"]
    return ["if (name == null) { net.minecraft.client.Screenshot.grab(dir, target, cb); return; }",
            "net.minecraft.client.Screenshot.grab(dir, name, target, cb);"]

# Self-test: `python compat.py` prints each drift across the matrix so boundaries are eyeballable.
if __name__ == "__main__":
    for ver in ["1.20", "1.20.6", "1.21", "1.21.2", "1.21.5", "1.21.11", "26.1", "26.2", "26.3"]:
        print("== %-8s ==" % ver)
        print("  screen   :", screen_get(ver)[0])
        print("  selected :", selected_get(ver)[0])
        print("  click    :", container_click(ver)[-1])
        print("  mouse    :", screen_click(ver)[0])
        print("  spawn    :", spawn_natural(ver)[0])
        print("  shot      :", screenshot_grab(ver)[-1])

def m1compat_imports(ver):
    """Extra imports M1Compat needs that do NOT exist on all versions (so must be version-gated by Cog).
    The 26.2 zombie registry form references EntityTypeIds/BuiltInRegistries which are absent pre-26."""
    v = V(ver); out = []
    if zombie_registry(v):
        out += ["net.minecraft.core.registries.BuiltInRegistries",
                "net.minecraft.world.entity.EntityTypeIds"]
    return sorted(set(out))
