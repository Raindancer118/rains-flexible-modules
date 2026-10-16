package de.raindancer.modules.oneblock.service;

import de.raindancer.core.platform.link.LinkDirectory;
import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.core.platform.network.NetworkCalls;
import de.raindancer.modules.oneblock.OneBlockSettings;
import de.raindancer.modules.oneblock.model.InviteKind;
import de.raindancer.modules.oneblock.model.Reply;
import de.raindancer.modules.oneblock.model.Requester;
import de.raindancer.modules.oneblock.rules.InviteRule;
import de.raindancer.modules.oneblock.store.InviteBook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Commands on two other servers, over the network's calls, to the island server's endpoint and back. */
class NetworkGatewayTest {

    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final List<AutoCloseable> open = new ArrayList<>();
    private final UUID bo = UUID.randomUUID();
    private final Requester ada = new Requester(UUID.randomUUID(), "Ada");

    private NetworkCalls calls(String id) {
        NetworkCalls calls = new NetworkCalls(hub.bus(id));
        open.add(calls);
        return calls;
    }

    private IslandEndpoint island(NetworkCalls calls) {
        InviteService service = new InviteService(new InviteServiceTest.FakeHost(), new InviteBook(), new InviteRule(500),
                (name, key, values) -> { },
                name -> CompletableFuture.completedFuture(Optional.of(new LinkDirectory.Found(bo, "Bo", "hub"))),
                Runnable::run, System::currentTimeMillis, OneBlockSettings.DEFAULTS);
        // Ada and Bo play on the hub.
        IslandEndpoint endpoint = new IslandEndpoint(service, List.of(), Optional.of(calls),
                (player, server) -> server.equals(player.equals(bo) || player.equals(ada.id()) ? "hub" : "smp"),
                (player, arguments) -> { });
        endpoint.listen();
        open.add(endpoint::stop);
        return endpoint;
    }

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable thing : open) {
            thing.close();
        }
    }

    @Test
    @DisplayName("two servers invite through the one island server, each answered, and the island decides")
    void twoServersOneIsland() throws Exception {
        island(calls("oneblock"));
        RemoteGateway fromHub = RemoteGateway.overNetwork(calls("hub"), () -> "oneblock");
        RemoteGateway fromSmp = RemoteGateway.overNetwork(calls("smp"), () -> "oneblock");

        Reply sent = fromHub.invite(ada, "bo", InviteKind.GUEST).get(10, TimeUnit.SECONDS);
        assertThat(sent.key()).isEqualTo("oneblock.invite.sent-guest");
        assertThat(sent.values()).containsExactly("player", "Bo", "minutes", "5");

        Reply refusedAsNoInvite = fromSmp.answer(new Requester(UUID.randomUUID(), "Cy"), "Ada", true).get(10, TimeUnit.SECONDS);
        assertThat(refusedAsNoInvite.key()).isEqualTo("oneblock.refuse.no-invite");

        Reply accepted = fromHub.answer(new Requester(bo, "Bo"), "Ada", true).get(10, TimeUnit.SECONDS);
        assertThat(accepted.key()).isEqualTo("oneblock.accept.on-the-way");
        assertThat(accepted.transfer()).isTrue();
    }

    @Test
    @DisplayName("an island server nobody hears, or none named, is asleep — and the player hears that")
    void asleep() throws Exception {
        RemoteGateway nobodyThere = RemoteGateway.overNetwork(calls("hub"), () -> "oneblock", Duration.ofMillis(300));
        RemoteGateway unnamed = RemoteGateway.overNetwork(calls("smp"), () -> " ", Duration.ofMillis(300));

        assertThat(nobodyThere.invite(ada, "Bo", InviteKind.TEAM).get(10, TimeUnit.SECONDS).key())
                .isEqualTo("oneblock.refuse.asleep");
        assertThat(unnamed.answer(ada, null, false).get(10, TimeUnit.SECONDS).key()).isEqualTo("oneblock.refuse.asleep");
    }

    @Test
    @DisplayName("going to the island asks nothing: it is the travel's job")
    void homeAsksNothing() throws Exception {
        assertThat(RemoteGateway.overNetwork(calls("hub"), () -> "oneblock").home(ada).get(1, TimeUnit.SECONDS).transfer())
                .isTrue();
    }

    @Test
    @DisplayName("a server speaks only for its own players: an answer or invite for somebody playing elsewhere is refused")
    void onlyForItsOwnPlayers() throws Exception {
        island(calls("oneblock"));
        RemoteGateway fromHub = RemoteGateway.overNetwork(calls("hub"), () -> "oneblock");
        RemoteGateway fromSmp = RemoteGateway.overNetwork(calls("smp"), () -> "oneblock");

        assertThat(fromSmp.invite(ada, "bo", InviteKind.TEAM).get(10, TimeUnit.SECONDS).key())
                .as("Ada is on the hub, not on smp").isEqualTo("oneblock.refuse.asleep");
        assertThat(fromHub.invite(ada, "bo", InviteKind.GUEST).get(10, TimeUnit.SECONDS).key())
                .isEqualTo("oneblock.invite.sent-guest");
        assertThat(fromSmp.answer(new Requester(bo, "Bo"), "Ada", true).get(10, TimeUnit.SECONDS).transfer())
                .as("smp cannot accept for Bo").isFalse();
        assertThat(fromHub.answer(new Requester(bo, "Bo"), "Ada", true).get(10, TimeUnit.SECONDS).key())
                .as("the invite is still there for Bo himself").isEqualTo("oneblock.accept.on-the-way");
    }
}
