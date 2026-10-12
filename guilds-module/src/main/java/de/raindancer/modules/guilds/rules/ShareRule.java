package de.raindancer.modules.guilds.rules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * How a disbanded guild's bank is shared out.
 *
 * <p>By the largest-remainder method, so the shares always add up to exactly the pot: rounding each share down
 * loses up to a coin per member, and a bank that pays out less than it holds has destroyed money nobody chose
 * to destroy. Ties in the remainder go to the lower UUID, so the result never depends on map order.
 */
public final class ShareRule implements IGuildsRule {

    public enum Mode { LEADER, CONTRIBUTION, EVEN }

    @Override
    public String describe() {
        return "a disbanded guild's bank goes to the leader, or is shared by what each put in, or evenly — "
                + "always adding up to exactly what was in it";
    }

    /**
     * @param pot     minor units to share
     * @param weights each member's claim — what they put in, net. Ignored for {@link Mode#EVEN} and {@link Mode#LEADER}
     * @param leader  who receives everything under {@link Mode#LEADER}, and the fallback when nobody has a claim
     */
    public Map<UUID, Long> share(long pot, Mode mode, Map<UUID, Long> weights, UUID leader) {
        Map<UUID, Long> shares = new LinkedHashMap<>();
        if (pot <= 0) {
            return shares;
        }
        if (mode == Mode.LEADER || weights.isEmpty()) {
            shares.put(leader, pot);
            return shares;
        }
        Map<UUID, Long> claims = new LinkedHashMap<>();
        weights.forEach((who, weight) -> claims.put(who, mode == Mode.EVEN ? 1L : Math.max(0, weight)));
        long total = claims.values().stream().mapToLong(Long::longValue).sum();
        if (total <= 0) {
            claims.replaceAll((who, weight) -> 1L);
            total = claims.size();
        }
        // Exact: pot × claim can pass a long, and a double loses coins long before that.
        record Part(UUID who, long whole, long remainder) {
        }
        java.math.BigInteger whole = java.math.BigInteger.valueOf(pot);
        java.math.BigInteger of = java.math.BigInteger.valueOf(total);
        List<Part> parts = new ArrayList<>();
        long given = 0;
        for (Map.Entry<UUID, Long> claim : claims.entrySet()) {
            java.math.BigInteger[] split = whole.multiply(java.math.BigInteger.valueOf(claim.getValue())).divideAndRemainder(of);
            parts.add(new Part(claim.getKey(), split[0].longValueExact(), split[1].longValueExact()));
            given += split[0].longValueExact();
        }
        long left = pot - given;
        parts.sort(Comparator.comparingLong(Part::remainder).reversed().thenComparing(Part::who));
        for (Part part : parts) {
            long extra = left > 0 ? 1 : 0;
            left -= extra;
            if (part.whole() + extra > 0) {
                shares.put(part.who(), part.whole() + extra);
            }
        }
        return shares;
    }
}
