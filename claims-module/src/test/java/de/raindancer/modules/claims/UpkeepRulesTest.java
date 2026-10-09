package de.raindancer.modules.claims;

import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimAttempt;
import de.raindancer.modules.claims.model.ClaimShape;
import de.raindancer.modules.claims.model.ClaimNames;
import de.raindancer.modules.claims.rules.ClaimAreaRule;
import de.raindancer.modules.claims.rules.ClaimRules;
import de.raindancer.modules.claims.store.ClaimRegistry;
import de.raindancer.modules.claims.store.ZoneRegistry;
import de.raindancer.core.world.protection.LandAction;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UpkeepRulesTest {

    private final UUID me = UUID.randomUUID();
    private final Player player = mock(Player.class);
    private final World world = mock(World.class);
    private final UUID worldId = UUID.randomUUID();

    private Verdict judge(ClaimAttempt attempt, Function<UUID, Money> owed, boolean bypass) {
        when(player.getUniqueId()).thenReturn(me);
        ClaimNames names = mock(ClaimNames.class);
        when(names.available(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(true);
        when(world.getName()).thenReturn("world");
        when(world.getUID()).thenReturn(worldId);
        when(world.getMaxHeight()).thenReturn(320);
        when(world.getMinHeight()).thenReturn(-64);
        return ClaimRules.standard(() -> ClaimSettings.DEFAULTS, new ClaimRegistry(), mock(ZoneRegistry.class),
                names, p -> bypass, owed).judge(attempt);
    }

    private static ClaimShape box(int size) {
        return ClaimShape.rectangle(0, 0, size - 1, size - 1, -64, 319);
    }

    @Test
    @DisplayName("somebody in arrears cannot make a new claim, and is told how much")
    void cannotCreate() {
        Verdict verdict = judge(ClaimAttempt.toCreate(player, world, box(32), "home"), who -> Money.of(500), false);
        assertThat(verdict.isAllowed()).isFalse();
        assertThat(verdict.reason()).isEqualTo("error.in-arrears");
        assertThat(verdict.detail()).isNotBlank();
    }

    @Test
    @DisplayName("nor enlarge one, but redrawing it smaller or the same is fine")
    void growingIsRefusedShrinkingIsNot() {
        Claim existing = new Claim(UUID.randomUUID(), "home", worldId, "world", box(32), me);
        Function<UUID, Money> owes = who -> Money.of(500);
        assertThat(judge(ClaimAttempt.toReshape(player, world, box(48), existing), owes, false).reason())
                .isEqualTo("error.in-arrears");
        assertThat(judge(ClaimAttempt.toReshape(player, world, box(32), existing), owes, false).isAllowed())
                .isTrue();
        assertThat(judge(ClaimAttempt.toReshape(player, world, box(16), existing), owes, false).isAllowed())
                .isTrue();
    }

    @Test
    @DisplayName("nobody owing anything, or a bypassing admin, is not held up")
    void paidUpOrBypassing() {
        assertThat(judge(ClaimAttempt.toCreate(player, world, box(32), "home"), who -> Money.ZERO, false)
                .isAllowed()).isTrue();
        assertThat(judge(ClaimAttempt.toCreate(player, world, box(32), "home"), who -> Money.of(500), true)
                .isAllowed()).isTrue();
    }

    @Test
    @DisplayName("a lapsed claim lets everybody do everything until it is paid; never before")
    void lapsedClaimIsOpen() {
        UUID stranger = UUID.randomUUID();
        Claim claim = new Claim(UUID.randomUUID(), "home", worldId, "world", box(32), me);
        ClaimAreaRule area = new ClaimAreaRule(claim);
        claim.setPublic(LandAction.BUILD, false);
        assertThat(area.may(stranger, LandAction.BUILD)).isFalse();
        claim.lapsed(true);
        assertThat(area.may(stranger, LandAction.BUILD)).isTrue();
        claim.lapsed(false);
        assertThat(area.may(stranger, LandAction.BUILD)).isFalse();
    }
}
