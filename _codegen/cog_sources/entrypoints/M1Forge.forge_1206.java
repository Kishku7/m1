package com.kishku7.m1.forge;

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
        com.kishku7.m1.M1SrvConfig.get();
        MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            M1Server.start();
            MinecraftForge.EVENT_BUS.addListener(this::onClientTick);
            MinecraftForge.EVENT_BUS.addListener(this::onSystemChat);
            MinecraftForge.EVENT_BUS.addListener(this::onPlayerChat);
        }
    }

    // TickEvent.Phase is deprecated-for-removal on Forge 1.20.6 but is the working client-tick API
    // for this Forge version (the Pre/Post replacement is used by the 1.21+ cells); suppress per-cell.
    // M1-Server: register the read-only /m1srv command server-side (runs on dedicated + integrated servers).
    private void onRegisterCommands(net.minecraftforge.event.RegisterCommandsEvent event) {
        event.getDispatcher().register(com.kishku7.m1.M1SrvCommand.build());
    }
    @SuppressWarnings({"deprecation", "removal"})
    private void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
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
    private void onSystemChat(ClientChatReceivedEvent event) {
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
