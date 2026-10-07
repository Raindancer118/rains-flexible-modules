package de.raindancer.modules.cosmetics;

import de.raindancer.core.moderation.audit.Audit;
import de.raindancer.core.moderation.audit.AuditEntry;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.text.NameStyle;
import de.raindancer.modules.cosmetics.model.ClearScope;
import de.raindancer.modules.cosmetics.model.ParticleChoice;
import de.raindancer.modules.cosmetics.service.ClearService;
import de.raindancer.modules.cosmetics.service.NameStyleService;
import de.raindancer.modules.cosmetics.service.ParticleService;
import de.raindancer.modules.cosmetics.util.PermissionNodes;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClearServiceTest {

    private final NameStyleService names = mock(NameStyleService.class);
    private final ParticleService particles = mock(ParticleService.class);
    private final Messages messages = mock(Messages.class);
    private final Audit audit = mock(Audit.class);
    private final ClearService service =
            new ClearService(names, particles, messages, audit, (who, task) -> task.run(), CosmeticsSettings.DEFAULTS);

    private static final NameStyle PAINTED = NameStyle.NONE.withColour(NamedTextColor.RED);
    private static final ParticleChoice WEARING = new ParticleChoice("flame",
            de.raindancer.core.ui.effect.ParticleShape.AMBIENT, null, null, null);

    private Player online(String name) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getName()).thenReturn(name);
        when(player.getPlayer()).thenReturn(player);
        when(player.isOnline()).thenReturn(true);
        when(names.current(player.getUniqueId())).thenReturn(PAINTED);
        when(particles.current(player)).thenReturn(WEARING);
        return player;
    }

    @Test
    @DisplayName("somebody clearing their own takes both off and writes no audit entry")
    void selfClearsBoth() {
        Player me = online("Tom");
        when(me.hasPermission(PermissionNodes.CLEAR)).thenReturn(true);

        service.clear(me, me, ClearScope.ALL);

        verify(names).strip(me.getUniqueId());
        verify(particles).takeOff(me, false);
        verify(audit, never()).record(org.mockito.ArgumentMatchers.<AuditEntry.Builder>any());
    }

    @Test
    @DisplayName("without the node nothing is touched")
    void refusedWithoutNode() {
        Player me = online("Tom");

        service.clear(me, me, ClearScope.ALL);

        verify(names, never()).strip(any());
        verify(particles, never()).takeOff(any(), anyBoolean());
        verify(messages).send(eq(me), eq("cosmetics.clear.not-allowed"), any(Object[].class));
    }

    @Test
    @DisplayName("holding the own node is not enough to clear somebody else's")
    void ownNodeDoesNotCoverOthers() {
        Player me = online("Tom");
        Player victim = online("Other");
        when(me.hasPermission(PermissionNodes.CLEAR)).thenReturn(true);

        service.clear(me, victim, ClearScope.ALL);

        verify(names, never()).strip(any());
        verify(particles, never()).takeOff(any(), anyBoolean());
        verify(messages).send(eq(me), eq("cosmetics.clear.not-allowed-others"), any(Object[].class));
        verify(audit, never()).record(org.mockito.ArgumentMatchers.<AuditEntry.Builder>any());
    }

    @Test
    @DisplayName("staff clearing somebody else's is audited and the owner is told")
    void staffClearIsAudited() {
        Player staff = online("Mod");
        Player victim = online("Griefer");
        when(staff.hasPermission(PermissionNodes.CLEAR_OTHERS)).thenReturn(true);

        service.clear(staff, victim, ClearScope.NAME);

        verify(names).strip(victim.getUniqueId());
        verify(particles, never()).takeOff(any(), anyBoolean());
        verify(audit).record(org.mockito.ArgumentMatchers.<AuditEntry.Builder>any());
        verify(messages).send(eq(victim), eq("cosmetics.clear.by-staff"), any(Object[].class));
    }

    @Test
    @DisplayName("an offline player's name is cleared and the particle is politely left, with a note")
    void offlineKeepsParticle() {
        CommandSender console = mock(CommandSender.class);
        when(console.hasPermission(PermissionNodes.CLEAR_OTHERS)).thenReturn(true);
        OfflinePlayer gone = mock(OfflinePlayer.class);
        UUID id = UUID.randomUUID();
        when(gone.getUniqueId()).thenReturn(id);
        when(gone.getName()).thenReturn("Gone");
        when(gone.getPlayer()).thenReturn(null);
        when(names.current(id)).thenReturn(PAINTED);

        service.clear(console, gone, ClearScope.ALL);

        verify(names).strip(id);
        verify(messages).send(eq(console), eq("cosmetics.clear.offline-particles-note"), any(Object[].class));
        verify(audit).record(org.mockito.ArgumentMatchers.<AuditEntry.Builder>any());
    }

    @Test
    @DisplayName("particles only, for somebody offline, is refused and nothing is written or audited")
    void offlineParticlesRefused() {
        CommandSender console = mock(CommandSender.class);
        when(console.hasPermission(PermissionNodes.CLEAR_OTHERS)).thenReturn(true);
        OfflinePlayer gone = mock(OfflinePlayer.class);
        when(gone.getUniqueId()).thenReturn(UUID.randomUUID());
        when(gone.getName()).thenReturn("Gone");
        when(names.current(gone.getUniqueId())).thenReturn(PAINTED);

        service.clear(console, gone, ClearScope.PARTICLES);

        verify(names, never()).strip(any());
        verify(messages).send(eq(console), eq("cosmetics.clear.offline-particles"), any(Object[].class));
        verify(audit, never()).record(org.mockito.ArgumentMatchers.<AuditEntry.Builder>any());
    }
}
