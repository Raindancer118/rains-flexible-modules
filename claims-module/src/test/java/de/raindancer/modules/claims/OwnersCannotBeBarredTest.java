package de.raindancer.modules.claims;

import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimBan;
import de.raindancer.modules.claims.model.ClaimShape;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That nobody can be barred from a claim they own.
 *
 * <h2>Why this is the model's job and not the screen's</h2>
 * The rule was enforced at each call site: the command checked {@code isOwner} and refused, and the ban screen
 * did the same after the name was typed. When the screen moved to a chooser, the check went with it — the
 * argument being that a list which never offers an owner cannot produce one.
 *
 * <p>That argument is true today and is the wrong place to rest an invariant. An exclusion list is a UI
 * affordance: it is recomputed on every open, it is easy to get subtly wrong, and the next screen that wants to
 * bar somebody starts from nothing. The state change is the one place every path goes through.
 *
 * <p>What it costs to get wrong is also worse than it sounds. A banned owner is denied entry to their own
 * claim, and lifting the ban is done from a screen inside it, so they cannot undo it themselves — the claim
 * has to be handed to an admin.
 */
class OwnersCannotBeBarredTest {

    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();
    private static final UUID SECOND_OWNER = UUID.randomUUID();

    private static Claim claim() {
        ClaimShape shape = ClaimShape.rectangle(0, 0, 8, 8, 60, 80);
        return new Claim(UUID.randomUUID(), "home", UUID.randomUUID(), "world", shape, OWNER);
    }

    @Test
    @DisplayName("barring the owner does nothing at all")
    void theOwnerIsNeverBarred() {
        Claim claim = claim();

        assertThat(claim.ban(ClaimBan.permanent(OWNER, STRANGER, "because")))
                .as("refused at the state change, so no screen or command can arrange it")
                .isFalse();
        assertThat(claim.bans()).isEmpty();
    }

    @Test
    @DisplayName("a co-owner is just as safe as the first one")
    void everyOwnerIsSafe() {
        Claim claim = claim();
        claim.addOwner(SECOND_OWNER);

        assertThat(claim.ban(ClaimBan.permanent(SECOND_OWNER, OWNER, "fell out"))).isFalse();
        assertThat(claim.bans()).isEmpty();
    }

    @Test
    @DisplayName("a timeout is a ban, so it is refused the same way")
    void atimeoutIsNotAWayRound() {
        Claim claim = claim();

        assertThat(claim.ban(ClaimBan.timeout(OWNER, STRANGER, 60_000L, "cooling off"))).isFalse();
        assertThat(claim.bans()).isEmpty();
    }

    @Test
    @DisplayName("everybody else can still be barred, which is the point of the feature")
    void astrangerIsBarredNormally() {
        Claim claim = claim();

        assertThat(claim.ban(ClaimBan.permanent(STRANGER, OWNER, "kept breaking chests"))).isTrue();
        assertThat(claim.bans()).containsKey(STRANGER);
    }

    @Test
    @DisplayName("somebody barred before they became an owner is let back in")
    void becomingAnOwnerLiftsTheBan() {
        // The order this can happen in: bar somebody, then hand them the claim. Leaving the ban in place would
        // give them a claim they are not allowed to walk into, and no way to fix it from the inside.
        Claim claim = claim();
        claim.ban(ClaimBan.permanent(STRANGER, OWNER, "at the time"));
        assertThat(claim.bans()).containsKey(STRANGER);

        claim.addOwner(STRANGER);

        assertThat(claim.bans())
                .as("an owner who cannot enter their own claim cannot lift the ban either")
                .doesNotContainKey(STRANGER);
    }
}
