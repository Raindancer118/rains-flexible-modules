package de.raindancer.modules.claims;

import de.raindancer.core.world.protection.LandAudience;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "May a claim do this at all?" — the server's policy against the owner's own switch.
 *
 * <p>Replaces the half of {@code FeaturePolicyTest} that was about resolution. The other half of that test
 * was about reading a {@code config.yml} written before feature policies existed. That migration belongs with
 * whatever writes the file, not with the resolver, so those cases are not here.
 *
 * <p>The three interactions worth pinning are the ones an admin actually relies on: forced-on has to beat an
 * owner who switched the perk off, forced-off has to beat an owner who switched it on, and available has to
 * leave them alone. Get the last one wrong and every claim on the server silently changes behaviour.
 */
class FeaturesTest {

    private final FeaturePolicies policies = FeaturePolicies.builtIn();
    private final Features features = new Features(policies);

    private static Claim claim() {
        ClaimShape shape = new ClaimShape(List.of(
                new ClaimPoint(0, 0), new ClaimPoint(0, 4),
                new ClaimPoint(4, 4), new ClaimPoint(4, 0)), 0, 128);
        return new Claim(UUID.randomUUID(), "test", UUID.randomUUID(), "world", shape, UUID.randomUUID());
    }

    @Nested
    @DisplayName("the policy enum itself")
    class Enum {

        @Test
        void readsEveryAcceptedSpellingAndRefusesTheRest() {
            assertThat(FeaturePolicy.byKey("on")).contains(FeaturePolicy.AVAILABLE);
            assertThat(FeaturePolicy.byKey("available")).contains(FeaturePolicy.AVAILABLE);
            assertThat(FeaturePolicy.byKey("off")).contains(FeaturePolicy.FORCED_OFF);
            assertThat(FeaturePolicy.byKey("forced-off")).contains(FeaturePolicy.FORCED_OFF);
            assertThat(FeaturePolicy.byKey("FORCED_ON")).contains(FeaturePolicy.FORCED_ON);
            assertThat(FeaturePolicy.byKey("force")).contains(FeaturePolicy.FORCED_ON);
            assertThat(FeaturePolicy.byKey("maybe")).isEmpty();
            assertThat(FeaturePolicy.byKey(null)).isEmpty();
        }

        @Test
        void cyclesThroughEveryStateAndComesBackRound() {
            FeaturePolicy policy = FeaturePolicy.AVAILABLE;
            assertThat(policy = policy.next()).isEqualTo(FeaturePolicy.FORCED_ON);
            assertThat(policy = policy.next()).isEqualTo(FeaturePolicy.FORCED_OFF);
            assertThat(policy.next()).isEqualTo(FeaturePolicy.AVAILABLE);
        }

        @Test
        void skipsForcedOnForAFeatureWithNoOwnerSwitchToOverride() {
            // Forcing on something the owner cannot switch off would mean nothing, so those features
            // offer two states rather than three.
            assertThat(FeaturePolicy.AVAILABLE.next(false)).isEqualTo(FeaturePolicy.FORCED_OFF);
        }
    }

    @Nested
    @DisplayName("available")
    class Available {

        @Test
        void leavesTheDecisionWithTheOwner() {
            Claim claim = claim();

            // Effects are on for a fresh claim, the other two off — the claim's own defaults.
            assertThat(features.isEnabled(claim, ClaimFeature.EFFECTS)).isTrue();
            assertThat(features.isEnabled(claim, ClaimFeature.PANTRY)).isFalse();
            assertThat(features.isEnabled(claim, ClaimFeature.AUTO_EQUIP)).isFalse();

            claim.effectsEnabled(false);
            claim.pantry().enabled(true);
            claim.equipment().enabled(true);

            assertThat(features.isEnabled(claim, ClaimFeature.EFFECTS)).isFalse();
            assertThat(features.isEnabled(claim, ClaimFeature.PANTRY)).isTrue();
            assertThat(features.isEnabled(claim, ClaimFeature.AUTO_EQUIP)).isTrue();
        }

        @Test
        void isTheOnlyStateAnOwnerMayChange() {
            assertThat(features.isEditableByOwner(ClaimFeature.PANTRY)).isTrue();
            policies.policy(ClaimFeature.PANTRY, FeaturePolicy.FORCED_ON);
            assertThat(features.isEditableByOwner(ClaimFeature.PANTRY)).isFalse();
            policies.policy(ClaimFeature.PANTRY, FeaturePolicy.FORCED_OFF);
            assertThat(features.isEditableByOwner(ClaimFeature.PANTRY)).isFalse();
        }
    }

    @Nested
    @DisplayName("forced")
    class Forced {

