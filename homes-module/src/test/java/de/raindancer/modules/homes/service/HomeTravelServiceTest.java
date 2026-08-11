package de.raindancer.modules.homes.service;

import de.raindancer.core.ui.effect.Effects;
import de.raindancer.core.ui.effect.EffectSink;
import de.raindancer.core.ui.effect.ParticleCue;
import de.raindancer.core.ui.effect.SoundCue;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.poi.Poi;
import de.raindancer.core.world.teleport.Travel;
import de.raindancer.core.world.teleport.TravelWatcher;
import de.raindancer.modules.homes.HomeSettings;
import de.raindancer.modules.homes.model.Home;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sending somebody home — specifically, whether arriving actually sounds like arriving.
 *
 * <h2>Why this goes through {@code Travel} rather than calling the watcher directly</h2>
 * {@code Arriving} is a private inner class of {@link HomeTravelService} for a reason: nothing outside
 * this service should be able to fire "somebody has arrived" without a real teleport behind it. So this
 * captures the {@link TravelWatcher} the service hands to a mocked {@link Travel} and calls
 * {@code arrived} on it, the same way the real {@code Travel} would once the teleport had happened.
 */
class HomeTravelServiceTest {

    /** What actually reached a place, instead of a mocked server. */
    private record PlayedAt(String world, double x, double y, double z, SoundCue sound) {
    }

    private final List<PlayedAt> played = new ArrayList<>();

    private Effects effects() {
        return new Effects(new EffectSink() {
            @Override
            public void toPlayer(UUID player, SoundCue sound) {
            }

            @Override
            public void toPlayer(UUID player, ParticleCue particles) {
            }

            @Override
            public void atPlace(String world, double x, double y, double z, SoundCue sound) {
                played.add(new PlayedAt(world, x, y, z, sound));
            }

            @Override
            public void atPlace(String world, double x, double y, double z, ParticleCue particles) {
            }

            @Override
            public void stopForPlayer(UUID player, String soundKey) {
            }

            @Override
            public void stopAllForPlayer(UUID player) {
            }
        }, () -> 0L);
    }

