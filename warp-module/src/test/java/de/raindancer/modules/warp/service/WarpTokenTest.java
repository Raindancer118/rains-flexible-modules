package de.raindancer.modules.warp.service;

import de.raindancer.core.content.items.CustomItem;
import de.raindancer.core.content.items.ItemFactory;
import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.poi.PoiStore;
import de.raindancer.modules.warp.WarpSettings;
import de.raindancer.modules.warp.rules.WarpAccessRule;
import de.raindancer.modules.warp.store.WarpCatalogue;
import de.raindancer.modules.warp.store.WarpRegistry;
import de.raindancer.modules.warp.util.PermissionNodes;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A warp token buys exactly one warp — and only once that warp actually exists. */
class WarpTokenTest {

    @TempDir
    Path directory;

    private Database database;
    private WarpCatalogue catalogue;
    private WarpAdminService admin;
    private ItemFactory factory;
    private WarpTokens tokens;
    private Player player;
    private ItemStack token;

    @BeforeEach
    void setUp() {
        database = Database.open(directory.resolve("core.db"), CoreSchema.CORE, () -> false);
        PoiStore places = new PoiStore(database);
        catalogue = new WarpCatalogue(new WarpRegistry(places, () -> 0L, name -> true), places::flush);
        Messages messages = mock(Messages.class);
        admin = new WarpAdminService(catalogue, new WarpAccessRule(), messages,
                WarpSettings.DEFAULTS.withMostOwnWarps(0));
        factory = mock(ItemFactory.class);
        tokens = new WarpTokens(admin, factory, messages);

        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        Set<String> held = Set.of(PermissionNodes.USE);
        when(player.hasPermission(anyString())).thenAnswer(ask -> held.contains(ask.<String>getArgument(0)));
        when(player.getLocation()).thenReturn(new Location(world, 1, 64, 1));

        token = mock(ItemStack.class);
        when(token.getAmount()).thenReturn(2);
        when(factory.keyOf(same(token))).thenReturn(Optional.of(WarpTokens.KEY));
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(inventory.getContents()).thenReturn(new ItemStack[]{null, token});
        when(player.getInventory()).thenReturn(inventory);
    }

    @AfterEach
    void close() {
        database.close();
    }

    @Test
    @DisplayName("redeemed, it makes the warp — even with no create node and a limit of nought — and one token goes")
    void buysAWarp() {
        assertThat(tokens.redeem(player, "hut")).isTrue();

        assertThat(catalogue.byName("hut").orElseThrow().owner()).contains(player.getUniqueId());
        verify(token).setAmount(1);
    }

    @Test
    @DisplayName("a warp that could not be made costs nothing")
    void refusedCostsNothing() {
        assertThat(tokens.redeem(player, "list")).as("a reserved word").isFalse();

        assertThat(catalogue.count()).isZero();
        verify(token, never()).setAmount(anyInt());
    }

    @Test
    @DisplayName("without a token left in the inventory, nothing is made")
    void noTokenNoWarp() {
        when(factory.keyOf(same(token))).thenReturn(Optional.empty());

        assertThat(tokens.redeem(player, "hut")).isFalse();
        assertThat(catalogue.count()).isZero();
    }

    @Test
    @DisplayName("the token is a nether star that glows, shows a name, and cannot be crafted into a beacon")
    void theItem() {
        CustomItem item = WarpTokens.definition();

        assertThat(item.key()).isEqualTo(WarpTokens.KEY);
        assertThat(item.material()).isEqualTo(org.bukkit.Material.NETHER_STAR);
        assertThat(item.isGlowing()).isTrue();
        assertThat(item.isIngredient()).isFalse();
        assertThat(item.name()).contains("Warp Token");
        assertThat(item.abilityInFull()).contains(WarpTokens.KEY);
    }
}
