package de.raindancer.modules.anticheat.model;

import java.util.Locale;
import java.util.Optional;

/**
 * Every check this module runs, with the numbers that decide what a failure leads to.
 *
 * <p>{@code experimental} checks are statistical: they may alert staff, but never set back, cancel,
 * kick or ban — a human reads them. {@code kickAt}/{@code banAt} of 0 means never.
 *
 * @param decayPerMinute how much violation level a player loses per clean minute
 */
public enum CheckType {

    FLY("fly", Category.MOVEMENT, "Fly", "Vertical movement against gravity: hovering, ascending without a jump, jumping too high, stepping too high, climbing walls.",
            false, Action.SETBACK, 3, 40, 80, 6),
    SPEED("speed", Category.MOVEMENT, "Speed", "Horizontal movement faster than friction and acceleration allow.",
            false, Action.SETBACK, 3, 40, 80, 6),
    STRAFE("strafe", Category.MOVEMENT, "Strafe", "Changing course in mid-air faster than air control allows.",
            false, Action.SETBACK, 3, 40, 0, 6),
    NO_SLOW("noslow", Category.MOVEMENT, "NoSlow", "Moving at full speed while eating, blocking, drawing a bow or sneaking.",
            false, Action.SETBACK, 3, 40, 0, 6),
    NO_FALL("nofall", Category.MOVEMENT, "NoFall", "Claiming to stand on ground that is not there, to skip fall damage.",
            false, Action.SETBACK, 2, 30, 60, 6),
    TIMER("timer", Category.MOVEMENT, "Timer", "Running the game faster than twenty ticks a second.",
            false, Action.SETBACK, 3, 30, 60, 6),
    PHASE("phase", Category.MOVEMENT, "Phase", "Moving into or through solid blocks.",
            false, Action.SETBACK, 1, 30, 60, 6),
    JESUS("jesus", Category.MOVEMENT, "Jesus", "Walking on water or lava.",
            false, Action.SETBACK, 2, 30, 0, 6),
    SPRINT("sprint", Category.MOVEMENT, "Sprint", "Sprinting where the game does not allow it: backwards, starving, while using an item.",
            false, Action.SETBACK, 3, 0, 0, 6),
    ELYTRA("elytra", Category.MOVEMENT, "Elytra", "Gliding that climbs or hovers without a firework.",
            false, Action.SETBACK, 3, 40, 0, 6),
    VEHICLE("vehicle", Category.MOVEMENT, "Vehicle", "Boats and rides flying or outrunning their own physics.",
            false, Action.SETBACK, 3, 40, 0, 6),

    REACH("reach", Category.COMBAT, "Reach", "Hitting from further away than the attack range allows.",
            false, Action.CANCEL, 2, 25, 60, 4),
    HITBOX("hitbox", Category.COMBAT, "Hitbox", "Hitting something the crosshair was not on.",
            false, Action.CANCEL, 3, 30, 0, 4),
    WALL_HIT("wallhit", Category.COMBAT, "WallHit", "Hitting through solid blocks.",
            false, Action.CANCEL, 2, 30, 0, 4),
    MULTI_AURA("multiaura", Category.COMBAT, "MultiAura", "Hitting several targets in one tick.",
            false, Action.CANCEL, 1, 20, 40, 4),
    INVALID_ATTACK("killaura", Category.COMBAT, "KillAura", "Attacking while eating, blocking, sleeping, dead or in a container.",
            false, Action.CANCEL, 2, 25, 0, 4),
    NO_SWING("noswing", Category.COMBAT, "NoSwing", "Hitting without swinging the arm.",
            false, Action.NONE, 3, 30, 0, 4),
    AUTOCLICKER("autoclicker", Category.COMBAT, "AutoClicker", "Clicking faster or more evenly than a hand can.",
            false, Action.NONE, 3, 30, 0, 3),
    CRITICALS("criticals", Category.COMBAT, "Criticals", "Critical hits without actually falling.",
            false, Action.CANCEL, 2, 25, 0, 4),
    VELOCITY("velocity", Category.COMBAT, "Velocity", "Taking no knockback.",
            false, Action.NONE, 3, 25, 0, 4),
    KEEP_SPRINT("keepsprint", Category.COMBAT, "KeepSprint", "Keeping full speed after a sprinting hit.",
            true, Action.NONE, 6, 0, 0, 3),
    AIM("aim", Category.COMBAT, "Aim", "Rotations no mouse produces: no sensitivity step, wrapped yaw, snaps onto targets.",
            true, Action.NONE, 8, 0, 0, 3),

