package de.raindancer.modules.moderation.command;

import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.Sentence;
import de.raindancer.modules.moderation.rules.StaffRule;
import de.raindancer.modules.moderation.service.NetworkModeration;
import de.raindancer.modules.moderation.service.PunishmentService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** /kick for a player on another server: asked of that server, recorded only once it did it. */
class KickCommandNetworkTest {

    private final UUID alexId = UUID.nameUUIDFromBytes("Alex".getBytes());
    private final ModerationServices live = mock(ModerationServices.class);
    private final Messages messages = mock(Messages.class);
    private final NetworkModeration network = mock(NetworkModeration.class);
    private final PunishmentService punishments = mock(PunishmentService.class);
    private final Vanish vanish = mock(Vanish.class);
    private final CommandSender console = mock(CommandSender.class);
    private final CommandSourceStack source = mock(CommandSourceStack.class);
    private final NetworkModeration.There there = new NetworkModeration.There(alexId, "Alex", "arena", false);

    @BeforeEach
    void wire() {
        Server server = mock(Server.class);
        OfflinePlayer alex = mock(OfflinePlayer.class);
        when(alex.getUniqueId()).thenReturn(alexId);
        when(alex.getName()).thenReturn("Alex");
        when(alex.isOnline()).thenReturn(false);
        when(server.getOfflinePlayerIfCached("Alex")).thenReturn(alex);
        StaffRule rule = mock(StaffRule.class);
        when(rule.canAct(any(), any(), any())).thenReturn(Verdict.allowed());
        when(live.server()).thenReturn(server);
        when(live.messages()).thenReturn(messages);
        when(live.network()).thenReturn(network);
        when(live.punishmentService()).thenReturn(punishments);
        when(live.staffRule()).thenReturn(rule);
        when(live.vanish()).thenReturn(vanish);
        when(source.getSender()).thenReturn(console);
    }

    @Test
    @DisplayName("their server kicked them: the kick is recorded as any kick, and the staff member is told")
    void kickedElsewhere() {
        when(network.whereIs(alexId, true)).thenReturn(Optional.of(there));
        when(network.kick(there, "spamming the chat", false)).thenReturn(CompletableFuture.completedFuture(true));

        new KickCommand(() -> live).execute(source, new String[]{"Alex", "spamming", "the", "chat"});

        verify(punishments).punishKicked(any(), eq("the console"), eq(alexId), eq("Alex"), eq("spamming the chat"));
        verify(messages).send(console, "moderation.punished", "player", "Alex", "what", "kicked", "length", "once");
        verify(punishments, never()).punish(any(), anyString(), any(), anyString(), any(PunishmentKind.class),
                any(Sentence.class), anyString());
    }

    @Test
    @DisplayName("their server did not kick them (gone, silent): nothing is recorded and the staff member hears they are not here")
    void notKickedElsewhere() {
        when(network.whereIs(alexId, true)).thenReturn(Optional.of(there));
        when(network.kick(there, "no reason given", false)).thenReturn(CompletableFuture.completedFuture(false));

        new KickCommand(() -> live).execute(source, new String[]{"Alex"});

        verify(punishments, never()).punishKicked(any(), anyString(), any(), anyString(), anyString());
        verify(messages).send(console, "moderation.not-here", "player", "Alex");
    }

    @Test
    @DisplayName("staff who may not see the vanished do not find a vanished player to kick")
    void vanishedStaysHidden() {
        Player staff = mock(Player.class);
        UUID staffId = UUID.randomUUID();
        when(staff.getUniqueId()).thenReturn(staffId);
        when(source.getSender()).thenReturn(staff);
        when(vanish.maySeeVanished(staffId)).thenReturn(false);
        when(network.whereIs(alexId, false)).thenReturn(Optional.empty());

        new KickCommand(() -> live).execute(source, new String[]{"Alex"});

        verify(network).whereIs(alexId, false);
        verify(network, never()).kick(any(), anyString(), anyBoolean());
        verify(messages).send(staff, "moderation.not-here", "player", "Alex");
    }
}
