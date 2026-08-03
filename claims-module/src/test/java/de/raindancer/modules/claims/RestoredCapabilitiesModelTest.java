package de.raindancer.modules.claims;

import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.modules.claims.model.ClaimBan;
import de.raindancer.modules.claims.model.ClaimMember;
import de.raindancer.modules.claims.model.ClaimPoint;
import de.raindancer.modules.claims.model.ClaimShape;
import de.raindancer.core.world.protection.LandAction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The model half of four capabilities that existed on disk and nowhere else: a co-owner list that only
 * {@code ClaimStorage} ever wrote to, a timed ban type with zero callers, and two {@code ClaimMember} sets
 * — {@code adminPermissions} and {@code grantablePermissions} — that were read by the rules and the menus
 * but never written outside the loader.
 *
 * <h2>Why this is separate from the wiring tests</h2>
 * These are the parts a JVM can check without a server: the data structures themselves, and the one rule
 * that had to change to make restoring them safe — {@link Claim#removeOwner} refusing the primary owner.
 * {@code RestoredCapabilitiesWiringTest} covers the commands and screens that now reach these from outside
 * {@code model} and {@code store}.
 */
class RestoredCapabilitiesModelTest {

    private static Claim claim(UUID primaryOwner) {
        ClaimShape shape = new ClaimShape(List.of(
                new ClaimPoint(0, 0), new ClaimPoint(0, 8),
                new ClaimPoint(8, 8), new ClaimPoint(8, 0)), 0, 128);
        return new Claim(UUID.randomUUID(), "test", UUID.randomUUID(), "world", shape, primaryOwner);
    }

    @Nested
    @DisplayName("co-owners — the primary owner can never be taken off")
    class CoOwners {

        @Test
        @DisplayName("a second owner may be removed")
        void aCoOwnerComesOff() {
            UUID primary = UUID.randomUUID();
            UUID coOwner = UUID.randomUUID();
            Claim claim = claim(primary);
            claim.addOwner(coOwner);

            assertThat(claim.removeOwner(coOwner)).isTrue();
            assertThat(claim.owners()).containsExactly(primary);
        }

        @Test
        @DisplayName("the primary owner cannot be removed even while a co-owner is still there")
        void thePrimaryOwnerStays() {
            // This is the bug the task called out by name: with only "the last owner may not leave"
            // enforced, a co-owner added after the fact could be used to strip the original claimant out
            // from under them while the claim kept working — just owned by whoever removed them last.
            UUID primary = UUID.randomUUID();
            UUID coOwner = UUID.randomUUID();
            Claim claim = claim(primary);
            claim.addOwner(coOwner);

            assertThat(claim.removeOwner(primary))
                    .as("removing the primary owner while somebody else still holds the claim must be refused")
                    .isFalse();
            assertThat(claim.owners()).containsExactlyInAnyOrder(primary, coOwner);
            assertThat(claim.primaryOwner()).isEqualTo(primary);
        }

        @Test
        @DisplayName("the last owner still cannot leave, exactly as before")
        void theLastOwnerStillCannotLeave() {
            UUID primary = UUID.randomUUID();
            Claim claim = claim(primary);

            assertThat(claim.removeOwner(primary)).isFalse();
            assertThat(claim.owners()).containsExactly(primary);
        }

        @Test
        @DisplayName("adding the same person twice does not duplicate them")
        void addingIsIdempotent() {
            UUID primary = UUID.randomUUID();
            UUID coOwner = UUID.randomUUID();
            Claim claim = claim(primary);

            assertThat(claim.addOwner(coOwner)).isTrue();
            assertThat(claim.addOwner(coOwner)).isFalse();
            assertThat(claim.owners()).hasSize(2);
        }

        @Test
        @DisplayName("becoming an owner drops any trusted-member entry — an owner is not also a guest")
        void becomingAnOwnerDropsMembership() {
            UUID primary = UUID.randomUUID();
            UUID promoted = UUID.randomUUID();
            Claim claim = claim(primary);
            claim.memberOrCreate(promoted).applyDefaultTrust();

            claim.addOwner(promoted);

            assertThat(claim.member(promoted)).isEmpty();
        }
    }

    @Nested
    @DisplayName("timed bans — ClaimBan.timeout, which nothing outside model called before this")
    class TimedBans {

        @Test
        @DisplayName("a timeout is not permanent and remembers roughly how long is left")
        void aTimeoutExpires() {
            ClaimBan ban = ClaimBan.timeout(UUID.randomUUID(), UUID.randomUUID(), 60_000L, "too loud");

            assertThat(ban.permanent()).isFalse();
            assertThat(ban.expired()).isFalse();
            assertThat(ban.remainingMillis()).isLessThanOrEqualTo(60_000L).isGreaterThan(0L);
        }

        @Test
        @DisplayName("a timeout in the past reads as expired")
        void anExpiredTimeoutSaysSo() {
            ClaimBan ban = new ClaimBan(UUID.randomUUID(), UUID.randomUUID(),
                    System.currentTimeMillis() - 10_000L, System.currentTimeMillis() - 1_000L, "");

            assertThat(ban.expired()).isTrue();
            assertThat(ban.remainingMillis()).isZero();
        }

        @Test
        @DisplayName("an expired timeout is pruned the moment the claim is asked about it")
        void aClaimForgetsAnExpiredTimeoutOnRead() {
            Claim claim = claim(UUID.randomUUID());
            UUID target = UUID.randomUUID();
            claim.ban(new ClaimBan(target, UUID.randomUUID(),
                    System.currentTimeMillis() - 10_000L, System.currentTimeMillis() - 1_000L, ""));

            assertThat(claim.activeBan(target)).isEmpty();
            assertThat(claim.bans()).doesNotContainKey(target);
        }

        @Test
        @DisplayName("a permanent ban still reads as permanent, unaffected by the new type existing")
        void permanentIsUnchanged() {
            ClaimBan ban = ClaimBan.permanent(UUID.randomUUID(), UUID.randomUUID(), "no reason");

            assertThat(ban.permanent()).isTrue();
            assertThat(ban.expired()).isFalse();
            assertThat(ban.remainingMillis()).isEqualTo(Long.MAX_VALUE);
        }

        @Test
        @DisplayName("a zero or negative duration still counts as a timeout, not a permanent ban")
        void aDegenerateDurationStaysATimeout() {
            ClaimBan ban = ClaimBan.timeout(UUID.randomUUID(), UUID.randomUUID(), 0L, "");
            assertThat(ban.permanent()).isFalse();
        }
    }

    @Nested
    @DisplayName("per-member admin rights and grantable permissions")
    class MemberRights {

        @Test
        @DisplayName("a fresh member has neither admin rights nor anything grantable")
        void freshMemberHasNothingExtra() {
            ClaimMember member = new ClaimMember(UUID.randomUUID());
            assertThat(member.adminPermissions()).isEmpty();
            assertThat(member.grantablePermissions()).isEmpty();
            assertThat(member.isClaimAdmin()).isFalse();
        }

        @Test
        @DisplayName("granting an admin right is what MemberAdminMenu now writes")
        void adminRightsAreMutable() {
            ClaimMember member = new ClaimMember(UUID.randomUUID());
            member.adminPermissions().add(ClaimAdminPermission.MANAGE_BANS);

            assertThat(member.has(ClaimAdminPermission.MANAGE_BANS)).isTrue();
            assertThat(member.isClaimAdmin()).isTrue();
        }

        @Test
        @DisplayName("granting something as grantable is what MemberGrantableMenu now writes")
        void grantablePermissionsAreMutable() {
            ClaimMember member = new ClaimMember(UUID.randomUUID());
            member.grantablePermissions().add(LandAction.DOORS);

            assertThat(member.grantablePermissions()).contains(LandAction.DOORS);
        }
    }
}
