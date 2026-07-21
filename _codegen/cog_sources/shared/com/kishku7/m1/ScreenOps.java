package com.kishku7.m1;

import java.util.List;

/**
 * Verb router + describe/click/type/key orchestration. MC-agnostic: every version-specific touch
 * goes through Platform. Runs on the render thread (M1Server dispatches it there).
 *
 * Protocol grammar (shared with modern M1's external driver):
 *   describe                 -> screen name + size, then one line per button and text field
 *   click &lt;id&gt;               -> synthesize a click on the button with that numeric id
 *   type &lt;fieldId&gt; &lt;text&gt;    -> set a text field's contents
 *   key &lt;name&gt;               -> drive a GUI key (escape/enter/tab/backspace/up/down/left/right)
 *   ping                     -> pong
 *   help                     -> this list
 */
public final class ScreenOps {
    private ScreenOps() {}

    private static final String HELP =
            "commands: describe | click <id> | select <index> | type <fieldId> <text> | key <name> | move <dir> [ms] | look <yaw> <pitch> | stop | attack [ms] | use [ms] | mine [ms] | target | nearby [r] | screenshot [name] | where | state | inv | openinv | slots | slot <id> | ping | help";

    public static String dispatch(String line) {
        if (line == null || line.isEmpty()) return "";
        String[] p = line.split("\\s+", 2);
        String verb = p[0].toLowerCase();
        String rest = p.length > 1 ? p[1].trim() : "";
        if (verb.equals("ping")) return "pong";
        if (verb.equals("help")) return HELP;
        if (verb.equals("describe")) return describe();
        if (verb.equals("click")) return click(rest);
        if (verb.equals("type")) return type(rest);
        if (verb.equals("key")) return key(rest);
        if (verb.equals("screenshot") || verb.equals("shot")) return Platform.screenshot(rest);
        if (verb.equals("where")) return Platform.where();
        if (verb.equals("state")) return Platform.state();
        if (verb.equals("inv") || verb.equals("inventory")) return Platform.inv();
        if (verb.equals("openinv") || verb.equals("openinventory")) return Platform.openInventory();
        if (verb.equals("slots")) return Platform.slots();
        if (verb.equals("slot")) return slot(rest);
        if (verb.equals("select") || verb.equals("enter")) return select(rest);
        if (verb.equals("move")) return move(rest);
        if (verb.equals("look")) return look(rest);
        if (verb.equals("stop")) return Platform.stopKeys();
        if (verb.equals("attack") || verb.equals("hit")) return attack(rest);
        if (verb.equals("use")) return use(rest);
        if (verb.equals("target")) return Platform.target();
        if (verb.equals("mine")) return mine(rest);
        if (verb.equals("nearby")) return nearby(rest);
        return "ERR unknown verb: " + verb;
    }

    private static String describe() {
        Object s = Platform.screen();
        if (s == null) return "screen: none";
        StringBuilder sb = new StringBuilder();
        int[] sz = Platform.screenSize(s);
        sb.append("screen: ").append(s.getClass().getSimpleName())
          .append(" size=").append(sz[0]).append('x').append(sz[1]);
        List<WidgetInfo> buttons = Platform.buttons(s);
        for (WidgetInfo w : buttons) {
            sb.append("\nbtn id=").append(w.id)
              .append(" label=\"").append(safe(w.label)).append('"')
              .append(" box=").append(w.x).append(',').append(w.y).append(',').append(w.w).append(',').append(w.h)
              .append(" active=").append(w.active)
              .append(" visible=").append(w.visible);
        }
        List<TfInfo> fields = Platform.textFields(s);
        for (TfInfo t : fields) {
            sb.append("\ntxt id=").append(t.id)
              .append(" text=\"").append(safe(t.text)).append('"')
              .append(" focused=").append(t.focused)
              .append(" visible=").append(t.visible);
        }
        List<WidgetInfo> entries = Platform.listEntries(s);
        for (WidgetInfo w : entries) {
            sb.append("\nlist id=").append(w.id)
              .append(" label=\"").append(safe(w.label)).append('"');
        }
        return sb.toString();
    }

