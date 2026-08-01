package com.kishku7.m1;

import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.entity.SignBlockEntity;

import java.util.ArrayList;
import java.util.List;

/**
 * Cross-version SIGN-TEXT facade (Cog, DIRECT per version).
 *
 * <p>Sign reading landed 2026-08-01 because a storage room's wall signs -- which label every chest
 * column -- were unreadable: the server's {@code lookingat} ray uses a COLLIDER context and wall
 * signs have no collision box, so it passes straight through and reports the chest behind. Reading
 * the block entity avoids rays entirely, but the block-entity API itself drifts, in TWO places that
 * move together at 26.3:
 *
 * <ul>
 *   <li>{@code SignBlockEntity.getFrontText()/getBackText()} (through 26.2) becomes
 *       {@code getText(SignTextSlot.FRONT|BACK)} (26.3+).</li>
 *   <li>{@code SignText.getMessages(boolean)} returns {@code Component[]} (through 26.2) and
 *       {@code List<Component>} (26.3+).</li>
 * </ul>
 *
 * <p>Boundary deobf-confirmed from MC-Java 26.2 vs 26.3-snapshot-6 (SignTextSlot.java first appears
 * in 26.3-snapshot-4). Predicate: {@code compat.sign_text_slot}.
 *
 * <p>This is a Cog facade rather than reflection because the pre-26 Fabric cells run an
 * INTERMEDIARY runtime, where reflection by mojmap name misses entirely.
 */
public final class SignCompat {

    private SignCompat() {}

    /** Non-blank lines of one sign face, newest-API-agnostic. {@code front=false} reads the back. */
    public static List<Component> lines(SignBlockEntity sign, boolean front) {
        //[[[cog
        //import sys; sys.path.insert(0, codegen); import compat
        //if compat.sign_text_slot(compat.V(mcver)):
        //    cog.outl("net.minecraft.world.level.block.entity.SignText t = sign.getText(")
        //    cog.outl("        front ? net.minecraft.world.level.block.entity.SignTextSlot.FRONT")
        //    cog.outl("              : net.minecraft.world.level.block.entity.SignTextSlot.BACK);")
        //    cog.outl("if (t == null) return java.util.Collections.emptyList();")
        //    cog.outl("return new ArrayList<>(t.getMessages(false));")
        //else:
        //    cog.outl("net.minecraft.world.level.block.entity.SignText t =")
        //    cog.outl("        front ? sign.getFrontText() : sign.getBackText();")
        //    cog.outl("if (t == null) return java.util.Collections.emptyList();")
        //    cog.outl("List<Component> out = new ArrayList<>();")
        //    cog.outl("for (Component c : t.getMessages(false)) out.add(c);")
        //    cog.outl("return out;")
        //]]]
        //[[[end]]]
    }

    /** One face rendered as a single line, blank lines dropped; empty string when nothing is written. */
    public static String face(SignBlockEntity sign, boolean front) {
        StringBuilder b = new StringBuilder();
        for (Component c : lines(sign, front)) {
            if (c == null) continue;
            String line = c.getString().trim();
            if (line.isEmpty()) continue;
            if (b.length() > 0) b.append(" / ");
            b.append(line);
        }
        return b.toString();
    }
}
