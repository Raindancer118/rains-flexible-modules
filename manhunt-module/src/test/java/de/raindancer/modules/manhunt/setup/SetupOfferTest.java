package de.raindancer.modules.manhunt.setup;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.manhunt.stats.HistoryStore;
import de.raindancer.modules.manhunt.stats.HuntLog;
import de.raindancer.modules.manhunt.stats.HuntRecord;
import de.raindancer.modules.manhunt.stats.StatsStore;
import de.raindancer.modules.manhunt.util.PermissionNodes;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("the setup offer, on a fresh server")
class SetupOfferTest {

    @TempDir
    Path directory;

    private final Messages messages = mock(Messages.class);

    private SetupOffer offer(SetupState state, HistoryStore history) {
        return new SetupOffer(state, new StatsStore(directory.resolve("stats.yml")), history, messages);
    }

    private static Player admin(boolean admin) {
        Player player = mock(Player.class);
        when(player.hasPermission(PermissionNodes.ADMIN)).thenReturn(admin);
        return player;
    }

    @SuppressWarnings("deprecation")
    private static PlayerJoinEvent join(Player player) {
        return new PlayerJoinEvent(player, (Component) null);
    }

    @Test
    @DisplayName("an admin on a server that never hunted is offered the wizard; a player is not")
    void offered() {
        SetupOffer offer = offer(new SetupState(directory.resolve("setup.yml")),
                new HistoryStore(directory.resolve("hunts.yml")));
        Player admin = admin(true);
        Player player = admin(false);

        offer.onJoin(join(admin));
        offer.onJoin(join(player));

        verify(messages).send(eq(admin), eq("manhunt.setup.offer"), any(Object[].class));
        verify(messages, never()).send(eq(player), eq("manhunt.setup.offer"), any(Object[].class));
    }

    @Test
    @DisplayName("once set up — or skipped — never again, and that survives a restart")
    void onceOnly() {
        new SetupState(directory.resolve("setup.yml")).markDone();
        SetupOffer offer = offer(new SetupState(directory.resolve("setup.yml")),
                new HistoryStore(directory.resolve("hunts.yml")));

        offer.onJoin(join(admin(true)));

        verify(messages, never()).send(any(), eq("manhunt.setup.offer"), any(Object[].class));
    }

    @Test
    @DisplayName("a server with hunts on record is set up already, wizard or not")
    void huntsOnRecord() {
        HistoryStore history = new HistoryStore(directory.resolve("hunts.yml"));
        history.add(new HuntLog(() -> 0L, Map.of(UUID.randomUUID(), "R"), Map.of(UUID.randomUUID(), "H"))
                .finish(1, "x", HuntRecord.Winner.NOBODY), 5);

        assertThat(offer(new SetupState(directory.resolve("setup.yml")), history).fresh()).isFalse();
    }

    @Test
    @DisplayName("every goal one click away is a vanilla advancement key, each once")
    void goals() {
        assertThat(Goals.all()).extracting(Goals.Goal::key).doesNotHaveDuplicates()
                .allMatch(key -> key.matches("minecraft:[a-z_]+/[a-z_]+"));
        assertThat(Goals.byKey("minecraft:end/kill_dragon")).isPresent();
        assertThat(Goals.byKey("minecraft:nope")).isEmpty();
    }
}
