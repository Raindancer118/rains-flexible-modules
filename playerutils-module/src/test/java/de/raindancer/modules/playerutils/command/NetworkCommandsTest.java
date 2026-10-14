package de.raindancer.modules.playerutils.command;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.playerutils.PlayerUtilsServices;
import de.raindancer.modules.playerutils.PlayerUtilsSettings;
import de.raindancer.modules.playerutils.model.Action;
import de.raindancer.modules.playerutils.rules.ArgumentRule;
import de.raindancer.modules.playerutils.rules.TargetRule;
import de.raindancer.modules.playerutils.service.InfoService;
import de.raindancer.modules.playerutils.service.NetworkReach;
import de.raindancer.modules.playerutils.service.SpectateService;
import de.raindancer.modules.playerutils.service.Targeting;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** /position and /spectate aimed at somebody on another server. */
class NetworkCommandsTest {

    private final PlayerUtilsServices live = mock(PlayerUtilsServices.class);
    private final Messages messages = mock(Messages.class);
    private final NetworkReach reach = mock(NetworkReach.class);
    private final InfoService info = mock(InfoService.class);
    private final SpectateService spectate = mock(SpectateService.class);
    private final CommandSourceStack source = mock(CommandSourceStack.class);
    private final NetworkReach.Remote alex = new NetworkReach.Remote(UUID.randomUUID(), "Alex", "arena");

    @BeforeEach
    void wire() {
        when(live.messages()).thenReturn(messages);
        when(live.reach()).thenReturn(reach);
        when(live.info()).thenReturn(info);
        when(live.spectate()).thenReturn(spectate);
        when(live.arguments()).thenReturn(new ArgumentRule());
        when(live.targeting()).thenReturn(new Targeting(mock(Server.class), messages, new TargetRule(),
                PlayerUtilsSettings.DEFAULTS));
    }

    @Test
    @DisplayName("/position <player> for somebody on another server asks their server, for the console too")
    void positionElsewhere() {
        CommandSender console = mock(CommandSender.class);
        when(source.getSender()).thenReturn(console);
        when(reach.find(console, "Alex")).thenReturn(Optional.of(alex));

        new ActionCommand(() -> live, Action.POSITION).execute(source, new String[]{"Alex"});

        verify(info).positionElsewhere(console, alex, reach);
        verify(messages, never()).send(any(CommandSender.class), org.mockito.ArgumentMatchers.eq("playerutils.nobody"),
                any(Object[].class));
    }

    @Test
    @DisplayName("without the permission for position, and for others, nothing is revealed about somebody elsewhere")
    void positionNeedsPermission() {
        Player staff = mock(Player.class);
        when(source.getSender()).thenReturn(staff);
        when(reach.find(staff, "Alex")).thenReturn(Optional.of(alex));

        new ActionCommand(() -> live, Action.POSITION).execute(source, new String[]{"Alex"});

        verify(info, never()).positionElsewhere(any(), any(), any());
        verify(messages).send(staff, "playerutils.no-permission", "action", "position", "player", "Alex");
    }

    @Test
    @DisplayName("/spectate <player> for somebody on another server sends the staff member there")
    void spectateElsewhere() {
        Player staff = mock(Player.class);
        when(staff.hasPermission(Action.SPECTATE.node())).thenReturn(true);
        when(staff.hasPermission(Action.SPECTATE.othersNode())).thenReturn(true);
        when(source.getSender()).thenReturn(staff);
        when(reach.find(staff, "Alex")).thenReturn(Optional.of(alex));

        new SpectateCommand(() -> live).execute(source, new String[]{"Alex"});

        verify(spectate).startAcross(staff, alex);
    }

    @Test
    @DisplayName("/spectate for somebody elsewhere without permission for others goes nowhere")
    void spectateNeedsPermission() {
        Player staff = mock(Player.class);
        when(staff.hasPermission(Action.SPECTATE.node())).thenReturn(true);
        when(source.getSender()).thenReturn(staff);
        when(reach.find(staff, "Alex")).thenReturn(Optional.of(alex));

        new SpectateCommand(() -> live).execute(source, new String[]{"Alex"});

        verify(spectate, never()).startAcross(any(), any());
        verify(messages).send(staff, "playerutils.no-permission-others", "action", "spectate", "player", "Alex");
    }
}
