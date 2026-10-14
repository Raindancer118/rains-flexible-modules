package de.raindancer.modules.cosmetics.service;

import de.raindancer.core.moderation.audit.Audit;
import de.raindancer.core.moderation.audit.AuditEntry;
import de.raindancer.core.platform.network.LocalBus;
import de.raindancer.core.platform.network.NetworkCalls;
import de.raindancer.core.platform.network.Presence;
import de.raindancer.core.ui.effect.ParticleShape;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.cosmetics.CosmeticsSettings;
import de.raindancer.modules.cosmetics.model.ClearScope;
import de.raindancer.modules.cosmetics.model.ParticleChoice;
import de.raindancer.modules.cosmetics.store.ClearNetwork;
import de.raindancer.modules.cosmetics.util.PermissionNodes;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A staff member on one server clears the particle of somebody on another: that server does it, and answers. */
class ClearAcrossServersTest {

    private static final ParticleChoice WEARING = new ParticleChoice("flame", ParticleShape.AMBIENT, null, null, null);

    private final Map<UUID, Presence.Here> presence = new ConcurrentHashMap<>();
    private final LocalBus.Hub hub = new LocalBus.Hub();
    private final List<String> told = new ArrayList<>();

    private final class Side {
        final String id;
        final NameStyleService names = mock(NameStyleService.class);
        final ParticleService particles = mock(ParticleService.class);
        final Messages messages = mock(Messages.class);
        final Audit audit = mock(Audit.class);
        final Map<UUID, Player> here = new ConcurrentHashMap<>();
        final ClearService service;

        Side(String id) {
            this.id = id;
            when(names.current(any())).thenReturn(de.raindancer.core.ui.text.NameStyle.NONE);
            service = new ClearService(names, particles, messages, audit, (who, task) -> task.run(),
                    CosmeticsSettings.DEFAULTS);
            service.connectNetwork(new ClearNetwork(directory(id), new NetworkCalls(hub.bus(id))), here::get);
        }

        Player join(String name, boolean wearing) {
            Player who = mock(Player.class);
            UUID uuid = UUID.nameUUIDFromBytes(name.getBytes());
            when(who.getUniqueId()).thenReturn(uuid);
            when(who.getName()).thenReturn(name);
            when(who.isOnline()).thenReturn(true);
            when(particles.current(who)).thenReturn(wearing ? WEARING : ParticleChoice.NONE);
            when(particles.wings(who)).thenReturn(ParticleChoice.NONE);
            here.put(uuid, who);
            presence.put(uuid, new Presence.Here(uuid, name, id, false, 0));
            return who;
        }
    }

    private ClearNetwork.Directory directory(String self) {
        return new ClearNetwork.Directory() {
            @Override
            public Optional<Presence.Here> where(UUID player, boolean withVanished) {
                return Optional.ofNullable(presence.get(player));
            }

            @Override
            public String self() {
                return self;
            }

            @Override
            public boolean tell(UUID player, String key, String... values) {
                told.add(player + ":" + key);
                return true;
            }
        };
    }

    private final Side smp = new Side("smp");
    private final Side arena = new Side("arena");

    /** A call and its reply are two trips over the hub. */
    private void settle() {
        hub.drain();
        hub.drain();
    }

    private OfflinePlayer remote(Player there) {
        OfflinePlayer offline = mock(OfflinePlayer.class);
        UUID id = there.getUniqueId();
        String name = there.getName();
        when(offline.getUniqueId()).thenReturn(id);
        when(offline.getName()).thenReturn(name);
        when(offline.getPlayer()).thenReturn(null);
        return offline;
    }

    private CommandSender staff() {
        CommandSender console = mock(CommandSender.class);
        when(console.hasPermission(PermissionNodes.CLEAR_OTHERS)).thenReturn(true);
        return console;
    }

    @Test
    @DisplayName("the particle and wings of somebody on another server come off there, and they are told")
    void clearsOnTheirServer() {
        Player victim = arena.join("Griefer", true);
        CommandSender staff = staff();

        smp.service.clear(staff, remote(victim), ClearScope.PARTICLES);
        settle();

        verify(arena.particles).takeOff(victim, false);
        verify(arena.particles).takeOffWings(victim);
        verify(smp.messages).send(eq(staff), eq("cosmetics.clear.done-other"), any(Object[].class));
        verify(smp.audit).record(org.mockito.ArgumentMatchers.<AuditEntry.Builder>any());
        org.assertj.core.api.Assertions.assertThat(told).containsExactly(victim.getUniqueId() + ":cosmetics.clear.by-staff");
    }

    @Test
    @DisplayName("somebody on another server wearing nothing is reported as already plain, and nothing is touched")
    void nothingToClear() {
        Player victim = arena.join("Plain", false);
        CommandSender staff = staff();

        smp.service.clear(staff, remote(victim), ClearScope.PARTICLES);
        settle();

        verify(arena.particles, never()).takeOff(any(), anyBoolean());
        verify(smp.messages).send(eq(staff), eq("cosmetics.clear.nothing"), any(Object[].class));
    }

    @Test
    @DisplayName("without the permission another server is not even asked")
    void notAllowed() {
        Player victim = arena.join("Griefer", true);
        CommandSender nobody = mock(CommandSender.class);

        smp.service.clear(nobody, remote(victim), ClearScope.PARTICLES);
        settle();

        verify(arena.particles, never()).takeOff(any(), anyBoolean());
        verify(smp.messages).send(eq(nobody), eq("cosmetics.clear.not-allowed-others"), any(Object[].class));
    }

    @Test
    @DisplayName("a server only clears somebody who is on it: a call about a player who is elsewhere changes nothing")
    void onlyItsOwnPlayers() {
        Player stale = arena.join("Moved", true);
        presence.put(stale.getUniqueId(), new Presence.Here(stale.getUniqueId(), "Moved", "smp", false, 0));
        CommandSender staff = staff();

        // smp believes they are on arena; arena's own view says smp — it must not act on a call about them.
        ClearNetwork onSmp = new ClearNetwork(new ClearNetwork.Directory() {
            @Override
            public Optional<Presence.Here> where(UUID player, boolean withVanished) {
                return Optional.of(new Presence.Here(player, "Moved", "arena", false, 0));
            }

            @Override
            public String self() {
                return "smp";
            }

            @Override
            public boolean tell(UUID player, String key, String... values) {
                return false;
            }
        }, new NetworkCalls(hub.bus("smp-view")));
        onSmp.ask("arena", stale.getUniqueId(), ClearScope.PARTICLES);
        settle();

        verify(arena.particles, never()).takeOff(any(), anyBoolean());
    }

    @Test
    @DisplayName("somebody who is on no server keeps the old answer: name cleared, particle out of reach")
    void trulyOffline() {
        CommandSender staff = staff();
        OfflinePlayer gone = mock(OfflinePlayer.class);
        UUID id = UUID.randomUUID();
        when(gone.getUniqueId()).thenReturn(id);
        when(gone.getName()).thenReturn("Gone");
        when(smp.names.current(id)).thenReturn(de.raindancer.core.ui.text.NameStyle.NONE
                .withColour(net.kyori.adventure.text.format.NamedTextColor.RED));

        smp.service.clear(staff, gone, ClearScope.ALL);
        settle();

        verify(smp.names).strip(id);
        verify(smp.messages).send(eq(staff), eq("cosmetics.clear.offline-particles-note"), any(Object[].class));
    }
}
