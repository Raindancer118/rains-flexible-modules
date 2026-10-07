package de.raindancer.modules.playerutils.rules;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * What may be put in somebody else's mouth.
 *
 * <p>Some commands are never run through somebody else: anything that hands out or takes away power, stops
 * the server, or is sudo itself — a chain of sudos is how an audit trail loses who started it. Compared on
 * the bare command word, with any slash and namespace taken off, so {@code /minecraft:op} is still op.
 */
public final class SudoRule implements IPlayerUtilsRule {

    public static final List<String> DEFAULT_BLOCKED = List.of("op", "deop", "stop", "restart", "reload",
            "sudo", "lp", "luckperms", "perm", "perms", "permissions", "promote", "demote", "ban", "ban-ip",
            "pardon", "pardon-ip", "whitelist", "plugman", "execute", "rl", "prefix", "settings");

    /** The reason {@code line} may not be run as somebody else, or empty when it may. */
    public Optional<String> refuse(String line, List<String> blocked) {
        if (chatLine(line).isPresent()) {
            return Optional.empty();
        }
        String command = commandLine(line);
        if (command.isEmpty()) {
            return Optional.of("there is no command in that");
        }
        String word = command.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        int colon = word.indexOf(':');
        String bare = colon >= 0 ? word.substring(colon + 1) : word;
        for (String no : blocked) {
            if (no.equalsIgnoreCase(bare)) {
                return Optional.of("/" + bare + " is never run as somebody else");
            }
        }
        return Optional.empty();
    }

    /** The message, when {@code line} is {@code c:message} or {@code chat:message}. */
    public static Optional<String> chatLine(String line) {
        if (line == null) {
            return Optional.empty();
        }
        String stripped = line.strip();
        String lowered = stripped.toLowerCase(Locale.ROOT);
        for (String prefix : List.of("chat:", "c:")) {
            if (lowered.startsWith(prefix)) {
                String said = stripped.substring(prefix.length()).strip();
                return said.isEmpty() ? Optional.empty() : Optional.of(said);
            }
        }
        return Optional.empty();
    }

    public static String commandLine(String line) {
        String stripped = line == null ? "" : line.strip();
        return stripped.startsWith("/") ? stripped.substring(1).strip() : stripped;
    }

    @Override
    public String describe() {
        return "which commands may never be run as somebody else";
    }
}
