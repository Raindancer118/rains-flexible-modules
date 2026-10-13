package de.raindancer.modules.oneblock.service;

import de.raindancer.core.platform.link.LinkDirectory;
import de.raindancer.modules.oneblock.OneBlockSettings;
import de.raindancer.modules.oneblock.model.Invite;
import de.raindancer.modules.oneblock.model.InviteKind;
import de.raindancer.modules.oneblock.model.IslandFacts;
import de.raindancer.modules.oneblock.model.Reply;
import de.raindancer.modules.oneblock.model.Requester;
import de.raindancer.modules.oneblock.rules.InviteRule;
import de.raindancer.modules.oneblock.store.InviteBook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** The island server's side: deciding, remembering and telling, with BentoBox and the network faked. */
class InviteServiceTest {

    private final Requester ada = new Requester(UUID.randomUUID(), "Ada");
    private final UUID bo = UUID.randomUUID();
    private final AtomicLong clock = new AtomicLong(1_000);
    private final List<String> told = new ArrayList<>();
    private final FakeHost host = new FakeHost();
    private final InviteService service = new InviteService(host, new InviteBook(), new InviteRule(500),
            (name, key, values) -> told.add(name + " " + key + " " + List.of(values)),
            name -> CompletableFuture.completedFuture(name.equalsIgnoreCase("bo")
                    ? Optional.of(new LinkDirectory.Found(bo, "Bo", "Hub")) : Optional.empty()),
            Runnable::run, clock::get, OneBlockSettings.DEFAULTS);

    static final class FakeHost implements IslandHost {
        boolean ready = true;
        boolean onIslands = false;
        Optional<IslandFacts> facts = Optional.of(new IslandFacts("is-1", 1000, 1000, 1, 4, false, false));
        final List<String> did = new ArrayList<>();

        @Override
        public boolean ready() {
            return ready;
        }

        @Override
        public Optional<IslandFacts> facts(UUID inviter, UUID invitee) {
            return facts;
        }

        @Override
        public void offerTeam(Invite invite) {
            did.add("offer " + invite.to().name());
        }

        @Override
        public void withdrawTeam(Invite invite) {
            did.add("withdraw " + invite.to().name());
        }

        @Override
        public void join(Invite invite) {
            did.add("join " + invite.to().id());
        }

        @Override
        public void visit(UUID player, String islandId) {
            did.add("visit " + player + " " + islandId);
        }

        @Override
        public boolean inGameWorld(UUID player) {
            return onIslands;
        }

        @Override
        public Optional<de.raindancer.modules.oneblock.model.IslandView> view(UUID player) {
            return Optional.empty();
        }

        @Override
        public void home(UUID player) {
            did.add("home " + player);
        }
    }

    private Reply invite(InviteKind kind) throws Exception {
        return service.invite(ada, "bo", kind).get();
    }

    @Test
    @DisplayName("an invite to somebody on another server: BentoBox gets its own invite, they get buttons, Ada a yes")
    void inviteAcross() throws Exception {
        Reply reply = invite(InviteKind.TEAM);

        assertThat(reply.ok()).isTrue();
        assertThat(reply.key()).isEqualTo("oneblock.invite.sent-team");
        assertThat(reply.values()).containsExactly("player", "Bo", "minutes", "5");
        assertThat(host.did).containsExactly("offer Bo");
        assertThat(told).containsExactly("Bo oneblock.invite.received-team [player, Ada, minutes, 5]");
    }

    @Test
    @DisplayName("nobody of that name anywhere, or no island server ready: refused, and nothing remembered")
    void refusedEarly() throws Exception {
        assertThat(service.invite(ada, "Ghost", InviteKind.GUEST).get().key()).isEqualTo("oneblock.refuse.nobody");
        host.ready = false;
        assertThat(invite(InviteKind.GUEST).key()).isEqualTo("oneblock.refuse.asleep");
        host.facts = Optional.empty();
        host.ready = true;
        assertThat(invite(InviteKind.GUEST).key()).isEqualTo("oneblock.refuse.no-island");
        assertThat(told).isEmpty();
        assertThat(service.answer(new Requester(bo, "Bo"), null, true, false).get().key())
                .isEqualTo("oneblock.refuse.no-invite");
    }

    @Test
    @DisplayName("accepted from another server: sent over, and on arrival BentoBox's own accept runs once")
    void acceptAcross() throws Exception {
        invite(InviteKind.TEAM);
        told.clear();

        Reply reply = service.answer(new Requester(bo, "Bo"), null, true, false).get();

        assertThat(reply.transfer()).isTrue();
        assertThat(told).containsExactly("Ada oneblock.invite.accepted [player, Bo]");
        assertThat(service.joined(bo)).isTrue();
        assertThat(host.did).containsExactly("offer Bo", "join " + bo);
        assertThat(service.joined(bo)).as("once").isFalse();
    }

    @Test
    @DisplayName("an arrival long after the accept does nothing — they may have changed their mind meanwhile")
    void staleArrival() throws Exception {
        invite(InviteKind.TEAM);
        service.answer(new Requester(bo, "Bo"), null, true, false).get();
        clock.addAndGet(InviteService.ARRIVAL_MILLIS + 1);
        assertThat(service.joined(bo)).isFalse();
    }

    @Test
    @DisplayName("a guest accepting while already on the island server is taken there straight away")
    void guestHere() throws Exception {
        invite(InviteKind.GUEST);
        Reply reply = service.answer(new Requester(bo, "Bo"), "ada", true, true).get();

        assertThat(reply.transfer()).isFalse();
        assertThat(host.did).containsExactly("visit " + bo + " is-1");
    }

    @Test
    @DisplayName("declining withdraws BentoBox's invite too and tells the inviter")
    void decline() throws Exception {
        invite(InviteKind.TEAM);
        told.clear();
        Reply reply = service.answer(new Requester(bo, "Bo"), null, false, false).get();

        assertThat(reply.key()).isEqualTo("oneblock.invite.declined-by-you");
        assertThat(told).containsExactly("Ada oneblock.invite.declined [player, Bo]");
        assertThat(host.did).containsExactly("offer Bo", "withdraw Bo");
        assertThat(service.answer(new Requester(bo, "Bo"), null, true, false).get().key())
                .isEqualTo("oneblock.refuse.no-invite");
    }

    @Test
    @DisplayName("an invite nobody answered runs out: withdrawn, and the inviter hears so")
    void runsOut() throws Exception {
        invite(InviteKind.TEAM);
        told.clear();
        clock.addAndGet(5 * 60_000L + 1);
        service.sweep();

        assertThat(told).containsExactly("Ada oneblock.invite.ran-out [player, Bo]");
        assertThat(host.did).containsExactly("offer Bo", "withdraw Bo");
    }

    @Test
    @DisplayName("a join with nothing to finish: outside the islands it goes home (or makes one), on them it stays")
    void plainJoin() {
        service.joined(bo);
        assertThat(host.did).containsExactly("home " + bo);
        host.onIslands = true;
        service.joined(bo);
        assertThat(host.did).as("logged out on their island, back on it").containsExactly("home " + bo);
    }
}
