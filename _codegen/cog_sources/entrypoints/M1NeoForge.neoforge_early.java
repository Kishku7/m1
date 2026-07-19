package com.kishku7.m1.neoforge;

import com.kishku7.m1.CraftHarvest;
import com.kishku7.m1.M1Server;
import com.kishku7.m1.MineControl;
import com.kishku7.m1.MoveControl;
import com.kishku7.m1.PickupUpgrade;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.TickEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * NeoForge 1.20.6 (NeoForge 20.6.x) loader glue for M1. M1 is a CLIENT-ONLY socket driver with
 * no HUD/render of its own, so this glue is minimal: start the localhost M1Server socket on the
 * client, then drive the per-tick command pump from a client-tick handler. The whole command/
 * socket/screenshot/screen-introspection engine (M1Server, ScreenOps, etc.) is loader-agnostic
 * and shared verbatim with the Fabric/Forge 1.20 builds.
 *
 * Mirrors the M1 NeoForge @Mod glue (26 branch) and EH3's verified NeoForge 1.20.6 glue. The
 * 1.20.6-era @Mod constructor is (IEventBus, ModContainer) (note: 26.x is (ModContainer, IEventBus,
 * Dist)); dist comes from FMLEnvironment. ClientTickEvent.Post already exists on 20.6 (same as 26.x).
 */
@Mod("m1")
public class M1NeoForge {
    public M1NeoForge(IEventBus bus, ModContainer mod) {
        com.kishku7.m1.M1SrvConfig.get();
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            M1Server.start();
            NeoForge.EVENT_BUS.addListener(this::onClientTick);
        }
    }
    // M1-Server: register the read-only /m1srv command server-side (dedicated + integrated servers).
    private void onRegisterCommands(net.neoforged.neoforge.event.RegisterCommandsEvent event) {
        event.getDispatcher().register(com.kishku7.m1.M1SrvCommand.build());
    }

    @SuppressWarnings({"deprecation", "removal"}) // NeoForge TickEvent.phase deprecated-for-removal (pre-ClientTickEvent.Post)

    private void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) { return; }
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        // Heavy-feature ticks (move/mine/craft/upgrade) are no-ops in the 1.20 core build, but the
        // registration is kept identical to the Fabric/Forge glue so re-enabling a feature later
        // only requires filling in the corresponding class.
        MoveControl.tick(mc);
        MineControl.tick(mc);
        CraftHarvest.tick(mc);
        PickupUpgrade.tick(mc);
    }
}
