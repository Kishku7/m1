package com.kishku7.m1;

import java.util.List;

import com.kishku7.m1.agent.ActionContext;
import com.kishku7.m1.agent.MinecraftAction;
import com.kishku7.m1.agent.ReportClass;
import com.kishku7.m1.agent.StepResult;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Blocks;

/**
 * GRAVE-SITE RECOVERY composite (Master directive after 702d): the grave mod leaves a chest
 * (loot), an armor stand on top (armor + last held item, player head), and a name sign on the
 * chest front. Recovering it manually took ~40 chat corrections. This verb does the whole dance:
 *
 *   1. FIND the nearest chest within range (the grave).
 *   2. BREAK the sign first (it eats the right-click that should open the chest).
 *   3. OPEN the chest, TAKEALL, close.
 *   4. BREAK the armor stand (two quick hits) so it drops its gear.
 *   5. COLLECT nearby item drops (walk over them).
 *   6. EQUIP ALL + ORGANIZE HOTBAR + STASH JUNK.
 *
 * Corpse-run navigation (walking BACK to a remembered death spot) is a future build; this verb
 * assumes you are already at the site. Usage: `recover` / `agent recover`.
 */
public final class RecoverAction implements MinecraftAction {

    private static final int FIND_RADIUS = 12;
    private static final int MAX_TICKS = 1200; // 60s cap

    private enum St { FIND, BREAK_SIGN, GOTO_CHEST, OPEN, TAKE, BREAK_STAND, COLLECT, EQUIP }

    private St st = St.FIND;
    private int ticks;
    private BlockPos chest;
    private BlockPos sign;
    private ArmorStand stand;
    private int standHitCooldown;
    private int standHits;
    private ItemEntity collecting;
    private int settle;

    @Override
    public String name() {
        return "recover";
    }

    @Override
    public StepResult step(ActionContext ctx) {
        Minecraft mc = ctx.service(Minecraft.class);
        if (mc == null || mc.player == null || mc.level == null) {
            ctx.report(ReportClass.STATUS, "recover: not in world");
            return StepResult.FAILED;
        }
        LocalPlayer p = mc.player;
        if (++ticks > MAX_TICKS) {
            ctx.report(ReportClass.STATUS, "recover: timeout in state " + st);
            MoveControl.stop();
            return StepResult.FAILED;
        }

        switch (st) {
            case FIND: {
                chest = findChest(mc, p);
                if (chest == null) {
                    ctx.report(ReportClass.STATUS, "recover: no chest within " + FIND_RADIUS
                            + " blocks (walk to the grave first)");
                    return StepResult.FAILED;
                }
                sign = findAttachedSign(mc, chest);
                stand = findStand(mc, chest);
                ctx.report(ReportClass.STATUS, "recover: grave at " + chest.toShortString()
                        + (sign != null ? ", sign " + sign.toShortString() : ", no sign")
                        + (stand != null ? ", armor stand id=" + stand.getId() : ", no armor stand"));
                if (sign != null) {
                    MineControl.start(sign, 200);
                    st = St.BREAK_SIGN;
                } else {
                    st = St.GOTO_CHEST;
                }
                return StepResult.RUNNING;
            }
            case BREAK_SIGN: {
                if (MineControl.isActive()) {
                    return StepResult.RUNNING;
                }
                if (mc.level.getBlockState(sign).is(BlockTags.ALL_SIGNS)) {
                    ctx.report(ReportClass.STATUS, "recover: could not break the sign at "
                            + sign.toShortString() + " -- trying to open the chest anyway");
                } else {
                    ctx.report(ReportClass.STATUS, "recover: sign broken");
                }
                st = St.GOTO_CHEST;
                return StepResult.RUNNING;
            }
            case GOTO_CHEST: {
                double d = Math.hypot(chest.getX() + 0.5 - p.getX(), chest.getZ() + 0.5 - p.getZ());
                if (d > 3.0) {
                    if (!MoveControl.isActive()) {
                        ScreenOps.startMove(mc, p, chest.getX() + 0.5, chest.getY(), chest.getZ() + 0.5,
                                2.0, "recover approach");
                    }
                    return StepResult.RUNNING;
                }
                MoveControl.stop();
                MineControl.faceBlock(p, chest);
                st = St.OPEN;
                settle = 0;
                return StepResult.RUNNING;
            }
            case OPEN: {
                if (p.containerMenu != p.inventoryMenu && M1Compat.screen(mc) != null) {
                    st = St.TAKE;
                    settle = 0;
                    return StepResult.RUNNING;
                }
                if ((settle++ % 10) == 0) {
                    MineControl.faceBlock(p, chest);
                    Crafting.place(mc); // right-click-use the chest under the crosshair
                }
                if (settle > 60) {
                    ctx.report(ReportClass.STATUS, "recover: cannot open the chest (obstructed?)");
                    st = St.BREAK_STAND; // still get the stand's gear
                    return StepResult.RUNNING;
                }
                return StepResult.RUNNING;
            }
            case TAKE: {
                if (settle++ < 8) {
                    return StepResult.RUNNING; // let the menu sync
                }
                String r = InventoryOps.takeAll(mc);
                ctx.report(ReportClass.STATUS, "recover: " + r);
                Crafting.close(mc);
                st = St.BREAK_STAND;
                return StepResult.RUNNING;
            }
            case BREAK_STAND: {
                if (stand == null || stand.isRemoved()) {
                    st = St.COLLECT;
                    settle = 0;
                    if (stand != null) {
                        ctx.report(ReportClass.STATUS, "recover: armor stand down, collecting drops");
                    }
                    return StepResult.RUNNING;
                }
                double d = Math.sqrt(stand.distanceToSqr(p));
                if (d > 3.5) {
                    if (!MoveControl.isActive()) {
                        ScreenOps.startMove(mc, p, stand.getX(), stand.getY(), stand.getZ(),
                                2.5, "recover stand");
                    }
                    return StepResult.RUNNING;
                }
                MoveControl.stop();
                faceEntity(p, stand);
                if (standHitCooldown-- <= 0) {
                    mc.gameMode.attack(p, stand);
                    M1Compat.swingMainHand(p);
                    standHitCooldown = 8; // two quick hits break a stand; keep hitting till gone
                    if (++standHits > 12) {
                        ctx.report(ReportClass.STATUS, "recover: armor stand will not break; moving on");
                        st = St.COLLECT;
                        settle = 0;
                    }
                }
                return StepResult.RUNNING;
            }
            case COLLECT: {
                if (settle++ < 20 && collecting == null) {
                    return StepResult.RUNNING; // let drops pop + spread
                }
                // Still chasing a drop that has not been picked up AND is not already underfoot?
                if (collecting != null && !collecting.isRemoved()
                        && collecting.distanceToSqr(p) > 1.5) {
                    if (!MoveControl.isActive()) {
                        ScreenOps.startMove(mc, p, collecting.getX(), collecting.getY(),
                                collecting.getZ(), 0.3, "recover pickup");
                    }
                    return StepResult.RUNNING;
                }
                MoveControl.stop();
                collecting = nextDrop(mc, p);
                if (collecting == null) {
                    st = St.EQUIP;
                    return StepResult.RUNNING;
                }
                // Already within pickup range of the next drop? do not re-path -- just wait a tick
                // for the pickup, then re-scan. (Kills the arrive/at-goal churn seen live.)
                if (collecting.distanceToSqr(p) <= 1.5) {
                    return StepResult.RUNNING;
                }
                ScreenOps.startMove(mc, p, collecting.getX(), collecting.getY(), collecting.getZ(),
                        0.3, "recover pickup");
                return StepResult.RUNNING;
            }
            case EQUIP: {
                String a = InventoryOps.equipAll(mc);
                String b = InventoryOps.organizeHotbar(mc);
                String c = InventoryOps.stashJunk(mc);
                Crafting.close(mc);
                ctx.report(ReportClass.STATUS, "recover: DONE. " + a + " | " + b + " | " + c);
                return StepResult.DONE;
            }
            default:
                return StepResult.FAILED;
        }
    }

