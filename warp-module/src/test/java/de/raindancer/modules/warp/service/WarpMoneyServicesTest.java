package de.raindancer.modules.warp.service;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.world.poi.PoiStore;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.invocation.Invocation;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

/** What it costs to make a warp, to own one and to visit one — on a real catalogue and a fake bank. */
class WarpMoneyServicesTest {

    private static final long WEEK = WarpRentRule.WEEK_MILLIS;

    @TempDir
    Path directory;

    private Database database;
    private WarpCatalogue catalogue;
    private Messages messages;
    private World world;
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final Map<UUID, Player> online = new HashMap<>();

    @BeforeEach
    void setUp() {
        database = Database.open(directory.resolve("core.db"), CoreSchema.CORE, () -> false);
        PoiStore places = new PoiStore(database);
        catalogue = new WarpCatalogue(new WarpRegistry(places, () -> 0L, name -> true), places::flush);
        messages = mock(Messages.class);
        world = mock(World.class);
        when(world.getName()).thenReturn("world");
    }

    @AfterEach
    void close() {
        FakeEconomy.uninstall();
        database.close();
    }

    private WarpSettings settings(String create, String rent, String mostFee, int cut) {
        return WarpSettings.DEFAULTS.withCreatePrice(create).withRentPerWeek(rent)
                .withMostVisitFee(mostFee).withVisitFeeServerCutPercent(cut).withMostOwnWarps(10);
    }

    private WarpRentService rent(WarpSettings settings) {
        return new WarpRentService(catalogue, new WarpRentRule(), messages, settings, now::get, online::get);
    }

    private WarpAdminService admin(WarpSettings settings) {
        return new WarpAdminService(catalogue, new WarpAccessRule(), messages, settings, rent(settings));
    }

    private Player player(String... nodes) {
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        Set<String> held = Set.of(nodes);
        when(player.getUniqueId()).thenReturn(id);
        when(player.hasPermission(anyString())).thenAnswer(ask -> held.contains(ask.<String>getArgument(0)));
        when(player.getLocation()).thenReturn(new Location(world, 5, 64, 5));
        when(player.getName()).thenReturn("p" + id.toString().substring(0, 4));
        online.put(id, player);
        return player;
    }

    private Player maker() {
        return player(PermissionNodes.USE, PermissionNodes.CREATE);
    }

