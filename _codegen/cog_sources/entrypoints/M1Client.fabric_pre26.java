package com.kishku7.m1.client;

import com.kishku7.m1.AgentRuntime;
import com.kishku7.m1.ChatWatch;
import com.kishku7.m1.CraftHarvest;
import com.kishku7.m1.DamageWatch;
import com.kishku7.m1.HungerWatch;
import com.kishku7.m1.M1Compat;
import com.kishku7.m1.M1Server;
import com.kishku7.m1.M1SrvNet;
import com.kishku7.m1.MineControl;
import com.kishku7.m1.MoveControl;
import com.kishku7.m1.PickupUpgrade;
import com.kishku7.m1.VaultNet;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;

public class M1Client implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        M1Server.start();
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            MoveControl.tick(mc);
            MineControl.tick(mc);
            CraftHarvest.tick(mc);
            PickupUpgrade.tick(mc);
            AgentRuntime.tick(mc);
            DamageWatch.tick(mc);
            HungerWatch.tick(mc);
            M1SrvNet.tick(mc);
        });
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            String nm = (sender != null) ? M1Compat.profileName(sender) : null;
            // Use the player's signed (raw) content -- the `message` component is decorated ("<name> ...").
            String content = (signedMessage != null) ? signedMessage.signedContent()
                    : ((message != null) ? message.getString() : "");
            ChatWatch.onChat(nm, content);
        });
        // System messages (command feedback): Bank Vault's "/bank api" and M1-Server's "/m1srv q" replies arrive here.
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay && message != null) {
                String s = message.getString();
                VaultNet.onSystemLine(s);
                M1SrvNet.onSystemLine(s);
            }
        });
    }
}