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
    parts = [int(x) for x in core.split(".")]
    while len(parts) < 3:
        parts.append(0)   # pad so "1.21" -> (1,21,0); (1,21) >= (1,21,0) is False otherwise
    return tuple(parts)

# ---- drift boundaries (verified this session unless marked TODO) ----
def is26(v):            return v[0] == 26
def gui_screen(v):      return v >= (26, 2)          # 26.2 moved screen/setScreen onto Minecraft.gui
def gameRenderer_rt(v): return v >= (26, 2)          # 26.2 moved the render target onto GameRenderer
def zombie_registry(v): return v >= (26, 2)          # 26.2 dropped EntityType.ZOMBIE field for registry getValue
def selected_accessor(v): return v >= (1, 21, 5)     # 1.21.5 made Inventory.selected private -> get/setSelectedSlot()
def mouse_event(v):     return v >= (1, 21, 9)       # MouseButtonEvent record + mouseClicked(ev,bool): 1.21.9+ (re-intermediation) THROUGH 26
def mouse_btn_base1(v): return v >= (26, 3)          # 26.3-snapshot-3 input rework: primary (left) click button encodes 1, not 0 (AbstractWidget.isValidClickButton flipped 0->1). 1.21.9..26.2 = 0; 26.3+ = 1. Hardcoded 0 makes 26.3+ menu clicks silent no-ops. Restored 2026-07-20 (dropped by the D16 refactor).
def container_input(v): return v[0] == 26            # handleContainerInput+ContainerInput @26 vs handleInventoryMouseClick+ClickType
def shot_int_arg(v):    return v >= (1, 21, 6)       # 5-arg grab(...,int downscale,...): 1.21.6+ THROUGH 26 (deobf-confirmed; 1.21.5 is the last 4-arg)
def id_rename(v):       return v >= (1, 21, 11)      # ResourceLocation->Identifier rename wave: ResourceKey.location()->identifier(), net.minecraft.resources.Identifier, DataComponents.PIERCING_WEAPON/PiercingWeapon. Deob-confirmed 1.21.11 (1.21.10 still location()/ResourceLocation, no PiercingWeapon). Mirrors id_ident. THROUGH 26.
def press_input(v):     return v >= (1, 21, 9)       # client.input wave: Button.onPress(InputWithModifiers) + net.minecraft.client.input.InputWithModifiers arrive 1.21.9 (deob-confirmed; same wave as mouse_event; 1.21.8 still onPress()). THROUGH 26.
def game_profile_record(v): return v >= (1, 21, 9)  # com.mojang.authlib.GameProfile: class(getName/getId) -> record(name/id). Same re-intermediation wave as mouse_event/press_input. Found 2026-07-31 (M1Client chat-listener build failure, ChatWatch backport). THROUGH 26.
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
        btn = 1 if mouse_btn_base1(V(ver)) else 0
        return ["MouseButtonEvent ev = new MouseButtonEvent(x, y, new MouseButtonInfo(" + str(btn) + ", 0));",
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

# ---- 1.21.9 re-intermediation wave (M1Compat.keyId/keyPath/itemTag/isPiercingWeapon/
#      tryPiercingAttack/pressButton). Version-specific symbols are FULLY QUALIFIED in the bodies so
#      no version-gated imports are needed on the Cog twin. ----

def key_id(ver):              # M1Compat.keyId(ResourceKey<?> key) -> String
    return ["return key.identifier().toString();"] if id_rename(V(ver)) else ["return key.location().toString();"]

def key_path(ver):            # M1Compat.keyPath(ResourceKey<?> key) -> String
    return ["return key.identifier().getPath();"] if id_rename(V(ver)) else ["return key.location().getPath();"]

def item_tag(ver):            # M1Compat.itemTag(String ns, String path) -> TagKey<Item>
    v = V(ver)
    if id_rename(v):
        loc = "net.minecraft.resources.Identifier.fromNamespaceAndPath(ns, path)"
    elif v >= (1, 21, 0):
        loc = "net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(ns, path)"
    else:
        loc = "new net.minecraft.resources.ResourceLocation(ns, path)"
    return ["return TagKey.create(Registries.ITEM, %s);" % loc]

def is_piercing(ver):         # M1Compat.isPiercingWeapon(ItemStack s) -> boolean
    if id_rename(V(ver)):
        return ["return s.has(net.minecraft.core.component.DataComponents.PIERCING_WEAPON);"]
    return ["return false;"]

def _swing_main(ver, p, kind="attack"):
    """ONE main-hand swing, as a single Java statement.

    26.3-snapshot-7 REMOVED both the 1-arg swing(InteractionHand) and the 2-arg
    swing(InteractionHand, boolean); the only remaining form is
    swing(hand, SwingAnimation, sendToSwingingEntity).

    Two details matter for faithfulness:
      * sendToSwingingEntity is FALSE. The old 1-arg overload delegated to swing(hand, false)
        (26.3-snapshot-6 LivingEntity.java L2037-2039), and every client-side vanilla call site in
        the new API also passes false (Minecraft.java L1637/L1707/L1742/L1754/L1771). The flag is
        server-side only -- it decides whether the swinging player also receives the broadcast.
      * the animation follows vanilla's own split: an ATTACK path passes
        heldItem.getAttackAnimation(), a USE/PLACE path passes heldItem.getInteractAnimation()
        (Minecraft.java L1675 vs L1729).
    """
    getter = "getAttackAnimation" if kind == "attack" else "getInteractAnimation"
    if swing_anim(V(ver)):
        return ('%s.swing(InteractionHand.MAIN_HAND, %s.getMainHandItem().%s(), false);'
                % (p, p, getter))
    return '%s.swing(InteractionHand.MAIN_HAND);' % p


def _piercing_call(ver):
    """MultiPlayerGameMode.piercingAttack gained a leading SwingAnimation at 26.3-snapshot-7;
    vanilla passes the same heldItem.getAttackAnimation() it passes to swing()."""
    if swing_anim(V(ver)):
        return 'mc.gameMode.piercingAttack(p.getMainHandItem().getAttackAnimation(), pw);'
    return 'mc.gameMode.piercingAttack(pw);'


def swing_main_hand(ver):     # M1Compat.swingMainHand(LocalPlayer p) -- ATTACK-path animation
    return [_swing_main(ver, "p", "attack")]


def swing_main_hand_use(ver): # M1Compat.swingMainHandUse(LocalPlayer p) -- USE/PLACE-path animation
    return [_swing_main(ver, "p", "interact")]

def try_piercing(ver):        # M1Compat.tryPiercingAttack(Minecraft mc, LocalPlayer p) -> boolean
    if id_rename(V(ver)):
        return ["if (mc.gameMode == null) { return false; }",
                "net.minecraft.world.item.component.PiercingWeapon pw ="
                + " p.getMainHandItem().get(net.minecraft.core.component.DataComponents.PIERCING_WEAPON);",
                "if (pw == null) { return false; }",
                _piercing_call(ver),
                _swing_main(ver, "p"),
                "return true;"]
    return ["return false;"]

def press_button(ver):        # M1Compat.pressButton(Button b)
    if press_input(V(ver)):
        return ["b.onPress(new net.minecraft.client.input.InputWithModifiers() {",
                "    @Override public int input() { return 257; }",
                "    @Override public int modifiers() { return 0; }",
                "});"]
    return ["b.onPress();"]

def profile_name(ver):        # M1Compat.profileName(GameProfile p) -> String
    return ["return p.name();"] if game_profile_record(V(ver)) else ["return p.getName();"]


def m1compat_imports(ver):
    """Extra imports M1Compat needs that do NOT exist on all versions (so must be version-gated by Cog).
    The 26.2 zombie registry form references EntityTypeIds/BuiltInRegistries which are absent pre-26.
    The 1.21.9 wave bodies are all fully-qualified, so they need no extra import here."""
    v = V(ver); out = []
    if zombie_registry(v):
        out += ["net.minecraft.core.registries.BuiltInRegistries",
                "net.minecraft.world.entity.EntityTypeIds"]
    return sorted(set(out))


# ---- recipe-book eras (RecipeCompat). RecipeDisplayEntry (1.21.2+); RecipeHolder<?> (1.20.2-1.21.1);
#      Recipe<?> directly (1.20.0-1.20.1, pre-RecipeHolder). Fully-qualified; opaque Object 'entry'. ----
def recipe_display(v):    return v >= (1, 21, 2)
def ingredient_stream(v): return v >= (1, 21, 4)   # Ingredient.items() Stream (1.21.4+) vs List (1.21.2-1.21.3). Deob-confirmed: 1.21.3 List, 1.21.4 Stream.
def recipe_holder(v):     return v >= (1, 20, 2)   # RecipeHolder<?> wrapper; below = Recipe<?> directly

def rc_entries(ver):
    return [
        "java.util.List<Object> out = new java.util.ArrayList<>();",
        "for (net.minecraft.client.gui.screens.recipebook.RecipeCollection c : mc.player.getRecipeBook().getCollections()) {",
        "    out.addAll(c.getRecipes());",
        "}",
        "return out;"]

def _result_tail():
    return ["if (rr.isEmpty()) { return \"\"; }",
            "return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(rr.getItem()).toString();"]

def rc_result_id(ver):
    v = V(ver)
    if recipe_display(v):
        return [
            "net.minecraft.util.context.ContextMap ctx = net.minecraft.world.item.crafting.display.SlotDisplayContext.fromLevel(mc.level);",
            "java.util.List<net.minecraft.world.item.ItemStack> rs = ((net.minecraft.world.item.crafting.display.RecipeDisplayEntry) entry).resultItems(ctx);",
            "if (rs.isEmpty() || rs.get(0).isEmpty()) { return \"\"; }",
            "return net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(rs.get(0).getItem()).toString();"]
    if recipe_holder(v):
        return ["net.minecraft.world.item.ItemStack rr = ((net.minecraft.world.item.crafting.RecipeHolder<?>) entry).value().getResultItem(mc.level.registryAccess());"] + _result_tail()
    return ["net.minecraft.world.item.ItemStack rr = ((net.minecraft.world.item.crafting.Recipe<?>) entry).getResultItem(mc.level.registryAccess());"] + _result_tail()

def rc_can_craft(ver):
    v = V(ver)
    if recipe_display(v):
        return [
            "net.minecraft.world.entity.player.StackedItemContents sic = new net.minecraft.world.entity.player.StackedItemContents();",
            "mc.player.getInventory().fillStackedContents(sic);",
            "return ((net.minecraft.world.item.crafting.display.RecipeDisplayEntry) entry).canCraft(sic);"]
    recipe = "((net.minecraft.world.item.crafting.RecipeHolder<?>) entry).value()" if recipe_holder(v) else "((net.minecraft.world.item.crafting.Recipe<?>) entry)"
    return [
        "net.minecraft.world.entity.player.StackedContents sc = new net.minecraft.world.entity.player.StackedContents();",
        "mc.player.getInventory().fillStackedContents(sc);",
        "return sc.canCraft(" + recipe + ", null);"]

def rc_requirements(ver):
    v = V(ver)
    if recipe_display(v):
        return [
            "java.util.List<net.minecraft.world.item.crafting.Ingredient> out = new java.util.ArrayList<>();",
            "java.util.Optional<java.util.List<net.minecraft.world.item.crafting.Ingredient>> opt = ((net.minecraft.world.item.crafting.display.RecipeDisplayEntry) entry).craftingRequirements();",
            "if (opt.isPresent()) { out.addAll(opt.get()); }",
            "return out;"]
    recipe = "((net.minecraft.world.item.crafting.RecipeHolder<?>) entry).value()" if recipe_holder(v) else "((net.minecraft.world.item.crafting.Recipe<?>) entry)"
    return [
        "java.util.List<net.minecraft.world.item.crafting.Ingredient> out = new java.util.ArrayList<>();",
        "out.addAll(" + recipe + ".getIngredients());",
        "return out;"]

def rc_ingredient_ids(ver):
    v = V(ver)
    if ingredient_stream(v):
        return [
            "java.util.List<String> out = new java.util.ArrayList<>();",
            "ing.items().forEach(h -> out.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(h.value()).toString()));",
            "return out;"]
    if recipe_display(v):
        return [
            "java.util.List<String> out = new java.util.ArrayList<>();",
            "for (net.minecraft.core.Holder<net.minecraft.world.item.Item> h : ing.items()) { out.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(h.value()).toString()); }",
            "return out;"]
    return [
        "java.util.List<String> out = new java.util.ArrayList<>();",
        "for (net.minecraft.world.item.ItemStack st : ing.getItems()) { out.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(st.getItem()).toString()); }",
        "return out;"]

def rc_place(ver):
    v = V(ver)
    if recipe_display(v):
        return ["mc.gameMode.handlePlaceRecipe(containerId, ((net.minecraft.world.item.crafting.display.RecipeDisplayEntry) entry).id(), shift);"]
    cast = "(net.minecraft.world.item.crafting.RecipeHolder<?>) entry" if recipe_holder(v) else "(net.minecraft.world.item.crafting.Recipe<?>) entry"
    return ["mc.gameMode.handlePlaceRecipe(containerId, " + cast + ", shift);"]

def rc_label(ver):
    v = V(ver)
    if recipe_display(v):
        return ["return \"recipe \" + ((net.minecraft.world.item.crafting.display.RecipeDisplayEntry) entry).id().index();"]
    if recipe_holder(v):
        return ["return \"recipe \" + ((net.minecraft.world.item.crafting.RecipeHolder<?>) entry).id();"]
    return ["return \"recipe \" + ((net.minecraft.world.item.crafting.Recipe<?>) entry).getId();"]


def bright_outside(v):    return v >= (1, 21, 5)   # Level.isBrightOutside() (1.21.5+) vs isDay() (pre)
def is_bright_outside(ver):
    return ["return lvl.isBrightOutside();"] if bright_outside(V(ver)) else ["return lvl.isDay();"]


def equippable(v):     return v >= (1, 21, 2)   # DataComponents.EQUIPPABLE + item.equipment.Equippable
def food_component(v): return v >= (1, 20, 5)   # DataComponents.FOOD vs Item.getFoodProperties()
def equip_slot(ver):
    v = V(ver)
    if equippable(v):
        return ["net.minecraft.world.item.equipment.Equippable eq = s.get(net.minecraft.core.component.DataComponents.EQUIPPABLE);",
                "return eq == null ? null : eq.slot();"]
    if v >= (1, 21, 0):
        return ["return mc.player.getEquipmentSlotForItem(s);"]
    return ["return net.minecraft.world.entity.LivingEntity.getEquipmentSlotForItem(s);"]
def food_value(ver):
    if food_component(V(ver)):
        return ["net.minecraft.world.food.FoodProperties f = s.get(net.minecraft.core.component.DataComponents.FOOD);",
                "return f == null ? 0.0 : (f.nutrition() + f.saturation());"]
    return ["net.minecraft.world.food.FoodProperties f = s.getItem().getFoodProperties();",
            "return f == null ? 0.0 : (f.getNutrition() + f.getSaturationModifier());"]


def ench_line(ver):   # M1Compat.enchantmentsLine(ItemStack s, boolean stored) -> String
    v = V(ver)
    if food_component(v):
        if v >= (1, 21, 0):
            lvl = "e.getLevel(h)"
            full = "net.minecraft.world.item.enchantment.Enchantment.getFullname(h, e.getLevel(h))"
        else:
            lvl = "e.getLevel(h.value())"
            full = "h.value().getFullname(e.getLevel(h.value()))"
        return [
            "net.minecraft.world.item.enchantment.ItemEnchantments e = s.getOrDefault(stored ? net.minecraft.core.component.DataComponents.STORED_ENCHANTMENTS : net.minecraft.core.component.DataComponents.ENCHANTMENTS, net.minecraft.world.item.enchantment.ItemEnchantments.EMPTY);",
            "if (e == null || e.isEmpty()) { return \"\"; }",
            "java.util.List<String> parts = new java.util.ArrayList<>();",
            "for (net.minecraft.core.Holder<net.minecraft.world.item.enchantment.Enchantment> h : e.keySet()) {",
            "    String eid = h.unwrapKey().map(k -> M1Compat.keyPath(k)).orElse(\"?\");",
            "    parts.add(eid + \" \" + " + lvl + " + \" (\" + " + full + ".getString() + \")\");",
            "}",
            "return String.join(\", \", parts);"]
    return [
        "java.util.List<String> parts = new java.util.ArrayList<>();",
        "for (java.util.Map.Entry<net.minecraft.world.item.enchantment.Enchantment, Integer> en : net.minecraft.world.item.enchantment.EnchantmentHelper.getEnchantments(s).entrySet()) {",
        "    net.minecraft.resources.ResourceLocation ek = net.minecraft.core.registries.BuiltInRegistries.ENCHANTMENT.getKey(en.getKey());",
        "    String eid = ek == null ? \"?\" : ek.getPath();",
        "    parts.add(eid + \" \" + en.getValue() + \" (\" + en.getKey().getFullname(en.getValue()).getString() + \")\");",
        "}",
        "return String.join(\", \", parts);"]

def has_attached_bag(ver):   # M1Compat.hasAttachedSleepingBag(ItemStack s, String ns) -> boolean
    if food_component(V(ver)):
        return [
            "for (net.minecraft.core.component.TypedDataComponent<?> comp : s.getComponents()) {",
            "    var id = net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(comp.type());",
            "    if (id != null && ns.equals(id.getNamespace()) && id.getPath().contains(\"sleeping_bag\")) {",
            "        Object v = comp.value();",
            "        return !(v instanceof Integer i) || i >= 0;",
            "    }",
            "}",
            "return false;"]
    return ["return false;"]

def custom_name(ver):        # M1Compat.customName(ItemStack s) -> Component
    if food_component(V(ver)):
        return ["return s.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME);"]
    return ["return s.hasCustomHoverName() ? s.getHoverName() : null;"]

def has_component_patch(ver):  # M1Compat.hasComponentPatch(ItemStack s) -> boolean
    if food_component(V(ver)):
        return ["return !s.getComponentsPatch().isEmpty();"]
    return ["return s.hasTag();"]

def component_patch_list(ver):  # M1Compat.componentPatchList(ItemStack s) -> List<String>
    if food_component(V(ver)):
        return [
            "java.util.List<String> out = new java.util.ArrayList<>();",
            "var sr = s.getComponentsPatch().split();",
            "for (var tc : sr.added()) {",
            "    String key = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(tc.type()));",
            "    String val = String.valueOf(tc.value());",
            "    if (val.length() > 60) { val = val.substring(0, 57) + \"...\"; }",
            "    out.add(key + \"=\" + val);",
            "}",
            "for (var t : sr.removed()) {",
            "    out.add(String.valueOf(net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(t)) + \"=(removed)\");",
            "}",
            "return out;"]
    return [
        "java.util.List<String> out = new java.util.ArrayList<>();",
        "net.minecraft.nbt.CompoundTag tag = s.getTag();",
        "if (tag != null) { for (String k : tag.getAllKeys()) { out.add(k); } }",
        "return out;"]

def tooltip_lines(ver):      # M1Compat.tooltipLines(Minecraft mc, Player p, ItemStack s) -> List<Component>
    if food_component(V(ver)):
        return ["return s.getTooltipLines(net.minecraft.world.item.Item.TooltipContext.of(mc.level), p, net.minecraft.world.item.TooltipFlag.ADVANCED);"]
    return ["return s.getTooltipLines(p, net.minecraft.world.item.TooltipFlag.ADVANCED);"]

def same_item_components(ver):  # M1Compat.sameItemSameComponents(ItemStack a, ItemStack b) -> boolean
    if food_component(V(ver)):
        return ["return net.minecraft.world.item.ItemStack.isSameItemSameComponents(a, b);"]
    return ["return net.minecraft.world.item.ItemStack.isSameItemSameTags(a, b);"]


# ================================================================================================
# M1-SERVER MERGE (2026-07-17): server-authority read drift for cog_sources/Queries.java.
# DIRECT compiled access, version-selected; universal across intermediary/SRG/mojmap runtimes.
# Boundaries verified from the on-disk MC-Java decompiles (m1-server, 2026-07-09..17).
# ================================================================================================
def data_components(v): return v >= (1, 20, 5)    # BlockEntity.saveWithoutMetadata gains HolderLookup.Provider arg
def value_output(v):    return v >= (1, 21, 6)    # Entity.saveWithoutId takes ValueOutput; CompoundTag below
def spawn_respawn(v):   return v >= (1, 21, 9)    # ServerLevel.getRespawnData().pos() (RespawnData introduced 1.21.9, getSharedSpawnPos() removed same step); getSharedSpawnPos() below. Boundary corrected 1.21.10->1.21.9 2026-07-20 (was off-by-one; only the 1.21.10 cell had exercised it).
def sign_text_slot(v):  return v >= (26, 3, 0)  # SignBlockEntity.getFrontText()/getBackText() -> getText(SignTextSlot.FRONT|BACK), AND SignText.getMessages(boolean) Component[] -> List<Component>. BOTH land together at 26.3 (SignTextSlot.java first appears 26.3-snapshot-4; 26.2 still has getFrontText + Component[]). Deobf-confirmed 2026-08-01 from MC-Java 26.2 vs 26.3-snapshot-6.
def swing_anim(v):      return v >= (26, 3, 0)  # LivingEntity.swing(InteractionHand) and swing(InteractionHand, boolean) REMOVED at 26.3-snapshot-7, replaced by swing(InteractionHand, SwingAnimation, boolean); MultiPlayerGameMode.piercingAttack(PiercingWeapon) -> piercingAttack(SwingAnimation, PiercingWeapon). Deobf-confirmed 2026-08-05 from MC-Java 26.3-snapshot-6 vs 26.3-snapshot-7 LivingEntity.java L2037/L2039 + MultiPlayerGameMode.java L520.
def drop_void(v):       return v >= (26, 3, 0)  # LocalPlayer.drop(boolean) return type boolean -> void at 26.3-snapshot-7 (the old return was !removeFromSelected(all).isEmpty(); snap-7 replaces it with a swing broadcast). Deobf-confirmed 2026-08-05 from MC-Java 26.3-snapshot-6 vs 26.3-snapshot-7 LocalPlayer.java:323.
def time_overworld(v):  return v[0] == 26         # Level.getOverworldClockTime() ; getDayTime() below
def gr_new(v):          return v >= (1, 21, 11)   # world.level.gamerules.GameRules + KEEP_INVENTORY names + get(GameRule)
def id_ident(v):        return v >= (1, 21, 11)   # resources.Identifier rename (1.21.10 still ResourceLocation)
def id_parse_static(v): return v >= (1, 21, 0)    # ResourceLocation.parse(String) (1.21+) ; new ResourceLocation below
def recipe_rk(v):       return v >= (1, 21, 2)    # RecipeManager.byKey/RecipeBook.contains take ResourceKey<Recipe<?>>
def adv_holder(v):      return v >= (1, 20, 2)    # ServerAdvancementManager.get(id)->AdvancementHolder ; getAdvancement below
def player_level(v):    return v >= (1, 21, 6)    # ServerPlayer.level() (1.21.6+) ; serverLevel() below
def tps_nanos(v):       return v >= (1, 20, 3)    # getAverageTickTimeNanos() from 1.20.3 ; float getAverageTickTime() below

def q_id_parse(ver, arg):
    v = V(ver)
    if id_ident(v):
        return "net.minecraft.resources.Identifier.parse(%s)" % arg
    if id_parse_static(v):
        return "net.minecraft.resources.ResourceLocation.parse(%s)" % arg
    return "new net.minecraft.resources.ResourceLocation(%s)" % arg

def q_level(ver):
    lvl = "player.level()" if player_level(V(ver)) else "player.serverLevel()"
    return ["        ServerPlayer player = src.getPlayer();",
            "        return player != null ? %s : src.getServer().overworld();" % lvl]

def q_spawn(ver):
    pos = "lvl.getRespawnData().pos()" if spawn_respawn(V(ver)) else "lvl.getSharedSpawnPos()"
    return ["        ServerLevel lvl = level(src);",
            "        BlockPos pos = %s;" % pos,
            '        return pos.getX() + "," + pos.getY() + "," + pos.getZ();']

def q_time(ver):
    clock = "lvl.getOverworldClockTime()" if time_overworld(V(ver)) else "lvl.getDayTime()"
    return ["        ServerLevel lvl = level(src);",
            "        long clock = %s;" % clock,
            '        return "day=" + (clock / 24000L)',
            '                + " daytime=" + (clock % 24000L)',
            '                + " gametime=" + lvl.getLevelData().getGameTime();']

def q_gamerules(ver):
    v = V(ver)
    if gr_new(v):
        gr = "net.minecraft.world.level.gamerules.GameRules"; acc = "get"
        rules = [("keepInventory", "KEEP_INVENTORY"), ("advanceTime", "ADVANCE_TIME"),
                 ("advanceWeather", "ADVANCE_WEATHER"), ("mobGriefing", "MOB_GRIEFING"),
                 ("spawnMobs", "SPAWN_MOBS")]
    else:
        gr = "net.minecraft.world.level.GameRules"; acc = "getBoolean"
        rules = [("keepInventory", "RULE_KEEPINVENTORY"), ("advanceTime", "RULE_DAYLIGHT"),
                 ("advanceWeather", "RULE_WEATHER_CYCLE"), ("mobGriefing", "RULE_MOBGRIEFING"),
                 ("spawnMobs", "RULE_DOMOBSPAWNING")]
    out = ["        var rules = level(src).getGameRules();",
           '        StringJoiner sj = new StringJoiner(" ");']
    for label, field in rules:
        out.append('        sj.add("%s=" + rules.%s(%s.%s));' % (label, acc, gr, field))
    out.append("        return sj.toString();")
    return out

def q_locate_structure(ver):
    return ["        var id = %s;" % q_id_parse(ver, "idStr"),
            "        TagKey<Structure> tag = TagKey.create(Registries.STRUCTURE, id);",
            "        BlockPos origin = BlockPos.containing(src.getPosition());",
            "        BlockPos found = level(src).findNearestMapStructure(tag, origin, 100, false);",
            '        return found != null ? (found.getX() + "," + found.getY() + "," + found.getZ()) : "notfound";']

def q_locate_biome(ver):
    return ["        var id = %s;" % q_id_parse(ver, "idStr"),
            "        ResourceKey<Biome> key = ResourceKey.create(Registries.BIOME, id);",
            "        BlockPos origin = BlockPos.containing(src.getPosition());",
            "        Pair<BlockPos, Holder<Biome>> result = level(src).findClosestBiome3d(h -> h.is(key), origin, 6400, 32, 64);",
            '        return result != null ? (result.getFirst().getX() + "," + result.getFirst().getY() + "," + result.getFirst().getZ()) : "notfound";']

def q_recipe(ver):
    v = V(ver)
    out = ["        var id = %s;" % q_id_parse(ver, "idStr")]
    if recipe_rk(v):
        out += ["        ResourceKey<net.minecraft.world.item.crafting.Recipe<?>> key = ResourceKey.create(Registries.RECIPE, id);",
                "        boolean exists = src.getServer().getRecipeManager().byKey(key).isPresent();",
                "        ServerPlayer p = src.getPlayer();",
                '        String unlocked = (p != null) ? Boolean.toString(p.getRecipeBook().contains(key)) : "n/a";']
    else:
        out += ["        boolean exists = src.getServer().getRecipeManager().byKey(id).isPresent();",
                "        ServerPlayer p = src.getPlayer();",
                '        String unlocked = (p != null) ? Boolean.toString(p.getRecipeBook().contains(id)) : "n/a";']
    out.append('        return "id=" + id + " exists=" + exists + " unlocked=" + unlocked;')
    return out

def q_advancement(ver):
    v = V(ver)
    out = ["        var id = %s;" % q_id_parse(ver, "idStr")]
    holder_var = "holder" if adv_holder(v) else "adv"
    getter = "get" if adv_holder(v) else "getAdvancement"
    out += ["        var %s = src.getServer().getAdvancements().%s(id);" % (holder_var, getter),
            '        if (%s == null) { return "id=" + id + " exists=false"; }' % holder_var,
            "        ServerPlayer p = src.getPlayer();",
            '        if (p == null) { return "id=" + id + " exists=true done=n/a"; }',
            "        var prog = p.getAdvancements().getOrStartProgress(%s);" % holder_var,
            '        return "id=" + id + " exists=true done=" + prog.isDone() + " percent=" + Math.round(prog.getPercent() * 100.0F) + "%";']
    return out

def q_serverinfo(ver):
    v = V(ver)
    out = ["        MinecraftServer server = src.getServer();"]
    if tps_nanos(v):
        out += ["        long avgNanos = server.getAverageTickTimeNanos();",
                "        double mspt = avgNanos / 1_000_000.0D;",
                "        double tps = avgNanos > 0L ? Math.min(20.0D, 1_000_000_000.0D / avgNanos) : 20.0D;"]
    else:
        out += ["        double mspt = server.getAverageTickTime();",
                "        double tps = mspt > 0.0D ? Math.min(20.0D, 1000.0D / mspt) : 20.0D;"]
    out += ['        return "motd=\\"" + server.getMotd() + "\\""',
            '                + " tps=" + String.format(Locale.ROOT, "%.1f", tps)',
            '                + " mspt=" + String.format(Locale.ROOT, "%.1f", mspt)',
            '                + " players=" + server.getPlayerCount() + "/" + server.getPlayerList().getMaxPlayers();']
    return out

def q_be_snapshot(ver):
    if data_components(V(ver)):
        return ["        return be.saveWithoutMetadata(lvl.registryAccess());"]
    return ["        return be.saveWithoutMetadata();"]

def q_entity_snapshot(ver):
    if value_output(V(ver)):
        return ["        net.minecraft.world.level.storage.TagValueOutput out ="
                " net.minecraft.world.level.storage.TagValueOutput.createWithContext("
                "net.minecraft.util.ProblemReporter.DISCARDING, lvl.registryAccess());",
                "        entity.saveWithoutId(out);",
                "        return out.buildResult();"]
    return ["        return entity.saveWithoutId(new net.minecraft.nbt.CompoundTag());"]


# Self-test: `python compat.py` prints each drift across the matrix so boundaries are eyeballable.
if __name__ == "__main__":
    for ver in ["1.20", "1.20.6", "1.21", "1.21.2", "1.21.4", "1.21.5", "1.21.8", "1.21.10", "1.21.11", "26.1", "26.2", "26.3"]:
        print("== %-8s ==" % ver)
        print("  screen   :", screen_get(ver)[0])
        print("  selected :", selected_get(ver)[0])
        print("  click    :", container_click(ver)[-1])
        print("  mouse    :", screen_click(ver)[0])
        print("  spawn    :", spawn_natural(ver)[0])
        print("  shot     :", screenshot_grab(ver)[-1])
        print("  keyId    :", key_id(ver)[0])
        print("  itemTag  :", item_tag(ver)[0])
        print("  piercing :", is_piercing(ver)[0])
        print("  button   :", press_button(ver)[0])
        print("  ingr     :", rc_ingredient_ids(ver)[1])
