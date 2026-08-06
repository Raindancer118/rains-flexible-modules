package de.raindancer.modules.hungergames.visual;

import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.List;

/**
 * The eight blocks at head height that keep a tribute on their platform until the bell.
 *
 * <h2>Why a ring of barriers and not a movement cancel</h2>
 * The version this replaces held tributes still by cancelling {@code PlayerMoveEvent} during the countdown.
 * That works and it is horrible: the client predicts the movement, the server refuses it, and the player is
 * rubber-banded backwards several times a second for the length of the countdown. On a laggy connection it
 * reads as the server being broken, and it fires for every one of forty players on every tick they twitch.
 *
 * <p>Eight blocks of barrier at head height do the same job with no events at all. The client can see them —
 * barriers render when held, and more importantly they behave like walls rather than like an unreliable
 * connection — and the movement code is untouched, so nothing has to be remembered, cancelled or restored.
 *
 * <h2>The three properties that make it safe, all of them easy to lose</h2>
 * <ul>
 *   <li><b>The middle is left open.</b> Tributes are lifted onto their platforms by levitation, and a barrier
 *       over their head stops the lift. It is the one block of the nine that must not be filled.</li>
 *   <li><b>Only air becomes barrier.</b> A tribute arrives up a glass tube, and the tube's own blocks stand
 *       exactly where this ring goes. Replacing whatever is there would eat the tube, and the tube is what
 *       stops them walking away underground.</li>
 *   <li><b>Only barrier becomes air again.</b> When the ring comes off at {@code /start}, clearing all nine
 *       neighbours would delete the tube glass, the platform's own edge, and anything an admin had built
 *       there. So removal is by material, not by position.</li>
 * </ul>
 *
 * <p>Those two material checks are the whole reason this is not four lines inline in the startup sequence: each
 * is the difference between a ring and a hole in the arena, and neither is visible in a diff that forgets it.
 */
public final class BarrierRing {

    /**
     * How far above the standing block the ring sits.
     *
     * <p>Head height. At foot height it would be a wall a tribute could jump over; two blocks up it would be a
     * ceiling they could walk under.
     */
    public static final int HEIGHT_ABOVE_FEET = 1;

    /** One offset from the middle of the ring. */
    public record Offset(int dx, int dz) {
    }

    private BarrierRing() {
    }

    /**
     * The eight offsets the ring occupies — every neighbour, and never the middle.
     *
     * <p>Pure, so the one property that cannot be checked by looking at a screenshot is checked by a test:
     * that the middle is not in the list. A ring including it stops the levitation that puts tributes on their
     * platforms, and the symptom is a round where nobody arrives.
     */
    public static List<Offset> offsets() {
        List<Offset> ring = new java.util.ArrayList<>(8);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;   // the way up. See the class note.
                }
                ring.add(new Offset(dx, dz));
            }
        }
        return List.copyOf(ring);
    }

    /**
     * Puts the ring up around a standing position, replacing air and nothing else.
     *
     * @return how many blocks were placed, for the log line — a ring that placed none is a tribute who can
     *         walk away, and is worth noticing rather than assuming
     */
    public static int place(World world, int feetX, int feetY, int feetZ) {
        if (world == null) {
            return 0;
        }
        int placed = 0;
        int headY = feetY + HEIGHT_ABOVE_FEET;
        for (Offset offset : offsets()) {
            Block block = world.getBlockAt(feetX + offset.dx(), headY, feetZ + offset.dz());
            // Air only. The glass of the tube a tribute came up stands exactly here.
            if (block.getType() == Material.AIR) {
                block.setType(Material.BARRIER);
                placed++;
            }
        }
        return placed;
    }

    /**
     * Takes the ring down again, clearing barriers and nothing else.
     *
     * @return how many blocks were cleared
     */
    public static int remove(World world, int feetX, int feetY, int feetZ) {
        if (world == null) {
            return 0;
        }
        int cleared = 0;
        int headY = feetY + HEIGHT_ABOVE_FEET;
        for (Offset offset : offsets()) {
            Block block = world.getBlockAt(feetX + offset.dx(), headY, feetZ + offset.dz());
            // Barrier only. Clearing by position would delete the tube glass and anything built here.
            if (block.getType() == Material.BARRIER) {
                block.setType(Material.AIR);
                cleared++;
            }
        }
        return cleared;
    }
}
