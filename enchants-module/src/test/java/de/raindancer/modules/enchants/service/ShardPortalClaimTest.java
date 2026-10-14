package de.raindancer.modules.enchants.service;

import de.raindancer.core.world.protection.Land;
import de.raindancer.core.world.protection.LandAction;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Who may open a portal on claimed land: whoever may build there — it places blocks, it sets nothing on fire. */
class ShardPortalClaimTest {

    private final World world = mock(World.class);
    private final Land land = mock(Land.class);
    private final Player player = mock(Player.class);
    private final List<int[]> inside = List.of(new int[]{1, 65, 0}, new int[]{2, 65, 0}, new int[]{1, 66, 0});

    @Test
    @DisplayName("somebody trusted to build, not to light fires, may open a portal in the claim")
    void buildingIsEnough() {
        when(land.can(eq(player), any(Location.class), eq(LandAction.BUILD))).thenReturn(true);
        when(land.can(eq(player), any(Location.class), eq(LandAction.IGNITE))).thenReturn(false);

        assertThat(ShardPortalService.mayOpen(land, player, world, inside)).isTrue();
    }

    @Test
    @DisplayName("somebody who may not build there may not open one")
    void notWithoutBuilding() {
        when(land.can(eq(player), any(Location.class), eq(LandAction.BUILD))).thenReturn(false);
        when(land.can(eq(player), any(Location.class), eq(LandAction.IGNITE))).thenReturn(true);

        assertThat(ShardPortalService.mayOpen(land, player, world, inside)).isFalse();
    }
}
