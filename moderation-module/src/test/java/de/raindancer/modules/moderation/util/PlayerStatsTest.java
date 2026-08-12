package de.raindancer.modules.moderation.util;

import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PlayerStatsTest {

    private static OfflinePlayer playerWhoHasPlayed() {
        OfflinePlayer player = mock(OfflinePlayer.class);
        when(player.hasPlayedBefore()).thenReturn(true);
        when(player.getStatistic(Statistic.PLAY_ONE_MINUTE)).thenReturn(20 * 3600); // one hour, in ticks
        when(player.getFirstPlayed())
                .thenReturn(Instant.now().minus(Duration.ofDays(10)).toEpochMilli());
        when(player.getLastPlayed())
                .thenReturn(Instant.now().minus(Duration.ofDays(1)).toEpochMilli());
        when(player.getStatistic(Statistic.DEATHS)).thenReturn(3);
        when(player.getStatistic(Statistic.MOB_KILLS)).thenReturn(40);
        when(player.getStatistic(Statistic.PLAYER_KILLS)).thenReturn(1);
        return player;
    }

    @Nested
    @DisplayName("somebody who has never joined")
    class NeverJoined {

        @Test
        @DisplayName("says so, and nothing else")
        void saysSoOnly() {
            OfflinePlayer player = mock(OfflinePlayer.class);
            when(player.hasPlayedBefore()).thenReturn(false);

            assertThat(PlayerStats.summarize(player)).hasSize(1);
        }

        @Test
        @DisplayName("a null player is treated the same way rather than thrown")
        void nullIsTreatedAsNeverJoined() {
            assertThat(PlayerStats.summarize(null)).hasSize(1);
        }
    }

    @Nested
    @DisplayName("somebody who has played before")
    class HasPlayed {

        @Test
        @DisplayName("the playtime is read from the vanilla statistic, converted from ticks")
        void readsPlaytime() {
            OfflinePlayer player = playerWhoHasPlayed();
            when(player.isOnline()).thenReturn(true);

            assertThat(PlayerStats.summarize(player))
                    .anySatisfy(line -> assertThat(line).contains("1 hour"));
        }

        @Test
        @DisplayName("says how long ago they first joined")
        void readsFirstJoined() {
            OfflinePlayer player = playerWhoHasPlayed();
            when(player.isOnline()).thenReturn(true);

            assertThat(PlayerStats.summarize(player))
                    .anySatisfy(line -> assertThat(line).contains("10 days"));
        }

        @Test
        @DisplayName("somebody online now is not told when they were last seen — they are here")
        void onlineHidesLastSeen() {
            OfflinePlayer player = playerWhoHasPlayed();
            when(player.isOnline()).thenReturn(true);

            assertThat(PlayerStats.summarize(player)).noneSatisfy(
                    line -> assertThat(line).contains("Last seen"));
        }

        @Test
        @DisplayName("somebody offline is told how long ago they were last seen")
        void offlineShowsLastSeen() {
            OfflinePlayer player = playerWhoHasPlayed();
            when(player.isOnline()).thenReturn(false);

            assertThat(PlayerStats.summarize(player))
                    .anySatisfy(line -> assertThat(line).contains("Last seen").contains("1 day"));
        }

        @Test
        @DisplayName("carries deaths, mob kills and player kills")
        void readsCombatStatistics() {
            OfflinePlayer player = playerWhoHasPlayed();
            when(player.isOnline()).thenReturn(true);

            List<String> lore = PlayerStats.summarize(player);
            assertThat(lore).anySatisfy(line -> assertThat(line)
                    .contains("3 death").contains("40 mob kill").contains("1 player kill"));
        }
    }
}
