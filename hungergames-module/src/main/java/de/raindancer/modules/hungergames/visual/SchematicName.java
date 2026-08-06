package de.raindancer.modules.hungergames.visual;

import java.util.Optional;

/**
 * Whether a string may be used as the name of a schematic file, decided without touching a disk.
 *
 * <h2>Why this is its own class, and pure</h2>
 * In the plugin this was ported from, the same guard lived inside the class that pasted schematics, tangled up
 * with a {@code File}, a canonical-path comparison and a static reference to the plugin. It was correct, and it
 * was untestable: proving it refused {@code ../../server.properties} meant a running server with a data folder.
 * So nothing proved it, and a guard nobody can exercise is a guard nobody notices the loss of.
 *
 * <p>It matters because the name reaches here from places a player can influence — a command argument, an
 * arena definition in a config file, a name typed into a menu. Everything this module pastes is one of six
 * files it ships, but the function that decides that has to be the one place it is decided, and it has to be
 * checkable.
 *
 * <h2>What is refused, and why each</h2>
 * <ul>
 *   <li><b>Anything with a separator or a dot-dot.</b> The classic traversal. {@code ../../../etc/passwd}
 *       resolved inside the schematic folder is a read of whatever the server process can read.</li>
 *   <li><b>An absolute path, and a Windows drive letter.</b> {@code /etc/shadow} and {@code C:\Windows} both
 *       escape without containing a single dot-dot, which is what a guard written only against {@code ..}
 *       misses.</li>
 *   <li><b>A null byte.</b> Historically the way a checked name and an opened file come apart: everything
 *       after the byte is dropped by some layers and kept by others, so the string that was validated is not
 *       the string that is opened.</li>
 *   <li><b>Anything but letters, digits, dash, underscore and a single trailing extension.</b> An allow-list,
 *       deliberately, because a deny-list of dangerous shapes is a list somebody has to have thought of
 *       everything for. These are files this project ships; they do not need interesting names.</li>
 * </ul>
 *
 * <p>The canonical-path check the source did as well is still worth doing, and still happens where the file
 * is actually opened — see {@link Schematics}. This is the cheap answer that can be tested; that is the
 * belt to this pair of braces.
 */
public final class SchematicName {

    /** The longest a name may be. Long enough for anything real, short enough not to be a payload. */
    private static final int LONGEST = 64;

    /**
     * Letters, digits, dash and underscore, then one extension of two to five letters.
     *
     * <p>The extension is required rather than optional: every caller names a concrete file, and a name with
     * no extension is a directory as often as it is a mistake.
     */
    private static final java.util.regex.Pattern ALLOWED =
            java.util.regex.Pattern.compile("[A-Za-z0-9_-]{1,58}\\.[A-Za-z]{2,5}");

    private SchematicName() {
    }

    /**
     * The name, if it is safe to use.
     *
     * <p>Empty rather than an exception: a bad name arrives from a config file or a command as often as from
     * a bug, and the caller is the one that knows whether to answer a player, log a line, or give up on
     * building the arena. Empty rather than a default, too — falling back to some other schematic would build
     * a different arena from the one that was asked for and say nothing.
     */
    public static Optional<String> checked(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String trimmed = name.strip();
        if (trimmed.isEmpty() || trimmed.length() > LONGEST) {
            return Optional.empty();
        }
        // Before the pattern, because a null byte inside a name that otherwise matches is the case where what
        // was checked and what gets opened are two different strings.
        if (trimmed.indexOf('\0') >= 0) {
            return Optional.empty();
        }
        if (trimmed.contains("..") || trimmed.contains("/") || trimmed.contains("\\")) {
            return Optional.empty();
        }
        // A drive letter escapes without a separator or a dot-dot, which is what a guard written against
        // those two alone lets through.
        if (trimmed.length() >= 2 && trimmed.charAt(1) == ':') {
            return Optional.empty();
        }
        if (!ALLOWED.matcher(trimmed).matches()) {
            return Optional.empty();
        }
        return Optional.of(trimmed);
    }

    /** Whether that name may be used. {@link #checked} where the name itself is wanted. */
    public static boolean isSafe(String name) {
        return checked(name).isPresent();
    }
}
