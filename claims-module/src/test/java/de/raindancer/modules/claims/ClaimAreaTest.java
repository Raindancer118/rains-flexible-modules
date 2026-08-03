package de.raindancer.modules.claims;

import de.raindancer.core.world.protection.LandAction;
import de.raindancer.core.world.protection.LandAudience;
import de.raindancer.core.world.protection.LandFlag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who may do what inside a claim.
 *
 * <p>The single most consequential rule in the plugin: wrong one way and a stranger empties somebody's chests,
 * wrong the other and an owner is locked out of their own house. It lives here rather than in Core because it
 * is about members, bans and public grants — things a claim has and an arena does not.
 *
 * <p>Each test pins one step of the order, and the one people get wrong is the ban: banning somebody who
 * happens to be trusted has to actually stop them, or the ban is decoration.
 */
class ClaimAreaTest {

    private final UUID owner = UUID.randomUUID();

    private Claim claim() {
        ClaimShape shape = new ClaimShape(List.of(
                new ClaimPoint(0, 0), new ClaimPoint(0, 16),
                new ClaimPoint(16, 16), new ClaimPoint(16, 0)), 0, 128);
        return new Claim(UUID.randomUUID(), "home", UUID.randomUUID(), "world", shape, owner);
    }

    private static ClaimArea areaOf(Claim claim) {
        return new ClaimArea(claim);
    }

    @Nested
    @DisplayName("the decision")
    class Decision {

        @Test
        void anOwnerMayDoAnything() {
            ClaimArea area = areaOf(claim());
            for (LandAction action : LandAction.values()) {
                assertThat(area.may(owner, action))
                        .as("an owner should hold %s", action)
                        .isTrue();
            }
        }

        @Test
        void aStrangerGetsOnlyWhatIsPublic() {
            ClaimArea area = areaOf(claim());
            UUID stranger = UUID.randomUUID();

            assertThat(area.may(stranger, LandAction.ENTER)).isTrue();
            assertThat(area.may(stranger, LandAction.BUILD)).isFalse();
            assertThat(area.may(stranger, LandAction.CONTAINERS)).isFalse();
        }

        @Test
        void aStrangerLosesWhatTheOwnerTookAway() {
            Claim claim = claim();
            claim.setPublic(LandAction.ENTER, false);

            assertThat(areaOf(claim).may(UUID.randomUUID(), LandAction.ENTER)).isFalse();
        }

        @Test
        void aTrustedMemberGetsWhatTheyWereGivenAndNothingElse() {
            Claim claim = claim();
            UUID friend = UUID.randomUUID();
            claim.memberOrCreate(friend).permissions().add(LandAction.BUILD);

            assertThat(areaOf(claim).may(friend, LandAction.BUILD)).isTrue();
            assertThat(areaOf(claim).may(friend, LandAction.CONTAINERS)).isFalse();
        }

        @Test
        void aTrustedMemberDoesNotFallBackToThePublicGrant() {
            // Being named in the claim is the whole permission list, not a bonus on top of the public one.
            // Otherwise taking a permission away from somebody trusted would silently do nothing whenever it
            // happened to be public as well.
            Claim claim = claim();
            UUID friend = UUID.randomUUID();
            claim.memberOrCreate(friend);

            assertThat(claim.publicHas(LandAction.WORKSTATIONS)).isTrue();
            assertThat(areaOf(claim).may(friend, LandAction.WORKSTATIONS)).isFalse();
        }

        @Test
        void aBanBeatsAnExplicitGrant() {
            Claim claim = claim();
            UUID friend = UUID.randomUUID();
            claim.memberOrCreate(friend).permissions().add(LandAction.BUILD);
            claim.ban(ClaimBan.permanent(friend, owner, "griefing"));

            assertThat(areaOf(claim).may(friend, LandAction.BUILD)).isFalse();
            assertThat(areaOf(claim).may(friend, LandAction.ENTER)).isFalse();
        }

        @Test
        void aBanDoesNotTouchTheOwner() {
            // A co-owner banning the other one must not be able to lock them out of their own claim.
            Claim claim = claim();
            claim.ban(ClaimBan.permanent(owner, UUID.randomUUID(), "a mistake"));

            assertThat(areaOf(claim).may(owner, LandAction.BUILD)).isTrue();
        }

        @Test
        void aClaimAdminMayAlwaysGetIn() {
            // Otherwise "may manage the members" is useless to somebody who may not walk in.
            Claim claim = claim();
            UUID manager = UUID.randomUUID();
            claim.setPublic(LandAction.ENTER, false);
            claim.memberOrCreate(manager).adminPermissions().add(ClaimAdminPermission.MANAGE_MEMBERS);

            assertThat(areaOf(claim).may(manager, LandAction.ENTER)).isTrue();
            assertThat(areaOf(claim).may(manager, LandAction.BUILD)).isFalse();
        }

        @Test
        void nobodyInParticularGetsThePublicGrant() {
            // Core asks with a null uuid for the things that happen with no player behind them.
            ClaimArea area = areaOf(claim());
            assertThat(area.may(null, LandAction.ENTER)).isTrue();
            assertThat(area.may(null, LandAction.BUILD)).isFalse();
        }
    }

    @Nested
    @DisplayName("what Core sees")
    class AsAnArea {

        @Test
        void theIdIsStableAndIsTheClaimsOwn() {
            Claim claim = claim();
            assertThat(areaOf(claim).id()).isEqualTo(claim.id().toString());
        }

        @Test
        void theOwnersComeThroughInOrder() {
            Claim claim = claim();
            UUID second = UUID.randomUUID();
            claim.addOwner(second);

            assertThat(areaOf(claim).owners()).containsExactly(owner, second);
        }

        @Test
        void theOwnerListCannotBeChangedThroughIt() {
            assertThat(org.assertj.core.api.Assertions.catchThrowable(
                    () -> areaOf(claim()).owners().add(UUID.randomUUID())))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void standingIsReportedForEachTier() {
            Claim claim = claim();
            UUID friend = UUID.randomUUID();
            claim.memberOrCreate(friend);

            ClaimArea area = areaOf(claim);
            assertThat(area.audienceOf(owner)).isEqualTo(LandAudience.OWNER);
            assertThat(area.audienceOf(friend)).isEqualTo(LandAudience.TRUSTED);
            assertThat(area.audienceOf(UUID.randomUUID())).isEqualTo(LandAudience.VISITOR);
            assertThat(area.audienceOf(null)).isEqualTo(LandAudience.VISITOR);
        }

        @Test
        void aFlagNobodyDecidedIsLeftUndecidedRatherThanAnswered() {
            // Empty is what lets the server default have a say. Answering a value for every flag would pin
            // every claim to whatever the defaults were the day it was made.
            assertThat(areaOf(claim()).flagOverride(LandFlag.PVP, LandAudience.OWNER)).isEmpty();
        }

        @Test
        void aFlagTheOwnerDecidedComesThrough() {
            Claim claim = claim();
            claim.setFlagOverride(LandFlag.PVP, LandAudience.VISITOR, true);

            assertThat(areaOf(claim).flagOverride(LandFlag.PVP, LandAudience.VISITOR)).contains(true);
        }
    }
}