    private static BlockPos findChest(Minecraft mc, LocalPlayer p) {
        BlockPos c = p.blockPosition();
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos bp : BlockPos.betweenClosed(c.offset(-FIND_RADIUS, -3, -FIND_RADIUS),
                c.offset(FIND_RADIUS, 3, FIND_RADIUS))) {
            if (!mc.level.getBlockState(bp).is(Blocks.CHEST)
                    && !mc.level.getBlockState(bp).is(Blocks.TRAPPED_CHEST)) {
                continue;
            }
            double d = bp.distSqr(c);
            if (d < bd) {
                bd = d;
                best = bp.immutable();
            }
        }
        return best;
    }

    /** A sign attached to any side of the chest (or standing directly beside/on it). */
    private static BlockPos findAttachedSign(Minecraft mc, BlockPos chest) {
        for (Direction d : Direction.values()) {
            BlockPos n = chest.relative(d);
            if (mc.level.getBlockState(n).is(BlockTags.ALL_SIGNS)) {
                return n.immutable();
            }
        }
        return null;
    }

    private static ArmorStand findStand(Minecraft mc, BlockPos chest) {
        List<ArmorStand> stands = mc.level.getEntitiesOfClass(ArmorStand.class,
                new net.minecraft.world.phys.AABB(chest).inflate(4.0));
        ArmorStand best = null;
        double bd = Double.MAX_VALUE;
        for (ArmorStand s : stands) {
            double d = s.distanceToSqr(chest.getX() + 0.5, chest.getY() + 1, chest.getZ() + 0.5);
            if (d < bd) {
                bd = d;
                best = s;
            }
        }
        return best;
    }

    private ItemEntity nextDrop(Minecraft mc, LocalPlayer p) {
        List<ItemEntity> drops = mc.level.getEntitiesOfClass(ItemEntity.class,
                p.getBoundingBox().inflate(8.0), e -> !e.isRemoved());
        ItemEntity best = null;
        double bd = Double.MAX_VALUE;
        for (ItemEntity e : drops) {
            double d = e.distanceToSqr(p);
            if (d < bd) {
                bd = d;
                best = e;
            }
        }
        return best;
    }

    private static void faceEntity(LocalPlayer p, ArmorStand e) {
        double dx = e.getX() - p.getX();
        double dy = (e.getY() + e.getBbHeight() * 0.5) - (p.getY() + p.getEyeHeight());
        double dz = e.getZ() - p.getZ();
        double h = Math.sqrt(dx * dx + dz * dz);
        p.setYRot((float) Math.toDegrees(Math.atan2(-dx, dz)));
        p.setXRot((float) -Math.toDegrees(Math.atan2(dy, h)));
    }

    @Override
    public void onInterrupted(ActionContext ctx) {
        MoveControl.stop();
        MineControl.stop();
    }
}
