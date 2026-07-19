package com.kishku7.m1.client;

import com.kishku7.m1.CraftHarvest;
import com.kishku7.m1.M1Server;
import com.kishku7.m1.M1SrvNet;
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
        // Heavy-feature ticks are no-ops in the core build; M1SrvNet.tick drives the M1-Server auto-grab.
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            MoveControl.tick(mc);
            MineControl.tick(mc);
            CraftHarvest.tick(mc);
            PickupUpgrade.tick(mc);
            M1SrvNet.tick(mc);
        });
        // Capture M1-Server "M1S|..." system-chat replies (optional companion; no-op if M1-Server absent).
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> M1SrvNet.onSystemLine(message.getString()));
    }
}