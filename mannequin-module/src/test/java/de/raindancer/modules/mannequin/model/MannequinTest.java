package de.raindancer.modules.mannequin.model;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MannequinTest {

    private final Mannequin base = Mannequin.freshlyPlaced("MQ1", UUID.randomUUID(), "world", 1, 64, 2);

    @Test
    void noOverrideFallsBackToTheServerDefault() {
        assertThat(base.maxHealthOverride()).isNull();
        assertThat(base.resolvedMaxHealth(20.0)).isEqualTo(20.0);
    }

    @Test
    void anOverrideWins() {
        Mannequin tough = base.withMaxHealthOverride(100.0);

        assertThat(tough.maxHealthOverride()).isEqualTo(100.0);
        assertThat(tough.resolvedMaxHealth(20.0)).isEqualTo(100.0);
    }

    @Test
    void settingItBackToNullRestoresTheDefault() {
        Mannequin tough = base.withMaxHealthOverride(100.0);

        assertThat(tough.withMaxHealthOverride(null).resolvedMaxHealth(20.0)).isEqualTo(20.0);
    }

    @Test
    void aZeroOrNegativeOverrideIsRejected() {
        assertThatThrownBy(() -> base.withMaxHealthOverride(0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> base.withMaxHealthOverride(-5.0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theAnchorBlockIsExactlyWhatWasPassedIn() {
        assertThat(base.x()).isEqualTo(1);
        assertThat(base.y()).isEqualTo(64);
        assertThat(base.z()).isEqualTo(2);
        assertThat(base.barrelY()).isEqualTo(63);
    }

    @Test
    void freshlyPlacedWithoutAKindDefaultsToPlayer() {
        assertThat(base.kind()).isEqualTo(MannequinKind.PLAYER);
    }

    @Test
    void freshlyPlacedWithAKindKeepsIt() {
        Mannequin zombie = Mannequin.freshlyPlaced("MQ2", UUID.randomUUID(), "world", 0, 64, 0,
                MannequinKind.ZOMBIE);

        assertThat(zombie.kind()).isEqualTo(MannequinKind.ZOMBIE);
    }

    @Test
    void aNullKindPassedDirectlyDefaultsToPlayer() {
        Mannequin built = new Mannequin("MQ3", UUID.randomUUID(), "world", 0, 64, 0, "Dummy",
                Map.of(), null, true, false, null, null, 0f, Set.of(), null);

        assertThat(built.kind()).isEqualTo(MannequinKind.PLAYER);
    }

    @Test
    void withKindChangesOnlyTheKind() {
        Mannequin golem = base.withKind(MannequinKind.IRON_GOLEM);

        assertThat(golem.kind()).isEqualTo(MannequinKind.IRON_GOLEM);
        assertThat(golem.id()).isEqualTo(base.id());
        assertThat(golem.owner()).isEqualTo(base.owner());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("a non-player kind with no override defaults to that kind's own health, not the server setting")
    void nonPlayerKindDefaultsToItsOwnHealthNotTheServerSetting() {
        Mannequin wither = base.withKind(MannequinKind.WITHER);

        assertThat(wither.maxHealthOverride()).isNull();
        assertThat(wither.resolvedMaxHealth(20.0)).isEqualTo(MannequinKind.WITHER.defaultMaxHealth());
        assertThat(wither.resolvedMaxHealth(20.0)).isEqualTo(300.0);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("an explicit override still wins over a non-player kind's own default")
    void explicitOverrideStillWinsForNonPlayerKinds() {
        Mannequin wither = base.withKind(MannequinKind.WITHER).withMaxHealthOverride(5.0);

        assertThat(wither.resolvedMaxHealth(20.0)).isEqualTo(5.0);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("freshlyPlaced without a yaw faces due south (0), same as vanilla's own default")
    void freshlyPlacedWithoutYawDefaultsToZero() {
        assertThat(base.yaw()).isZero();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("freshlyPlaced with a yaw keeps it, and withYaw changes only that")
    void freshlyPlacedWithYawKeepsIt() {
        Mannequin facingWest = Mannequin.freshlyPlaced("MQ4", UUID.randomUUID(), "world", 0, 64, 0,
                MannequinKind.PLAYER, 90f);

        assertThat(facingWest.yaw()).isEqualTo(90f);
        assertThat(base.withYaw(180f).yaw()).isEqualTo(180f);
        assertThat(base.withYaw(180f).id()).isEqualTo(base.id());
    }

    @Test
    @org.junit.jupiter.api.DisplayName("freshlyPlaced starts trusted by nobody")
    void freshlyPlacedHasNoTrustedPlayers() {
        assertThat(base.trusted()).isEmpty();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("the owner may always manage it, trusted or not")
    void ownerAlwaysMayManage() {
        assertThat(base.mayManage(base.owner())).isTrue();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("a stranger may not manage it")
    void strangerMayNotManage() {
        assertThat(base.mayManage(UUID.randomUUID())).isFalse();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("withTrusted adds somebody who may then manage it")
    void withTrustedGrantsManagement() {
        UUID friend = UUID.randomUUID();
        Mannequin shared = base.withTrusted(friend);

        assertThat(shared.trusted()).containsExactly(friend);
        assertThat(shared.mayManage(friend)).isTrue();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("withoutTrusted withdraws the right again")
    void withoutTrustedWithdrawsManagement() {
        UUID friend = UUID.randomUUID();
        Mannequin shared = base.withTrusted(friend);
        Mannequin withdrawn = shared.withoutTrusted(friend);

        assertThat(withdrawn.trusted()).isEmpty();
        assertThat(withdrawn.mayManage(friend)).isFalse();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("trusting the owner is a no-op — they already may manage it")
    void trustingTheOwnerIsANoOp() {
        assertThat(base.withTrusted(base.owner()).trusted()).isEmpty();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("the owner can never end up inside their own trusted set, even handed in directly")
    void ownerNeverEndsUpInTrustedSet() {
        UUID owner = UUID.randomUUID();
        Mannequin built = new Mannequin("MQ5", owner, "world", 0, 64, 0, "Dummy",
                Map.of(), null, true, false, null, null, 0f, Set.of(owner), null);

        assertThat(built.trusted()).isEmpty();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("freshlyPlaced belongs to no claim")
    void freshlyPlacedHasNoClaim() {
        assertThat(base.claimId()).isNull();
    }

    @Test
    @org.junit.jupiter.api.DisplayName("withClaimId links it to a claim")
    void withClaimIdLinksIt() {
        UUID claim = UUID.randomUUID();
        assertThat(base.withClaimId(claim).claimId()).isEqualTo(claim);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("withClaimId(null) takes it off any claim it was on")
    void withClaimIdNullUnlinksIt() {
        Mannequin linked = base.withClaimId(UUID.randomUUID());
        assertThat(linked.withClaimId(null).claimId()).isNull();
    }
}
