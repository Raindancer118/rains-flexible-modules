package de.raindancer.modules.hungergames.command;

import de.raindancer.core.content.items.CustomItem;
import de.raindancer.modules.hungergames.HungerGamesServices;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** {@code /hg give <item> <amount> <who>}: selectors, offline players, and who gets nothing. */
class GiveTargetsTest {

    private final Server server = mock(Server.class);
    private final HungerGamesServices services = mock(HungerGamesServices.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
    private final Player admin = mock(Player.class);
    private final Player ann = mock(Player.class);
    private final Player ben = mock(Player.class);
    private final PlayerInventory annBag = mock(PlayerInventory.class);
    private final PlayerInventory benBag = mock(PlayerInventory.class);
    private HungerGamesCommand command;
    private CommandSourceStack source;

    @BeforeEach
    void setUp() {
        CustomItem item = mock(CustomItem.class);
        when(item.plugin()).thenReturn("hungergames");
        when(item.id()).thenReturn("medikit");
        when(services.items().all()).thenReturn(List.of(item));
        ItemStack stack = mock(ItemStack.class);
        when(stack.clone()).thenReturn(stack);
        when(services.itemFactory().create(item, 1)).thenReturn(Optional.of(stack));
        when(services.server()).thenReturn(server);
        when(admin.hasPermission(org.mockito.ArgumentMatchers.anyString())).thenReturn(true);
        when(admin.isOp()).thenReturn(true);
        for (Player p : List.of(ann, ben)) {
            String name = p == ann ? "Ann" : "Ben";
            when(p.getName()).thenReturn(name);
            when(p.getUniqueId()).thenReturn(UUID.nameUUIDFromBytes(name.getBytes()));
            when(p.isOnline()).thenReturn(true);
            when(server.getPlayerExact(name)).thenReturn(p);
        }
        when(ann.getInventory()).thenReturn(annBag);
        when(ben.getInventory()).thenReturn(benBag);
        when(annBag.addItem(any(ItemStack.class))).thenReturn(new java.util.HashMap<>());
        when(benBag.addItem(any(ItemStack.class))).thenReturn(new java.util.HashMap<>());
        source = mock(CommandSourceStack.class);
        when(source.getSender()).thenReturn(admin);
        command = new HungerGamesCommand(() -> services);
    }

    @Test
    @DisplayName("a selector hands the item to everybody it matches")
    void selectorGivesToEach() {
        when(server.selectEntities(admin, "@a")).thenReturn(List.of(ann, ben));

        command.execute(source, new String[]{"give", "medikit", "1", "@a"});

        verify(annBag).addItem(any(ItemStack.class));
        verify(benBag).addItem(any(ItemStack.class));
    }

    @Test
    @DisplayName("an offline player is called offline and gets nothing")
    void offline() {
        OfflinePlayer away = mock(OfflinePlayer.class);
        when(away.getName()).thenReturn("Zed");
        when(away.getUniqueId()).thenReturn(UUID.randomUUID());
        when(server.getOfflinePlayerIfCached("Zed")).thenReturn(away);

        command.execute(source, new String[]{"give", "medikit", "1", "Zed"});

        verify(services.messages()).send(eq(admin), eq("hungergames.give-offline"), any(Object[].class));
        verify(annBag, never()).addItem(any(ItemStack.class));
    }

    @Test
    @DisplayName("a selector the sender may not use gives nothing")
    void selectorRefused() {
        when(admin.hasPermission("minecraft.command.selector")).thenReturn(false);

        command.execute(source, new String[]{"give", "medikit", "1", "@a"});

        verify(services.messages()).send(eq(admin), eq("hungergames.give-selector-refused"), any(Object[].class));
        verify(annBag, never()).addItem(any(ItemStack.class));
    }
}
