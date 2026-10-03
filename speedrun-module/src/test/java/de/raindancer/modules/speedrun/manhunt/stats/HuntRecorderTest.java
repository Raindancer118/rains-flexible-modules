package de.raindancer.modules.speedrun.manhunt.stats;

import de.raindancer.modules.speedrun.manhunt.model.Hunt;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerPortalEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The one moment of a hunt the lobby's own splits do not take: the portals everybody goes through,
 * for their own numbers. The milestones are the run's splits — see SpeedrunMilestoneListenerTest,
 * {@code onlyWhoTheGameCounts}, for a Hunter in the Nether being no news in a hunt.
 */
@DisplayName("the portals of a hunt")
class HuntRecorderTest {

    private static final UUID RUNNER = UUID.nameUUIDFromBytes("runner".getBytes());
    private static final UUID HUNTER = UUID.nameUUIDFromBytes("hunter".getBytes());

    private final Hunt hunt = Hunt.of(Set.of(RUNNER, HUNTER), Set.of(RUNNER));
    private final List<UUID> portals = new ArrayList<>();
    private final HuntRecorder recorder = new HuntRecorder(hunt, portals::add);

    private static PlayerPortalEvent through(UUID id) {
        PlayerPortalEvent portal = mock(PlayerPortalEvent.class);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(portal.getPlayer()).thenReturn(player);
        return portal;
    }

    @Test
    @DisplayName("every portal somebody in the hunt takes is counted, and nobody else's")
    void portalsCounted() {
        recorder.onPortal(through(HUNTER));
        recorder.onPortal(through(UUID.randomUUID()));

        assertThat(portals).containsExactly(HUNTER);
    }
}
