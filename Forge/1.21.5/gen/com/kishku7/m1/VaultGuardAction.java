package com.kishku7.m1;

import com.kishku7.m1.agent.ActionContext;
import com.kishku7.m1.agent.MinecraftAction;
import com.kishku7.m1.agent.ReportClass;
import com.kishku7.m1.agent.StepResult;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tick-stepped "close the vault safely" action with the Trinkets eject-on-close workaround
 * (bank-vault.md: Trinkets Updated rebuild() can intermittently eject worn trinkets into the
 * main inventory when the vault screen closes).
 *
 * Flow: close -> settle -> if trinkets were worn (snapshot taken during the vault session),
 * REOPEN the marked vault block, compare the trinket slots against the snapshot, re-equip any
 * ejected trinket found in the player slots (two PICKUP clicks), close again, report.
 * Needs `vault mark` (or a recent mark) for the reopen; without a marked position it closes,
 * reports what to check, and finishes.
 */
public final class VaultGuardAction implements MinecraftAction {

    private static final int SETTLE_TICKS = 4;
    private static final int REOPEN_TIMEOUT = 60;

    private int state;          // 0 close, 1 settle, 2 reopen, 3 wait-screen, 4 verify+fix, 5 settle2, 6 final-close
    private int wait;
    private Map<Integer, String> snapshot = new LinkedHashMap<>();
    private final StringBuilder log = new StringBuilder();

    @Override
    public String name() {
        return "vaultclose";
    }

    @Override
    public StepResult step(ActionContext ctx) {
        Minecraft mc = ctx.service(Minecraft.class);
        if (mc == null || mc.player == null) {
            ctx.report(ReportClass.STATUS, "vaultclose: not in world");
            return StepResult.FAILED;
        }
        switch (state) {
            case 0: {
                if (VaultOps.isVaultScreen(mc)) {
                    VaultOps.snapshotTrinkets(mc);
                }
                snapshot = new LinkedHashMap<>(VaultOps.lastTrinketSnapshot());
                closeMenu(mc);
                state = 1;
                wait = SETTLE_TICKS;
                return StepResult.RUNNING;
            }
            case 1: {
                if (--wait > 0) {
                    return StepResult.RUNNING;
                }
                if (snapshot.isEmpty()) {
                    ctx.report(ReportClass.STATUS, "vaultclose: closed (no trinkets were worn)");
                    return StepResult.DONE;
                }
                if (VaultOps.markedPos() == null) {
                    ctx.report(ReportClass.STATUS, "vaultclose: closed. Trinkets were worn ("
                            + snapshot.size() + ") but no vault is marked -- cannot verify; "
                            + "run 'vault mark' next time. Check your trinkets.");
                    return StepResult.DONE;
                }
                state = 2;
                return StepResult.RUNNING;
            }
            case 2: {
                BlockPos p = VaultOps.markedPos();
                Vec3 center = Vec3.atCenterOf(p);
                if (mc.player.getEyePosition().distanceTo(center) > 4.2) {
                    ctx.report(ReportClass.STATUS, "vaultclose: closed. Trinkets were worn but the "
                            + "marked vault is out of reach for the verify reopen. Check your trinkets.");
                    return StepResult.DONE;
                }
                // face + use the vault block directly (server validates reach)
                BlockHitResult hit = new BlockHitResult(center, mc.player.getDirection().getOpposite(), p, false);
                mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND, hit);
                state = 3;
                wait = REOPEN_TIMEOUT;
                return StepResult.RUNNING;
            }
            case 3: {
                if (VaultOps.isVaultScreen(mc)) {
                    state = 4;
                    return StepResult.RUNNING;
                }
                if (--wait <= 0) {
                    ctx.report(ReportClass.STATUS, "vaultclose: verify reopen timed out -- vault did not "
                            + "open. Check your trinkets manually.");
                    return StepResult.DONE;
                }
                return StepResult.RUNNING;
            }
            case 4: {
                AbstractContainerMenu menu = mc.player.containerMenu;
                if (!VaultOps.isVaultMenu(menu)) {
                    ctx.report(ReportClass.STATUS, "vaultclose: reopened screen is not the vault; aborting verify");
                    return StepResult.DONE;
                }
                int fixed = 0;
                int lost = 0;
                for (Map.Entry<Integer, String> e : snapshot.entrySet()) {
                    int slot = e.getKey();
                    String id = e.getValue();
                    if (slot < menu.slots.size() && id.equals(Crafting.itemId(menu.slots.get(slot).getItem()))) {
                        continue;   // still worn
                    }
                    // ejected: look for the item in the player slots (0..35) and click it back in
                    int src = -1;
                    for (int i = 0; i <= 35 && i < menu.slots.size(); i++) {
                        ItemStack st = menu.slots.get(i).getItem();
                        if (!st.isEmpty() && id.equals(Crafting.itemId(st))) {
                            src = i;
                            break;
                        }
                    }
                    if (src < 0) {
                        lost++;
                        log.append(" [").append(id).append(": ejected, NOT found in inventory]");
                        continue;
                    }
                    ContainerCompat.click(mc, menu.containerId, src, 0, ContainerCompat.Mode.PICKUP);
                    ContainerCompat.click(mc, menu.containerId, slot, 0, ContainerCompat.Mode.PICKUP);
                    fixed++;
                    log.append(" [").append(id).append(": re-equipped from slot ").append(src).append(']');
                }
                if (fixed == 0 && lost == 0) {
                    log.append(" all trinkets still worn");
                }
                state = 5;
                wait = SETTLE_TICKS;
                return StepResult.RUNNING;
            }
            case 5: {
                if (--wait > 0) {
                    return StepResult.RUNNING;
                }
                state = 6;
                return StepResult.RUNNING;
            }
            case 6:
            default: {
                closeMenu(mc);
                ctx.report(ReportClass.STATUS, "vaultclose: done --" + log);
                return StepResult.DONE;
            }
        }
    }

    private static void closeMenu(Minecraft mc) {
        if (mc.player != null && VaultOps.isVaultMenu(mc.player.containerMenu)) {
            mc.player.closeContainer();     // sends the container-close packet (server-synced)
        } else {
            M1Compat.setScreen(mc, null);
        }
    }
}