    FAST_BREAK("fastbreak", Category.WORLD, "FastBreak", "Breaking blocks faster than the tool allows.",
            false, Action.CANCEL, 2, 30, 0, 4),
    NUKER("nuker", Category.WORLD, "Nuker", "Breaking many blocks at once.",
            false, Action.CANCEL, 1, 15, 40, 4),
    FAST_PLACE("fastplace", Category.WORLD, "FastPlace", "Placing blocks faster than clicking can.",
            false, Action.CANCEL, 2, 30, 0, 4),
    SCAFFOLD("scaffold", Category.WORLD, "Scaffold", "Placing on a face that cannot be seen, against air, or without looking at it.",
            false, Action.CANCEL, 2, 25, 60, 4),
    BLOCK_REACH("blockreach", Category.WORLD, "BlockReach", "Breaking, placing or using blocks out of reach.",
            false, Action.CANCEL, 2, 25, 0, 4),
    GHOST_HAND("ghosthand", Category.WORLD, "GhostHand", "Opening or breaking blocks through walls.",
            false, Action.CANCEL, 2, 25, 0, 4),
    AUTO_FISH("autofish", Category.WORLD, "AutoFish", "Reeling in with inhuman reaction times.",
            true, Action.NONE, 5, 0, 0, 2),

    INVENTORY_MOVE("inventorymove", Category.INVENTORY, "InventoryMove", "Walking while a container is open, or clicking while walking.",
            false, Action.CANCEL, 3, 30, 0, 4),
    FAST_CLICK("fastclick", Category.INVENTORY, "ChestStealer", "Inventory clicks faster than a hand can.",
            true, Action.NONE, 6, 0, 0, 3),
    AUTO_TOTEM("autototem", Category.INVENTORY, "AutoTotem", "Refilling the off hand with a totem the instant one pops.",
            true, Action.NONE, 3, 0, 0, 2),

    BAD_PACKETS("badpackets", Category.PACKETS, "BadPackets", "Packets the game never sends: impossible pitch, repeated rotations or slots, self-interaction.",
            false, Action.NONE, 1, 20, 40, 4),
    PING_SPOOF("pingspoof", Category.PACKETS, "PingSpoof", "Holding back keep-alive answers to fake a bad connection and buy lag compensation.",
            false, Action.NONE, 3, 0, 0, 3),
    POST("post", Category.PACKETS, "Post", "Actions sent after the tick's movement, the way a killaura sends them.",
            true, Action.NONE, 8, 0, 0, 3),

    IMPROBABLE("improbable", Category.OVERALL, "Improbable", "Many checks failing a little at the same time — the shape of a full cheat client.",
            false, Action.NONE, 1, 0, 0, 2),

    CLIENT("client", Category.CLIENT, "Client", "A client brand or channel this server does not allow.",
            false, Action.NONE, 1, 0, 0, 0);

    /** What a failed check does to the thing that failed it, once the player is past {@code alertAt}. */
    public enum Action {
        /** Nothing but the violation level. */
        NONE,
        /** Teleport back to the last place they were legitimately. */
        SETBACK,
        /** Cancel the event: the hit, the break, the placement. */
        CANCEL
    }

    private final String key;
    private final Category category;
    private final String title;
    private final String description;
    private final boolean experimental;
    private final Action action;
    private final int alertAt;
    private final int kickAt;
    private final int banAt;
    private final double decayPerMinute;

    CheckType(String key, Category category, String title, String description, boolean experimental,
              Action action, int alertAt, int kickAt, int banAt, double decayPerMinute) {
        this.key = key;
        this.category = category;
        this.title = title;
        this.description = description;
        this.experimental = experimental;
        this.action = experimental ? Action.NONE : action;
        this.alertAt = alertAt;
        this.kickAt = experimental ? 0 : kickAt;
        this.banAt = experimental ? 0 : banAt;
        this.decayPerMinute = decayPerMinute;
    }

    public String key() {
        return key;
    }

    public Category category() {
        return category;
    }

    public String title() {
        return title;
    }

    public String description() {
        return description;
    }

    public boolean experimental() {
        return experimental;
    }

    public Action action() {
        return action;
    }

    public int alertAt() {
        return alertAt;
    }

    public int kickAt() {
        return kickAt;
    }

    public int banAt() {
        return banAt;
    }

    public double decayPerMinute() {
        return decayPerMinute;
    }

    /** By key ({@code fly}) or by name ({@code FLY}, {@code no_fall}), case-insensitive. */
    public static Optional<CheckType> find(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String wanted = text.trim().toLowerCase(Locale.ROOT);
        for (CheckType type : values()) {
            if (type.key.equals(wanted) || type.name().toLowerCase(Locale.ROOT).equals(wanted)
                    || type.title.toLowerCase(Locale.ROOT).equals(wanted)) {
                return Optional.of(type);
            }
        }
        return Optional.empty();
    }
}
