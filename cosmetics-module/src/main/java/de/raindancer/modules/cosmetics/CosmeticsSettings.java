package de.raindancer.modules.cosmetics;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What an owner decides about cosmetics. Who may wear what is permissions, not settings — see
 * {@code util.PermissionNodes}. The palette and presets are lists, so they are hand-read from the same
 * file by {@code store.CatalogueFile}.
 *
 * <p>Keys start with {@code name-} because Core's {@code /settings} namespace is flat and names-module
 * already owns {@code max-gradient-stops}.
 */
@Settings(id = "cosmetics", topics = {
        @Topic(path = "cosmetics", title = "Cosmetics", icon = Material.NAME_TAG),
        @Topic(path = "cosmetics/names", title = "Name styles", icon = Material.NAME_TAG),
        @Topic(path = "cosmetics/particles", title = "Particles", icon = Material.BLAZE_POWDER),
        @Topic(path = "cosmetics/teleport", title = "Teleports", icon = Material.ENDER_PEARL),
        @Topic(path = "cosmetics/prices", title = "Prices", icon = Material.GOLD_NUGGET),
})
public record CosmeticsSettings(

        @In("cosmetics/names") @Title("Most colours in one name") @Range(min = 1, max = 8)
        @Describe("How many stops a custom gradient may have. Presets are not held to this.")
        @Key("name-max-stops")
        int maxStops,

        @In("cosmetics/names") @Title("Take a style off when its permission is gone")
        @Describe("Checked when somebody joins. On: a player who lost the rank that gave them a "
                + "gradient is back to a plain name, and told why. Off: what they once picked stays.")
        @Key("name-drop-without-permission")
        boolean dropWithoutPermission,

        @In("cosmetics/names") @Title("Styled names above heads")
        @Describe("On: the name over everybody's head shows their colours, gradient, decorations and "
                + "nickname — drawn by RainsCore, with the vanilla one hidden. Off: the plain vanilla "
                + "nametag, and styles show in chat and the player list only.")
        @Key("name-above-head")
        boolean nameAboveHead,

        @In("cosmetics/particles") @Title("Players may wear particles")
        @Describe("Off: nobody's particles are drawn, and nobody can pick one. What they picked is kept.")
        @Key("particles-enabled")
        boolean particlesEnabled,

        @In("cosmetics/particles") @Title("Draw them every") @Range(min = 2, max = 40)
        @Describe("Ticks between two draws. Lower is smoother and costs more; 4 is five times a second.")
        @Key("particles-every-ticks")
        int particleEveryTicks,

        @In("cosmetics/particles") @Title("Particles per point, unless somebody picks") @Range(min = 1, max = 8)
        @Describe("What a particle is drawn with before its wearer chooses a density.")
        @Key("particles-count")
        int particleCount,

        @In("cosmetics/particles") @Title("Most particles per point anybody may pick") @Range(min = 1, max = 8)
        @Describe("The ceiling on a player's density. Very dense is 6; lower this if a crowd of auras lags.")
        @Key("particles-max-count")
        int particleMaxCount,

        @In("cosmetics/particles") @Title("Particles nobody may wear")
        @Describe("Vanilla names, like ELDER_GUARDIAN. The shipped ones cover the screen of whoever sees "
                + "them or shake it, which is a weapon rather than a cosmetic.")
        @Key("particles-blocked")
        List<String> blockedParticles,

        @In("cosmetics/teleport") @Title("Players may choose their teleport effects")
        @Describe("Their own departure and arrival sound and the particles while they wait, for every "
                + "teleport — homes, warps, /tpa, spawn. Off: everybody gets the server's, from Core's cues. "
                + "What they picked is kept.")
        @Key("teleport-looks")
        boolean teleportLooks,

        @In("cosmetics/teleport") @Title("Sounds players may pick")
        @Describe("Sound keys, like entity.enderman.teleport. Everybody near a teleport hears it, so the list "
                + "keeps out the deafening ones; whoever has rainscosmetics.teleport.any-sound may pick any.")
        @Key("teleport-sounds")
        List<String> teleportSounds,

        @In("cosmetics/prices") @Title("Sell cosmetics")
        @Describe("The switch for everything below. Off: nothing is for sale whatever the prices say, and the "
                + "permissions decide who wears what. On: every cosmetic with a price below is bought "
                + "under Unlocks in /cosmetics; one with price 0 stays decided by its permission.")
        @Key("sell-cosmetics")
        boolean sellCosmetics,

        @In("cosmetics/prices") @Title("Price: one name colour")
        @Describe("In the server's currency, like 250 or 1.5k. Zero: as today, the permission "
                + "rainscosmetics.name.colour decides. With a price nobody wears it without buying it "
                + "(or rainscosmetics.free), whatever their permissions say.")
        @Key("price-name-colour")
        String priceNameColour,

        @In("cosmetics/prices") @Title("Price: gradients")
        @Describe("Zero: rainscosmetics.name.gradient decides. With a price it is bought once, for good.")
        @Key("price-name-gradient")
        String priceNameGradient,

        @In("cosmetics/prices") @Title("Price: colours outside the palette")
        @Describe("Zero: rainscosmetics.name.any-colour decides. With a price it is bought once, for good.")
        @Key("price-name-any-colour")
        String priceNameAnyColour,

        @In("cosmetics/prices") @Title("Price: a flowing gradient")
        @Describe("Zero: rainscosmetics.name.animated decides. With a price it is bought once, for good.")
        @Key("price-name-animated")
        String priceNameAnimated,

        @In("cosmetics/prices") @Title("Price: each decoration")
        @Describe("Bold, italic and the rest are bought one at a time at this price. Zero: the "
                + "rainscosmetics.name.decoration.* nodes decide.")
        @Key("price-name-decoration")
        String priceNameDecoration,

        @In("cosmetics/prices") @Title("Price: each preset")
        @Describe("What a preset costs unless it has a price: of its own under presets: in config.yml. "
                + "Zero: as today, public presets are free and restricted ones need their permission.")
        @Key("price-preset")
        String pricePreset,

        @In("cosmetics/prices") @Title("Price: particles and wings")
        @Describe("One purchase unlocks both. Zero: rainscosmetics.particles decides.")
        @Key("price-particles")
        String priceParticles,

        @In("cosmetics/prices") @Title("Price: teleport effects")
        @Describe("Zero: rainscosmetics.teleport decides.")
        @Key("price-teleport-looks")
        String priceTeleportLooks) {

    /** Every setting from before prices, with every price at zero — so what predates them keeps compiling. */
    public CosmeticsSettings(int maxStops, boolean dropWithoutPermission, boolean nameAboveHead,
                             boolean particlesEnabled, int particleEveryTicks, int particleCount,
                             int particleMaxCount, List<String> blockedParticles, boolean teleportLooks,
                             List<String> teleportSounds) {
        this(maxStops, dropWithoutPermission, nameAboveHead, particlesEnabled, particleEveryTicks, particleCount,
                particleMaxCount, blockedParticles, teleportLooks, teleportSounds, false,
                "0", "0", "0", "0", "0", "0", "0", "0");
    }

    /** Short, recognisable and nobody's ears hurt — the list a player picks from. */
    public static final List<String> TELEPORT_SOUNDS = List.of(
            "entity.enderman.teleport", "item.chorus_fruit.teleport", "block.beacon.power_select",
            "block.amethyst_block.chime", "block.bell.use", "entity.player.levelup",
            "block.note_block.pling", "block.note_block.chime", "entity.firework_rocket.twinkle",
            "entity.breeze.wind_burst", "block.bubble_column.upwards_inside", "entity.allay.item_given",
            "entity.experience_orb.pickup", "block.respawn_anchor.charge", "item.trident.return",
            "entity.cat.ambient", "entity.chicken.egg", "entity.villager.celebrate");

    public static final CosmeticsSettings DEFAULTS = new CosmeticsSettings(8, true, true, true, 4, 1, 6,
            List.of("ELDER_GUARDIAN", "EXPLOSION_EMITTER", "EXPLOSION", "FLASH", "SONIC_BOOM",
                    "GUST_EMITTER_LARGE", "GUST_EMITTER_SMALL"),
            true, TELEPORT_SOUNDS, false, "0", "0", "0", "0", "0", "0", "0", "0");

    public CosmeticsSettings {
        blockedParticles = blockedParticles == null ? List.of()
                : blockedParticles.stream().map(name -> name.trim().toUpperCase(Locale.ROOT)).toList();
        teleportSounds = teleportSounds == null ? List.of()
                : teleportSounds.stream().map(key -> key.trim().toLowerCase(Locale.ROOT))
                        .filter(key -> !key.isEmpty()).distinct().toList();
    }

    public Set<String> blocked() {
        return Set.copyOf(blockedParticles);
    }

    public int everyTicks() {
        return Math.max(2, Math.min(40, particleEveryTicks));
    }

    public int count() {
        return Math.max(1, Math.min(8, particleCount));
    }

    public int maxCount() {
        return Math.max(1, Math.min(8, particleMaxCount));
    }

    /** The ceiling, clamped even if the file was edited by hand. */
    public int stops() {
        return Math.max(1, Math.min(8, maxStops));
    }
}
