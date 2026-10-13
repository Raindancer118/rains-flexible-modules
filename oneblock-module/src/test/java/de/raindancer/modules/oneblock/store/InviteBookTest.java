package de.raindancer.modules.oneblock.store;

import de.raindancer.modules.oneblock.model.Invite;
import de.raindancer.modules.oneblock.model.InviteKind;
import de.raindancer.modules.oneblock.model.Requester;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class InviteBookTest {

    private final Requester ada = new Requester(UUID.randomUUID(), "Ada");
    private final Requester cy = new Requester(UUID.randomUUID(), "Cy");
    private final Requester bo = new Requester(UUID.randomUUID(), "Bo");
    private final InviteBook book = new InviteBook();

    private Invite invite(Requester from, InviteKind kind, long until) {
        return new Invite(kind, from, bo, "island-of-" + from.name(), until);
    }

    @Test
    @DisplayName("the one invite waiting needs no name; with several, the inviter's name picks one")
    void pick() {
        book.add(invite(ada, InviteKind.TEAM, 100));
        assertThat(book.pick(bo.id(), null).invite()).hasValueSatisfying(found -> assertThat(found.from()).isEqualTo(ada));

        book.add(invite(cy, InviteKind.GUEST, 100));
        assertThat(book.pick(bo.id(), null).several()).isTrue();
        assertThat(book.pick(bo.id(), "cy").invite()).hasValueSatisfying(found -> assertThat(found.kind()).isEqualTo(InviteKind.GUEST));
        assertThat(book.pick(bo.id(), "Nobody").invite()).isEmpty();
        assertThat(book.pick(ada.id(), null).invite()).isEmpty();
    }

    @Test
    @DisplayName("a new invite from the same person replaces their old one instead of piling up")
    void replaces() {
        book.add(invite(ada, InviteKind.TEAM, 100));
        book.add(invite(ada, InviteKind.GUEST, 200));
        assertThat(book.waitingFor(bo.id())).singleElement().satisfies(only -> assertThat(only.kind()).isEqualTo(InviteKind.GUEST));
    }

    @Test
    @DisplayName("ran-out invites are taken out once and handed back, so their inviter can be told")
    void expiry() {
        book.add(invite(ada, InviteKind.TEAM, 100));
        book.add(invite(cy, InviteKind.TEAM, 300));
        assertThat(book.expire(200)).extracting(Invite::from).containsExactly(ada);
        assertThat(book.expire(200)).isEmpty();
        assertThat(book.waitingFor(bo.id())).extracting(Invite::from).containsExactly(cy);
    }

    @Test
    @DisplayName("taking an invite removes exactly that one")
    void take() {
        Invite first = invite(ada, InviteKind.TEAM, 100);
        book.add(first);
        assertThat(book.take(first)).isTrue();
        assertThat(book.take(first)).isFalse();
        assertThat(book.waitingFor(bo.id())).isEmpty();
    }
}