    private static String click(String rest) {
        Object s = Platform.screen();
        if (s == null) return "ERR no screen";
        int id;
        try {
            id = Integer.parseInt(rest.trim());
        } catch (NumberFormatException e) {
            return "ERR click needs a numeric button id";
        }
        return Platform.click(s, id);
    }

    private static String type(String rest) {
        Object s = Platform.screen();
        if (s == null) return "ERR no screen";
        int sp = rest.indexOf(' ');
        if (sp < 0) return "ERR type needs: type <fieldId> <text>";
        return Platform.setText(s, rest.substring(0, sp), rest.substring(sp + 1));
    }

    private static String key(String rest) {
        Object s = Platform.screen();
        if (s == null) return "ERR no screen";
        return Platform.key(s, rest.trim());
    }

    private static String slot(String rest) {
        String[] a = rest.trim().split("\\s+");
        if (a.length == 0 || a[0].isEmpty()) return "ERR slot needs: slot <slotId> [button] [mode]";
        try {
            int id = Integer.parseInt(a[0]);
            int btn = a.length > 1 ? Integer.parseInt(a[1]) : 0;
            int mode = a.length > 2 ? Integer.parseInt(a[2]) : 0;
            return Platform.slotClick(id, btn, mode);
        } catch (NumberFormatException e) { return "ERR slot args must be integers"; }
    }

    private static String select(String rest) {
        Object s = Platform.screen();
        if (s == null) return "ERR no screen";
        int idx;
        try {
            idx = Integer.parseInt(rest.trim());
        } catch (NumberFormatException e) {
            return "ERR select needs a numeric list index";
        }
        return Platform.selectEntry(s, idx);
    }

    private static String move(String rest) {
        String[] a = rest.trim().split("\\s+");
        if (a.length == 0 || a[0].isEmpty()) return "ERR move needs: move <dir> [ms]";
        int ms = 500;
        if (a.length > 1) {
            try { ms = Integer.parseInt(a[1]); }
            catch (NumberFormatException e) { return "ERR move ms must be an integer"; }
        }
        return Platform.move(a[0], ms);
    }

    private static String look(String rest) {
        String[] a = rest.trim().split("\\s+");
        if (a.length < 2) return "ERR look needs: look <yaw> <pitch>";
        try { return Platform.look(Float.parseFloat(a[0]), Float.parseFloat(a[1])); }
        catch (NumberFormatException e) { return "ERR look args must be numbers"; }
    }

    private static String attack(String rest) {
        int ms = 150; String t = rest.trim();
        if (!t.isEmpty()) { try { ms = Integer.parseInt(t); } catch (NumberFormatException e) { return "ERR attack ms must be an integer"; } }
        return Platform.attack(ms);
    }

    private static String use(String rest) {
        int ms = 150; String t = rest.trim();
        if (!t.isEmpty()) { try { ms = Integer.parseInt(t); } catch (NumberFormatException e) { return "ERR use ms must be an integer"; } }
        return Platform.use(ms);
    }

    private static String mine(String rest) {
        int ms = 3000; String t = rest.trim();
        if (!t.isEmpty()) { try { ms = Integer.parseInt(t); } catch (NumberFormatException e) { return "ERR mine ms must be an integer"; } }
        return Platform.mine(ms);
    }

    private static String nearby(String rest) {
        double r = 8.0; String t = rest.trim();
        if (!t.isEmpty()) { try { r = Double.parseDouble(t); } catch (NumberFormatException e) { return "ERR nearby radius must be a number"; } }
        return Platform.nearby(r);
    }

    private static String safe(String s) {
        return s == null ? "" : s.replace('"', '\'').replace('\n', ' ');
    }
}



