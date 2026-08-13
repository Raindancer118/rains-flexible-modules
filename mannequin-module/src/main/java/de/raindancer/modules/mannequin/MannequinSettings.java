package de.raindancer.modules.mannequin;

import de.raindancer.core.data.settings.Describe;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Key;
import de.raindancer.core.data.settings.Range;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.bukkit.Material;

/**
 * What an owner can decide about training dummies.
 *
 * <p>The record <em>is</em> the schema — see {@code RtpSettings} for why, and why every component
 * has a {@code with…} rather than a positional constructor being the way to change one.
 *
 * <h2>A mannequin can die</h2>
 * It is not invulnerable — {@link #maxHealth} is a real health pool, matching a normal player's own
 * 20 by default, and it can be reduced to zero like anything else. What makes that safe is {@link
 * #respawnDelaySeconds}: {@code MannequinDeathListener} clears every drop on death, and {@code
 * MannequinService} brings an identical replacement back after the delay, in the exact same spot.
 */
@Settings(id = "mannequin", topics = {
        @Topic(path = "mannequin", title = "Mannequins", icon = Material.ARMOR_STAND),
        @Topic(path = "mannequin/access", title = "Who may create one", icon = Material.NAME_TAG),
        @Topic(path = "mannequin/combat", title = "Combat", icon = Material.DIAMOND_SWORD),
        @Topic(path = "mannequin/life", title = "Health and respawn", icon = Material.TOTEM_OF_UNDYING),
})
public record MannequinSettings(

        @In("mannequin/access") @Title("Anybody may create a mannequin")
        @Describe("On lets any player use /mannequin create. Off gates it behind the create "
                + "permission, for a server that wants an admin or builder to place them instead.")
        @Key("open-creation")
        boolean openCreation,

        @In("mannequin/combat") @Title("Combo window") @Range(min = 0, max = 10000)
        @Describe("Milliseconds. Two hits land inside this window count as the same combo streak; "
                + "a gap longer than this starts a fresh one.")
        @Key("combo-window-millis")
        int comboWindowMillis,

        @In("mannequin/combat") @Title("Shield blocking")
        @Describe("Whether a mannequin holding a shield actively raises it against a nearby "
                + "attacker, the same way a player would.")
        @Key("blocking-enabled")
        boolean blockingEnabled,

        @In("mannequin/combat") @Title("Shield block range") @Range(min = 1, max = 16)
        @Describe("Blocks. How close an attacker has to be before a mannequin holding a shield "
                + "raises it.")
        @Key("shield-range-blocks")
        int shieldRangeBlocks,

        @In("mannequin/combat") @Title("One-shot threshold") @Range(min = 1, max = 1000)
        @Describe("Damage that counts as 'would have killed a bare, full-health player' — also the "
                + "calibration point the redstone signal is scaled against, so this damage produces "
                + "the maximum signal, 15.")
        @Key("one-shot-threshold")
        int oneShotThreshold,

        @In("mannequin/combat") @Title("Redstone pulse length") @Range(min = 1, max = 200)
        @Describe("Ticks. How long the barrel under an opted-in mannequin stays filled after a hit "
                + "before it is cleared back to empty, so a comparator reads a pulse rather than a "
                + "permanent state.")
        @Key("redstone-pulse-ticks")
        int redstonePulseTicks,

        @In("mannequin/life") @Title("Max health") @Range(min = 1, max = 2000)
        @Describe("A normal player's own 20 by default. A single mannequin can be given its own "
                + "health pool instead — see the loadout screen's presets, or any raw number.")
        @Key("max-health")
        double maxHealth,

        @In("mannequin/life") @Title("Respawn delay") @Range(min = 0, max = 3600)
        @Describe("Seconds after a mannequin dies before an identical replacement — same block, "
                + "same loadout, same skin — takes its place.")
        @Key("respawn-delay-seconds")
        int respawnDelaySeconds) {

    public static final MannequinSettings DEFAULTS =
            new MannequinSettings(true, 2000, true, 4, 20, 20, 20.0, 1);

    // ------------------------------------------------------------------ read back safely

    public long comboWindow() {
        return Math.max(0, Math.min(10000, comboWindowMillis));
    }

    public int shieldRange() {
        return Math.max(1, Math.min(16, shieldRangeBlocks));
    }

    /** {@link #oneShotThreshold} clamped and widened to a {@code double} for the damage rules. */
    public double oneShotThresholdDamage() {
        return Math.max(1, Math.min(1000, oneShotThreshold));
    }

    /** {@link #redstonePulseTicks} clamped and widened to a {@code long} for {@code Scheduling}. */
    public long redstonePulseTicksClamped() {
        return Math.max(1, Math.min(200, redstonePulseTicks));
    }

    /** {@link #maxHealth}, clamped. */
    public double maxHealthClamped() {
        return Math.max(1.0, Math.min(2000.0, maxHealth));
    }

    /** {@link #respawnDelaySeconds}, in the ticks {@code Scheduling}'s delayed calls take. */
    public long respawnDelayTicks() {
        return Math.max(0, Math.min(3600, respawnDelaySeconds)) * 20L;
    }

    // ------------------------------------------------------------------ one component at a time

    public MannequinSettings withOpenCreation(boolean open) {
        return new MannequinSettings(open, comboWindowMillis, blockingEnabled, shieldRangeBlocks,
                oneShotThreshold, redstonePulseTicks, maxHealth, respawnDelaySeconds);
    }

    public MannequinSettings withComboWindowMillis(int millis) {
        return new MannequinSettings(openCreation, millis, blockingEnabled, shieldRangeBlocks,
                oneShotThreshold, redstonePulseTicks, maxHealth, respawnDelaySeconds);
    }

    public MannequinSettings withBlockingEnabled(boolean enabled) {
        return new MannequinSettings(openCreation, comboWindowMillis, enabled, shieldRangeBlocks,
                oneShotThreshold, redstonePulseTicks, maxHealth, respawnDelaySeconds);
    }

    public MannequinSettings withShieldRangeBlocks(int blocks) {
        return new MannequinSettings(openCreation, comboWindowMillis, blockingEnabled, blocks,
                oneShotThreshold, redstonePulseTicks, maxHealth, respawnDelaySeconds);
    }

    public MannequinSettings withOneShotThreshold(int threshold) {
        return new MannequinSettings(openCreation, comboWindowMillis, blockingEnabled,
                shieldRangeBlocks, threshold, redstonePulseTicks, maxHealth, respawnDelaySeconds);
    }

    public MannequinSettings withRedstonePulseTicks(int ticks) {
        return new MannequinSettings(openCreation, comboWindowMillis, blockingEnabled,
                shieldRangeBlocks, oneShotThreshold, ticks, maxHealth, respawnDelaySeconds);
    }

    public MannequinSettings withMaxHealth(double health) {
        return new MannequinSettings(openCreation, comboWindowMillis, blockingEnabled,
                shieldRangeBlocks, oneShotThreshold, redstonePulseTicks, health, respawnDelaySeconds);
    }

    public MannequinSettings withRespawnDelaySeconds(int seconds) {
        return new MannequinSettings(openCreation, comboWindowMillis, blockingEnabled,
                shieldRangeBlocks, oneShotThreshold, redstonePulseTicks, maxHealth, seconds);
    }
}
