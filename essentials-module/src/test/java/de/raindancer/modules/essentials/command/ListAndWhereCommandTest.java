package de.raindancer.modules.essentials.command;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.essentials.EssentialsServices;
import de.raindancer.modules.essentials.service.NetworkWho;
import de.raindancer.modules.essentials.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** What /list and /where say, given what the network says: the wording keys and their values. */
class ListAndWhereCommandTest {

    private final EssentialsServices live = mock(EssentialsServices.class);
    private final Messages messages = mock(Messages.class);
    private final NetworkWho who = mock(NetworkWho.class);
    private final CommandSender sender = mock(CommandSender.class);
    private final CommandSourceStack source = mock(CommandSourceStack.class);
    private final UUID alex = UUID.nameUUIDFromBytes("Alex".getBytes());

    @BeforeEach
    void wire() {
        when(live.messages()).thenReturn(messages);
        when(live.who()).thenReturn(who);
        when(live.server()).thenReturn(mock(Server.class));
        when(source.getSender()).thenReturn(sender);
    }

    @Test
    @DisplayName("/list on a network: a header, this server first, then the others with their players")
    void listOnANetwork() {
        when(who.isNetwork()).thenReturn(true);
        when(who.everybody(sender)).thenReturn(List.of(
                new NetworkWho.Group("smp", true, List.of(new NetworkWho.Listed(alex, "Tom", false))),
                new NetworkWho.Group("arena", false, List.of(new NetworkWho.Listed(alex, "Alex", false),
                        new NetworkWho.Listed(alex, "Ghost", true)))));

        new ListCommand(() -> live).execute(source, new String[0]);

        verify(messages).send(sender, "essentials.list.header", "count", "3", "servers", "2");
        verify(messages).send(sender, "essentials.list.this-server", "server", "smp", "count", "1", "players", "Tom");
        verify(messages).send(sender, "essentials.list.server", "server", "arena", "count", "2",
                "players", "Alex, Ghost (vanished)");
    }

    @Test
    @DisplayName("/list on a server of its own says who is here, without servers")
    void listAlone() {
        when(who.isNetwork()).thenReturn(false);
        when(who.everybody(sender)).thenReturn(List.of(new NetworkWho.Group("", true, List.of())));

        new ListCommand(() -> live).execute(source, new String[0]);

        verify(messages).send(sender, "essentials.list.alone", "count", "0", "players", "-");
    }

    @Test
    @DisplayName("/where without the location permission names the server and nothing about where they stand")
    void whereServerOnly() {
        when(who.isNetwork()).thenReturn(true);
        NetworkWho.Found found = new NetworkWho.Found(alex, "Alex", "arena", false, false);
        when(who.find(sender, "alex")).thenReturn(Optional.of(found));

        new WhereCommand(() -> live).execute(source, new String[]{"alex"});

        verify(messages).send(sender, "essentials.where.there", "player", "Alex", "server", "arena");
        verify(who, never()).locate(any());
    }

    @Test
    @DisplayName("/where for staff adds where they stand, asked of their server")
    void whereForStaff() {
        when(who.isNetwork()).thenReturn(true);
        when(sender.hasPermission(PermissionNodes.WHERE_LOCATION)).thenReturn(true);
        NetworkWho.Found found = new NetworkWho.Found(alex, "Alex", "arena", false, false);
        when(who.find(sender, "alex")).thenReturn(Optional.of(found));
        when(who.locate(found)).thenReturn(CompletableFuture.completedFuture(
                Optional.of(new NetworkWho.Spot("world", 1, 2, 3))));

        new WhereCommand(() -> live).execute(source, new String[]{"alex"});

        verify(messages).send(sender, "essentials.where.there-at", "player", "Alex", "server", "arena",
                "world", "world", "x", "1", "y", "2", "z", "3");
    }

    @Test
    @DisplayName("/where for staff still names the server when the place cannot be told")
    void whereWhenThePlaceIsLost() {
        when(who.isNetwork()).thenReturn(true);
        when(sender.hasPermission(PermissionNodes.WHERE_LOCATION)).thenReturn(true);
        NetworkWho.Found found = new NetworkWho.Found(alex, "Alex", "arena", false, false);
        when(who.find(sender, "alex")).thenReturn(Optional.of(found));
        when(who.locate(found)).thenReturn(CompletableFuture.completedFuture(Optional.empty()));

        new WhereCommand(() -> live).execute(source, new String[]{"alex"});

        verify(messages).send(sender, "essentials.where.there", "player", "Alex", "server", "arena");
    }

    @Test
    @DisplayName("/where about nobody the sender may know says the server has never seen them")
    void whereNobody() {
        when(who.find(sender, "ghost")).thenReturn(Optional.empty());

        new WhereCommand(() -> live).execute(source, new String[]{"ghost"});

        verify(messages).send(sender, "essentials.no-such-player", "player", "ghost");
    }

    @Test
    @DisplayName("/where without a name shows the usage, and refuses selectors")
    void whereUsage() {
        new WhereCommand(() -> live).execute(source, new String[0]);
        verify(messages).send(sender, "essentials.usage", "usage", "/where <player>");

        new WhereCommand(() -> live).execute(source, new String[]{"@a"});
        verify(messages).send(sender, "essentials.player.selector-refused", "selector", "@a");
    }
}
