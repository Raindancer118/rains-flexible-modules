package de.raindancer.modules.chat.service;

import de.raindancer.modules.chat.model.PrivateChat;
import de.raindancer.modules.chat.service.PrivateChatService.Outcome;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class PrivateChatServiceTest {

    private final PrivateChatService service = new PrivateChatService();

    private final UUID alice = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID carol = UUID.randomUUID();

    @Nested
    @DisplayName("going private")
    class GoingPrivate {

        @Test
        @DisplayName("somebody in no chat starts their own, owns it, and is talking in it")
        void startsOne() {
            assertThat(service.goPrivate(alice)).isEqualTo(Outcome.STARTED);

            assertThat(service.chatOf(alice)).get()
                    .satisfies(chat -> {
                        assertThat(chat.owner()).isEqualTo(alice);
                        assertThat(chat.members()).containsExactly(alice);
                    });
            assertThat(service.isTalkingPrivately(alice)).isTrue();
        }

        @Test
        @DisplayName("somebody already talking privately is told so, and nothing else changes")
        void alreadyPrivate() {
            service.goPrivate(alice);

            assertThat(service.goPrivate(alice)).isEqualTo(Outcome.ALREADY_PRIVATE);
            assertThat(service.chatOf(alice)).get().extracting(PrivateChat::owner).isEqualTo(alice);
        }

        @Test
        @DisplayName("a member who switched to public switches back into the same chat, not a new one")
        void backIntoTheSameChat() {
            service.goPrivate(alice);
            service.add(alice, bob);
            service.goPublic(bob);

            assertThat(service.goPrivate(bob)).isEqualTo(Outcome.SWITCHED);
            assertThat(service.isTalkingPrivately(bob)).isTrue();
            assertThat(service.chatOf(bob)).get().extracting(PrivateChat::owner).isEqualTo(alice);
        }

        @Test
        @DisplayName("somebody in no chat at all is not talking privately")
        void nobodyByDefault() {
            assertThat(service.isTalkingPrivately(alice)).isFalse();
            assertThat(service.chatOf(alice)).isEmpty();
        }
    }

    @Nested
    @DisplayName("adding somebody")
    class Adding {

        @Test
        @DisplayName("puts them in the chat and switches them to it straight away")
        void addedAndSwitched() {
            service.goPrivate(alice);

            assertThat(service.add(alice, bob)).isEqualTo(Outcome.ADDED);

            assertThat(service.isTalkingPrivately(bob)).isTrue();
            assertThat(service.chatOf(alice)).get().extracting(PrivateChat::members)
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.collection(UUID.class))
                    .containsExactlyInAnyOrder(alice, bob);
        }

        @Test
        @DisplayName("starts a chat for somebody who had none yet, rather than refusing")
        void startsOneImplicitly() {
            assertThat(service.add(alice, bob)).isEqualTo(Outcome.ADDED);

            assertThat(service.chatOf(bob)).get().extracting(PrivateChat::owner).isEqualTo(alice);
            assertThat(service.isTalkingPrivately(alice)).isTrue();
        }

        @Test
        @DisplayName("only the owner may add — a member asking is refused")
        void onlyTheOwner() {
            service.add(alice, bob);

            assertThat(service.add(bob, carol)).isEqualTo(Outcome.NOT_THE_OWNER);
            assertThat(service.chatOf(carol)).isEmpty();
        }

        @Test
        @DisplayName("somebody already in another private chat is not pulled out of it")
        void notOutOfAnotherChat() {
            service.add(alice, bob);
            service.goPrivate(carol);

            assertThat(service.add(carol, bob)).isEqualTo(Outcome.IN_ANOTHER_CHAT);
            assertThat(service.chatOf(bob)).get().extracting(PrivateChat::owner).isEqualTo(alice);
        }

        @Test
        @DisplayName("adding a member twice changes nothing")
        void twice() {
            service.add(alice, bob);

            assertThat(service.add(alice, bob)).isEqualTo(Outcome.ALREADY_A_MEMBER);
        }

        @Test
        @DisplayName("adding yourself is refused")
        void yourself() {
            assertThat(service.add(alice, alice)).isEqualTo(Outcome.NOT_YOURSELF);
            assertThat(service.chatOf(alice)).isEmpty();
        }

        @Test
        @DisplayName("somebody who walked out may not be dragged back in by the same chat")
        void walkedOutStaysOut() {
            service.add(alice, bob);
            service.leave(bob);

            assertThat(service.add(alice, bob)).isEqualTo(Outcome.LEFT_THIS_CHAT);
            assertThat(service.chatOf(bob)).isEmpty();
        }

        @Test
        @DisplayName("somebody the owner removed may be added again — that was the owner's call, not theirs")
        void removedMayComeBack() {
            service.add(alice, bob);
            service.remove(alice, bob);

            assertThat(service.add(alice, bob)).isEqualTo(Outcome.ADDED);
        }

        @Test
        @DisplayName("somebody who only disconnected may be added again")
        void disconnectedMayComeBack() {
            service.add(alice, bob);
            service.disconnect(bob);

            assertThat(service.add(alice, bob)).isEqualTo(Outcome.ADDED);
        }
    }

    @Nested
    @DisplayName("switching to public")
    class GoingPublic {

        @Test
        @DisplayName("keeps them in the chat — they still read it — but their lines go public")
        void staysAMember() {
            service.add(alice, bob);

            assertThat(service.goPublic(bob)).isTrue();

            assertThat(service.isTalkingPrivately(bob)).isFalse();
            assertThat(service.chatOf(bob)).isPresent();
            assertThat(service.readersOf(alice)).contains(bob);
        }

        @Test
        @DisplayName("somebody already talking publicly changes nothing")
        void alreadyPublic() {
            assertThat(service.goPublic(alice)).isFalse();
        }
    }

    @Nested
    @DisplayName("who reads a line")
    class Readers {

        @Test
        @DisplayName("every member, the speaker included")
        void everyMember() {
            service.add(alice, bob);
            service.add(alice, carol);

            assertThat(service.readersOf(bob)).containsExactlyInAnyOrder(alice, bob, carol);
        }

        @Test
        @DisplayName("nobody, for somebody in no chat")
        void nobody() {
            assertThat(service.readersOf(alice)).isEmpty();
        }
    }

    @Nested
    @DisplayName("leaving, removing and ending")
    class Ending {

        @Test
        @DisplayName("a member leaving takes only them out")
        void memberLeaves() {
            service.add(alice, bob);
            service.add(alice, carol);

            assertThat(service.leave(bob)).isEqualTo(Outcome.LEFT);

            assertThat(service.chatOf(bob)).isEmpty();
            assertThat(service.readersOf(alice)).containsExactlyInAnyOrder(alice, carol);
        }

        @Test
        @DisplayName("the owner leaving ends the chat for everybody")
        void ownerLeavingEnds() {
            service.add(alice, bob);

            assertThat(service.leave(alice)).isEqualTo(Outcome.ENDED);

            assertThat(service.chatOf(bob)).isEmpty();
            assertThat(service.isTalkingPrivately(bob)).isFalse();
        }

        @Test
        @DisplayName("ending hands back everybody who was in it, and puts all of them back in public chat")
        void endReturnsTheMembers() {
            service.add(alice, bob);
            service.add(alice, carol);

            Optional<PrivateChat> ended = service.end(alice);

            assertThat(ended).get().extracting(PrivateChat::members)
                    .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.collection(UUID.class))
                    .containsExactlyInAnyOrder(alice, bob, carol);
            assertThat(service.isTalkingPrivately(alice)).isFalse();
            assertThat(service.isTalkingPrivately(bob)).isFalse();
            assertThat(service.isTalkingPrivately(carol)).isFalse();
        }

        @Test
        @DisplayName("a member cannot end somebody else's chat")
        void memberCannotEnd() {
            service.add(alice, bob);

            assertThat(service.end(bob)).isEmpty();
            assertThat(service.chatOf(alice)).isPresent();
        }

        @Test
        @DisplayName("a walked-out list does not outlive the chat — a fresh chat may add them again")
        void freshChatFreshList() {
            service.add(alice, bob);
            service.leave(bob);
            service.end(alice);

            assertThat(service.add(alice, bob)).isEqualTo(Outcome.ADDED);
        }

        @Test
        @DisplayName("the owner may remove a member, but not themselves and not a stranger")
        void removing() {
            service.add(alice, bob);

            assertThat(service.remove(alice, carol)).isEqualTo(Outcome.NOT_A_MEMBER);
            assertThat(service.remove(alice, alice)).isEqualTo(Outcome.NOT_YOURSELF);
            assertThat(service.remove(bob, alice)).isEqualTo(Outcome.NOT_THE_OWNER);
            assertThat(service.remove(alice, bob)).isEqualTo(Outcome.REMOVED);
            assertThat(service.chatOf(bob)).isEmpty();
        }

        @Test
        @DisplayName("leaving when in no chat is refused")
        void leaveNothing() {
            assertThat(service.leave(alice)).isEqualTo(Outcome.NOT_IN_A_CHAT);
        }

        @Test
        @DisplayName("the owner disconnecting ends the chat and says so; a member disconnecting does not")
        void disconnecting() {
            service.add(alice, bob);
            service.add(alice, carol);

            assertThat(service.disconnect(bob)).isEqualTo(Outcome.LEFT);
            assertThat(service.chatOf(alice)).isPresent();

            assertThat(service.disconnect(alice)).isEqualTo(Outcome.ENDED);
            assertThat(service.chatOf(carol)).isEmpty();
        }

        @Test
        @DisplayName("disconnecting while in no chat leaves nothing behind")
        void disconnectingFromNothing() {
            assertThat(service.disconnect(alice)).isEqualTo(Outcome.NOT_IN_A_CHAT);
        }
    }
}
