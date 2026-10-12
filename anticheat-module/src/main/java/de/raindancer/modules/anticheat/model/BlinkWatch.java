package de.raindancer.modules.anticheat.model;

import java.util.Optional;

/**
 * Blink: a client holding its moves back to send them all at once later. A vanilla client since 1.21.2
 * ends every tick with a packet and sends a move at least every twenty ticks — the position reminder — even
 * standing still. Ticking on for {@link #HELD} without a move, while answering everything else, is holding.
 */
public final class BlinkWatch {

    /** A second and a half: half again the reminder's twenty ticks. */
    public static final int HELD = 30;

    private int ticksWithoutMove;
    private boolean told;
    private boolean excused;

    /** A move packet of any kind arrived. */
    public synchronized void moved() {
        ticksWithoutMove = 0;
        told = false;
    }

    /** Dead on the respawn screen, just teleported, riding — counting stops and starts afresh after. */
    public synchronized void excused(boolean excused) {
        this.excused = excused;
        if (excused) {
            ticksWithoutMove = 0;
            told = false;
        }
    }

    /** A tick ended. @return what to say, once per hold */
    public synchronized Optional<String> tickEnd() {
        if (excused) {
            return Optional.empty();
        }
        ticksWithoutMove++;
        if (ticksWithoutMove >= HELD && !told) {
            told = true;
            return Optional.of(ticksWithoutMove + " ticks without a move (vanilla reminds every 20)");
        }
        return Optional.empty();
    }
}
