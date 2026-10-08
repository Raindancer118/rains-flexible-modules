package de.raindancer.modules.moderation.rules;

import de.raindancer.modules.moderation.util.Statistics;

import de.raindancer.modules.moderation.model.MiningLedger;
import de.raindancer.modules.moderation.model.OreDensity;
import de.raindancer.modules.moderation.model.OreKind;
import de.raindancer.modules.moderation.model.RockBand;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns one player's ledger into evidence. Three independent tests, each asking "how likely is
 * this if they were honest?", combined with Fisher's method into one score:
 *
 * <ol>
 *   <li><b>Ore found</b> — veins their digging revealed, against what the world's own density
 *       predicts for the blocks they revealed, band by band (Poisson). The kinds are tested
 *       separately and corrected for testing several.</li>
 *   <li><b>Bait</b> — honeypot ores only an x-ray client can see, reached against chance (Poisson).</li>
 *   <li><b>Steering</b> — turns that favoured the hidden ore around them over the hidden rock,
 *       against turns that favoured the rock (binomial, ½).</li>
 * </ol>
 */
public final class XrayVerdictRule implements IModerationRule {

    /** One test's outcome, for a moderator to read. */
    public record Signal(String name, double observed, double expected, double p, String summary) {

        public double score() {
            return Statistics.score(p);
        }
    }

    public record Verdict(List<Signal> signals, double p, double score) {

        public static final Verdict NOTHING = new Verdict(List.of(), 1, 0);

        public int percent() {
            return (int) Math.floor(Math.max(0, Math.min(1, 1 - p)) * 100);
        }
    }

    public Verdict judge(MiningLedger ledger, OreDensity density, Collection<OreKind> watched) {
        List<Signal> signals = new ArrayList<>();

        Signal ore = oreFound(ledger, density, watched);
        if (ore != null) {
            signals.add(ore);
        }
        double baitExpected = ledger.baitExpected();
        int baitReached = (int) Math.round(ledger.baitReached());
        if (baitExpected > 0 || baitReached > 0) {
            double p = Statistics.poissonAtLeast(baitReached, Math.max(1e-9, baitExpected));
            signals.add(new Signal("Bait ores", baitReached, baitExpected, p, String.format(Locale.ROOT,
                    "reached %d hidden bait ore(s) only an x-ray shows, chance alone %.2f", baitReached, baitExpected)));
        }
        int toward = (int) Math.round(ledger.turnsToward());
        int away = (int) Math.round(ledger.turnsAway());
        if (toward + away >= 4) {
            double p = Statistics.binomialAtLeast(toward, toward + away, 0.5);
            signals.add(new Signal("Steering", toward, (toward + away) / 2.0, p, String.format(Locale.ROOT,
                    "%d of %d telling turns went for hidden ore rather than plain rock, chance alone half", toward, toward + away)));
        }
        if (signals.isEmpty()) {
            return Verdict.NOTHING;
        }
        double[] ps = signals.stream().mapToDouble(Signal::p).toArray();
        double combined = Statistics.fisher(ps);
        return new Verdict(List.copyOf(signals), combined, Statistics.score(combined));
    }

    private Signal oreFound(MiningLedger ledger, OreDensity density, Collection<OreKind> watched) {
        Map<String, Double> revealed = ledger.revealedByBand();
        Signal worst = null;
        int tested = 0;
        for (OreKind kind : watched) {
            double expected = 0;
            double observed = 0;
            for (Map.Entry<String, Double> band : revealed.entrySet()) {
                double rate = density.rate(RockBand.parse(band.getKey()), kind);
                if (Double.isNaN(rate)) {
                    continue;
                }
                expected += band.getValue() * rate;
                observed += ledger.veins(band.getKey(), kind);
            }
            if (expected <= 0 && observed <= 0) {
                continue;
            }
            tested++;
            int found = (int) Math.round(observed);
            double p = Statistics.poissonAtLeast(found, Math.max(1e-9, expected));
            if (worst == null || p < worst.p()) {
                worst = new Signal(kind.title(), observed, expected, p, String.format(Locale.ROOT,
                        "%s: %d vein(s) in what they dug through, chance alone %.2f", kind.title(), found, expected));
            }
        }
        if (worst == null) {
            return null;
        }
        double corrected = Math.min(1, worst.p() * tested);
        return new Signal("Ore found", worst.observed(), worst.expected(), corrected, worst.summary());
    }

    @Override
    public String describe() {
        return "how unlikely a player's mining is for somebody who cannot see through stone";
    }
}
