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

    private long now = 1_000_000L;
    private final PrivateChatService service = new PrivateChatService(() -> now);

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
    @DisplayName("inviting somebody")
    class Inviting {

        @Test
        @DisplayName("an invitation does not put them in — only accepting it does")
        void invitationIsNotMembership() {
            assertThat(service.invite(alice, bob)).isEqualTo(Outcome.INVITED);

            assertThat(service.readersOf(alice)).containsExactly(alice);
            assertThat(service.chatOf(bob)).isEmpty();
            assertThat(service.isTalkingPrivately(bob)).isFalse();
        }

        @Test
        @DisplayName("inviting starts the inviter's chat and switches them into it")
        void startsTheInvitersChat() {
            service.invite(alice, bob);

            assertThat(service.chatOf(alice)).get().extracting(PrivateChat::owner).isEqualTo(alice);
            assertThat(service.isTalkingPrivately(alice)).isTrue();
        }

        @Test
        @DisplayName("accepting puts them in and switches them to it straight away")
        void acceptingJoins() {
            service.invite(alice, bob);

            assertThat(service.accept(bob, alice)).isEqualTo(Outcome.ADDED);

            assertThat(service.readersOf(alice)).containsExactlyInAnyOrder(alice, bob);
            assertThat(service.isTalkingPrivately(bob)).isTrue();
        }

        @Test
        @DisplayName("an invitation is good for one accept — a second click does nothing")
        void oneUse() {
            service.invite(alice, bob);
            service.accept(bob, alice);
            service.leave(bob);

            assertThat(service.accept(bob, alice)).isEqualTo(Outcome.INVITE_GONE);
            assertThat(service.chatOf(bob)).isEmpty();
        }

        @Test
        @DisplayName("nobody can accept an invitation that was never sent")
        void noInvitationNoEntry() {
            service.goPrivate(alice);

            assertThat(service.accept(bob, alice)).isEqualTo(Outcome.INVITE_GONE);
            assertThat(service.readersOf(alice)).containsExactly(alice);
        }

        @Test
        @DisplayName("an invitation runs out")
        void expires() {
            service.invite(alice, bob);
            now += PrivateChatService.INVITE_STANDS.toMillis() + 1;

            assertThat(service.accept(bob, alice)).isEqualTo(Outcome.INVITE_GONE);
            assertThat(service.chatOf(bob)).isEmpty();
        }

        @Test
        @DisplayName("declining throws the invitation away, and leaves them out")
        void declining() {
            service.invite(alice, bob);

            assertThat(service.decline(bob, alice)).isEqualTo(Outcome.DECLINED);
            assertThat(service.accept(bob, alice)).isEqualTo(Outcome.INVITE_GONE);
            assertThat(service.decline(bob, alice)).isEqualTo(Outcome.INVITE_GONE);
        }

        @Test
        @DisplayName("a chat that was closed takes its invitations with it")
        void endedChatInvitationsGone() {
            service.invite(alice, bob);
            service.end(alice);

            assertThat(service.accept(bob, alice)).isEqualTo(Outcome.INVITE_GONE);
            assertThat(service.chatOf(alice)).isEmpty();
        }

        @Test
        @DisplayName("inviting the same person twice while the first stands is refused, not repeated")
        void noSpam() {
            service.invite(alice, bob);

            assertThat(service.invite(alice, bob)).isEqualTo(Outcome.ALREADY_INVITED);

            now += PrivateChatService.INVITE_STANDS.toMillis() + 1;
            assertThat(service.invite(alice, bob)).isEqualTo(Outcome.INVITED);
        }

        @Test
        @DisplayName("only the owner may invite, and never themselves or a member")
        void refusals() {
            service.add(alice, bob);

            assertThat(service.invite(bob, carol)).isEqualTo(Outcome.NOT_THE_OWNER);
            assertThat(service.invite(alice, alice)).isEqualTo(Outcome.NOT_YOURSELF);
            assertThat(service.invite(alice, bob)).isEqualTo(Outcome.ALREADY_A_MEMBER);
        }

        @Test
        @DisplayName("somebody in another chat is not invited, and cannot accept into a second one")
        void oneChatEach() {
            service.add(carol, bob);

            assertThat(service.invite(alice, bob)).isEqualTo(Outcome.IN_ANOTHER_CHAT);

            UUID dave = UUID.randomUUID();
            service.invite(alice, dave);
            service.add(carol, dave);
            assertThat(service.accept(dave, alice)).isEqualTo(Outcome.IN_ANOTHER_CHAT);
            assertThat(service.readersOf(alice)).containsExactly(alice);
        }

        @Test
        @DisplayName("somebody who left may be invited back — they have to say yes again anyway")
        void walkedOutMayBeInvitedBack() {
            service.invite(alice, bob);
            service.accept(bob, alice);
            service.leave(bob);

            assertThat(service.invite(alice, bob)).isEqualTo(Outcome.INVITED);
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

    @Nested
    @DisplayName("the latest choice wins, between a private chat and a channel")
    class LatestChoiceWins {

        private final de.raindancer.core.ui.chat.ChatChannel staff = new de.raindancer.core.ui.chat.ChatChannel() {
            @Override
            public String id() {
                return "staff";
            }

            @Override
            public String label() {
                return "Staff";
            }

            @Override
            public Optional<java.util.Set<UUID>> audienceFor(UUID speaker) {
                return Optional.of(java.util.Set.of(speaker));
            }
        };

        @org.junit.jupiter.api.BeforeEach
        void register() {
            de.raindancer.core.ui.chat.ChatChannels.register(staff);
        }

        @org.junit.jupiter.api.AfterEach
        void clean() {
            de.raindancer.core.ui.chat.ChatChannels.unregister(staff);
            for (UUID who : java.util.List.of(alice, bob, carol)) {
                de.raindancer.core.ui.chat.ChatChannels.forget(who);
            }
        }

        @Test
        @DisplayName("choosing a channel stops talking privately — but they stay in the chat and read it")
        void channelAfterPrivate() {
            service.add(alice, bob);

            service.channelChosen(bob, "staff");

            assertThat(service.isTalkingPrivately(bob)).isFalse();
            assertThat(service.readersOf(alice)).contains(bob);
        }

        @Test
        @DisplayName("choosing everybody is left to /chat all and /chat public, which say so themselves")
        void allIsNotAChannelChoice() {
            service.goPrivate(alice);

            service.channelChosen(alice, de.raindancer.core.ui.chat.ChatChannels.ALL);

            assertThat(service.isTalkingPrivately(alice)).isTrue();
        }

        @Test
        @DisplayName("going private again drops the channel that was chosen")
        void privateAfterChannel() {
            service.add(alice, bob);
            de.raindancer.core.ui.chat.ChatChannels.select(bob, "staff");
            service.goPublic(bob);

            service.goPrivate(bob);

            assertThat(de.raindancer.core.ui.chat.ChatChannels.selected(bob))
                    .isEqualTo(de.raindancer.core.ui.chat.ChatChannels.ALL);
        }

        @Test
        @DisplayName("starting a chat, or joining one, drops the channel too")
        void startingOrJoiningDropsIt() {
            de.raindancer.core.ui.chat.ChatChannels.select(alice, "staff");
            de.raindancer.core.ui.chat.ChatChannels.select(bob, "staff");

            service.invite(alice, bob);
            service.accept(bob, alice);

            assertThat(de.raindancer.core.ui.chat.ChatChannels.selected(alice))
                    .isEqualTo(de.raindancer.core.ui.chat.ChatChannels.ALL);
            assertThat(de.raindancer.core.ui.chat.ChatChannels.selected(bob))
                    .isEqualTo(de.raindancer.core.ui.chat.ChatChannels.ALL);
        }
    }
}
