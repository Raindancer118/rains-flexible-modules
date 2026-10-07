package de.raindancer.modules.warp.service;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.poi.PoiStore;
import de.raindancer.modules.warp.WarpSettings;
import de.raindancer.modules.warp.model.Warp;
import de.raindancer.modules.warp.model.WarpAccess;
import de.raindancer.modules.warp.rules.WarpAccessRule;
import de.raindancer.modules.warp.store.WarpCatalogue;
import de.raindancer.modules.warp.store.WarpRegistry;
import de.raindancer.modules.warp.util.PermissionNodes;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.invocation.Invocation;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/**
 * Players making, changing and sharing warps of their own — through the same service the commands and
 * the menus use, so neither can come to allow what the other refuses.
 */
class OwnedWarpsServiceTest {

    @TempDir
    Path directory;

    private Database database;
    private WarpCatalogue catalogue;
    private Messages messages;
    private WarpAdminService service;
    private World world;

    @BeforeEach
    void setUp() {
        database = Database.open(directory.resolve("core.db"), CoreSchema.CORE, () -> false);
        PoiStore places = new PoiStore(database);
        catalogue = new WarpCatalogue(new WarpRegistry(places, () -> 0L, name -> true), places::flush);
        messages = mock(Messages.class);
        service = new WarpAdminService(catalogue, new WarpAccessRule(), messages,
                WarpSettings.DEFAULTS.withMostOwnWarps(1));
        world = mock(World.class);
        when(world.getName()).thenReturn("world");
    }

    @AfterEach
    void close() {
        database.close();
    }

    private Player player(String... nodes) {
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        Set<String> held = Set.of(nodes);
        when(player.getUniqueId()).thenReturn(id);
        when(player.hasPermission(anyString())).thenAnswer(ask -> held.contains(ask.<String>getArgument(0)));
        when(player.getLocation()).thenReturn(new Location(world, 5, 64, 5));
        when(player.getName()).thenReturn("p" + id.toString().substring(0, 4));
        return player;
    }

    /** Every message key sent to this player, in order. */
    private List<String> told(Player who) {
        return mockingDetails(messages).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("send"))
                .filter(call -> call.getArguments().length > 1 && call.getArguments()[0] == who)
                .map(Invocation::getArguments)
                .map(arguments -> String.valueOf(arguments[1]))
                .toList();
    }

    @Test
    @DisplayName("a player with the node sets a warp of their own, up to the limit; a token is one more")
    void makingOwn() {
        Player maker = player(PermissionNodes.USE, PermissionNodes.CREATE);

        assertThat(service.create(maker, "cave")).isPresent();
        assertThat(catalogue.byName("cave").orElseThrow().owner()).contains(maker.getUniqueId());

        assertThat(service.create(maker, "lake")).isEmpty();
        assertThat(told(maker)).contains("warps.own-limit");

        assertThat(service.create(maker, "lake", true)).isPresent();
    }

    @Test
    @DisplayName("without the node or a token, nothing is made — and they are told what would let them")
    void notAllowed() {
        Player player = player(PermissionNodes.USE);

        assertThat(service.create(player, "cave")).isEmpty();
        assertThat(catalogue.count()).isZero();
        assertThat(told(player)).containsExactly("warps.cannot-make");
    }

    @Test
    @DisplayName("a name somebody else's warp has is not taken over; one's own can be set again")
    void namesStayTheirs() {
        Player owner = player(PermissionNodes.USE, PermissionNodes.CREATE);
        Player other = player(PermissionNodes.USE, PermissionNodes.CREATE);
        service.create(owner, "cave");

        assertThat(service.create(other, "cave")).isEmpty();
        assertThat(told(other)).contains("warps.name-taken");
        assertThat(catalogue.byName("cave").orElseThrow().owner()).contains(owner.getUniqueId());

        assertThat(service.create(owner, "cave")).as("replacing one's own counts as no new warp").isPresent();
    }

    @Test
    @DisplayName("its owner may move and delete it; somebody else may not")
    void changing() {
        Player owner = player(PermissionNodes.USE, PermissionNodes.CREATE);
        Player stranger = player(PermissionNodes.USE);
        service.create(owner, "cave");

        assertThat(service.delete(stranger, "cave")).isFalse();
        assertThat(told(stranger)).contains("warps.not-your-warp");
        assertThat(service.move(owner, "cave")).isTrue();
        assertThat(service.delete(owner, "cave")).isTrue();
        assertThat(catalogue.byName("cave")).isEmpty();
    }

    @Test
    @DisplayName("somebody who cannot see a private warp is told there is no such warp, not that it is not theirs")
    void privateStaysUnseen() {
        Player owner = player(PermissionNodes.USE, PermissionNodes.CREATE);
        Player stranger = player(PermissionNodes.USE);
        service.create(owner, "den");
        service.setAccess(owner, "den", WarpAccess.PRIVATE);

        assertThat(service.delete(stranger, "den")).isFalse();
        assertThat(told(stranger)).contains("warps.unknown").doesNotContain("warps.not-your-warp");
    }

    @Test
    @DisplayName("an owner keeps it private or opens it up, but does not make it staff-only")
    void ownersAccess() {
        Player owner = player(PermissionNodes.USE, PermissionNodes.CREATE);
        service.create(owner, "cave");

        assertThat(service.setAccess(owner, "cave", WarpAccess.PRIVATE)).isTrue();
        assertThat(service.setAccess(owner, "cave", WarpAccess.STAFF)).isFalse();
        assertThat(told(owner)).contains("warps.access-not-yours");
        assertThat(catalogue.accessOf(catalogue.byName("cave").orElseThrow())).isEqualTo(WarpAccess.PRIVATE);
    }

    @Test
    @DisplayName("staff hand a warp to a player, who then owns it; a player cannot give one away")
    void giving() {
        Player admin = player(PermissionNodes.USE, PermissionNodes.MANAGE);
        Player owner = player(PermissionNodes.USE);
        Player other = player(PermissionNodes.USE);
        service.create(admin, "market");

        assertThat(service.giveTo(other, "market", other.getUniqueId(), other.getName())).isFalse();
        assertThat(service.giveTo(admin, "market", owner.getUniqueId(), owner.getName())).isTrue();

        Warp market = catalogue.byName("market").orElseThrow();
        assertThat(market.owner()).contains(owner.getUniqueId());
        assertThat(service.delete(owner, "market")).as("now theirs to decide about").isTrue();
    }

    @Test
    @DisplayName("an owner adds and removes the people who may use their private warp")
    void members() {
        Player owner = player(PermissionNodes.USE, PermissionNodes.CREATE);
        Player friend = player(PermissionNodes.USE);
        Player stranger = player(PermissionNodes.USE);
        service.create(owner, "den");
        service.setAccess(owner, "den", WarpAccess.PRIVATE);

        assertThat(service.addMember(stranger, "den", stranger.getUniqueId(), stranger.getName())).isFalse();
        assertThat(service.addMember(owner, "den", friend.getUniqueId(), friend.getName())).isTrue();
        assertThat(catalogue.byName("den").orElseThrow().members()).containsExactly(friend.getUniqueId());

        assertThat(service.removeMember(owner, "den", friend.getUniqueId(), friend.getName())).isTrue();
        assertThat(catalogue.byName("den").orElseThrow().members()).isEmpty();
    }
}
