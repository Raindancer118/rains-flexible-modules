package de.raindancer.modules.arena.rules;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** What a mob of a mob run is spared and what it is given: no sunburn, and the weapon its kind fights with. */
public final class WaveMobRule implements IArenaRule {

    private static final Map<String, String> WEAPONS = Map.of(
            "PIGLIN_BRUTE", "GOLDEN_AXE",
            "PIGLIN", "GOLDEN_SWORD",
            "SKELETON", "BOW",
            "STRAY", "BOW",
            "BOGGED", "BOW",
            "WITHER_SKELETON", "STONE_SWORD",
            "VINDICATOR", "IRON_AXE",
            "PILLAGER", "CROSSBOW",
            "ZOMBIFIED_PIGLIN", "GOLDEN_SWORD");

    /** @param caused set alight by something — fire aspect, flame, lava, a fire block — rather than by the sun */
    public boolean stopsBurning(boolean player, boolean inArena, boolean caused) {
        return !player && inArena && !caused;
    }

    public Optional<String> weaponFor(String entityType) {
        return Optional.ofNullable(WEAPONS.get(entityType.toUpperCase(Locale.ROOT)));
    }

    @Override
    public String describe() {
        return "no sunburn for a fight's mobs, and a weapon for those that fight with one";
    }
}
