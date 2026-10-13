package de.raindancer.modules.oneblock.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The answer to a player's request: the line they are told (a message key and its placeholder values in pairs), and
 * whether they are to be sent over to the island server. Travels over the link as a flat map.
 */
public record Reply(boolean ok, String key, List<String> values, boolean transfer) {

    public Reply {
        values = List.copyOf(values);
    }

    public static Reply ok(String key, String... values) {
        return new Reply(true, key, List.of(values), false);
    }

    public static Reply refused(String key, String... values) {
        return new Reply(false, key, List.of(values), false);
    }

    /** Said and nothing else to do — {@code key} empty when whatever ran speaks for itself. */
    public static Reply silent() {
        return new Reply(true, "", List.of(), false);
    }

    public Reply andTransfer() {
        return new Reply(ok, key, values, true);
    }

    public Map<String, String> toWire() {
        Map<String, String> wire = new HashMap<>();
        wire.put("ok", String.valueOf(ok));
        wire.put("key", key);
        wire.put("transfer", String.valueOf(transfer));
        wire.put("n", String.valueOf(values.size()));
        for (int i = 0; i < values.size(); i++) {
            wire.put("v" + i, values.get(i));
        }
        return wire;
    }

    /** An answer that cannot be read is no answer: {@code unread} stands in for it. */
    public static Reply fromWire(Map<String, String> wire, Reply unread) {
        if (wire == null || !wire.containsKey("key")) {
            return unread;
        }
        List<String> values = new ArrayList<>();
        int count;
        try {
            count = Math.clamp(Integer.parseInt(wire.getOrDefault("n", "0")), 0, 32);
        } catch (NumberFormatException garbled) {
            return unread;
        }
        for (int i = 0; i < count; i++) {
            values.add(wire.getOrDefault("v" + i, ""));
        }
        return new Reply("true".equals(wire.get("ok")), wire.get("key"), values, "true".equals(wire.get("transfer")));
    }
}
