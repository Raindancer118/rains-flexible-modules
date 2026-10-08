package de.raindancer.modules.anticheat.rules;

import java.util.Arrays;

/**
 * Two measurements of the same thing: the server's keep-alive ping, which a client can delay at
 * will, and the round trip of the anti-cheat's own ping packets, which it cannot delay without
 * delaying everything else too. Far apart, the keep-alive is being held back to buy lag compensation.
 */
public final class PingRule implements IAntiCheatRule {

    public static final int FEWEST = 6;

    public double median(double[] values) {
        if (values.length == 0) {
            return Double.NaN;
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int middle = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2;
    }

    public Judgement spoofed(int keepAliveMillis, double[] roundTrips) {
        if (roundTrips.length < FEWEST) {
            return Judgement.PASS;
        }
        double real = median(roundTrips);
        if (keepAliveMillis > real * 2 + 150) {
            return Judgement.fail(keepAliveMillis - real, String.format(java.util.Locale.ROOT,
                    "keep-alive ping %d ms, real round trip %.0f ms", keepAliveMillis, real));
        }
        return Judgement.PASS;
    }

    @Override
    public String describe() {
        return "whether the keep-alive ping is being held back";
    }
}
