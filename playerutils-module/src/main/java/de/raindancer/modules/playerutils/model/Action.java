package de.raindancer.modules.playerutils.model;

import org.bukkit.Material;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Every single thing this module can do to, or find out about, a player.
 *
 * <p>One list rather than a class per command because they differ in data and nothing else: a word, a
 * sentence, a category, an icon, whether it hurts, and what else it can be told. Written out one class each
 * they drift, and the drift is always the same — one forgets the audit, one forgets that naming somebody
 * else needs a second permission, one forgets that an exempt player may not be hurt.
 */
public enum Action {

    HEAL("heal", "Restores somebody to full health, puts out fire and takes the bad effects off",
            Category.VITALS, Material.GOLDEN_APPLE, Harm.NONE, Self.OP, List.of()),
    FEED("feed", "Fills somebody's hunger and saturation", Category.VITALS, Material.COOKED_BEEF,
            Harm.NONE, Self.OP, List.of()),
    DAMAGE("damage", "Takes hearts from somebody — never all of them unless told to",
            Category.VITALS, Material.IRON_SWORD, Harm.HARMFUL, Self.OP,
            List.of(Parameter.number("hearts", 0.5, 1000, 5), Parameter.flag("lethal"))),
    STARVE("starve", "Empties somebody's hunger bar, or sets it", Category.VITALS, Material.ROTTEN_FLESH,
            Harm.HARMFUL, Self.OP, List.of(Parameter.number("drumsticks", 0, 10, 0))),
    DROWN("drown", "Empties somebody's lungs and lets the water have a go", Category.VITALS,
            Material.PUFFERFISH, Harm.HARMFUL, Self.OP, List.of(Parameter.number("hearts", 0, 1000, 2))),
    BREATHE("breathe", "Fills somebody's lungs", Category.VITALS, Material.CONDUIT, Harm.NONE, Self.OP,
            List.of()),
    EXTINGUISH("extinguish", "Puts somebody out", Category.VITALS, Material.WATER_BUCKET, Harm.NONE,
            Self.OP, List.of()),
    IGNITE("ignite", "Sets somebody alight", Category.VITALS, Material.FLINT_AND_STEEL, Harm.HARMFUL,
            Self.OP, List.of(Parameter.number("seconds", 1, 3600, 5))),

    FLY("fly", "Lets somebody fly — through relogs, deaths and gamemode changes", Category.MOVEMENT,
            Material.ELYTRA, Harm.NONE, Self.OP, List.of(Parameter.word("state", "toggle", "on", "off"))),
    SPEED("speed", "How fast somebody walks or flies, 0 to 10", Category.MOVEMENT, Material.SUGAR,
            Harm.NONE, Self.OP, List.of(Parameter.word("kind", "auto", "walk", "fly", "both"),
            Parameter.word("reset", "set", "reset"), Parameter.number("level", 0, 10, 1))),
    LAUNCH("launch", "Throws somebody into the air, or the way they face", Category.MOVEMENT,
            Material.FIREWORK_ROCKET, Harm.HARMFUL, Self.OP,
            List.of(Parameter.word("way", "up", "forward", "look"), Parameter.number("power", 0.1, 10, 2))),
    SPECTATE("spectate", "Watches somebody through their own eyes, and puts you back after",
            Category.MOVEMENT, Material.ENDER_EYE, Harm.NONE, Self.NEVER, List.of()),

    SCALE("scale", "Makes somebody bigger or smaller", Category.BODY, Material.SLIME_BALL, Harm.NONE,
            Self.OP, List.of(Parameter.word("reset", "set", "reset"), Parameter.number("size", 0.0625, 16, 1))),
    WIPE("wipe", "Clears somebody's inventory, advancements and experience", Category.BODY,
            Material.LAVA_BUCKET, Harm.DESTRUCTIVE, Self.OP,
            List.of(Parameter.flag("inventory"), Parameter.flag("enderchest"), Parameter.flag("advancements"),
                    Parameter.flag("xp"), Parameter.flag("effects"), Parameter.flag("all"),
                    Parameter.flag("confirm"))),
    EXPLODE("explode", "A bang where somebody stands", Category.POWER, Material.TNT, Harm.DESTRUCTIVE,
            Self.OP, List.of(Parameter.number("power", 0.5, 16, 2), Parameter.flag("blocks"),
            Parameter.flag("fire"))),
    SUDO("sudo", "Makes somebody run a command, or say something (c:hello)", Category.POWER,
            Material.PLAYER_HEAD, Harm.HARMFUL, Self.NEVER, List.of(Parameter.rest("command"))),

    PING("ping", "How long somebody's connection takes to answer", Category.INFO, Material.CLOCK,
            Harm.NONE, Self.EVERYBODY, List.of()),
    STATUS("status", "Everything about somebody right now", Category.INFO, Material.BOOK, Harm.NONE,
            Self.EVERYBODY, List.of()),
    HUNGER("hunger", "Somebody's hunger, saturation and exhaustion", Category.INFO, Material.BREAD,
            Harm.NONE, Self.EVERYBODY, List.of()),
    EFFECTS("effects", "The effects on somebody, and how long they have left", Category.INFO,
            Material.POTION, Harm.NONE, Self.EVERYBODY, List.of()),
    POSITION("position", "Where somebody is, which way they face, which chunk and biome",
            Category.INFO, Material.COMPASS, Harm.NONE, Self.EVERYBODY, List.of()),
    NEAR("near", "Who is near you, how far and which way", Category.INFO, Material.SPYGLASS, Harm.NONE,
            Self.OP, List.of(Parameter.number("radius", 1, 10000, 200)));

    /** How much an action can cost the player it is pointed at. */
    public enum Harm {
        /** Nothing they would mind. */
        NONE,
        /** Hurts, or takes something away for a while — refused against exempt players. */
        HARMFUL,
        /** Takes something away for good — refused against exempt players, and confirmed first. */
        DESTRUCTIVE
    }

    /** Who may point it at themselves by default. */
    public enum Self {
        EVERYBODY, OP, NEVER
    }

    private final String word;
    private final String description;
    private final Category category;
    private final Material icon;
    private final Harm harm;
    private final Self self;
    private final List<Parameter> parameters;

    Action(String word, String description, Category category, Material icon, Harm harm, Self self,
           List<Parameter> parameters) {
        this.word = word;
        this.description = description;
        this.category = category;
        this.icon = icon;
        this.harm = harm;
        this.self = self;
        this.parameters = List.copyOf(parameters);
    }

    public String word() {
        return word;
    }

    public String describe() {
        return description;
    }

    public Category category() {
        return category;
    }

    public Material icon() {
        return icon;
    }

    public Harm harm() {
        return harm;
    }

    public Self self() {
        return self;
    }

    public List<Parameter> parameters() {
        return parameters;
    }

    public boolean isHarmful() {
        return harm != Harm.NONE;
    }

    /** {@code rainsplayerutils.heal} — pointing it at yourself. */
    public String node() {
        return "rainsplayerutils." + word;
    }

    /** {@code rainsplayerutils.heal.others} — pointing it at somebody else. */
    public String othersNode() {
        return node() + ".others";
    }

    public boolean isInfo() {
        return category == Category.INFO;
    }

    public static Optional<Action> byWord(String word) {
        if (word == null) {
            return Optional.empty();
        }
        String lowered = word.toLowerCase(Locale.ROOT);
        for (Action action : values()) {
            if (action.word.equals(lowered)) {
                return Optional.of(action);
            }
        }
        return Optional.empty();
    }
}
