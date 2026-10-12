package de.raindancer.modules.arena.rules;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Who gets what out of a pot: every player paid the stake in; the winners share it, less the house's cut, which leaves
 * the economy. Shares are whole units; the remainder goes to the first winner, so not one unit is created or lost.
 */
public final class StakeRule implements IArenaRule {

    public long pot(long stake, int players) {
        return Math.multiplyExact(Math.max(0, stake), Math.max(0, players));
    }

    /** @return what each winner is paid; empty for a draw, which is a refund instead */
    public Map<UUID, Long> payouts(long stake, int players, List<UUID> winners, int houseCutPercent) {
        Map<UUID, Long> paid = new LinkedHashMap<>();
        if (winners.isEmpty() || stake <= 0) {
            return paid;
        }
        long pot = pot(stake, players);
        int percent = Math.max(0, Math.min(100, houseCutPercent));
        // Split so no step can overflow, however large the pot: whole hundreds first, then the rest.
        long cut = (pot / 100) * percent + (pot % 100) * percent / 100;
        long left = pot - cut;
        long each = left / winners.size();
        long remainder = left - each * winners.size();
        for (UUID winner : winners) {
            paid.put(winner, each);
        }
        paid.merge(winners.getFirst(), remainder, Long::sum);
        return paid;
    }

    @Override
    public String describe() {
        return "the winners share the pot, less the house's cut";
    }
}
