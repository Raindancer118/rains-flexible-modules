package de.raindancer.modules.claims.rules;

import de.raindancer.modules.claims.model.ClaimAttempt;
import de.raindancer.modules.claims.model.ClaimNames;
import de.raindancer.core.platform.rule.AbstractRule;
import de.raindancer.core.platform.rule.IRule;
import de.raindancer.core.platform.rule.Rules;
import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.modules.claims.ClaimSettings;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.NoClaimZone;
import de.raindancer.modules.claims.store.ClaimRegistry;
import de.raindancer.modules.claims.store.ZoneRegistry;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The reasons a claim might not be allowed, one class each.
 *
 * <h2>What this replaces</h2>
 * A ninety-line {@code validate} that returned early nine times. That shape has three problems: nothing else can
 * add a tenth reason, nothing can list the reasons to show somebody why their outline will not fit, and testing
 * the sixth branch means getting past the first five.
 *
 * <p>As rules each reason is a few lines, tested on its own, and the chain is data — so an admin bypass is a
 * shorter chain rather than a boolean threaded through everything.
 *
 * <h2>Order is deliberate</h2>
 * Cheapest first. The world check is a string compare; the overlap check walks a spatial index. A player drawing
 * something in a disabled world should not cost an index walk to be told so.
 */
public final class ClaimRules {

    /** Somebody who may draw a claim inside a no-claim zone, which is an admin-only thing. */
    public static final String ZONE_BYPASS = "rec.admin.zonebypass";

    private ClaimRules() {
    }

    /**
     * The whole chain.
     *
     * @param settings read through a supplier so a reload changes what the rules say, not what they said when
     *                 the chain was built
     */
    public static Rules<ClaimAttempt> standard(Supplier<ClaimSettings> settings, ClaimRegistry claims,
                                                   ZoneRegistry zones, ClaimNames names) {
        return Rules.of(
                new WorldIsEnabledRule(settings),
                new NameIsUsableRule(names),
                new NotTooManyCornersRule(settings),
                new BigEnoughRule(settings),
                new SmallEnoughRule(settings),
                new NotWhollyUndergroundRule(settings),
                new OutsideNoClaimZonesRule(zones),
                new DoesNotOverlapRule(settings, claims));
    }

    // ── the rules ──────────────────────────────────────────────────────────────────────────────────

    /** Claims can be switched off per world. Existing ones keep protecting; new ones are refused. */
    static final class WorldIsEnabledRule extends AbstractRule<ClaimAttempt> implements IClaimRule {

        private final Supplier<ClaimSettings> settings;

        WorldIsEnabledRule(Supplier<ClaimSettings> settings) {
            super("claims are allowed in this world");
            this.settings = settings;
        }

        @Override
        public Verdict judge(ClaimAttempt attempt) {
            return settings.get().worldEnabled(attempt.world().getName())
                    ? Verdict.allowed()
                    : Verdict.refused("error.world-disabled", attempt.world().getName());
        }

    }

    /**
     * The name is well formed and not already this owner's.
     *
     * <p>Per owner rather than per server: five people each wanting a "home" is the obvious thing to want, and
     * the first one should not take the word from everybody else.
     */
    static final class NameIsUsableRule extends AbstractRule<ClaimAttempt> implements IClaimRule {

        private final ClaimNames names;

        NameIsUsableRule(ClaimNames names) {
            super("the name is usable");
            this.names = names;
        }

        @Override
        public Verdict judge(ClaimAttempt attempt) {
            String name = attempt.name();
            if (name == null) {
                return Verdict.allowed();   // a reshape keeps the name it has
            }
            if (!ClaimNames.isValidName(name)) {
                return Verdict.refused("error.name-invalid", name);
            }
            return names.available(name, attempt.claimantId())
                    ? Verdict.allowed()
                    : Verdict.refused("error.name-taken", name);
        }

    }

    static final class NotTooManyCornersRule extends AbstractRule<ClaimAttempt> implements IClaimRule {

        private final Supplier<ClaimSettings> settings;

        NotTooManyCornersRule(Supplier<ClaimSettings> settings) {
            super("the outline has few enough corners");
            this.settings = settings;
        }

