package de.raindancer.modules.arena.service;

import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.core.platform.network.NetworkCalls;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.arena.api.ArenaProtocol;
import de.raindancer.modules.arena.api.MatchSpec;
import de.raindancer.modules.arena.api.Mode;
import de.raindancer.modules.arena.api.wire.NetworkWire;
import de.raindancer.modules.arena.api.wire.Wire;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Invites between the arena and two survival servers: each goes to, and is answered by, the one server it belongs to. */
class InviteAcrossServersTest {

    private static final UUID ADA = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID CY = UUID.fromString("00000000-0000-0000-0000-00000000000c");
    private static final MatchSpec DUEL = new MatchSpec(Mode.DUEL, "gladiator", 1, 0, 1, null);

    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final List<AutoCloseable> open = new ArrayList<>();
    private final List<String> heard = new CopyOnWriteArrayList<>();
    private final Server server = mock(Server.class);
    private final Player ada = player(ADA, "Ada");
    private final Player cy = player(CY, "Cy");
    private final AtomicReference<Optional<Wire.Found>> onSmp = new AtomicReference<>(Optional.empty());

    private static Player player(UUID id, String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getName()).thenReturn(name);
        return player;
    }

    private NetworkWire wire(String id) {
        NetworkCalls calls = new NetworkCalls(hub.bus(id));
        NetworkWire.Directory directory = new NetworkWire.Directory() {
            @Override
            public Set<String> servers() {
                return Set.of("arena", "smp-1", "smp-2");
            }

            @Override
            public Optional<Wire.Found> named(String name) {
                return onSmp.get().filter(found -> found.name().equalsIgnoreCase(name));
            }

            @Override
            public Set<String> names() {
                return Set.of();
            }
        };
        NetworkWire wire = new NetworkWire(calls, id, directory, (who, to) -> { });
        open.add(wire::close);
        open.add(calls);
        return wire;
    }

    private InviteService invites(NetworkWire arena) {
        return new InviteService(server, mock(Messages.class), new ArenaLink(arena, player -> Optional.empty()),
                mock(QueueService.class), new AgreedFights(System::currentTimeMillis), player -> false, kit -> true,
                System::currentTimeMillis, 0);
    }

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable thing : open) {
            thing.close();
        }
    }

    private void listening(String id) {
        NetworkWire smp = wire(id);
        smp.on(ArenaProtocol.INVITE, (peer, data) -> {
            heard.add(id + " invite " + data.get("toName") + " from " + peer);
            return Map.of("ok", "true");
        });
        smp.on(ArenaProtocol.SUMMON, (peer, data) -> {
            heard.add(id + " summon " + data.get("player"));
            return Map.of("ok", "true");
        });
        smp.on(ArenaProtocol.INVITE_CANCEL, (peer, data) -> {
            heard.add(id + " cancel");
            return Map.of("ok", "true");
        });
    }

    private static void settle() throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(400);
    }

    @Test
    @DisplayName("somebody on the second survival server is asked there, and only there")
    void inviteGoesToTheServerTheyAreOn() throws Exception {
        NetworkWire arena = wire("arena");
        listening("smp-1");
        listening("smp-2");
        onSmp.set(Optional.of(new Wire.Found(CY, "Cy", "smp-2")));

        invites(arena).invite(ada, "Cy", DUEL);
        settle();

        assertThat(heard).containsExactly("smp-2 invite Cy from arena");
    }

    @Test
    @DisplayName("an answer is taken from the server the invite went to, not from another")
    void onlyTheRightServerAnswers() throws Exception {
        NetworkWire arena = wire("arena");
        listening("smp-2");
        onSmp.set(Optional.of(new Wire.Found(CY, "Cy", "smp-2")));
        when(server.getPlayer(ADA)).thenReturn(ada);
        InviteService invites = invites(arena);
        invites.invite(ada, "Cy", DUEL);
        settle();
        String id = invites.madeBy(ADA).getFirst().id();

        assertThat(invites.answeredAcross("smp-1", Map.of("id", id, "accepted", "true"))).containsEntry("ok", "false");
        assertThat(invites.madeBy(ADA)).hasSize(1);
        assertThat(invites.answeredAcross("smp-2", Map.of("id", id, "accepted", "true"))).containsEntry("ok", "true");
        assertThat(invites.madeBy(ADA)).isEmpty();
    }

    @Test
    @DisplayName("an invite from the first survival server is summoned back to the first, whoever else is online")
    void summonGoesBackToTheAsker() throws Exception {
        NetworkWire arena = wire("arena");
        listening("smp-1");
        listening("smp-2");
        when(server.getPlayer(CY)).thenReturn(cy);
        InviteService invites = invites(arena);

        Map<String, String> data = new java.util.HashMap<>(de.raindancer.modules.arena.api.SpecCodec.encode(DUEL));
        data.put("id", "abc123");
        data.put("from", ADA.toString());
        data.put("fromName", "Ada");
        data.put("to", CY.toString());
        assertThat(invites.fromAcross("smp-1", data)).containsEntry("ok", "true");
        invites.accept(cy, "abc123");
        settle();

        assertThat(heard).containsExactly("smp-1 summon " + ADA);
    }

    @Test
    @DisplayName("taking an invite back tells the server it was made on")
    void cancelGoesToTheServerOfTheInvite() throws Exception {
        NetworkWire arena = wire("arena");
        listening("smp-1");
        listening("smp-2");
        onSmp.set(Optional.of(new Wire.Found(CY, "Cy", "smp-2")));
        InviteService invites = invites(arena);
        invites.invite(ada, "Cy", DUEL);
        settle();

        invites.cancel(ada, null);
        settle();

        assertThat(heard).contains("smp-2 cancel").doesNotContain("smp-1 cancel");
    }
}
