package com.kishku7.m1.forge;

import com.kishku7.m1.CraftHarvest;
import com.kishku7.m1.M1Server;
import com.kishku7.m1.MineControl;
import com.kishku7.m1.MoveControl;
import com.kishku7.m1.PickupUpgrade;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import com.kishku7.m1.M1SrvNet;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Forge 1.20.1 loader glue for M1. M1 is a CLIENT-ONLY socket driver with no HUD/render of its
 * own, so this glue is minimal: start the localhost M1Server socket on the client, then drive
 * the per-tick command pump from a client-tick handler. The whole command/socket/screenshot/
 * screen-introspection engine (M1Server, ScreenOps, etc.) is loader-agnostic and shared verbatim
 * with the Fabric 1.20 build -- this class just replaces the Fabric ClientModInitializer +
 * ClientTickEvents with the Forge @Mod entry + MinecraftForge.EVENT_BUS / TickEvent.ClientTickEvent.
 *
 * Mirrors the M1 NeoForge @Mod glue (26 branch) one-for-one, in the net.minecraftforge namespace
 * (the Forge/NeoForge 1.20.1 fork point), matching EH3's verified Forge 1.20.1 glue pattern.
 */
@Mod("m1")
public class M1Forge {
    public M1Forge() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            M1Server.start();
            MinecraftForge.EVENT_BUS.addListener(this::onClientTick);
            MinecraftForge.EVENT_BUS.addListener(this::onSystemChat);
        }
    }

    // TickEvent.Phase is deprecated-for-removal on this Forge version but is the working client-tick API
    // (the Pre/Post replacement is used by the 1.21+ cells); suppress per-cell.
    @SuppressWarnings({"deprecation", "removal"})
    private void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        // The heavy-feature ticks (move/mine/craft/upgrade) are no-ops in the 1.20 core build,
        // but the registration is kept identical to the Fabric/NeoForge glue so re-enabling a
        // feature later only requires filling in the corresponding class.
        MoveControl.tick(mc);
        MineControl.tick(mc);
        CraftHarvest.tick(mc);
        PickupUpgrade.tick(mc);
        M1SrvNet.tick(mc);
    }

    // M1-Server system-chat replies captured via the shared facade; no-op if M1-Server absent.
    private void onSystemChat(ClientChatReceivedEvent event) {
        M1SrvNet.onChatMessage(event.getMessage());
    }
}