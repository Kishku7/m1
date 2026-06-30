package com.kishku7.m1.client;

import com.kishku7.m1.CraftHarvest;
import com.kishku7.m1.M1Server;
import com.kishku7.m1.MineControl;
import com.kishku7.m1.MoveControl;
import com.kishku7.m1.PickupUpgrade;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class M1Client implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        M1Server.start();
        // The heavy-feature ticks (move/mine/craft/upgrade) are no-ops in the 1.20 core build,
        // but the registration is kept identical to 26.x so re-enabling a feature later only
        // requires filling in the corresponding class.
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            MoveControl.tick(mc);
            MineControl.tick(mc);
            CraftHarvest.tick(mc);
            PickupUpgrade.tick(mc);
        });
    }
}
