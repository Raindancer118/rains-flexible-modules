package de.raindancer.modules.warp.service;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.poi.Poi;
import de.raindancer.core.world.poi.PoiStore;
import de.raindancer.core.world.teleport.Travel;
import de.raindancer.core.world.teleport.TravelReason;
import de.raindancer.core.world.teleport.TravelWatcher;
import de.raindancer.core.world.teleport.Trip;
import de.raindancer.modules.warp.FakeEconomy;
import de.raindancer.modules.warp.WarpSettings;
import de.raindancer.modules.warp.model.Warp;
import de.raindancer.modules.warp.rules.WarpAccessRule;
import de.raindancer.modules.warp.rules.WarpFeeRule;
import de.raindancer.modules.warp.rules.WarpRentRule;
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
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The warp trip itself: a closed warp is shut to strangers, and a visit fee is paid back when the trip fails. */
class TravelServiceMoneyTest {

    @TempDir
    Path directory;

    private Database database;
    private WarpCatalogue catalogue;
    private Messages messages;
    private Travel travel;
    private World world;
    private final UUID ownerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        database = Database.open(directory.resolve("core.db"), CoreSchema.CORE, () -> false);
        PoiStore places = new PoiStore(database);
        catalogue = new WarpCatalogue(new WarpRegistry(places, () -> 0L, name -> true), places::flush);
        messages = mock(Messages.class);
        travel = mock(Travel.class);
        world = mock(World.class);
        when(world.getName()).thenReturn("world");
    }

    @AfterEach
    void close() {
        FakeEconomy.uninstall();
        database.close();
    }

    private WarpSettings settings(String rent, String mostFee, int cut) {
        return WarpSettings.DEFAULTS.withRentPerWeek(rent).withMostVisitFee(mostFee)
                .withVisitFeeServerCutPercent(cut).withWarmupSeconds(0);
    }

    private TravelService service(WarpSettings settings) {
        WarpRentService rent = new WarpRentService(catalogue, new WarpRentRule(), messages, settings,
                () -> 0L, id -> null);
        WarpVisitFees visits = new WarpVisitFees(messages, new WarpFeeRule(), settings);
        return new TravelService(catalogue, new WarpRegistry(mock(PoiStore.class), () -> 0L, name -> true),
                travel, new WarpAccessRule(), messages, settings, visits, rent);
    }

    private Player player(String... nodes) {
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        Set<String> held = Set.of(nodes);
        when(player.getUniqueId()).thenReturn(id);
        when(player.hasPermission(anyString())).thenAnswer(ask -> held.contains(ask.<String>getArgument(0)));
        return player;
    }

    /** A warp that can actually be gone to: a place with a loaded world, owned by somebody, with tags. */
    private Warp warp(String fee, boolean closed) {
        Poi poi = mock(Poi.class);
        when(poi.name()).thenReturn("shop");
        when(poi.label()).thenReturn("shop");
        when(poi.owner()).thenReturn(ownerId);
        when(poi.location()).thenReturn(Optional.of(new Location(world, 1, 2, 3)));
        when(poi.tag(anyString())).thenReturn(Optional.empty());
        when(poi.tag(Warp.TAG_VISIT_FEE)).thenReturn(Optional.of(String.valueOf(Fees.amount(fee).minor())));
        if (closed) {
            when(poi.tag(Warp.TAG_RENT_CLOSED)).thenReturn(Optional.of("1"));
        }
        return new Warp(poi);
    }

    private TravelWatcher watcher() {
        ArgumentCaptor<TravelWatcher> captor = ArgumentCaptor.forClass(TravelWatcher.class);
        verify(travel).go(any(), any(Location.class), any(), captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("a warp closed for unpaid rent is shut to a stranger, who is told so")
    void closedToStrangers() {
        Player stranger = player(PermissionNodes.USE);

        service(settings("5", "0", 0)).go(stranger, warp("0", true));

        verify(travel, never()).go(any(), any(), any(), any());
        verify(messages).send(eq(stranger), eq("warps.closed"), any(Object[].class));
    }

    @Test
    @DisplayName("its owner and staff can still go to it")
    void openToOwnerAndStaff() {
        Player owner = player(PermissionNodes.USE);
        when(owner.getUniqueId()).thenReturn(ownerId);
        Player staff = player(PermissionNodes.USE, PermissionNodes.MANAGE);
        TravelService service = service(settings("5", "0", 0));

        service.go(owner, warp("0", true));
        service.go(staff, warp("0", true));

        verify(travel, org.mockito.Mockito.times(2)).go(any(), any(Location.class), any(), any());
    }

    @Test
    @DisplayName("with rent off a warp that was closed is open")
    void rentOffOpensIt() {
        Player stranger = player(PermissionNodes.USE);

        service(settings("0", "0", 0)).go(stranger, warp("0", true));

        verify(travel).go(any(), any(Location.class), any(), any());
    }

    @Test
    @DisplayName("a visit fee is taken before the trip and kept when they arrive")
    void feeKept() {
        FakeEconomy bank = FakeEconomy.install();
        Player visitor = player(PermissionNodes.USE);
        bank.give(visitor.getUniqueId(), "50").give(ownerId, "0");

        service(settings("0", "20", 10)).go(visitor, warp("10", false));
        watcher().arrived(visitor, new Location(world, 1, 2, 3), Trip.to("shop"));

        assertThat(bank.balance(visitor.getUniqueId())).isEqualTo(Fees.amount("40"));
        assertThat(bank.balance(ownerId)).isEqualTo(Fees.amount("9"));
    }

    @Test
    @DisplayName("a trip that is cancelled gives the fee back")
    void feeRefundedWhenCancelled() {
        FakeEconomy bank = FakeEconomy.install();
        Player visitor = player(PermissionNodes.USE);
        bank.give(visitor.getUniqueId(), "50").give(ownerId, "0");

        service(settings("0", "20", 10)).go(visitor, warp("10", false));
        watcher().cancelled(visitor, TravelReason.MOVED, Trip.to("shop"));

        assertThat(bank.balance(visitor.getUniqueId())).isEqualTo(Fees.amount("50"));
        assertThat(bank.balance(ownerId)).isEqualTo(Fees.amount("0"));
        verify(messages).send(eq(visitor), eq("warps.visit.refunded"), any(Object[].class));
    }

    @Test
    @DisplayName("a trip refused on the way gives the fee back")
    void feeRefundedWhenRefused() {
        FakeEconomy bank = FakeEconomy.install();
        Player visitor = player(PermissionNodes.USE);
        bank.give(visitor.getUniqueId(), "50").give(ownerId, "0");

        service(settings("0", "20", 0)).go(visitor, warp("10", false));
        watcher().refused(visitor, TravelReason.NOWHERE_SAFE, Trip.to("shop"));

        assertThat(bank.balance(visitor.getUniqueId())).isEqualTo(Fees.amount("50"));
    }

    @Test
    @DisplayName("logging out in the middle of the wait gives the fee back")
    void feeRefundedOnQuit() {
        FakeEconomy bank = FakeEconomy.install();
        Player visitor = player(PermissionNodes.USE);
        bank.give(visitor.getUniqueId(), "50").give(ownerId, "0");
        TravelService service = service(settings("0", "20", 0));
        service.go(visitor, warp("10", false));

        service.leaves(visitor.getUniqueId());

        assertThat(bank.balance(visitor.getUniqueId())).isEqualTo(Fees.amount("50"));
    }

    @Test
    @DisplayName("somebody who cannot pay does not go")
    void cannotPay() {
        FakeEconomy bank = FakeEconomy.install();
        Player visitor = player(PermissionNodes.USE);
        bank.give(visitor.getUniqueId(), "1");

        service(settings("0", "20", 0)).go(visitor, warp("10", false));

        verify(travel, never()).go(any(), any(), any(), any());
    }

    @Test
    @DisplayName("with the cap at zero a fee written on the warp is ignored: it is free and needs no economy")
    void capZeroIgnoresTheFee() {
        Player visitor = player(PermissionNodes.USE);

        service(settings("0", "0", 0)).go(visitor, warp("10", false));

        verify(travel).go(any(), any(Location.class), any(), any());
    }
}
