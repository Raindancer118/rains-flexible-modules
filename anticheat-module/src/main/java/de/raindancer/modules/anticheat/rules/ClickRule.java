package de.raindancer.modules.anticheat.rules;

import java.util.Arrays;

/**
 * Click timing. Clicks are handled once per client tick, so what reaches the server is quantised to
 * 50 ms; a human's hand still varies by more than a tick now and then, a constant-delay clicker never
 * does. Two signals, kept apart: more clicks a second than a hand manages, and a long run of clicks
 * that all land the same number of ticks apart.
 */
public final class ClickRule implements IAntiCheatRule {

    /** Fewest intervals the evenness question is asked over. */
    public static final int EVEN_SAMPLE = 40;

    /** Statistics over the intervals between clicks, in milliseconds. */
    public record Stats(double cps, double mean, double deviation, double skewness, double kurtosis,
                        int distinctTicks) {
    }

    public Stats stats(double[] intervalsMillis) {
        int n = intervalsMillis.length;
        if (n == 0) {
            return new Stats(0, 0, 0, 0, 0, 0);
        }
        double mean = Arrays.stream(intervalsMillis).average().orElse(0);
        double m2 = 0;
        double m3 = 0;
        double m4 = 0;
        for (double value : intervalsMillis) {
            double d = value - mean;
            m2 += d * d;
            m3 += d * d * d;
            m4 += d * d * d * d;
        }
        m2 /= n;
        m3 /= n;
        m4 /= n;
        double deviation = Math.sqrt(m2);
        double skewness = m2 < 1e-12 ? 0 : m3 / Math.pow(m2, 1.5);
        double kurtosis = m2 < 1e-12 ? 0 : m4 / (m2 * m2) - 3;
        long distinct = Arrays.stream(intervalsMillis).map(value -> Math.round(value / 50.0)).distinct().count();
        double cps = mean <= 0 ? 0 : 1000.0 / mean;
        return new Stats(cps, mean, deviation, skewness, kurtosis, (int) distinct);
    }

    /** @param clicksLastSecond clicks counted over the last 1000 ms */
    public Judgement tooFast(int clicksLastSecond, int maxCps) {
        if (clicksLastSecond > maxCps) {
            return Judgement.fail(clicksLastSecond - maxCps, clicksLastSecond + " clicks in one second (limit " + maxCps + ")");
        }
        return Judgement.PASS;
    }

    /** A long run of fast clicks that all landed the same number of ticks apart. */
    public Judgement tooEven(double[] intervalsMillis) {
        if (intervalsMillis.length < EVEN_SAMPLE) {
            return Judgement.PASS;
        }
        Stats stats = stats(intervalsMillis);
        if (stats.cps() >= 7 && stats.distinctTicks() == 1) {
            return Judgement.fail(stats.cps(), String.format("%d clicks all %.0f ms apart (%.1f cps, σ %.1f ms)",
                    intervalsMillis.length + 1, stats.mean(), stats.cps(), stats.deviation()));
        }
        return Judgement.PASS;
    }

    /** Clicks the pattern question is asked over: ten seconds of fighting at least. */
    public static final int PATTERN_SAMPLE = 100;
    /** Below this a hand keeps rhythm easily, and nothing is judged. */
    static final double PATTERN_CPS = 13;
    /** A hand stops this long now and then — to aim, to move, to breathe. */
    static final double PAUSE_MILLIS = 200;

    /**
     * A long fast run with no pause, no outlier, and a spread flatter than a hand's (negative excess
     * kurtosis): the shape of a delay randomised within bounds. Statistical, so its check is experimental.
     */
    public Judgement tooRegular(double[] intervalsMillis) {
        if (intervalsMillis.length < PATTERN_SAMPLE) {
            return Judgement.PASS;
        }
        Stats stats = stats(intervalsMillis);
        if (stats.cps() < PATTERN_CPS) {
            return Judgement.PASS;
        }
        double longest = Arrays.stream(intervalsMillis).max().orElse(0);
        long outliers = Arrays.stream(intervalsMillis).filter(value -> value > stats.mean() + 2.5 * stats.deviation()).count();
        if (longest < PAUSE_MILLIS && outliers == 0 && stats.kurtosis() < -0.5) {
            return Judgement.fail(-stats.kurtosis(), String.format(java.util.Locale.ROOT,
                    "%d clicks at %.1f cps, no pause, no outlier, spread too flat (kurtosis %.2f, σ %.1f ms)",
                    intervalsMillis.length + 1, stats.cps(), stats.kurtosis(), stats.deviation()));
        }
        return Judgement.PASS;
    }

    @Override
    public String describe() {
        return "whether clicks come faster or more evenly than a hand manages";
    }
}
