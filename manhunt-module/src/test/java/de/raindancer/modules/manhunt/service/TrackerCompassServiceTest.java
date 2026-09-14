package de.raindancer.modules.manhunt.service;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.manhunt.ManhuntSettings;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Handing a Hunter their tracking compass — reported live as "I tested with more people and did
 * still not get a compass". {@link TrackerCompassService#give} called {@code Inventory.addItem} and
 * threw the return value away: {@code addItem} does not throw when there is no room, it hands back
 * whatever would not fit — and a Hunter whose inventory happened to be full during a test session
 * (or just carrying a full loadout) was told "<gold>You have been handed a tracking compass." while
 * nothing landed anywhere.
 *
 * <h2>Why {@code place()}, not {@code give()}, for the inventory-behaviour tests</h2>
 * {@code give()} builds a real {@code Material.COMPASS} {@code ItemStack}, which lazily resolves
 * {@code io.papermc.paper.registry.RegistryAccess} — not available outside a running Paper server.
 * No test anywhere else in this reactor constructs a real {@code ItemStack} for the same reason
 * (see {@code MannequinEquipServiceTest}'s own note). {@link TrackerCompassService#place} is exactly
 * {@code give()}'s fix, split out so the decision — inventory first, feet if it does not fit, the
 * message either way — is reachable with a mocked {@code ItemStack} instead.
 */
class TrackerCompassServiceTest {

    private Player hunter;
    private PlayerInventory inventory;
    private World world;
    private Location location;
    private Messages messages;
    private TrackerCompassService service;

    @BeforeEach
    void setUp() {
        hunter = mock(Player.class);
        inventory = mock(PlayerInventory.class);
        world = mock(World.class);
        location = mock(Location.class);
        when(hunter.getInventory()).thenReturn(inventory);
        when(hunter.getWorld()).thenReturn(world);
        when(hunter.getLocation()).thenReturn(location);
        // No tracker already carried — findTracker() sees an empty inventory.
        when(inventory.getContents()).thenReturn(new ItemStack[36]);

        messages = mock(Messages.class);
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("manhunt");
        when(plugin.namespace()).thenReturn("manhunt");
        service = new TrackerCompassService(plugin, mock(ManhuntService.class),
                new TrackerCompass(ManhuntSettings.DEFAULTS, new PortalMemory()), new PortalMemory(),
                messages, null, ManhuntSettings.DEFAULTS);
    }

    @Test
    @DisplayName("a compass that fits goes straight into the inventory, nothing dropped")
    void fitsInInventory() {
        ItemStack compass = mock(ItemStack.class);
        when(inventory.addItem(compass)).thenReturn(new HashMap<>());

        service.place(hunter, compass);

        verify(world, never()).dropItem(any(Location.class), any(ItemStack.class));
        verify(messages).send(hunter, "manhunt.tracker.given");
    }

    @Test
    @DisplayName("a full inventory drops the compass at the Hunter's feet instead of losing it")
    void fullInventoryDropsAtFeet() {
        ItemStack compass = mock(ItemStack.class);
        HashMap<Integer, ItemStack> notFitted = new HashMap<>();
        notFitted.put(0, compass);
        when(inventory.addItem(compass)).thenReturn(notFitted);

        service.place(hunter, compass);

        verify(world).dropItem(location, compass);
        verify(messages).send(hunter, "manhunt.tracker.given");
    }

    @Test
    @DisplayName("in the overworld the needle comes from the player's compass target, never the item")
    void overworldUsesTheCompassTarget() {
        // Player.setCompassTarget moves the needle without touching the item at all, so it cannot
        // redraw the compass in a hand however often the Runner moves.
        assertThat(TrackerCompassService.needleFromCompassTarget(org.bukkit.World.Environment.NORMAL)).isTrue();
    }

    @Test
    @DisplayName("in the Nether and the End it stays a lodestone, where a plain compass only spins")
    void otherDimensionsKeepTheLodestone() {
        assertThat(TrackerCompassService.needleFromCompassTarget(org.bukkit.World.Environment.NETHER)).isFalse();
        assertThat(TrackerCompassService.needleFromCompassTarget(org.bukkit.World.Environment.THE_END)).isFalse();
    }

    @Test
    @DisplayName("the distance is no longer part of the item — a lore that changes is an item that changes")
    void distanceIsNotInTheLore() {
        java.util.List<net.kyori.adventure.text.Component> lore = service.loreFor("<gray>Straight ahead.");
        String plain = lore.stream()
                .map(line -> net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(line))
                .reduce("", (a, b) -> a + "\n" + b);

        assertThat(plain).contains("Straight ahead.").doesNotContain("blocks away");
    }

    @Test
    @DisplayName("a Hunter already carrying one of ours is left alone — never a second compass")
    void alreadyCarryingIsUntouched() {
        ItemStack existing = mock(ItemStack.class);
        org.bukkit.inventory.meta.CompassMeta meta = mock(org.bukkit.inventory.meta.CompassMeta.class);
        when(existing.getType()).thenReturn(org.bukkit.Material.COMPASS);
        when(existing.hasItemMeta()).thenReturn(true);
        when(existing.getItemMeta()).thenReturn(meta);
        org.bukkit.persistence.PersistentDataContainer pdc =
                mock(org.bukkit.persistence.PersistentDataContainer.class);
        when(meta.getPersistentDataContainer()).thenReturn(pdc);
        when(pdc.get(any(), any())).thenReturn("tracker");
        ItemStack[] contents = new ItemStack[36];
        contents[0] = existing;
        when(inventory.getContents()).thenReturn(contents);

        service.give(hunter);

        verify(inventory, never()).addItem(any(ItemStack.class));
    }
}
