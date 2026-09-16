package de.raindancer.modules.worldutils.model;

import org.bukkit.World;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A world this module made, and therefore has to load again after every restart.
 *
 * <h2>Why a list is kept at all</h2>
 * Paper loads the primary level's worlds by itself and nothing else. A world made at runtime is simply
 * absent after the next restart — its folder is there, its players' last positions point into it, and
 * {@code /w} says it does not exist. That is the first surprise everybody meets with runtime worlds.
 */
public record ManagedWorld(String name, World.Environment environment) {

    /**
     * What a world may be called. A world's key is {@code minecraft:<name>}, and a key allows only these;
     * a name outside them fails deep inside world creation rather than at the command.
     */
    private static final Pattern VALID_NAME = Pattern.compile("[a-z0-9_.-]{1,48}");

    public ManagedWorld {
        environment = environment == null ? World.Environment.NORMAL : environment;
    }

    /** Whether {@code name} can be a world's name. */
    public static boolean isValidName(String name) {
        return name != null && VALID_NAME.matcher(name).matches();
    }

    /** Whether this is the world called {@code other}, the way Bukkit compares names. */
    public boolean isCalled(String other) {
        return other != null && name.toLowerCase(Locale.ROOT).equals(other.toLowerCase(Locale.ROOT));
    }
}
