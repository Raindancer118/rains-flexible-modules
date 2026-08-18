package de.raindancer.modules.speedrun.conditions;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.advancement.Advancement;
import org.bukkit.advancement.AdvancementProgress;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Clearing the goal off every racer as a run arms itself — without it, anybody who has ever earned
 * that advancement before is never granted it again, and the condition waiting for the grant waits
 * forever.
 */
class GoalAdvancementTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());
    private static final NamespacedKey KEY = NamespacedKey.minecraft("end/kill_dragon");

    private static Player playerRunningItsOwnTasks() {
        Player player = mock(Player.class);
        EntityScheduler scheduler = mock(EntityScheduler.class);
        when(scheduler.run(any(), any(), any())).thenAnswer(invocation -> {
            invocation.getArgument(1, Consumer.class).accept(null);
            return null;
        });
        when(player.getScheduler()).thenReturn(scheduler);
        return player;
    }

    @Test
    @DisplayName("revokes every criterion a participant already has for the goal")
    void revokesWhatTheRacerAlreadyEarned() {
        Plugin plugin = mock(Plugin.class);
        Advancement advancement = mock(Advancement.class);
        Player alice = playerRunningItsOwnTasks();
        AdvancementProgress progress = mock(AdvancementProgress.class);
        when(progress.getAwardedCriteria()).thenReturn(Set.of("killed_dragon"));
        when(alice.getAdvancementProgress(advancement)).thenReturn(progress);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getAdvancement(KEY)).thenReturn(advancement);
            bukkit.when(() -> Bukkit.getPlayer(ALICE)).thenReturn(alice);

            GoalAdvancement.revokeFor(plugin, KEY, List.of(ALICE));
        }

        verify(progress).revokeCriteria("killed_dragon");
    }

    @Test
    @DisplayName("does nothing for a goal this server does not have")
    void unknownGoalIsLeftAlone() {
        Plugin plugin = mock(Plugin.class);
        Player alice = playerRunningItsOwnTasks();

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getAdvancement(KEY)).thenReturn(null);
            bukkit.when(() -> Bukkit.getPlayer(ALICE)).thenReturn(alice);

            GoalAdvancement.revokeFor(plugin, KEY, List.of(ALICE));
        }

        verify(alice, never()).getAdvancementProgress(any());
    }

    @Test
    @DisplayName("skips a participant who is not online")
    void offlineParticipantIsSkipped() {
        Plugin plugin = mock(Plugin.class);
        Advancement advancement = mock(Advancement.class);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getAdvancement(KEY)).thenReturn(advancement);
            bukkit.when(() -> Bukkit.getPlayer(ALICE)).thenReturn(null);

            assertThatCode(() -> GoalAdvancement.revokeFor(plugin, KEY, List.of(ALICE)))
                    .doesNotThrowAnyException();
        }
    }
}
