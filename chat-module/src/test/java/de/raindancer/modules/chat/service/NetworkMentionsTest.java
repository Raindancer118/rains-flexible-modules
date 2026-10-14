package de.raindancer.modules.chat.service;

import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.moderation.vanish.VanishSink;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.core.platform.network.NetworkCalls;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.chat.ChatSettings;
import de.raindancer.modules.chat.model.Mention;
import de.raindancer.modules.chat.store.MentionInbox;
import net.kyori.adventure.sound.Sound;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A mention of somebody on another server pings them there; a note is left only when they are offline everywhere. */
class NetworkMentionsTest {

    @TempDir
    Path directory;

    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final UUID alexId = UUID.nameUUIDFromBytes("Alex".getBytes());

    /** Where the network last saw Alex; empty when he is offline or hidden everywhere. */
    private final AtomicReference<Optional<String>> alexIsOn = new AtomicReference<>(Optional.of("arena"));

    private final Server smpServer = mock(Server.class);
    private final Server arenaServer = mock(Server.class);
    private final Messages arenaMessages = mock(Messages.class);
    private final Player alex = mock(Player.class);
    private final OfflinePlayer alexAway = mock(OfflinePlayer.class);
    private final Player tom = mock(Player.class);
    private MentionInbox inbox;
    private MentionService smp;
    private MentionService arena;

    @BeforeEach
    void servers() {
        when(tom.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes("Tom".getBytes()));
        when(tom.getName()).thenReturn("Tom");
        when(alex.getUniqueId()).thenReturn(alexId);
        when(alexAway.getUniqueId()).thenReturn(alexId);
        when(alexAway.getName()).thenReturn("Alex");
        when(smpServer.getOfflinePlayerIfCached("Alex")).thenReturn(alexAway);
        when(smpServer.getOfflinePlayer(alexId)).thenReturn(alexAway);
        when(arenaServer.getPlayer(alexId)).thenReturn(alex);
        PlayerTargets.useNicknames(null);

        inbox = new MentionInbox(directory);
        smp = new MentionService(smpServer, new Vanish(mock(VanishSink.class)), mock(Messages.class),
                ChatSettings.DEFAULTS, inbox);
        arena = new MentionService(arenaServer, new Vanish(mock(VanishSink.class)), arenaMessages,
                ChatSettings.DEFAULTS, null);

        NetworkCalls smpCalls = new NetworkCalls(hub.bus("smp"));
        NetworkMentions smpNet = new NetworkMentions(() -> Optional.of(smpCalls), who -> alexIsOn.get(), smp::pingHere);
        smp.network(smpNet);
        smpNet.listen();
        NetworkCalls arenaCalls = new NetworkCalls(hub.bus("arena"));
        NetworkMentions arenaNet = new NetworkMentions(() -> Optional.of(arenaCalls), who -> Optional.empty(),
                arena::pingHere);
        arena.network(arenaNet);
        arenaNet.listen();
    }

    @AfterEach
    void tearDown() {
        PlayerTargets.useNicknames(null);
    }

    private void settle() {
        for (int round = 0; round < 4; round++) {
            hub.drain();
        }
    }

    @Test
    @DisplayName("somebody online on another server is a reachable mention, and is pinged there")
    void pingedThere() {
        List<Mention> found = smp.find(tom, "hey @Alex");
        assertThat(found).singleElement().satisfies(mention -> assertThat(mention.reachable()).isTrue());

        smp.notify(tom, "hey @Alex", found);
        settle();

        verify(alex).playSound(any(Sound.class));
        verify(arenaMessages).send(alex, "chat.mention.pinged", "player", "Tom", "text", "hey @Alex");
        assertThat(inbox.waitingFor(alexId)).isEmpty();
    }

    @Test
    @DisplayName("somebody offline or hidden everywhere is not reachable and gets a note")
    void offlineEverywhere() {
        alexIsOn.set(Optional.empty());
        List<Mention> found = smp.find(tom, "hey @Alex");
        assertThat(found).singleElement().satisfies(mention -> assertThat(mention.reachable()).isFalse());

        smp.notify(tom, "hey @Alex", found);
        settle();

        assertThat(inbox.waitingFor(alexId)).singleElement()
                .satisfies(note -> assertThat(note.text()).isEqualTo("hey @Alex"));
        verify(alex, never()).playSound(any(Sound.class));
    }

    @Test
    @DisplayName("somebody who left in the meantime is not lost: the note is left after all")
    void leftInTheMeantime() {
        when(arenaServer.getPlayer(alexId)).thenReturn(null);

        smp.notify(tom, "hey @Alex", smp.find(tom, "hey @Alex"));
        settle();

        assertThat(inbox.waitingFor(alexId)).hasSize(1);
    }

    @Test
    @DisplayName("a server nobody answers for is a note too, not a lost mention")
    void nobodyAnswers() throws InterruptedException {
        alexIsOn.set(Optional.of("ghost-server"));

        smp.notify(tom, "hey @Alex", smp.find(tom, "hey @Alex"));
        for (int waited = 0; waited < 100 && inbox.waitingFor(alexId).isEmpty(); waited++) {
            Thread.sleep(100);
        }

        assertThat(inbox.waitingFor(alexId)).hasSize(1);
    }

    @Test
    @DisplayName("a mention is pinged once however often the name stands in the line")
    void onceOnly() {
        smp.notify(tom, "@Alex @Alex", smp.find(tom, "@Alex @Alex"));
        settle();

        verify(alex).playSound(any(Sound.class));
    }

    @Test
    @DisplayName("off a network nothing is asked: an offline name is a note, as before")
    void aloneIsAsBefore() {
        MentionService alone = new MentionService(smpServer, new Vanish(mock(VanishSink.class)),
                mock(Messages.class), ChatSettings.DEFAULTS, inbox);

        List<Mention> found = alone.find(tom, "hey @Alex");
        alone.notify(tom, "hey @Alex", found);

        assertThat(found).singleElement().satisfies(mention -> assertThat(mention.reachable()).isFalse());
        assertThat(inbox.waitingFor(alexId)).hasSize(1);
    }
}