        @Override
        public Verdict judge(ClaimAttempt attempt) {
            int most = settings.get().maxVertices();
            return attempt.shape().vertices().size() <= most
                    ? Verdict.allowed()
                    : Verdict.refused("error.too-many-vertices", most);
        }

    }

    static final class BigEnoughRule extends AbstractRule<ClaimAttempt> implements IClaimRule {

        private final Supplier<ClaimSettings> settings;

        BigEnoughRule(Supplier<ClaimSettings> settings) {
            super("the claim is big enough");
            this.settings = settings;
        }

        @Override
        public Verdict judge(ClaimAttempt attempt) {
            int least = settings.get().minClaimArea();
            return attempt.shape().areaBlocks() >= least
                    ? Verdict.allowed()
                    : Verdict.refused("error.claim-too-small", least);
        }

    }

    static final class SmallEnoughRule extends AbstractRule<ClaimAttempt> implements IClaimRule {

        private final Supplier<ClaimSettings> settings;

        SmallEnoughRule(Supplier<ClaimSettings> settings) {
            super("the claim is small enough");
            this.settings = settings;
        }

        @Override
        public Verdict judge(ClaimAttempt attempt) {
            long most = settings.get().maxClaimArea();
            // -1 is "no limit", and reading it as a maximum would refuse every claim on the server.
            return most <= 0 || attempt.shape().areaBlocks() <= most
                    ? Verdict.allowed()
                    : Verdict.refused("error.claim-too-large", most);
        }

    }

    /** Some servers refuse a claim that is entirely below the surface, because nobody can see it is there. */
    static final class NotWhollyUndergroundRule extends AbstractRule<ClaimAttempt> implements IClaimRule {

        private final Supplier<ClaimSettings> settings;

        NotWhollyUndergroundRule(Supplier<ClaimSettings> settings) {
            super("the claim is not wholly underground");
            this.settings = settings;
        }

        @Override
        public Verdict judge(ClaimAttempt attempt) {
            if (settings.get().allowUndergroundClaims()) {
                return Verdict.allowed();
            }
            boolean reachesTop = attempt.shape().maxY() >= attempt.world().getMaxHeight() - 1;
            boolean reachesBottom = attempt.shape().minY() <= attempt.world().getMinHeight();
            return reachesTop && reachesBottom
                    ? Verdict.allowed()
                    : Verdict.refused("error.underground-disallowed", "");
        }

    }

    static final class OutsideNoClaimZonesRule extends AbstractRule<ClaimAttempt> implements IClaimRule {

        private final ZoneRegistry zones;

        OutsideNoClaimZonesRule(ZoneRegistry zones) {
            super("the ground is not in a no-claim zone");
            this.zones = zones;
        }

        @Override
        public Verdict judge(ClaimAttempt attempt) {
            if (attempt.claimant().hasPermission(ZONE_BYPASS)) {
                return Verdict.allowed();
            }
            Optional<NoClaimZone> zone =
                    zones.firstOverlap(attempt.world().getUID(), attempt.shape());
            return zone.map(found -> Verdict.refused("error.in-no-claim-zone", found.name()))
                    .orElseGet(Verdict::allowed);
        }

    }

    static final class DoesNotOverlapRule extends AbstractRule<ClaimAttempt> implements IClaimRule {

        private final Supplier<ClaimSettings> settings;
        private final ClaimRegistry claims;

        DoesNotOverlapRule(Supplier<ClaimSettings> settings, ClaimRegistry claims) {
            super("the claim does not overlap another");
            this.settings = settings;
            this.claims = claims;
        }

        @Override
        public Verdict judge(ClaimAttempt attempt) {
            if (!settings.get().allowOverlappingWorldsOnly()) {
                return Verdict.allowed();
            }
            for (Claim other : claims.candidatesFor(attempt.world().getUID(), attempt.shape())) {
                if (other.id().equals(attempt.ignoring())) {
                    continue;   // a reshape does not overlap itself
                }
                if (other.shape().intersects(attempt.shape())) {
                    return Verdict.refused("error.overlaps-claim", other.name());
                }
            }
            return Verdict.allowed();
        }

    }

    /** Every rule in the standard chain, for a diagnostic that lists them. */
    public static List<String> describeAll(Rules<ClaimAttempt> rules) {
        return rules.all().stream().map(IRule::describe).toList();
    }
}
