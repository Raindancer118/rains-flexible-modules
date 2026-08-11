package de.raindancer.modules.moderation.rules;

import de.raindancer.core.platform.rule.Verdict;

/**
 * Whether a player's recent mining looks like x-ray, from the numbers alone.
 *
 * <h2>What "looks like x-ray" actually means here</h2>
 * The server cannot see a texture pack or a hacked client's overlay — it sees blocks being broken.
 * What x-ray actually changes is <em>which</em> blocks somebody chooses to break: ordinary mining finds
 * ore mixed into a great deal of stone, and x-ray finds it because the stone was never dug through in
 * the first place. So the tell is a mining ratio ordinary play does not produce, and this rule is
 * nothing but that comparison — two counts in, a verdict out, no player and no world.
 *
 * <h2>Why two conditions and not one</h2>
 * The ratio alone flags a genuinely lucky player: three diamonds in the first ten blocks of a fresh
 * vein is a real ratio and not a pattern. {@link #mayBeFlagged} therefore first asks whether there is
 * enough ore in the window to be a pattern at all, and only then asks whether the ratio is one ordinary
 * digging would produce.
 */
public final class XrayRule implements IModerationRule {

    public static final String NOT_ENOUGH_ORE_YET = "moderation.xray.not-enough-ore";
    public static final String RATIO_TOO_LOW = "moderation.xray.ratio-too-low";

    /**
     * Whether this player's current window is worth reporting.
     *
     * @param oreCount        valuable ore in the window
     * @param totalCount      blocks in the window altogether
     * @param minimumOre      ore has to reach at least this many before the ratio means anything
     * @param thresholdPercent the ore's share of the window, out of a hundred, that counts as a pattern
     */
    public Verdict mayBeFlagged(int oreCount, int totalCount, int minimumOre, int thresholdPercent) {
        if (oreCount < Math.max(1, minimumOre)) {
            return Verdict.refused(NOT_ENOUGH_ORE_YET, minimumOre);
        }
        if (totalCount <= 0) {
            return Verdict.refused(NOT_ENOUGH_ORE_YET, minimumOre);
        }
        double ratio = (double) oreCount / totalCount;
        if (ratio * 100 >= thresholdPercent) {
            return Verdict.allowed();
        }
        return Verdict.refused(RATIO_TOO_LOW, thresholdPercent);
    }

    /**
     * The threshold actually in force, once the server's own learnt baseline has had its say.
     *
     * <p>Only ever raises the configured percentage, never lowers it — see
     * {@code ServerMiningBaseline}'s own note on why letting a learnt number excuse a low threshold
     * would let x-ray become "normal" simply because enough players are already doing it.
     *
     * @param configuredPercent the owner's own floor
     * @param learningEnabled   whether the learnt baseline is consulted at all
     * @param baselineRatio     what this server's players have actually been mining lately, 0 to 1
     * @param multiplier        how many times that baseline counts as suspicious
     */
    public int effectiveThresholdPercent(int configuredPercent, boolean learningEnabled,
                                         double baselineRatio, int multiplier) {
        if (!learningEnabled) {
            return configuredPercent;
        }
        double learned = baselineRatio * 100 * Math.max(1, multiplier);
        return (int) Math.min(100, Math.max(configuredPercent, Math.round(learned)));
    }

    @Override
    public String describe() {
        return "whether a player's recent ore-to-stone ratio looks like x-ray rather than luck";
    }
}
