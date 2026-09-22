package de.raindancer.modules.speedrun;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import org.bukkit.Bukkit;
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
 * Wiping every racer's advancements as a run starts — the whole book, not only the goal, so a repeat
 * racer plays the same game the first-timer beside them does.
 */
class SpeedrunAdvancementsTest {

    private static final UUID ALICE = UUID.nameUUIDFromBytes("alice".getBytes());

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
    @DisplayName("every criterion of every advancement a racer has earned is taken back")
    void clearsTheWholeBook() {
        Plugin plugin = mock(Plugin.class);
        Player alice = playerRunningItsOwnTasks();
        Advancement mined = mock(Advancement.class);
        Advancement dragon = mock(Advancement.class);
        AdvancementProgress minedProgress = mock(AdvancementProgress.class);
        AdvancementProgress dragonProgress = mock(AdvancementProgress.class);
        when(minedProgress.getAwardedCriteria()).thenReturn(Set.of("get_stone"));
        when(dragonProgress.getAwardedCriteria()).thenReturn(Set.of("killed_dragon"));
        when(alice.getAdvancementProgress(mined)).thenReturn(minedProgress);
        when(alice.getAdvancementProgress(dragon)).thenReturn(dragonProgress);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::advancementIterator).thenReturn(List.of(mined, dragon).iterator());
            bukkit.when(() -> Bukkit.getPlayer(ALICE)).thenReturn(alice);

            SpeedrunAdvancements.clearFor(plugin, List.of(ALICE));
        }

        verify(minedProgress).revokeCriteria("get_stone");
        verify(dragonProgress).revokeCriteria("killed_dragon");
    }

    @Test
    @DisplayName("an advancement nobody has earned is not written to at all")
    void untouchedAdvancementsAreLeftAlone() {
        Plugin plugin = mock(Plugin.class);
        Player alice = playerRunningItsOwnTasks();
        Advancement never = mock(Advancement.class);
        AdvancementProgress progress = mock(AdvancementProgress.class);
        when(progress.getAwardedCriteria()).thenReturn(Set.of());
        when(alice.getAdvancementProgress(never)).thenReturn(progress);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::advancementIterator).thenReturn(List.of(never).iterator());
            bukkit.when(() -> Bukkit.getPlayer(ALICE)).thenReturn(alice);

            SpeedrunAdvancements.clearFor(plugin, List.of(ALICE));
        }

        verify(progress, never()).revokeCriteria(any());
    }

    @Test
    @DisplayName("a participant who is offline is skipped, not guessed at")
    void offlineParticipantIsSkipped() {
        Plugin plugin = mock(Plugin.class);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(ALICE)).thenReturn(null);

            assertThatCode(() -> SpeedrunAdvancements.clearFor(plugin, List.of(ALICE)))
                    .doesNotThrowAnyException();
        }
    }
}
