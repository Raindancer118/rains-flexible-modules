package de.raindancer.modules.claims;

import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAdminPermission;
import de.raindancer.modules.claims.model.ClaimBan;
import de.raindancer.modules.claims.model.ClaimPoint;
import de.raindancer.modules.claims.model.ClaimShape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Handing a claim to somebody else entirely, the admin route.
 *
 * <h2>Why this is not {@code addOwner}/{@code removeOwner}</h2>
 * Those two protect a co-owner from ever being able to strip the original owner out from under them —
 * {@code removeOwner} refuses outright, whatever else is true. That protection has to hold for players.
 * An admin reassigning a claim whose owner has left for good, or one made in the wrong person's name, is
 * not a co-owner trying to push somebody out — nobody here still wants the claim, so the whole ownership
 * list is simply replaced. {@code transferTo} is the one place that is allowed to do that.
 */
class ClaimOwnershipTest {

    private static final UUID ORIGINAL_OWNER = UUID.randomUUID();
    private static final UUID CO_OWNER = UUID.randomUUID();
    private static final UUID NEW_OWNER = UUID.randomUUID();

    private Claim claim() {
        ClaimShape shape = new ClaimShape(List.of(
                new ClaimPoint(0, 0), new ClaimPoint(0, 16),
                new ClaimPoint(16, 16), new ClaimPoint(16, 0)), 0, 128);
        return new Claim(UUID.randomUUID(), "home", UUID.randomUUID(), "world", shape, ORIGINAL_OWNER);
    }

    @Test
    @DisplayName("the new owner is the only owner afterwards")
    void replacesEveryOwner() {
        Claim claim = claim();
        claim.addOwner(CO_OWNER);

        claim.transferTo(NEW_OWNER);

        assertThat(claim.owners()).containsExactly(NEW_OWNER);
        assertThat(claim.isOwner(ORIGINAL_OWNER)).isFalse();
        assertThat(claim.isOwner(CO_OWNER)).isFalse();
        assertThat(claim.primaryOwner()).isEqualTo(NEW_OWNER);
    }

    @Test
    @DisplayName("succeeds where removeOwner refuses — the whole point of the admin route")
    void doesWhatRemoveOwnerWontEvenWithACoOwner() {
        Claim claim = claim();
        claim.addOwner(CO_OWNER);

        // The protection this is deliberately not: a co-owner cannot remove the primary owner even
        // while somebody else remains, so the player-facing route has no way to reach this state.
        assertThat(claim.removeOwner(ORIGINAL_OWNER)).isFalse();

        claim.transferTo(NEW_OWNER);

        assertThat(claim.owners()).containsExactly(NEW_OWNER);
    }

    @Test
    @DisplayName("a new owner who was trusted stops being merely trusted")
    void takesThemOffTheMemberList() {
        Claim claim = claim();
        claim.memberOrCreate(NEW_OWNER).permissions().add(de.raindancer.core.world.protection.LandAction.BUILD);

        claim.transferTo(NEW_OWNER);

        assertThat(claim.member(NEW_OWNER)).isEmpty();
        assertThat(claim.isOwner(NEW_OWNER)).isTrue();
    }

    @Test
    @DisplayName("a new owner who was banned is not still barred from the claim they now own")
    void liftsAnExistingBan() {
        Claim claim = claim();
        claim.ban(ClaimBan.permanent(NEW_OWNER, ORIGINAL_OWNER, "test"));

        claim.transferTo(NEW_OWNER);

        assertThat(claim.activeBan(NEW_OWNER)).isEmpty();
    }

    @Test
    @DisplayName("the previous owner's own-rules exemption does not survive the handover")
    void clearsEverybodysExemption() {
        Claim claim = claim();
        claim.toggleIgnoringOwnRules(ORIGINAL_OWNER);
        assertThat(claim.isIgnoringOwnRules(ORIGINAL_OWNER)).isTrue();

        claim.transferTo(NEW_OWNER);

        assertThat(claim.isIgnoringOwnRules(ORIGINAL_OWNER))
                .as("they are not an owner any more, so the exemption cannot mean anything")
                .isFalse();
    }

    @Test
    @DisplayName("a claim admin who was one of the members loses that role by becoming the owner")
    void aClaimAdminMemberBecomingOwnerIsNoLongerJustAMember() {
        Claim claim = claim();
        claim.memberOrCreate(NEW_OWNER).adminPermissions().add(ClaimAdminPermission.MANAGE_MEMBERS);
        assertThat(claim.member(NEW_OWNER).orElseThrow().isClaimAdmin()).isTrue();

        claim.transferTo(NEW_OWNER);

        assertThat(claim.member(NEW_OWNER)).isEmpty();
        assertThat(claim.isOwner(NEW_OWNER)).isTrue();
    }

    @Test
    @DisplayName("nothing changes if handed to nobody")
    void aNullNewOwnerChangesNothing() {
        Claim claim = claim();

        claim.transferTo(null);

        assertThat(claim.owners()).containsExactly(ORIGINAL_OWNER);
    }
}
