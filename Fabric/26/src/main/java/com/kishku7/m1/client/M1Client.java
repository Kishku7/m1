package com.kishku7.m1.client;

import com.kishku7.m1.AgentRuntime;
import com.kishku7.m1.ChatWatch;
import com.kishku7.m1.CraftHarvest;
import com.kishku7.m1.DamageWatch;
import com.kishku7.m1.M1Server;
import com.kishku7.m1.MineControl;
import com.kishku7.m1.MoveControl;
import com.kishku7.m1.PickupUpgrade;
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
        });
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            String nm = (sender != null) ? sender.name() : null;
            String tx = (message != null) ? message.getString() : "";
            ChatWatch.onChat(nm, tx);
        });
    }
}
