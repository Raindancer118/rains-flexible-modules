package de.raindancer.modules.chat.store;

import de.raindancer.core.data.store.Documents;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** What was said about somebody while they were away waits for them on whichever server they join. */
@Tag("database")
class MentionInboxNetworkTest {

    @TempDir
    Path plugins;

    private Servers servers;
    private final UUID tom = UUID.randomUUID();

    private MentionInbox on(String id) {
        if (servers == null) {
            servers = new Servers(plugins);
        }
        MentionInbox inbox = new MentionInbox(servers.network(servers.server(id), "mentions.yml"));
        inbox.load();
        inbox.watch();
        return inbox;
    }

    @AfterEach
    void close() {
        if (servers != null) {
            servers.close();
        }
    }

    @Test
    @DisplayName("a mention left on one server is waiting on the other")
    void mentionTravels() {
        MentionInbox smp = on("smp");
        MentionInbox arena = on("arena");

        smp.add(tom, "Bea", "hey @Tom");
        smp.save();
        servers.heard();

        assertThat(arena.waitingFor(tom)).extracting(MentionInbox.Note::text).containsExactly("hey @Tom");
    }

    @Test
    @DisplayName("mentions left for one player on two servers at once are both kept")
    void bothMentionsKept() {
        MentionInbox smp = on("smp");
        MentionInbox arena = on("arena");

        smp.add(tom, "Bea", "from the smp");
        arena.add(tom, "Max", "from the arena");
        smp.save();
        arena.save();
        servers.heard();

        assertThat(smp.waitingFor(tom)).extracting(MentionInbox.Note::text)
                .containsExactlyInAnyOrder("from the smp", "from the arena");
        assertThat(arena.waitingFor(tom)).hasSize(2);
    }

    @Test
    @DisplayName("taking the mentions on one server empties them on the other, so they are told once")
    void takenIsGoneEverywhere() {
        MentionInbox smp = on("smp");
        MentionInbox arena = on("arena");
        smp.add(tom, "Bea", "hey");
        smp.save();
        servers.heard();

        assertThat(arena.take(tom)).hasSize(1);
        arena.save();
        servers.heard();

        assertThat(smp.waitingFor(tom)).isEmpty();
    }

    @Test
    @DisplayName("a mention not yet written survives another server's write")
    void unwrittenMentionSurvives() {
        MentionInbox smp = on("smp");
        MentionInbox arena = on("arena");

        smp.add(tom, "Bea", "not written yet");
        arena.add(UUID.randomUUID(), "Max", "for somebody else");
        arena.save();
        servers.heard();

        assertThat(smp.waitingFor(tom)).extracting(MentionInbox.Note::text).containsExactly("not written yet");
        smp.save();
        servers.heard();
        assertThat(arena.waitingFor(tom)).hasSize(1);
    }

    @Test
    @DisplayName("1.x's list of mentions per player is read, and no more than ten are kept")
    void legacyListIsRead() {
        servers = new Servers(plugins);
        Documents documents = servers.server("smp");
        servers.network(documents, "mentions.yml").write(yaml ->
                yaml.set("players." + tom, List.of(Map.of("from", "Bea", "text", "old one", "at", 5L))));

        MentionInbox inbox = new MentionInbox(servers.network(documents, "mentions.yml"));
        inbox.load();

        assertThat(inbox.waitingFor(tom)).extracting(MentionInbox.Note::text).containsExactly("old one");
        inbox.add(tom, "Max", "new one");
        assertThat(inbox.save()).isTrue();
        MentionInbox again = new MentionInbox(servers.network(documents, "mentions.yml"));
        again.load();
        assertThat(again.waitingFor(tom)).extracting(MentionInbox.Note::text).containsExactly("old one", "new one");
    }

    @Test
    @DisplayName("one player keeps their ten newest mentions")
    void tenNewest() {
        MentionInbox smp = on("smp");
        for (int i = 0; i < 14; i++) {
            smp.add(tom, "Bea", "m" + i);
        }
        smp.save();

        MentionInbox arena = on("arena");

        assertThat(arena.waitingFor(tom)).hasSize(10);
        assertThat(arena.waitingFor(tom).getLast().text()).isEqualTo("m13");
    }

}
