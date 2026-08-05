package com.kishku7.m1;

import net.minecraft.client.Minecraft;

/**
 * Cross-version DROP facade (Cog, DIRECT per version).
 *
 * <p>{@code LocalPlayer.drop(boolean)} is the vanilla drop-key path (it sends the proper
 * serverbound player-action packet, so it is desync-safe) and M1's {@link DropAction} is built on
 * it. Its RETURN TYPE drifts:
 *
 * <ul>
 *   <li>Through 26.3-snapshot-6 it is {@code boolean}, returning
 *       {@code !getInventory().removeFromSelected(all).isEmpty()} -- i.e. "the held stack was not
 *       empty, so something was actually thrown".</li>
 *   <li>From 26.3-snapshot-7 it is {@code void}: the same emptiness test survives inside the
 *       method but now only decides whether to broadcast a swing
 *       ({@code swing(MAIN_HAND, SwingAnimation.DEFAULT, false)}), and nothing is returned.</li>
 * </ul>
 *
 * <p>The facade restores the old answer on the new API by asking the SAME question the vanilla
 * method asks, one instruction earlier: the main-hand stack is inspected BEFORE the call, which is
 * exactly what {@code removeFromSelected} consumes. Semantically identical, not an approximation.
 *
 * <p>This is a Cog facade rather than reflection because the pre-26 Fabric cells run an
 * INTERMEDIARY runtime, where reflection by mojmap name misses entirely. Predicate:
 * {@code compat.drop_void}.
 */
public final class DropCompat {

    private DropCompat() {}

    /**
     * Throw the held stack ({@code all = true}) or a single item ({@code all = false}) using the
     * vanilla drop-key path.
     *
     * @return {@code true} if there was something in the main hand to throw, {@code false} if the
     *         hand was empty and the call was a no-op -- the pre-snapshot-7 return contract.
     */
    public static boolean drop(Minecraft mc, boolean all) {
        //[[[cog
        //import sys; sys.path.insert(0, codegen); import compat
        //if compat.drop_void(compat.V(mcver)):
        //    cog.outl("// 26.3-snapshot-7+: drop() returns void -- ask the emptiness question first.")
        //    cog.outl("boolean threw = !mc.player.getMainHandItem().isEmpty();")
        //    cog.outl("mc.player.drop(all);")
        //    cog.outl("return threw;")
        //else:
        //    cog.outl("// through 26.3-snapshot-6: drop() answers it itself.")
        //    cog.outl("return mc.player.drop(all);")
        //]]]
        //[[[end]]]
    }
}
