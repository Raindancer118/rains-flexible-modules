package de.raindancer.modules.anticheat.rules;

/**
 * How long a block takes to break. {@code Block#getBreakSpeed} is the progress one tick adds, so a
 * block needs {@code ceil(1 / speed)} ticks; vanilla's server forgives up to 30% of that, which is
 * exactly the room fast-break cheats live in.
 */
public final class BreakRule implements IAntiCheatRule {

    /** Ticks of digging a block needs at this progress per tick; 0 means it breaks instantly. */
    public int expectedTicks(double progressPerTick) {
        if (!(progressPerTick > 0)) {
            return Integer.MAX_VALUE;
        }
        if (progressPerTick >= 1) {
            return 0;
        }
        return (int) Math.ceil(1 / progressPerTick - 1e-9);
    }

    /**
     * How many milliseconds too early this break came, after allowing {@code slackMillis} for the
     * network; 0 when it was in time.
     */
    public double shortfall(long elapsedMillis, int expectedTicks, double slackMillis) {
        if (expectedTicks <= 0 || expectedTicks == Integer.MAX_VALUE) {
            return 0;
        }
        return Math.max(0, expectedTicks * 50.0 - slackMillis - elapsedMillis);
    }

    /** Broken in less than half the time — no lag explains that. */
    public Judgement blatant(long elapsedMillis, int expectedTicks) {
        if (expectedTicks >= 4 && elapsedMillis < expectedTicks * 25L) {
            return Judgement.fail(expectedTicks * 50.0 - elapsedMillis,
                    String.format("broken in %d ms, takes %d", elapsedMillis, expectedTicks * 50));
        }
        return Judgement.PASS;
    }

    @Override
    public String describe() {
        return "whether a block was broken in the time its hardness and the tool allow";
    }
}
