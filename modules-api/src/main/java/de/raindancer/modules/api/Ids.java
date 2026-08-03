package de.raindancer.modules.api;

import java.util.regex.Pattern;

/**
 * The one place a name is checked.
 *
 * <p>A module id is not a label. It becomes a folder under the host's data folder, a section in a
 * settings tree, part of a permission node, and the token another module writes down to depend on this
 * one. Each of those has its own idea of what a legal character is, and the intersection is narrow —
 * so rather than four half-checks in four places, everything goes through here and the rule is one
 * regular expression.
 *
 * <p>The strictness is load-bearing rather than fussy. {@link ModuleLayout} resolves a folder from an
 * id, so an id of {@code ../../..} would put a module's files outside the plugins directory; and an id
 * differing from another only in case would be two modules on Linux and one on Windows.
 */
final class Ids {

    /** Lower case, digits, single dashes between parts, starting with a letter. */
    private static final Pattern MODULE_ID = Pattern.compile("[a-z][a-z0-9]*(-[a-z0-9]+)*");

    /** Long enough for anything readable, short enough to be a folder name on any filesystem. */
    private static final int MAX_ID = 64;

    /** Brigadier is happy with more, but a command nobody can type is not a feature. */
    private static final int MAX_COMMAND = 32;

    private Ids() {
    }

    /** @return the id, so this can be used inside a record's compact constructor */
    static String checkModuleId(String id) {
        return check(id, "module id", MAX_ID);
    }

    static String checkCommandName(String name) {
        if (name != null && name.startsWith("/")) {
            throw new IllegalArgumentException(
                    "a command name is written without its slash, so '" + name + "' should be '"
                            + name.substring(1) + "'");
        }
        return check(name, "command name", MAX_COMMAND);
    }

    private static String check(String value, String what, int maxLength) {
        if (value == null) {
            throw new IllegalArgumentException("a " + what + " is required, and this one is null");
        }
        if (value.isBlank()) {
            throw new IllegalArgumentException("a " + what + " is required, and this one is blank");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(
                    "a " + what + " may be at most " + maxLength + " characters, and '" + value
                            + "' is " + value.length());
        }
        if (!MODULE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "a " + what + " is lower case letters, digits and single dashes, starting with a "
                            + "letter — '" + value + "' is not");
        }
        return value;
    }

    /** For the human-facing fields, which may be anything as long as they say something. */
    static String required(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("a module's " + what + " may not be blank");
        }
        return value.strip();
    }
}