    private List<String> told(Player who) {
        return mockingDetails(messages).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("send"))
                .filter(call -> call.getArguments().length > 1 && call.getArguments()[0] == who)
                .map(Invocation::getArguments)
                .map(arguments -> String.valueOf(arguments[1]))
                .toList();
    }

    private static Money money(String written) {
        return Fees.amount(written);
    }

    // ------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("the price of making a warp")
    class Creating {

        @Test
        @DisplayName("by default it is free and needs no economy")
        void free() {
            Player player = maker();

            assertThat(admin(WarpSettings.DEFAULTS.withMostOwnWarps(10)).create(player, "cave")).isPresent();
        }

        @Test
        @DisplayName("the price is taken, and the warp is made")
        void charged() {
            FakeEconomy bank = FakeEconomy.install();
            Player player = maker();
            bank.give(player.getUniqueId(), "100");

            assertThat(admin(settings("30", "0", "0", 0)).create(player, "cave")).isPresent();

            assertThat(bank.balance(player.getUniqueId())).isEqualTo(money("70"));
        }

        @Test
        @DisplayName("somebody who cannot pay gets no warp and keeps their money")
        void cannotAfford() {
            FakeEconomy bank = FakeEconomy.install();
            Player player = maker();
            bank.give(player.getUniqueId(), "10");

            assertThat(admin(settings("30", "0", "0", 0)).create(player, "cave")).isEmpty();

            assertThat(catalogue.count()).isZero();
            assertThat(bank.balance(player.getUniqueId())).isEqualTo(money("10"));
            assertThat(told(player)).contains("warps.create.cannot-afford");
        }

        @Test
        @DisplayName("a price with no economy is a refusal, never a free warp")
        void noEconomy() {
            Player player = maker();

            assertThat(admin(settings("30", "0", "0", 0)).create(player, "cave")).isEmpty();

            assertThat(catalogue.count()).isZero();
            assertThat(told(player)).contains("warps.create.no-economy");
        }

        @Test
        @DisplayName("setting the same warp again is moving it, and costs nothing")
        void replacingIsFree() {
            FakeEconomy bank = FakeEconomy.install();
            Player player = maker();
            bank.give(player.getUniqueId(), "100");
            WarpAdminService service = admin(settings("30", "0", "0", 0));
            service.create(player, "cave");

            service.create(player, "cave");

            assertThat(bank.balance(player.getUniqueId())).isEqualTo(money("70"));
        }

        @Test
        @DisplayName("a warp token pays for the warp, so a token holder is not charged as well")
        void aTokenCoversIt() {
            FakeEconomy bank = FakeEconomy.install();
            Player player = player(PermissionNodes.USE);
            bank.give(player.getUniqueId(), "100");

            assertThat(admin(settings("30", "0", "0", 0)).create(player, "cave", true)).isPresent();

            assertThat(bank.balance(player.getUniqueId())).isEqualTo(money("100"));
        }

        @Test
        @DisplayName("staff who manage warps, and holders of the bypass node, are not charged")
        void staffAreNot() {
            FakeEconomy bank = FakeEconomy.install();
            Player staff = player(PermissionNodes.USE, PermissionNodes.MANAGE);
            Player bypassing = player(PermissionNodes.USE, PermissionNodes.CREATE, PermissionNodes.BYPASS_FEES);
            bank.give(staff.getUniqueId(), "100").give(bypassing.getUniqueId(), "100");
            WarpAdminService service = admin(settings("30", "0", "0", 0));

            assertThat(service.create(staff, "spawn")).isPresent();
            assertThat(service.create(bypassing, "cave")).isPresent();

            assertThat(bank.balance(staff.getUniqueId())).isEqualTo(money("100"));
            assertThat(bank.balance(bypassing.getUniqueId())).isEqualTo(money("100"));
        }
    }

    // ------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("rent")
    class Rent {

        private Warp made(WarpAdminService service, Player owner, String name) {
            service.create(owner, name);
            return catalogue.byName(name).orElseThrow();
        }

        @Test
        @DisplayName("with rent off nothing is enrolled and nothing is ever closed")
        void off() {
            Player owner = maker();
            WarpSettings off = settings("0", "0", "0", 0);
            Warp warp = made(admin(off), owner, "cave");

            assertThat(warp.rentPaidUntil()).isEmpty();
            now.addAndGet(100 * WEEK);
            assertThat(rent(off).sweep()).isZero();
            assertThat(catalogue.byName("cave").orElseThrow().isClosedForRent()).isFalse();
        }

        @Test
        @DisplayName("a player's new warp starts paid for a week")
        void enrolled() {
            Player owner = maker();

            Warp warp = made(admin(settings("0", "5", "0", 0)), owner, "cave");

            assertThat(warp.rentPaidUntil()).hasValue(now.get() + WEEK);
        }

        @Test
        @DisplayName("a staff member's warp is never enrolled, so spawn can never be closed for rent")
        void staffWarpsAreExempt() {
            Player staff = player(PermissionNodes.USE, PermissionNodes.MANAGE);

            Warp warp = made(admin(settings("0", "5", "0", 0)), staff, "spawn");

            assertThat(warp.rentPaidUntil()).isEmpty();
        }

        @Test
        @DisplayName("nothing happens before the week is up")
        void notDueYet() {
            FakeEconomy bank = FakeEconomy.install();
            Player owner = maker();
            bank.give(owner.getUniqueId(), "100");
            WarpSettings on = settings("0", "5", "0", 0);
            made(admin(on), owner, "cave");

            now.addAndGet(WEEK - 1);

            assertThat(rent(on).sweep()).isZero();
            assertThat(bank.balance(owner.getUniqueId())).isEqualTo(money("100"));
        }

        @Test
        @DisplayName("when it is due the rent is taken and the week extended")
        void collected() {
            FakeEconomy bank = FakeEconomy.install();
            Player owner = maker();
            bank.give(owner.getUniqueId(), "100");
            WarpSettings on = settings("0", "5", "0", 0);
            made(admin(on), owner, "cave");
            long due = now.get() + WEEK;

            now.set(due + 10);
            assertThat(rent(on).sweep()).isEqualTo(1);

            Warp warp = catalogue.byName("cave").orElseThrow();
            assertThat(bank.balance(owner.getUniqueId())).isEqualTo(money("95"));
            assertThat(warp.rentPaidUntil()).hasValue(due + WEEK);
            assertThat(warp.isClosedForRent()).isFalse();
            assertThat(told(owner)).contains("warps.rent.collected");
        }

        @Test
        @DisplayName("unpaid rent closes the warp, tells the owner, and never deletes anything")
        void unpaidCloses() {
            FakeEconomy bank = FakeEconomy.install();
            Player owner = maker();
            bank.give(owner.getUniqueId(), "1");
            WarpSettings on = settings("0", "5", "0", 0);
            made(admin(on), owner, "cave");

            now.addAndGet(WEEK + 10);
            rent(on).sweep();

            Warp warp = catalogue.byName("cave").orElseThrow();
            assertThat(warp.isClosedForRent()).isTrue();
            assertThat(told(owner)).contains("warps.rent.closed");
            assertThat(catalogue.count()).isEqualTo(1);
        }

        @Test
        @DisplayName("a closed warp is not retried: it stays closed until its owner pays")
        void notRetried() {
            FakeEconomy bank = FakeEconomy.install();
            Player owner = maker();
            bank.give(owner.getUniqueId(), "1");
            WarpSettings on = settings("0", "5", "0", 0);
            made(admin(on), owner, "cave");
            now.addAndGet(WEEK + 10);
            rent(on).sweep();

            bank.give(owner.getUniqueId(), "100");
            now.addAndGet(WEEK);

            assertThat(rent(on).sweep()).isZero();
            assertThat(catalogue.byName("cave").orElseThrow().isClosedForRent()).isTrue();
            assertThat(bank.balance(owner.getUniqueId())).isEqualTo(money("100"));
        }

        @Test
        @DisplayName("paying reopens it and covers a week from now")
        void paying() {
            FakeEconomy bank = FakeEconomy.install();
            Player owner = maker();
            bank.give(owner.getUniqueId(), "1");
            WarpSettings on = settings("0", "5", "0", 0);
            made(admin(on), owner, "cave");
            now.addAndGet(WEEK + 10);
            WarpRentService service = rent(on);
            service.sweep();
            bank.give(owner.getUniqueId(), "100");

            assertThat(service.pay(owner, "cave")).isTrue();

            Warp warp = catalogue.byName("cave").orElseThrow();
            assertThat(warp.isClosedForRent()).isFalse();
            assertThat(warp.rentPaidUntil()).hasValue(now.get() + WEEK);
            assertThat(bank.balance(owner.getUniqueId())).isEqualTo(money("95"));
        }

        @Test
        @DisplayName("paying without the money leaves it closed and takes nothing")
        void payingBroke() {
            FakeEconomy bank = FakeEconomy.install();
            Player owner = maker();
            bank.give(owner.getUniqueId(), "1");
            WarpSettings on = settings("0", "5", "0", 0);
            made(admin(on), owner, "cave");
            now.addAndGet(WEEK + 10);
            WarpRentService service = rent(on);
            service.sweep();

            assertThat(service.pay(owner, "cave")).isFalse();

            assertThat(catalogue.byName("cave").orElseThrow().isClosedForRent()).isTrue();
            assertThat(bank.balance(owner.getUniqueId())).isEqualTo(money("1"));
            assertThat(told(owner)).contains("warps.rent.cannot-afford");
        }

        @Test
        @DisplayName("only the owner pays, and a warp that is not closed has nothing to pay")
        void whoMayPay() {
            FakeEconomy bank = FakeEconomy.install();
            Player owner = maker();
            Player stranger = maker();
            bank.give(owner.getUniqueId(), "100").give(stranger.getUniqueId(), "100");
            WarpSettings on = settings("0", "5", "0", 0);
            made(admin(on), owner, "cave");
            WarpRentService service = rent(on);

            assertThat(service.pay(stranger, "cave")).isFalse();
            assertThat(service.pay(owner, "cave")).isFalse();

            assertThat(bank.balance(owner.getUniqueId())).isEqualTo(money("100"));
            assertThat(bank.balance(stranger.getUniqueId())).isEqualTo(money("100"));
            assertThat(told(owner)).contains("warps.rent.nothing-owed");
            assertThat(told(stranger)).contains("warps.unknown");
        }

        @Test
        @DisplayName("switching rent off opens everything that was closed")
        void switchingOff() {
            FakeEconomy bank = FakeEconomy.install();
            Player owner = maker();
            WarpSettings on = settings("0", "5", "0", 0);
            made(admin(on), owner, "cave");
            now.addAndGet(WEEK + 10);
            rent(on).sweep();
            Warp closed = catalogue.byName("cave").orElseThrow();
            assertThat(rent(on).isClosed(closed)).isTrue();

            assertThat(rent(settings("0", "0", "0", 0)).isClosed(closed)).isFalse();
        }

        @Test
        @DisplayName("setting a warp again keeps its rent: re-making it is not a way to dodge being closed")
        void replacingKeepsRent() {
            FakeEconomy bank = FakeEconomy.install();
            Player owner = maker();
            bank.give(owner.getUniqueId(), "1");
            WarpSettings on = settings("0", "5", "0", 0);
            WarpAdminService service = admin(on);
            made(service, owner, "cave");
            now.addAndGet(WEEK + 10);
            rent(on).sweep();

            service.create(owner, "cave");

            assertThat(catalogue.byName("cave").orElseThrow().isClosedForRent()).isTrue();
        }
    }

    // ------------------------------------------------------------------------------------------------

    @Nested
    @DisplayName("visit fees")
    class Visiting {

        private final UUID ownerId = UUID.randomUUID();

        private Warp warpWithFee(String written) {
            catalogue.create("shop", "world", 1, 2, 3, ownerId);
            catalogue.setVisitFee("shop", money(written));
            return catalogue.byName("shop").orElseThrow();
        }

        private WarpVisitFees fees(WarpSettings settings) {
            return new WarpVisitFees(messages, new WarpFeeRule(), settings);
        }

        @Test
        @DisplayName("by default nothing is charged, whatever a warp says, and no economy is needed")
        void off() {
            Warp warp = warpWithFee("10");
            Player visitor = player(PermissionNodes.USE);

            assertThat(fees(settings("0", "0", "0", 0)).charge(visitor, warp)).isTrue();
        }

        @Test
        @DisplayName("the visitor pays the fee, the owner receives it, and a cut goes to the server")
        void paid() {
            FakeEconomy bank = FakeEconomy.install();
            Player visitor = player(PermissionNodes.USE);
            bank.give(visitor.getUniqueId(), "100").give(ownerId, "0");
            Warp warp = warpWithFee("20");

            assertThat(fees(settings("0", "0", "50", 10)).charge(visitor, warp)).isTrue();

            assertThat(bank.balance(visitor.getUniqueId())).isEqualTo(money("80"));
            assertThat(bank.balance(ownerId)).as("fee minus the 10 percent cut").isEqualTo(money("18"));
        }

        @Test
        @DisplayName("a fee above the cap is charged as the cap")
        void capped() {
            FakeEconomy bank = FakeEconomy.install();
            Player visitor = player(PermissionNodes.USE);
            bank.give(visitor.getUniqueId(), "100");
            Warp warp = warpWithFee("90");

            fees(settings("0", "0", "25", 0)).charge(visitor, warp);

            assertThat(bank.balance(visitor.getUniqueId())).isEqualTo(money("75"));
            assertThat(bank.balance(ownerId)).isEqualTo(money("25"));
        }

        @Test
        @DisplayName("the owner and staff with the bypass visit free")
        void freeForSome() {
            FakeEconomy bank = FakeEconomy.install();
            Player owner = player(PermissionNodes.USE);
            when(owner.getUniqueId()).thenReturn(ownerId);
            Player staff = player(PermissionNodes.USE, PermissionNodes.BYPASS_FEES);
            bank.give(ownerId, "100").give(staff.getUniqueId(), "100");
            Warp warp = warpWithFee("20");
            WarpVisitFees fees = fees(settings("0", "0", "50", 10));

            assertThat(fees.charge(owner, warp)).isTrue();
            assertThat(fees.charge(staff, warp)).isTrue();

            assertThat(bank.balance(ownerId)).isEqualTo(money("100"));
            assertThat(bank.balance(staff.getUniqueId())).isEqualTo(money("100"));
        }

        @Test
        @DisplayName("somebody who cannot pay is refused and nothing moves")
        void cannotAfford() {
            FakeEconomy bank = FakeEconomy.install();
            Player visitor = player(PermissionNodes.USE);
            bank.give(visitor.getUniqueId(), "5").give(ownerId, "0");
            Warp warp = warpWithFee("20");

            assertThat(fees(settings("0", "0", "50", 10)).charge(visitor, warp)).isFalse();

            assertThat(bank.balance(visitor.getUniqueId())).isEqualTo(money("5"));
            assertThat(bank.balance(ownerId)).isEqualTo(money("0"));
            assertThat(told(visitor)).contains("warps.visit.cannot-afford");
        }

        @Test
        @DisplayName("a fee with no economy is a refusal, never a free visit")
        void noEconomy() {
            Player visitor = player(PermissionNodes.USE);
            Warp warp = warpWithFee("20");

            assertThat(fees(settings("0", "0", "50", 0)).charge(visitor, warp)).isFalse();

            assertThat(told(visitor)).contains("warps.visit.no-economy");
        }

        @Test
        @DisplayName("a visit that does not happen gives everything back, owner's share and cut alike")
        void refunded() {
            FakeEconomy bank = FakeEconomy.install();
            Player visitor = player(PermissionNodes.USE);
            bank.give(visitor.getUniqueId(), "100").give(ownerId, "0");
            Warp warp = warpWithFee("20");
            WarpVisitFees fees = fees(settings("0", "0", "50", 10));
            fees.charge(visitor, warp);

            fees.refund(visitor.getUniqueId());

            assertThat(bank.balance(visitor.getUniqueId())).isEqualTo(money("100"));
            assertThat(bank.balance(ownerId)).isEqualTo(money("0"));
        }

        @Test
        @DisplayName("arriving keeps the money, and a later refund finds nothing to give back")
        void settled() {
            FakeEconomy bank = FakeEconomy.install();
            Player visitor = player(PermissionNodes.USE);
            bank.give(visitor.getUniqueId(), "100");
            Warp warp = warpWithFee("20");
            WarpVisitFees fees = fees(settings("0", "0", "50", 0));
            fees.charge(visitor, warp);

            fees.settle(visitor.getUniqueId());
            fees.refund(visitor.getUniqueId());

            assertThat(bank.balance(visitor.getUniqueId())).isEqualTo(money("80"));
        }

        @Test
        @DisplayName("an owner who has already spent the money cannot be made to give it back twice over")
        void ownerSpentIt() {
            FakeEconomy bank = FakeEconomy.install();
            Player visitor = player(PermissionNodes.USE);
            bank.give(visitor.getUniqueId(), "100").give(ownerId, "0");
            Warp warp = warpWithFee("20");
            WarpVisitFees fees = fees(settings("0", "0", "50", 10));
            fees.charge(visitor, warp);
            bank.give(ownerId, "0");

            fees.refund(visitor.getUniqueId());

            assertThat(bank.balance(visitor.getUniqueId())).as("no money appears from nowhere").isLessThan(money("100"));
            assertThat(bank.balance(ownerId)).isEqualTo(money("0"));
        }
    }
}