        @Test
        void onBeatsAnOwnerWhoSwitchedItOff() {
            Claim claim = claim();
            claim.effectsEnabled(false);
            claim.pantry().enabled(false);
            claim.equipment().enabled(false);

            policies.policy(ClaimFeature.EFFECTS, FeaturePolicy.FORCED_ON);
            policies.policy(ClaimFeature.PANTRY, FeaturePolicy.FORCED_ON);
            policies.policy(ClaimFeature.AUTO_EQUIP, FeaturePolicy.FORCED_ON);

            assertThat(features.isEnabled(claim, ClaimFeature.EFFECTS)).isTrue();
            assertThat(features.isEnabled(claim, ClaimFeature.PANTRY)).isTrue();
            assertThat(features.isEnabled(claim, ClaimFeature.AUTO_EQUIP)).isTrue();
        }

        @Test
        void offBeatsAnOwnerWhoSwitchedItOn() {
            Claim claim = claim();
            claim.effectsEnabled(true);
            claim.pantry().enabled(true);
            claim.equipment().enabled(true);

            policies.policy(ClaimFeature.EFFECTS, FeaturePolicy.FORCED_OFF);
            policies.policy(ClaimFeature.PANTRY, FeaturePolicy.FORCED_OFF);
            policies.policy(ClaimFeature.AUTO_EQUIP, FeaturePolicy.FORCED_OFF);

            assertThat(features.isEnabled(claim, ClaimFeature.EFFECTS)).isFalse();
            assertThat(features.isEnabled(claim, ClaimFeature.PANTRY)).isFalse();
            assertThat(features.isEnabled(claim, ClaimFeature.AUTO_EQUIP)).isFalse();
        }

        @Test
        void offTakesTheFeatureOutOfEveryListAsWell() {
            // Not merely inert: a menu that still shows a switch nothing obeys is worse than one that
            // does not show it, because the owner clicks it and nothing happens.
            policies.policy(ClaimFeature.PANTRY, FeaturePolicy.FORCED_OFF);

            assertThat(features.isOffered(ClaimFeature.PANTRY)).isFalse();
            assertThat(features.offeredFeatures()).doesNotContain(ClaimFeature.PANTRY);
        }

        @Test
        void onStillCountsAsOffered() {
            policies.policy(ClaimFeature.PANTRY, FeaturePolicy.FORCED_ON);
            assertThat(features.isOffered(ClaimFeature.PANTRY)).isTrue();
            assertThat(features.isForced(ClaimFeature.PANTRY)).isTrue();
        }
    }

    @Nested
    @DisplayName("with no claim to ask about")
    class NoClaim {

        @Test
        void anAvailableFeatureCountsAsOn() {
            // Asked about the server rather than about a claim — "is the pantry a thing here at all".
            assertThat(features.isEnabled(null, ClaimFeature.PANTRY)).isTrue();
        }

        @Test
        void aForcedOffFeatureIsStillOff() {
            policies.policy(ClaimFeature.PANTRY, FeaturePolicy.FORCED_OFF);
            assertThat(features.isEnabled(null, ClaimFeature.PANTRY)).isFalse();
        }
    }

    @Nested
    @DisplayName("who a feature reaches")
    class Audiences {

        @Test
        void everybodyUntilTheOwnerNarrowsIt() {
            Claim claim = claim();
            claim.pantry().enabled(true);

            for (LandAudience audience : LandAudience.values()) {
                assertThat(claim.featureServes(ClaimFeature.PANTRY, audience))
                        .as("a pantry nobody has narrowed should serve %s", audience)
                        .isTrue();
            }
        }

        @Test
        void narrowingLeavesTheOthersOut() {
            Claim claim = claim();
            claim.setFeatureAudience(ClaimFeature.PANTRY, LandAudience.VISITOR, false);

            assertThat(claim.featureServes(ClaimFeature.PANTRY, LandAudience.VISITOR)).isFalse();
            assertThat(claim.featureServes(ClaimFeature.PANTRY, LandAudience.OWNER)).isTrue();
        }

        @Test
        void aFeatureThatCannotBeNarrowedServesEverybodyWhateverIsAsked() {
            // A fence cannot exist for some people and not others.
            Claim claim = claim();
            claim.setFeatureAudience(ClaimFeature.FENCE, LandAudience.VISITOR, false);

            assertThat(claim.featureServes(ClaimFeature.FENCE, LandAudience.VISITOR)).isTrue();
        }

        @Test
        void narrowingEverybodyBackInStopsBeingStoredAtAll() {
            // Otherwise the claim file grows a line saying "serves everybody", which is the default.
            Claim claim = claim();
            claim.setFeatureAudience(ClaimFeature.PANTRY, LandAudience.VISITOR, false);
            claim.setFeatureAudience(ClaimFeature.PANTRY, LandAudience.VISITOR, true);

            assertThat(claim.narrowedFeatureAudiences()).doesNotContainKey(ClaimFeature.PANTRY);
        }
    }
}
