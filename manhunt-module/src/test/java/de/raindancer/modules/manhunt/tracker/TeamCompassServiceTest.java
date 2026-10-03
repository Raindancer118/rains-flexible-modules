package de.raindancer.modules.manhunt.tracker;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.manhunt.ManhuntSettings;
import de.raindancer.modules.manhunt.model.Hunt;
import de.raindancer.modules.manhunt.tracker.TrackerCompass.Aim;
import de.raindancer.modules.manhunt.tracker.TrackerCompass.Candidate;
import de.raindancer.modules.manhunt.tracker.TrackerCompass.Following;
import de.raindancer.modules.manhunt.tracker.TrackerCompass.Point;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The second compass — a recovery compass pointing at your own side. */
@DisplayName("the team compass")
class TeamCompassServiceTest {

    private static final UUID RUNNER_A = UUID.nameUUIDFromBytes("runner-a".getBytes());
    private static final UUID RUNNER_B = UUID.nameUUIDFromBytes("runner-b".getBytes());
    private static final UUID HUNTER_A = UUID.nameUUIDFromBytes("hunter-a".getBytes());
    private static final UUID HUNTER_B = UUID.nameUUIDFromBytes("hunter-b".getBytes());

    private final World world = mock(World.class);
    private final Server server = mock(Server.class);
    private final Messages messages = mock(Messages.class);
    private Hunt hunt;
    private AtomicReference<ManhuntSettings> settings;
    private TeamCompassService team;

    @BeforeEach
    void setUp() {
        when(world.getName()).thenReturn("hunt");
        hunt = Hunt.of(Set.of(RUNNER_A, RUNNER_B, HUNTER_A, HUNTER_B), Set.of(RUNNER_A, RUNNER_B));
        settings = new AtomicReference<>(ManhuntSettings.DEFAULTS.withTrackerTeamCompass(true));
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("manhunt");
        when(plugin.namespace()).thenReturn("manhunt");
        when(plugin.getServer()).thenReturn(server);
        online(RUNNER_A, "RunnerA", 0);
        online(RUNNER_B, "RunnerB", 40);
        online(HUNTER_A, "HunterA", 10);
        online(HUNTER_B, "HunterB", 90);
        team = new TeamCompassService(plugin, () -> Optional.of(hunt),
                new TrackerCompass(settings.get(), new PortalMemory()), messages, null, settings.get());
    }

