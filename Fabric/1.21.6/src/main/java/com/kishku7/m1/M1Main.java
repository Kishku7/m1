package com.kishku7.m1;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

/**
 * Common (both-sides) entrypoint: registers the M1-Server "/m1srv q <query>" read command. Runs on
 * dedicated AND integrated servers (CommandRegistrationCallback fires on both), so the merged M1 jar
 * answers /m1srv wherever a server exists. The client-side agent lives in M1Client; this touches no
 * client code, so it is safe on a dedicated server. Optional, read-only; see M1SrvCommand / M1SrvConfig.
 */
public class M1Main implements ModInitializer {
    @Override
    public void onInitialize() {
        M1SrvConfig.get();
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(M1SrvCommand.build()));
    }
}