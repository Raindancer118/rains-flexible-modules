package de.raindancer.modules.manhunt.tracker;

import org.bukkit.World;

import java.util.List;
import java.util.Optional;

/**
 * The structures a Runner's structure compass can point at, per dimension — strongholds deliberately
 * not among them, so the compass is no shortcut to the End. Plain keys (vanilla structure ids) rather
 * than Bukkit's registry objects, so the list is testable without a server; they are resolved when a
 * Runner actually chooses.
 */
public final class StructureChoices {

    /** How close counts as there — measured flat, since structures like ancient cities lie deep. */
    public static final double REACHED_WITHIN = 20;

    /** One thing to choose: an id, a label, the icon, and every vanilla variant that counts. */
    public record Choice(String id, String label, String icon, List<String> structureKeys) {
    }

    private static final List<Choice> OVERWORLD = List.of(
            new Choice("village", "Village", "BELL", List.of("village_plains", "village_desert",
                    "village_savanna", "village_snowy", "village_taiga")),
            new Choice("desert_pyramid", "Desert temple", "SANDSTONE", List.of("desert_pyramid")),
            new Choice("jungle_pyramid", "Jungle temple", "MOSSY_COBBLESTONE", List.of("jungle_pyramid")),
            new Choice("swamp_hut", "Witch hut", "CAULDRON", List.of("swamp_hut")),
            new Choice("igloo", "Igloo", "SNOW_BLOCK", List.of("igloo")),
            new Choice("pillager_outpost", "Pillager outpost", "CROSSBOW", List.of("pillager_outpost")),
            new Choice("mansion", "Woodland mansion", "DARK_OAK_LOG", List.of("mansion")),
            new Choice("monument", "Ocean monument", "PRISMARINE", List.of("monument")),
            new Choice("shipwreck", "Shipwreck", "OAK_BOAT", List.of("shipwreck", "shipwreck_beached")),
            new Choice("ocean_ruin", "Ocean ruins", "SUSPICIOUS_SAND", List.of("ocean_ruin_cold",
                    "ocean_ruin_warm")),
            new Choice("buried_treasure", "Buried treasure", "CHEST", List.of("buried_treasure")),
            new Choice("ruined_portal", "Ruined portal", "CRYING_OBSIDIAN", List.of("ruined_portal",
                    "ruined_portal_desert", "ruined_portal_jungle", "ruined_portal_swamp",
                    "ruined_portal_mountain", "ruined_portal_ocean")),
            new Choice("mineshaft", "Mineshaft", "RAIL", List.of("mineshaft", "mineshaft_mesa")),
            new Choice("ancient_city", "Ancient city", "SCULK", List.of("ancient_city")),
            new Choice("trail_ruins", "Trail ruins", "BRUSH", List.of("trail_ruins")),
            new Choice("trial_chambers", "Trial chambers", "TRIAL_SPAWNER", List.of("trial_chambers")));

    private static final List<Choice> NETHER = List.of(
            new Choice("fortress", "Nether fortress", "NETHER_BRICKS", List.of("fortress")),
            new Choice("bastion_remnant", "Bastion", "GILDED_BLACKSTONE", List.of("bastion_remnant")),
            new Choice("ruined_portal_nether", "Ruined portal", "CRYING_OBSIDIAN", List.of("ruined_portal_nether")));

    private static final List<Choice> END = List.of(
            new Choice("end_city", "End city", "PURPUR_BLOCK", List.of("end_city")));

    private StructureChoices() {
    }

    /** Every choice in every dimension. */
    public static List<Choice> all() {
        return java.util.stream.Stream.of(OVERWORLD, NETHER, END).flatMap(List::stream).toList();
    }

    /** What can be chosen standing in {@code environment}. */
    public static List<Choice> in(World.Environment environment) {
        return switch (environment) {
            case NETHER -> NETHER;
            case THE_END -> END;
            default -> OVERWORLD;
        };
    }

    public static Optional<Choice> byId(String id) {
        return java.util.stream.Stream.of(OVERWORLD, NETHER, END).flatMap(List::stream)
                .filter(choice -> choice.id().equals(id)).findFirst();
    }

    /** Whether somebody at {@code (x, z)} has reached a destination at {@code (tx, tz)}. */
    public static boolean reached(String world, double x, double z, String targetWorld, double tx, double tz) {
        if (world == null || !world.equals(targetWorld)) {
            return false;
        }
        return Math.hypot(tx - x, tz - z) <= REACHED_WITHIN;
    }
}
