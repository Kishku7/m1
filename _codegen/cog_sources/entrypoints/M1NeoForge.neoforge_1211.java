package com.kishku7.m1.neoforge;

import com.kishku7.m1.CraftHarvest;
import com.kishku7.m1.M1Server;
import com.kishku7.m1.MineControl;
import com.kishku7.m1.MoveControl;
import com.kishku7.m1.AgentRuntime;
import com.kishku7.m1.DamageWatch;
import com.kishku7.m1.HungerWatch;
import com.kishku7.m1.PickupUpgrade;
import com.kishku7.m1.VaultNet;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import com.kishku7.m1.M1SrvNet;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
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
    public M1NeoForge(IEventBus bus, ModContainer mod, Dist dist) {
        com.kishku7.m1.M1SrvConfig.get();
        net.neoforged.neoforge.common.NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        if (dist.isClient()) {
            M1Server.start();
            NeoForge.EVENT_BUS.addListener(this::onClientTick);
            NeoForge.EVENT_BUS.addListener(this::onSystemChat);
            NeoForge.EVENT_BUS.addListener(this::onPlayerChat);
        }
    }
    // M1-Server: register the read-only /m1srv command server-side (dedicated + integrated servers).
    private void onRegisterCommands(net.neoforged.neoforge.event.RegisterCommandsEvent event) {
        event.getDispatcher().register(com.kishku7.m1.M1SrvCommand.build());
    }

    private void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        // Same per-tick chain as the Fabric client entrypoint -- keep the two in step. Before
        // 2026-08-25 this glue stopped after PickupUpgrade, so on every Forge/NeoForge cell the
        // agent loop never stepped (queued multi-tick actions froze), ScreenWatch never ran (no
        // death auto-respawn, no sign-dialog dismissal) and damage/hunger reflexes were dead --
        // the mod answered instantaneous verbs and nothing else.
        MoveControl.tick(mc);
        MineControl.tick(mc);
        CraftHarvest.tick(mc);
        PickupUpgrade.tick(mc);
        AgentRuntime.tick(mc);
        DamageWatch.tick(mc);
        HungerWatch.tick(mc);
        M1SrvNet.tick(mc);
    }

    // M1-Server system-chat replies captured via the shared facade; no-op if M1-Server absent.
    private void onSystemChat(ClientChatReceivedEvent.System event) {
        if (event.getMessage() != null) {
            String s = event.getMessage().getString();
            VaultNet.onSystemLine(s);
            M1SrvNet.onSystemLine(s);
        }
    }

    // Player chat relay for M1's master/ChatWatch feature (2026-07-31 backport parity).
    private void onPlayerChat(ClientChatReceivedEvent.Player event) {
        Minecraft mc = Minecraft.getInstance();
        var info = (mc != null && mc.getConnection() != null) ? mc.getConnection().getPlayerInfo(event.getSender()) : null;
        String nm = (info != null) ? com.kishku7.m1.M1Compat.profileName(info.getProfile()) : null;
        var pcm = event.getPlayerChatMessage();
        String content = (pcm != null) ? pcm.signedContent() : (event.getMessage() != null ? event.getMessage().getString() : "");
        com.kishku7.m1.ChatWatch.onChat(nm, content);
    }
}
