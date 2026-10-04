package de.raindancer.modules.speedrun;

import de.raindancer.core.content.items.BoundItems;
import de.raindancer.core.testkit.TestInventories;
import de.raindancer.core.testkit.TestItems;
import de.raindancer.core.testkit.TestPlayers;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The lobby's two items, built for real with Core's testkit: what they are, that they are recognised
 * by their tag rather than by material or name, and that handing them out leaves everything else a
 * player carries alone.
 */
class SpeedrunLobbyItemsTest {

    private SpeedrunLobbyItems items;
    private NamespacedKey marker;

    @BeforeEach
    void setUp() {
        Plugin plugin = mock(Plugin.class);
        when(plugin.getName()).thenReturn("RainsCore");
        when(plugin.namespace()).thenReturn("rainscore");
        marker = new NamespacedKey(plugin, SpeedrunLobbyItems.MARKER_KEY);
        items = new SpeedrunLobbyItems(plugin);
    }

    @Test
    @DisplayName("the menu compass is a bound compass, recognised as the menu and nothing else")
    void menuCompass() {
        ItemStack compass = items.menuCompass();

        assertThat(compass.getType()).isEqualTo(Material.COMPASS);
        assertThat(BoundItems.isBound(compass)).isTrue();
        assertThat(items.isMenu(compass)).isTrue();
        assertThat(items.isStart(compass)).isFalse();
    }

    @Test
    @DisplayName("the start block is a bound lime block, recognised as the start and nothing else")
    void startBlock() {
        ItemStack block = items.startBlock();

        assertThat(block.getType()).isEqualTo(Material.LIME_CONCRETE);
        assertThat(BoundItems.isBound(block)).isTrue();
        assertThat(items.isStart(block)).isTrue();
        assertThat(items.isMenu(block)).isFalse();
    }

    @Test
    @DisplayName("recognised by the tag: a plain compass is neither, a renamed lobby compass still is")
    void byTagNotByLook() {
        ItemStack plain = TestItems.of(Material.COMPASS);
        ItemStack renamed = items.menuCompass();
        renamed.editMeta(meta -> meta.displayName(net.kyori.adventure.text.Component.text("Anything")));

        assertThat(items.isMenu(plain)).isFalse();
        assertThat(items.isMenu(renamed)).isTrue();
    }

    @Test
    @DisplayName("a stack tagged for something else entirely, or null, is neither")
    void unrelatedOrNull() {
        ItemStack other = TestItems.tagged(Material.COMPASS, marker, "something-else");

        assertThat(items.isMenu(other)).isFalse();
        assertThat(items.isStart(other)).isFalse();
        assertThat(items.isMenu(null)).isFalse();
        assertThat(items.isStart(null)).isFalse();
    }

    @Test
    @DisplayName("handing out the lobby items never clears what somebody carries — only old lobby items go")
    void giveKeepsTheInventory() {
        Player player = TestPlayers.player("Alex");
        ItemStack diamonds = TestItems.of(Material.DIAMOND, 5);
        player.getInventory().setItem(0, diamonds);
        player.getInventory().setItem(1, items.menuCompass());

        items.give(player, true);

        assertThat(player.getInventory().getItem(0)).isEqualTo(diamonds);
        assertThat(TestInventories.stacksIn(player.getInventory()))
                .filteredOn(items::isMenu).hasSize(1);
        assertThat(TestInventories.stacksIn(player.getInventory()))
                .filteredOn(items::isStart).hasSize(1);
    }

    @Test
    @DisplayName("without the start block, only the compass is handed out")
    void withoutStartBlock() {
        Player player = TestPlayers.player("Alex");

        items.give(player, false);

        assertThat(TestInventories.stacksIn(player.getInventory())).singleElement()
                .satisfies(stack -> assertThat(items.isMenu(stack)).isTrue());
    }

    @Test
    @DisplayName("take() takes the lobby items, the one on the cursor included, and nothing else")
    void take() {
        Player player = TestPlayers.player("Alex");
        ItemStack bread = TestItems.of(Material.BREAD, 3);
        player.getInventory().setItem(4, bread);
        items.give(player, true);
        player.setItemOnCursor(items.startBlock());

        items.take(player);

        assertThat(TestInventories.stacksIn(player.getInventory())).containsExactly(bread);
        assertThat(items.isStart(player.getItemOnCursor())).isFalse();
    }
}
