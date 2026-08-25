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
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

/**
 * Forge loader glue for M1. M1 is a CLIENT-ONLY socket driver with no HUD/render of its own, so
 * this glue is minimal: start the localhost M1Server socket on the client, then drive the per-tick
 * command pump from a client-tick handler. The whole command/socket/screenshot/screen-introspection
 * engine (M1Server, ScreenOps, etc.) is loader-agnostic and shared verbatim with the Fabric build --
 * this class just replaces the Fabric ClientModInitializer + ClientTickEvents with the Forge @Mod
 * entry + the client-tick EventBus listener.
 *
 * Forge 1.21.8 (58.x) note: Forge migrated to EventBus 7. The pre-58 idiom
 * (MinecraftForge.EVENT_BUS.addListener(this::onClientTick)) no longer exists -- EVENT_BUS is now an
 * EventBusMigrationHelper, and EventBus 7 rejects registering a single @SubscribeEvent method via
 * register(Object) ("call addListener() on the EventBus of ClientTickEvent instead"). The EventBus-7
 * idiom is per-event-type buses: each concrete event subclass carries a public static EventBus<T> BUS.
 * We listen on TickEvent.ClientTickEvent.Post.BUS (fired at END phase), which replaces the old
 * deprecated phase == END check on the abstract ClientTickEvent.
 */
@Mod("m1")
public class M1Forge {
    public M1Forge() {
        com.kishku7.m1.M1SrvConfig.get();
        net.minecraftforge.event.RegisterCommandsEvent.BUS.addListener(this::onRegisterCommands);
        if (FMLEnvironment.dist == Dist.CLIENT) {
            M1Server.start();
            TickEvent.ClientTickEvent.Post.BUS.addListener(this::onClientTickPost);
            ClientChatReceivedEvent.BUS.addListener(this::onSystemChat);
            ClientChatReceivedEvent.Player.BUS.addListener(this::onPlayerChat);
        }
    }
    // M1-Server: register the read-only /m1srv command server-side (runs on dedicated + integrated servers).
    private void onRegisterCommands(net.minecraftforge.event.RegisterCommandsEvent event) {
        event.getDispatcher().register(com.kishku7.m1.M1SrvCommand.build());
    }

    private void onClientTickPost(TickEvent.ClientTickEvent.Post event) {
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
