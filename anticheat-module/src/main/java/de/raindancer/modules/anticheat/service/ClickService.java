package de.raindancer.modules.anticheat.service;

import de.raindancer.modules.anticheat.AntiCheatSettings;
import de.raindancer.modules.anticheat.model.CheckType;
import de.raindancer.modules.anticheat.model.Flag;
import de.raindancer.modules.anticheat.model.PlayerTrack;
import de.raindancer.modules.anticheat.rules.ClickRule;
import de.raindancer.modules.anticheat.rules.Judgement;

import java.util.Deque;

/** Counts left clicks — arm swings that were neither digging nor using an item — and judges their rhythm. */
public final class ClickService implements IAntiCheatService {

    private final ClickRule rule = new ClickRule();
    private volatile AntiCheatSettings settings = AntiCheatSettings.DEFAULTS;

    /** @return a failure to raise on the player's own thread, or null */
    public Flag click(PlayerTrack track, long millis) {
        synchronized (track) {
            PlayerTrack.Combat c = track.combat;
            if (c.lastClickMillis > 0) {
                long interval = millis - c.lastClickMillis;
                if (interval < 1000) {
                    c.clickIntervals.add(interval);
                } else {
                    c.clickIntervals.clear();
                }
            }
            c.lastClickMillis = millis;
            Deque<Long> clicks = c.clicks;
            clicks.addLast(millis);
            while (!clicks.isEmpty() && millis - clicks.peekFirst() > 1000) {
                clicks.removeFirst();
            }
            Judgement fast = rule.tooFast(clicks.size(), settings.maxCps());
            if (fast.failed()) {
                clicks.clear();
                return Flag.of(CheckType.AUTOCLICKER, Math.min(3, fast.offset() / 3 + 1), fast.reason());
            }
            if (c.clickIntervals.full()) {
                Judgement even = rule.tooEven(c.clickIntervals.toArray());
                if (even.failed()) {
                    c.clickIntervals.clear();
                    return Flag.of(CheckType.AUTOCLICKER, 2, even.reason());
                }
            }
            return null;
        }
    }

    public ClickRule.Stats stats(PlayerTrack track) {
        synchronized (track) {
            return rule.stats(track.combat.clickIntervals.toArray());
        }
    }

    @Override
    public void settings(AntiCheatSettings fresh) {
        this.settings = fresh == null ? AntiCheatSettings.DEFAULTS : fresh;
    }

    @Override
    public String describe() {
        return "counting clicks and judging their rhythm";
    }
}
