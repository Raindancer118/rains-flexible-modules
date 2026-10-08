package de.raindancer.modules.moderation.util;

/**
 * The few distributions x-ray detection needs, computed in log space so that the tails that matter —
 * one in a billion — stay exact instead of rounding to zero.
 *
 * <p>Every test here asks the same question: <em>if this player were honest, how likely is a result
 * at least this extreme?</em> That is a p-value, and only p-values are combined, never raw counts.
 */
public final class Statistics {

    public static final double MAX_SCORE = 30;

    private Statistics() {
    }

    /** P(X ≥ k) for X ~ Poisson(λ). */
    public static double poissonAtLeast(int k, double lambda) {
        if (k <= 0) {
            return 1.0;
        }
        if (lambda <= 0) {
            return 0.0;
        }
        // Below the mean the upper tail is large; summing the lower tail is then the stable way round.
        if (k <= lambda) {
            double below = 0;
            for (int i = 0; i < k; i++) {
                below += Math.exp(logPoisson(i, lambda));
            }
            return clamp(1 - below);
        }
        double sum = 0;
        double logTerm = logPoisson(k, lambda);
        double term = 1;
        for (int i = k; i < k + 10_000; i++) {
            sum += term;
            term *= lambda / (i + 1);
            if (term < 1e-17 * sum) {
                break;
            }
        }
        return clamp(Math.exp(logTerm + Math.log(sum)));
    }

    /** P(X ≥ k) for X ~ Binomial(n, p). */
    public static double binomialAtLeast(int k, int n, double p) {
        if (k <= 0) {
            return 1.0;
        }
        if (k > n || p <= 0) {
            return 0.0;
        }
        if (p >= 1) {
            return 1.0;
        }
        double sum = 0;
        for (int i = k; i <= n; i++) {
            sum += Math.exp(logChoose(n, i) + i * Math.log(p) + (n - i) * Math.log1p(-p));
        }
        return clamp(sum);
    }

    /**
     * Fisher's method: independent p-values into one. {@code -2 Σ ln p} follows a chi-square with
     * {@code 2k} degrees of freedom, whose survival function has a closed form for even degrees.
     */
    public static double fisher(double[] pValues) {
        if (pValues.length == 0) {
            return 1.0;
        }
        double x = 0;
        for (double p : pValues) {
            x += -2 * Math.log(Math.max(1e-300, Math.min(1, p)));
        }
        double half = x / 2;
        double term = 1;
        double sum = 1;
        for (int i = 1; i < pValues.length; i++) {
            term *= half / i;
            sum += term;
        }
        return clamp(Math.exp(-half + Math.log(sum)));
    }

    /** How surprising a p-value is: 3 is one in a thousand, 6 one in a million. */
    public static double score(double p) {
        if (p <= 0) {
            return MAX_SCORE;
        }
        return Math.min(MAX_SCORE, Math.max(0, -Math.log10(p)));
    }

    private static double logPoisson(int k, double lambda) {
        return k * Math.log(lambda) - lambda - logFactorial(k);
    }

    private static double logChoose(int n, int k) {
        return logFactorial(n) - logFactorial(k) - logFactorial(n - k);
    }

    private static double logFactorial(int n) {
        if (n < 2) {
            return 0;
        }
        if (n < 256) {
            double sum = 0;
            for (int i = 2; i <= n; i++) {
                sum += Math.log(i);
            }
            return sum;
        }
        // Stirling with the first correction terms: exact to far below a double's precision here.
        double x = n;
        return x * Math.log(x) - x + 0.5 * Math.log(2 * Math.PI * x) + 1 / (12 * x) - 1 / (360 * x * x * x);
    }

    private static double clamp(double p) {
        return Double.isNaN(p) ? 1.0 : Math.max(0, Math.min(1, p));
    }
}
