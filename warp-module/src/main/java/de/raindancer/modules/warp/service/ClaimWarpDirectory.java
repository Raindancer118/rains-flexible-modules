package de.raindancer.modules.warp.service;

import de.raindancer.core.world.poi.ClaimWarps;
import de.raindancer.core.world.poi.Poi;
import de.raindancer.modules.warp.WarpSettings;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Claims' warps and players' main homes, as a claims plugin left them in Core — found by what somebody
 * types and listed for whoever may arrive there.
 *
 * <p>Empty, and harmless, on a server without a claims plugin: nothing writes these then.
 */
public final class ClaimWarpDirectory implements IWarpService {

    /** What typing a claim's name came to. */
    public sealed interface Found {
        record One(Poi point) implements Found {
        }

        /** Two claims share the name; {@code owner/claim} picks one. */
        record Several(List<Poi> points) implements Found {
        }

        record None() implements Found {
        }
    }

    private final ClaimWarps warps;
    private final Function<UUID, String> nameOf;

    /** @param nameOf a player's name by id, as Core knows it; null for somebody it does not */
    public ClaimWarpDirectory(ClaimWarps warps, Function<UUID, String> nameOf) {
        this.warps = warps;
        this.nameOf = nameOf;
    }

    @Override
    public void settings(WarpSettings fresh) {
        // Nothing to read: where a claim's warp is and who may use it are the claim's.
    }

    /** Every claim warp {@code mayArrive} lets in, by owner then name. */
    public List<Poi> visible(Predicate<Poi> mayArrive) {
        return warps.all().stream().filter(mayArrive)
                .sorted(Comparator.comparing((Poi point) -> ownerName(point), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Poi::name, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    /** The claim warp {@code typed} means: a claim's name, or {@code owner/claim}. */
    public Found find(String typed, Predicate<Poi> mayArrive) {
        if (typed == null || typed.isBlank()) {
            return new Found.None();
        }
        String wanted = typed.trim().toLowerCase(Locale.ROOT).replace(' ', '_');
        int slash = wanted.indexOf('/');
        List<Poi> matches = visible(mayArrive).stream()
                .filter(point -> slash < 0
                        ? typable(point).equals(wanted)
                        : ownerName(point).equalsIgnoreCase(wanted.substring(0, slash))
                                && typable(point).equals(wanted.substring(slash + 1)))
                .toList();
        return switch (matches.size()) {
            case 0 -> new Found.None();
            case 1 -> new Found.One(matches.getFirst());
            default -> new Found.Several(matches);
        };
    }

    /** What to type for it: its name where that is unique among what this player sees, else owner/name. */
    public String typedAs(Poi point, Predicate<Poi> mayArrive) {
        long sharing = visible(mayArrive).stream().filter(other -> other.name().equalsIgnoreCase(point.name())).count();
        String name = typable(point);
        return sharing > 1 ? ownerName(point).toLowerCase(Locale.ROOT) + "/" + name : name;
    }

    /** A claim's name as it is typed: lower case, a space as an underscore. */
    private static String typable(Poi point) {
        return point.name().toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    /** {@code player}'s main home, if they chose one and it would let this traveller in. */
    public Optional<Poi> homeOf(UUID player, Predicate<Poi> mayArrive) {
        return warps.mainOf(player).filter(mayArrive);
    }

    /** Whose claim it is, as people know them. */
    public String ownerName(Poi point) {
        String name = point.owner() == null ? null : nameOf.apply(point.owner());
        return name == null ? "nobody" : name;
    }

    @Override
    public String describe() {
        return "claims' warps and players' main homes";
    }
}
