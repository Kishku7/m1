package com.kishku7.m1.client;

import com.kishku7.m1.M1Server;
import net.fabricmc.api.ClientModInitializer;

/**
 * Legacy Fabric client entrypoint (single-source master; no cog directives -> copied byte-identical
 * into every legacy cell's gen tree by cog-gen.ps1). Starts the M1 socket core. Dispatch onto the
 * render thread is handled by Platform.onMainThread (MinecraftClient.submit), so no tick pump here.
 */
public final class M1Client implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        M1Server.start();
        System.out.println("[M1] legacy cell initialized (m1.launcher=" + System.getProperty("m1.launcher") + ")");
    }
}
