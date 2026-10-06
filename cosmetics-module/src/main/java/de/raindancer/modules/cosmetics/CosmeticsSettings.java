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
        List<String> blockedParticles) {

    public static final CosmeticsSettings DEFAULTS = new CosmeticsSettings(8, true, true, true, 4, 1, 6,
            List.of("ELDER_GUARDIAN", "EXPLOSION_EMITTER", "EXPLOSION", "FLASH", "SONIC_BOOM",
                    "GUST_EMITTER_LARGE", "GUST_EMITTER_SMALL"));

    public CosmeticsSettings {
        blockedParticles = blockedParticles == null ? List.of()
                : blockedParticles.stream().map(name -> name.trim().toUpperCase(Locale.ROOT)).toList();
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
