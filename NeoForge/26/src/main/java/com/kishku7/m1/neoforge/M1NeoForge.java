package com.kishku7.m1.neoforge;

import com.kishku7.m1.CraftHarvest;
import com.kishku7.m1.M1Server;
import com.kishku7.m1.M1SrvNet;
import com.kishku7.m1.MineControl;
import com.kishku7.m1.MoveControl;
import com.kishku7.m1.PickupUpgrade;
import com.kishku7.m1.VaultNet;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod("m1")
public class M1NeoForge {
    public M1NeoForge(ModContainer mod, IEventBus bus, Dist dist) {
        if (dist.isClient()) {
            M1Server.start();
            NeoForge.EVENT_BUS.addListener(this::onClientTick);
            NeoForge.EVENT_BUS.addListener(this::onSystemChat);
        }
    }

    private void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) return;
        MoveControl.tick(mc);
        MineControl.tick(mc);
        CraftHarvest.tick(mc);
        PickupUpgrade.tick(mc);
        M1SrvNet.tick(mc);
    }

    /** System messages (command feedback): Bank Vault's "/bank api" and M1-Server's "/m1srv q" replies arrive here. */
    private void onSystemChat(ClientChatReceivedEvent.System event) {
        if (event.getMessage() != null) {
            String s = event.getMessage().getString();
            VaultNet.onSystemLine(s);
            M1SrvNet.onSystemLine(s);
        }
    }
}
