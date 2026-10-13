package de.raindancer.modules.oneblock.service;

import de.raindancer.core.platform.link.LinkGuest;
import de.raindancer.core.platform.link.LinkHost;
import de.raindancer.core.platform.link.ServerLink;
import de.raindancer.modules.oneblock.OneBlockSettings;
import de.raindancer.modules.oneblock.model.InviteKind;
import de.raindancer.modules.oneblock.model.Reply;
import de.raindancer.modules.oneblock.model.Requester;
import de.raindancer.modules.oneblock.rules.InviteRule;
import de.raindancer.modules.oneblock.store.InviteBook;
import de.raindancer.core.platform.link.LinkDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** A command on another server, over a real link, to the island server's endpoint and back. */
class RemoteGatewayTest {

    private static final String SECRET = "a-shared-secret-of-reasonable-length";
    private final List<ServerLink> open = new ArrayList<>();

    @AfterEach
    void closeAll() {
        open.forEach(ServerLink::close);
    }

    @Test
    @DisplayName("invite and accept from the hub: the island server decides, the hub is told to send the player over")
    void roundTrip() throws Exception {
        LinkHost island = LinkHost.listen("127.0.0.1", 0, SECRET, Duration.ofSeconds(2));
        open.add(island);
        LinkGuest hub = LinkGuest.connect(URI.create("http://127.0.0.1:" + island.port()), SECRET,
                Duration.ofSeconds(2), Duration.ofMillis(50));
        open.add(hub);
        UUID bo = UUID.randomUUID();
        InviteService service = new InviteService(new InviteServiceTest.FakeHost(), new InviteBook(), new InviteRule(500),
                (name, key, values) -> { },
                name -> CompletableFuture.completedFuture(Optional.of(new LinkDirectory.Found(bo, "Bo", "Hub"))),
                Runnable::run, System::currentTimeMillis, OneBlockSettings.DEFAULTS);
        new IslandEndpoint(service, List.of(island)).listen();
        RemoteGateway gateway = new RemoteGateway(() -> Optional.of(hub));
        long until = System.currentTimeMillis() + 5_000;
        while (!hub.isConnected() && System.currentTimeMillis() < until) {
            Thread.sleep(20);
        }

        Reply sent = gateway.invite(new Requester(UUID.randomUUID(), "Ada"), "bo", InviteKind.GUEST).get();
        assertThat(sent.key()).isEqualTo("oneblock.invite.sent-guest");
        assertThat(sent.values()).containsExactly("player", "Bo", "minutes", "5");

        Reply accepted = gateway.answer(new Requester(bo, "Bo"), "Ada", true).get();
        assertThat(accepted.transfer()).isTrue();
        assertThat(accepted.key()).isEqualTo("oneblock.accept.on-the-way");
    }

    @Test
    @DisplayName("no link, or one nobody answers on: the island server is asleep, and the player hears that")
    void asleep() throws Exception {
        assertThat(new RemoteGateway(Optional::empty).invite(new Requester(UUID.randomUUID(), "Ada"), "Bo",
                InviteKind.TEAM).get().key()).isEqualTo("oneblock.refuse.asleep");
        assertThat(new RemoteGateway(Optional::empty).home(new Requester(UUID.randomUUID(), "Ada")).get().transfer())
                .as("going there needs only the proxy").isTrue();
    }

    @Test
    @DisplayName("a reply survives the wire, and garbage reads as the stand-in")
    void wire() {
        Reply reply = Reply.refused("oneblock.refuse.full", "player", "Bo", "most", "4");
        assertThat(Reply.fromWire(reply.toWire(), Reply.silent())).isEqualTo(reply);
        assertThat(Reply.fromWire(java.util.Map.of("n", "x", "key", "k"), Reply.silent())).isEqualTo(Reply.silent());
    }
}