    private Player online(UUID id, String name, double x) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(name);
        when(player.isOnline()).thenReturn(true);
        when(player.getWorld()).thenReturn(world);
        when(player.getLocation()).thenReturn(new Location(world, x, 64, 0));
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getContents()).thenReturn(new ItemStack[36]);
        when(player.getInventory()).thenReturn(inventory);
        when(server.getPlayer(id)).thenReturn(player);
        return player;
    }

    private Player player(UUID id) {
        return server.getPlayer(id);
    }

    @Nested
    @DisplayName("who counts as a teammate")
    class Teammates {

        @Test
        @DisplayName("your own side, without you")
        void ownSide() {
            assertThat(team.teammatesOf(hunt, RUNNER_A)).extracting(Candidate::id).containsExactly(RUNNER_B);
            assertThat(team.teammatesOf(hunt, HUNTER_A)).extracting(Candidate::id).containsExactly(HUNTER_B);
        }

        @Test
        @DisplayName("a caught Runner is nobody's teammate to find any more")
        void caughtRunner() {
            hunt.eliminate(RUNNER_B);

            assertThat(team.teammatesOf(hunt, RUNNER_A)).isEmpty();
        }

        @Test
        @DisplayName("a side change moves you to the other side's compass at once")
        void sideChange() {
            hunt.moveToRunners(HUNTER_B);

            assertThat(team.teammatesOf(hunt, HUNTER_A)).isEmpty();
            assertThat(team.teammatesOf(hunt, RUNNER_A)).extracting(Candidate::id)
                    .containsExactlyInAnyOrder(RUNNER_B, HUNTER_B);
        }
    }

    @Nested
    @DisplayName("where it points")
    class Aiming {

        @Test
        @DisplayName("the nearest teammate, until you pick one")
        void nearestThenPicked() {
            List<Candidate> mates = List.of(
                    new Candidate(HUNTER_B, new Point("hunt", 90, 64, 0)),
                    new Candidate(RUNNER_B, new Point("hunt", 20, 64, 0)));
            Point me = new Point("hunt", 0, 64, 0);

            assertThat(team.aimFor(me, mates, null).target()).isEqualTo(RUNNER_B);
            assertThat(team.aimFor(me, mates, Following.of(HUNTER_B)).target()).isEqualTo(HUNTER_B);
        }

        @Test
        @DisplayName("a pick who left the side falls back to the nearest")
        void stalePick() {
            List<Candidate> mates = List.of(new Candidate(RUNNER_B, new Point("hunt", 20, 64, 0)));

            assertThat(team.aimFor(new Point("hunt", 0, 64, 0), mates, Following.of(HUNTER_B)).target())
                    .isEqualTo(RUNNER_B);
        }

        @Test
        @DisplayName("nobody on your side is nothing to point at")
        void alone() {
            assertThat(team.aimFor(new Point("hunt", 0, 64, 0), List.of(), null).kind()).isEqualTo(Aim.Kind.NONE);
        }
    }

    @Nested
    @DisplayName("picking")
    class Picking {

        @Test
        @DisplayName("the list is the nearest teammate and each teammate")
        void targets() {
            assertThat(team.targetsFor(player(HUNTER_A)))
                    .extracting(TrackerCompassService.Target::name)
                    .containsExactly("Nearest teammate", "HunterB");
        }

        @Test
        @DisplayName("right-click cycles through the teammates and back to the nearest")
        void cycles() {
            Player hunter = player(HUNTER_A);

            team.cycle(hunter);
            assertThat(team.pickOf(HUNTER_A)).contains(Following.of(HUNTER_B));
            team.cycle(hunter);
            assertThat(team.pickOf(HUNTER_A)).contains(Following.NEAREST);
        }

        @Test
        @DisplayName("somebody from the other side cannot be picked")
        void notTheOtherSide() {
            team.pick(player(HUNTER_A), Following.of(RUNNER_A));

            assertThat(team.pickOf(HUNTER_A)).isEmpty();
        }

        @Test
        @DisplayName("with the team compass off, nobody is given one and nothing is aimed")
        void off() {
            ManhuntSettings off = ManhuntSettings.DEFAULTS.withTrackerTeamCompass(false);
            team.settings(off);
            Player hunter = player(HUNTER_A);

            team.fit(hunt, hunter);
            team.sweep(hunt);

            verify(hunter.getInventory(), never()).addItem(org.mockito.ArgumentMatchers.any(ItemStack.class));
            verify(hunter, never()).setLastDeathLocation(org.mockito.ArgumentMatchers.any());
        }
    }

    @Test
    @DisplayName("the needle is the recovery compass' own: the holder's last-death spot, set to the teammate")
    void needle() {
        Player hunter = player(HUNTER_A);
        Aim aim = Aim.tracking(HUNTER_B, new Point("hunt", 90.7, 64, 0.2), 80);

        team.aimNeedle(hunter, aim);

        verify(hunter).setLastDeathLocation(new Location(world, 90, 64, 0));
    }

    @Test
    @DisplayName("as a plain compass, the needle is kept in the item as a lodestone — nothing else is touched")
    void plainCompassUsesALodestone() {
        Player hunter = player(HUNTER_A);
        org.bukkit.inventory.meta.CompassMeta meta = mock(org.bukkit.inventory.meta.CompassMeta.class);
        Aim aim = Aim.tracking(HUNTER_B, new Point("hunt", 90.7, 64, 0.2), 80);

        team.aimNeedle(hunter, aim, meta);

        verify(meta).setLodestoneTracked(false);
        verify(meta).setLodestone(new Location(world, 90, 64, 0));
        verify(hunter, never()).setLastDeathLocation(org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("the item follows the setting")
    void itemFollowsTheSetting() {
        assertThat(TeamCompassService.materialFor(ManhuntSettings.TeamCompassItem.RECOVERY_COMPASS))
                .isEqualTo(org.bukkit.Material.RECOVERY_COMPASS);
        assertThat(TeamCompassService.materialFor(ManhuntSettings.TeamCompassItem.COMPASS))
                .isEqualTo(org.bukkit.Material.COMPASS);
    }

    @Test
    @DisplayName("switched off mid-hunt, it is taken back at once and its needle cleared")
    void switchedOffMidHunt() {
        Player hunter = player(HUNTER_A);
        ItemStack carried = mock(ItemStack.class);
        when(carried.getType()).thenReturn(org.bukkit.Material.RECOVERY_COMPASS);
        org.bukkit.persistence.PersistentDataContainer data = mock(org.bukkit.persistence.PersistentDataContainer.class);
        when(carried.getPersistentDataContainer()).thenReturn(data);
        when(data.get(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn("team-compass");
        ItemStack[] contents = new ItemStack[36];
        contents[5] = carried;
        when(hunter.getInventory().getContents()).thenReturn(contents);
        team.aimNeedle(hunter, Aim.tracking(HUNTER_B, new Point("hunt", 90, 64, 0), 80));
        team.settings(ManhuntSettings.DEFAULTS.withTrackerTeamCompass(false));

        team.fit(hunt, hunter);

        verify(hunter.getInventory()).setItem(5, null);
        verify(hunter).setLastDeathLocation(null);
    }
}