    /** A home whose {@code poi().location()} resolves, without needing a real Bukkit world. */
    private Home homeAt(World world) {
        Location where = new Location(world, 1, 2, 3);
        Poi poi = mock(Poi.class);
        when(poi.location()).thenReturn(Optional.of(where));
        Home home = mock(Home.class);
        when(home.poi()).thenReturn(poi);
        when(home.name()).thenReturn("base");
        when(home.isIn(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        return home;
    }

    /** Fires {@code go}, captures the watcher {@code Travel} was handed, and returns it. */
    private TravelWatcher watcherFrom(HomeTravelService service, Travel travel, Player traveller,
                                       Home home) {
        service.go(traveller, home);
        ArgumentCaptor<TravelWatcher> captor = ArgumentCaptor.forClass(TravelWatcher.class);
        verify(travel).go(eq(traveller), any(Location.class), any(), captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("the sound on arrival")
    class SoundOnArrival {

        @Test
        @DisplayName("plays Core's own teleport cue, at the place somebody actually arrived")
        void playsOnArrival() {
            Travel travel = mock(Travel.class);
            Messages messages = mock(Messages.class);
            Effects effects = effects();
            HomeTravelService service = new HomeTravelService(travel, messages, effects,
                    HomeSettings.DEFAULTS.withPlaySound(true));

            Player traveller = mock(Player.class);
            when(traveller.getUniqueId()).thenReturn(UUID.randomUUID());
            World destWorld = mock(World.class);
            when(destWorld.getName()).thenReturn("world");
            when(traveller.getWorld()).thenReturn(destWorld);
            Home home = homeAt(destWorld);

            TravelWatcher watcher = watcherFrom(service, travel, traveller, home);

            World arrivalWorld = mock(World.class);
            when(arrivalWorld.getName()).thenReturn("TTV");
            Location arrival = new Location(arrivalWorld, 10, 20, 30);
            watcher.arrived(traveller, arrival, de.raindancer.core.world.teleport.Trip.to("home"));

            assertThat(played).singleElement().satisfies(heard -> {
                assertThat(heard.world()).isEqualTo("TTV");
                assertThat(heard.x()).isEqualTo(10);
                assertThat(heard.y()).isEqualTo(20);
                assertThat(heard.z()).isEqualTo(30);
                assertThat(heard.sound().key())
                        .as("the same enderman-teleport sound Cues.TELEPORT is bound to for every "
                                + "other module — a home does not get its own sound")
                        .isEqualTo("entity.enderman.teleport");
            });
        }

        @Test
        @DisplayName("says nothing when the setting is off")
        void silentWhenSwitchedOff() {
            Travel travel = mock(Travel.class);
            Messages messages = mock(Messages.class);
            Effects effects = effects();
            HomeTravelService service = new HomeTravelService(travel, messages, effects,
                    HomeSettings.DEFAULTS.withPlaySound(false));

            Player traveller = mock(Player.class);
            when(traveller.getUniqueId()).thenReturn(UUID.randomUUID());
            World destWorld = mock(World.class);
            when(destWorld.getName()).thenReturn("world");
            when(traveller.getWorld()).thenReturn(destWorld);
            Home home = homeAt(destWorld);

            TravelWatcher watcher = watcherFrom(service, travel, traveller, home);

            World arrivalWorld = mock(World.class);
            when(arrivalWorld.getName()).thenReturn("TTV");
            watcher.arrived(traveller, new Location(arrivalWorld, 10, 20, 30),
                    de.raindancer.core.world.teleport.Trip.to("home"));

            assertThat(played).as("play-sound is off, so nothing should have reached the sink").isEmpty();
        }

        @Test
        @DisplayName("a settings reload actually reaches whether arriving is heard")
        void settingsReloadIsHonoured() {
            Travel travel = mock(Travel.class);
            Messages messages = mock(Messages.class);
            Effects effects = effects();
            HomeTravelService service = new HomeTravelService(travel, messages, effects,
                    HomeSettings.DEFAULTS.withPlaySound(true));
            service.settings(HomeSettings.DEFAULTS.withPlaySound(false));

            Player traveller = mock(Player.class);
            when(traveller.getUniqueId()).thenReturn(UUID.randomUUID());
            World destWorld = mock(World.class);
            when(destWorld.getName()).thenReturn("world");
            when(traveller.getWorld()).thenReturn(destWorld);
            Home home = homeAt(destWorld);

            TravelWatcher watcher = watcherFrom(service, travel, traveller, home);
            World arrivalWorld = mock(World.class);
            when(arrivalWorld.getName()).thenReturn("TTV");
            watcher.arrived(traveller, new Location(arrivalWorld, 1, 1, 1),
                    de.raindancer.core.world.teleport.Trip.to("home"));

            assertThat(played).isEmpty();
        }
    }

    /**
     * The bug this exists to catch: {@code homes.bypass.warmup} used to default to
     * {@code PermissionDefault.OP}, so every operator bypassed the wait no matter what
     * {@code operators-bypass} said — the setting whose own javadoc promises the opposite by
     * default. Fixed by giving the permission itself a plain {@code FALSE} default, the same as
     * {@code tpa-module}'s identical setting already had, so the setting is the only thing deciding.
     */
    @Nested
    @DisplayName("an operator's own wait")
    class OperatorsBypassingTheWait {

        private int warmupOf(HomeSettings settings, Player traveller) {
            Travel travel = mock(Travel.class);
            HomeTravelService service = new HomeTravelService(travel, mock(Messages.class), effects(),
                    settings);
            World world = mock(World.class);
            when(world.getName()).thenReturn("world");
            when(traveller.getWorld()).thenReturn(world);
            when(traveller.getUniqueId()).thenReturn(UUID.randomUUID());
            Home home = homeAt(world);

            service.go(traveller, home);
            ArgumentCaptor<de.raindancer.core.world.teleport.Trip> trip =
                    ArgumentCaptor.forClass(de.raindancer.core.world.teleport.Trip.class);
            verify(travel).go(eq(traveller), any(Location.class), trip.capture(), any());
            return trip.getValue().warmupSeconds();
        }

        @Test
        @DisplayName("an operator with nothing granted waits like anybody else, by default")
        void anOperatorWaitsByDefault() {
            Player op = mock(Player.class);
            when(op.isOp()).thenReturn(true);

            assertThat(warmupOf(HomeSettings.DEFAULTS, op)).isEqualTo(HomeSettings.DEFAULTS.warmup());
        }

        @Test
        @DisplayName("turning the setting on is what actually lets an operator skip it")
        void theSettingIsWhatBypassesIt() {
            Player op = mock(Player.class);
            when(op.isOp()).thenReturn(true);

            assertThat(warmupOf(HomeSettings.DEFAULTS.withOperatorsBypass(true), op)).isZero();
        }

        @Test
        @DisplayName("a plain player explicitly granted the node bypasses it regardless of the setting")
        void anExplicitGrantAlwaysWorks() {
            Player granted = mock(Player.class);
            when(granted.hasPermission(
                    de.raindancer.modules.homes.util.PermissionNodes.BYPASS_WARMUP)).thenReturn(true);

            assertThat(warmupOf(HomeSettings.DEFAULTS, granted)).isZero();
        }

        @Test
        @DisplayName("a plain player with neither waits the ordinary amount")
        void anOrdinaryPlayerWaits() {
            Player nobody = mock(Player.class);

            assertThat(warmupOf(HomeSettings.DEFAULTS, nobody)).isEqualTo(HomeSettings.DEFAULTS.warmup());
        }
    }
}
