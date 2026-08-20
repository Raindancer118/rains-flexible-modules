package de.raindancer.modules.xaeromap.model;

import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;
import java.util.Locale;

/**
 * A waypoint, in the one form a plain Xaero's Minimap client will take from a server.
 *
 * <h2>Why a chat line and not a packet</h2>
 * There is no server-to-client waypoint protocol in either Xaero mod. What there <em>is</em> is the
 * mod's own "share a waypoint in chat" feature: a chat message consisting of nothing but
 * {@code xaero-waypoint:…} is caught by the client, replaced with a button, and adding it is one click.
 * That is the whole of what a server can do here without asking players to install something else, and
 * it is why homes arrive as an <em>offer</em> rather than appearing by themselves — see
 * {@code WaypointService} for the two consequences that follow from it.
 *
 * <h2>The format</h2>
 * <pre>xaero-waypoint:name:initials:x:y:z:colour:useYaw:yaw:Internal-&lt;dimension&gt;-waypoints</pre>
 * Ten fields separated by colons, and the count is load-bearing: the client checks it before anything
 * else and ignores a line with nine or eleven. Which is also why a name may not contain a colon —
 * {@link #safeName} takes them out rather than sending a line that is silently dropped.
 */
public final class XaeroShare {

    /** What every share line starts with. */
    public static final String PREFIX = "xaero-waypoint";

    /** Separator, and therefore the one character a name may not contain. */
    public static final String SEPARATOR = ":";

    /** The client's own ceiling on a waypoint name. */
    public static final int MAX_NAME = 32;

    /** A marker holds two characters. Not a style choice — the third is not drawn. */
    public static final int MAX_INITIALS = 2;

    /**
     * The client's sixteen colours, in the order it indexes them — which is vanilla's own order.
     *
     * <p>Not a colour <em>value</em>: the field on the wire is an index into this list, so a colour
     * that is not one of these has to become the nearest one that is.
     */
    private static final List<NamedTextColor> COLOURS = List.of(
            NamedTextColor.BLACK, NamedTextColor.DARK_BLUE, NamedTextColor.DARK_GREEN,
            NamedTextColor.DARK_AQUA, NamedTextColor.DARK_RED, NamedTextColor.DARK_PURPLE,
            NamedTextColor.GOLD, NamedTextColor.GRAY, NamedTextColor.DARK_GRAY,
            NamedTextColor.BLUE, NamedTextColor.GREEN, NamedTextColor.AQUA,
            NamedTextColor.RED, NamedTextColor.LIGHT_PURPLE, NamedTextColor.YELLOW,
            NamedTextColor.WHITE);

    private XaeroShare() {
    }

    /** The whole line, ready to be sent as a chat message of its own. */
    public static String line(Waypoint waypoint) {
        return String.join(SEPARATOR,
                PREFIX,
                safeName(waypoint.name()),
                initialsOf(waypoint.name()),
                String.valueOf(waypoint.x()),
                String.valueOf(waypoint.y()),
                String.valueOf(waypoint.z()),
                String.valueOf(colourIndex(waypoint.colour())),
                // Yaw is deliberately not sent. It only decides which way a player faces after
                // teleporting to the waypoint through the mod, this server does not teleport anybody
                // through the mod, and a stored yaw of zero would turn every arrival due south.
                "false",
                "0",
                dimensionOf(waypoint.dimensionKey()));
    }

    /** A name that cannot break the line: no colons, no newlines, and short enough to be drawn. */
    public static String safeName(String name) {
        String cleaned = (name == null ? "" : name)
                .replace(SEPARATOR, " ")
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim();
        if (cleaned.isEmpty()) {
            cleaned = "Place";
        }
        return cleaned.length() > MAX_NAME ? cleaned.substring(0, MAX_NAME).trim() : cleaned;
    }

    /** The two characters drawn in the marker itself. */
    public static String initialsOf(String name) {
        String cleaned = safeName(name);
        String[] words = cleaned.split("\\s+");
        if (words.length > 1 && !words[0].isEmpty() && !words[1].isEmpty()) {
            // Two words, two initials: "Sunset Hill" is SH, which is worth more on a crowded map than
            // "Su" — the first two letters of two neighbouring places are often the same.
            return ("" + words[0].charAt(0) + words[1].charAt(0)).toUpperCase(Locale.ROOT);
        }
        return cleaned.length() <= MAX_INITIALS
                ? cleaned.toUpperCase(Locale.ROOT)
                : cleaned.substring(0, MAX_INITIALS).toUpperCase(Locale.ROOT);
    }

    /** The nearest of the client's sixteen colours, as its index. */
    public static int colourIndex(NamedTextColor colour) {
        if (colour == null) {
            return COLOURS.indexOf(NamedTextColor.WHITE);
        }
        int exact = COLOURS.indexOf(colour);
        return exact >= 0 ? exact : nearest(colour.value());
    }

    /**
     * A world key as the client's own waypoint list files it.
     *
     * <p>The three vanilla dimensions have names of their own; everything else — which on a Bukkit
     * server is every world somebody made, since Paper keys those {@code minecraft:<lowercased name>}
     * — is escaped: {@code :} becomes {@code $}, {@code /} becomes {@code %}, and {@code _} becomes
     * {@code -}, behind a {@code dim%} prefix.
     *
     * <p>Worth being straight about the failure here, because it is not a crash: a dimension string the
     * client files differently puts the waypoint in another of its own sub-worlds rather than losing
     * it. The player still has the waypoint; it is on the wrong map until they switch worlds.
     */
    public static String dimensionOf(String worldKey) {
        String key = worldKey == null ? "" : worldKey.trim().toLowerCase(Locale.ROOT);
        String dimension = switch (key) {
            case "minecraft:overworld" -> "overworld";
            case "minecraft:the_nether" -> "the-nether";
            case "minecraft:the_end" -> "the-end";
            default -> "dim%" + key.replace(":", "$").replace("/", "%").replace("_", "-");
        };
        return "Internal-" + dimension + "-waypoints";
    }

    /** Whether a line is one of ours — for the test that proves a client would accept it. */
    public static boolean looksValid(String line) {
        String[] fields = line == null ? new String[0] : line.split(SEPARATOR);
        return fields.length == 10 && PREFIX.equals(fields[0]);
    }

    private static int nearest(int rgb) {
        int best = COLOURS.indexOf(NamedTextColor.WHITE);
        long closest = Long.MAX_VALUE;
        for (int i = 0; i < COLOURS.size(); i++) {
            int candidate = COLOURS.get(i).value();
            long distance = squared(rgb >> 16 & 0xFF, candidate >> 16 & 0xFF)
                    + squared(rgb >> 8 & 0xFF, candidate >> 8 & 0xFF)
                    + squared(rgb & 0xFF, candidate & 0xFF);
            if (distance < closest) {
                closest = distance;
                best = i;
            }
        }
        return best;
    }

    private static long squared(int one, int other) {
        long difference = one - other;
        return difference * difference;
    }
}
