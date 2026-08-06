package de.raindancer.modules.hungergames.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Where the starting platforms go: a ring of them around the cornucopia, evenly spaced, all facing inwards.
 *
 * <h2>Why this is pure arithmetic in its own class</h2>
 * In the plugin this was ported from, this lived inside the preflight runner, interleaved with terrain
 * flattening, chat messages and {@code Location} construction. It could only be exercised by running
 * {@code /init} on a real server and looking at the result — so the one part of arena generation that is
 * genuinely a calculation was the part nothing checked, and the mistake below sat in it undetected.
 *
 * <h2>The mistake, and why it mattered</h2>
 * The original sized the ring by <b>arc</b> length: it added up one platform's width plus the wanted gap for
 * every tribute, called that the circumference, and divided by 2π. But two platforms on a circle are not
 * separated by the arc between them — they are separated by the straight line, the <b>chord</b>, and the chord
 * is always shorter than the arc. So the ring came out too small and the platforms ended up closer together
 * than the gap an owner had asked for.
 *
 * <p><b>Where it actually bites is not where it looks like it should</b>, and working that out is the only
 * reason this is worth a class of its own. The arc overstates the chord most at small counts — with three
 * platforms the arc is 2.09r against a chord of 1.73r — so the obvious conclusion is that small rounds were
 * the broken ones. They were not: at small counts {@link #SMALLEST_RADIUS} rescues the ring, because a handful
 * of platforms needs so little circumference that the floor is what sets the radius and the floor is generous.
 *
 * <p>So the shortfall appears just above the count at which the floor stops binding, and where that is depends
 * on the configured gap. With the shipped defaults — three-wide platforms, two blocks between them — every
 * tournament of <b>thirteen tributes or more</b> got less than the two blocks it asked for. With a gap of ten,
 * it starts at <b>five</b>, and five tributes were nearly a block short. Thirteen upwards is most real
 * tournaments, which is why this mattered, and the miss is under a block, which is why nobody could point at
 * it. The setting exists so that nobody can reach a neighbour before the countdown ends.
 *
 * <p>So the radius here comes from the chord: {@code r = spacing / (2·sin(π/n))}. {@link #actualGap()} reports
 * what the ring really delivers, and {@link #gapIsHonoured(double)} is what a test and a preflight check ask.
 *
 * <h2>The facing, which the original had right</h2>
 * {@code yaw = degrees(angle) + 90} genuinely does face the centre, and it is worth writing down why, because
 * it looks like an off-by-ninety waiting to be "fixed". Minecraft's yaw runs from south, and a yaw of
 * {@code y} looks along {@code (-sin y, cos y)}. A platform at angle θ sits at {@code (cos θ, sin θ)} from the
 * middle and must look along {@code (-cos θ, -sin θ)}. Solving gives {@code y = θ + 90°}. Pinned by a test, so
 * nobody has to re-derive it at speed.
 */
public record ArenaRing(int platforms, double radius, int platformWidth, int minimumGap) {

    /**
     * The smallest ring worth building, in blocks.
     *
     * <p>Two tributes with narrow platforms would otherwise be handed a radius of a couple of blocks, which
     * puts them inside the cornucopia rather than around it. Clamping upwards is always safe: it can only
     * increase the distance between platforms, never reduce it below what was asked for.
     */
    public static final double SMALLEST_RADIUS = 10.0;

    /** One position on the ring: where a platform goes, and which way whoever stands on it looks. */
    public record Spot(double x, double z, float yaw) {
    }

    /**
     * The ring for this many tributes.
     *
     * @param platforms     how many platforms are needed — one per tribute. Below one is treated as one, so a
     *                      round somebody starts alone still builds something rather than dividing by zero
     * @param platformWidth how wide one platform is, in blocks
     * @param minimumGap    the clear space wanted between two neighbouring platforms, in blocks
     */
    public static ArenaRing forTributes(int platforms, int platformWidth, int minimumGap) {
        int count = Math.max(1, platforms);
        int width = Math.max(1, platformWidth);
        int gap = Math.max(0, minimumGap);
        return new ArenaRing(count, radiusFor(count, width + gap), width, gap);
    }

    /**
     * The radius at which {@code n} points are {@code spacing} apart along the straight line between them.
     *
     * <p>From the chord, not the arc — see the class note. With one platform there is no neighbour and so no
     * constraint, and with two the formula gives exactly half the spacing, which is right: they sit opposite
     * each other and the whole diameter is between them.
     */
    private static double radiusFor(int count, double spacing) {
        if (count < 2) {
            return SMALLEST_RADIUS;
        }
        double fromChord = spacing / (2.0 * Math.sin(Math.PI / count));
        return Math.max(SMALLEST_RADIUS, fromChord);
    }

    /** Where each platform goes, in order, starting due east and going anticlockwise. */
    public List<Spot> spots() {
        List<Spot> found = new ArrayList<>(platforms);
        for (int i = 0; i < platforms; i++) {
            double angle = (2 * Math.PI * i) / platforms;
            found.add(new Spot(
                    radius * Math.cos(angle),
                    radius * Math.sin(angle),
                    // Facing the middle. See the class note before changing this.
                    (float) (Math.toDegrees(angle) + 90)));
        }
        return List.copyOf(found);
    }

    /**
     * The straight-line distance between two neighbouring platform centres.
     *
     * <p>Infinite for a single platform, which has no neighbour — reported rather than zero, because zero
     * reads as "they are on top of each other" and would fail every gap check.
     */
    public double neighbourDistance() {
        if (platforms < 2) {
            return Double.POSITIVE_INFINITY;
        }
        return 2.0 * radius * Math.sin(Math.PI / platforms);
    }

    /**
     * The clear space actually left between two neighbouring platforms.
     *
     * <p>What an owner set {@code arena.platform-min-gap} expecting. Reported rather than assumed, because the
     * radius may have been clamped up by {@link #SMALLEST_RADIUS}, in which case they get more than they asked
     * for — and a preflight check that said "not what you configured" about extra room would be noise.
     */
    public double actualGap() {
        double distance = neighbourDistance();
        return Double.isInfinite(distance) ? Double.POSITIVE_INFINITY : distance - platformWidth;
    }

    /** Whether the ring delivers at least the gap that was asked for. Allows a hair of rounding. */
    public boolean gapIsHonoured(double tolerance) {
        return actualGap() + Math.abs(tolerance) >= minimumGap;
    }

    /**
     * How far out the flattened ground has to reach, given the extra margin an owner configured.
     *
     * <p>Rounded up, and the platform's own width added: the radius locates a platform's centre, so half of
     * every platform sticks out past it, and terrain flattened only to the radius leaves each one half buried.
     * That was visible in the original as platforms with a wall on their outer edge.
     */
    public int groundRadius(int extraMargin) {
        return (int) Math.ceil(radius + platformWidth / 2.0) + Math.max(0, extraMargin);
    }
}
