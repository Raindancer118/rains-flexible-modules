package de.raindancer.modules.warp.service;

import de.raindancer.core.social.economy.Economies;
import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.warp.WarpSettings;
import de.raindancer.modules.warp.model.Warp;
import de.raindancer.modules.warp.rules.WarpRentRule;
import de.raindancer.modules.warp.store.WarpCatalogue;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * Weekly rent on player-owned warps.
 *
 * <p>The sanction for not paying is a <b>closed</b> warp, never a deleted one: it stays exactly as it
 * was, others cannot use it, and its owner can pay at any time. Which warps pay rent is decided once,
 * when they are made ({@link #enroll}); the due time is kept on the warp itself, so it survives
 * restarts.
 */
public final class WarpRentService implements IWarpService {

    /** The economy source of rent. */
    public static final String SOURCE = "warp.rent";

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("d MMM yyyy HH:mm").withZone(ZoneId.systemDefault());

    private final WarpCatalogue catalogue;
    private final WarpRentRule rule;
    private final Messages messages;
    private final LongSupplier clock;
    private final Function<UUID, Player> online;
    private volatile WarpSettings settings;

    /**
     * @param online finds the player if they are logged in, so they can be told; null when they are not
     */
    public WarpRentService(WarpCatalogue catalogue, WarpRentRule rule, Messages messages,
                           WarpSettings settings, LongSupplier clock, Function<UUID, Player> online) {
        this.catalogue = catalogue;
        this.rule = rule;
        this.messages = messages;
        this.settings = settings;
        this.clock = clock;
        this.online = online;
    }

    @Override
    public void settings(WarpSettings fresh) {
        this.settings = fresh;
    }

    /** The rent as written, before the economy's price index. */
    public Money rent() {
        return Fees.amount(settings.rentPerWeek());
    }

    public boolean isOn() {
        return rent().isPositive();
    }

    /** The weekly rent as a player should read it. */
    public String describeRent() {
        return Fees.format(Fees.quote(SOURCE, rent()));
    }

    /** Whether this warp is shut because its rent is unpaid, and rent is still on. */
    public boolean isClosed(Warp warp) {
        return warp != null && rule.isClosed(warp.isClosedForRent(), rent());
    }

    /** When its rent is paid until, as a date, or empty for a warp that pays no rent. */
    public String paidUntilText(Warp warp) {
        OptionalLong until = warp.rentPaidUntil();
        return until.isPresent() ? DAY.format(Instant.ofEpochMilli(until.getAsLong())) : "";
    }

    /** Puts a warp just made on rent, unless rent is off or its maker is exempt. */
    public void enroll(Warp warp, boolean ownerBypasses) {
        if (warp == null || !rule.enrols(rent(), warp.owner().isPresent(), ownerBypasses)) {
            return;
        }
        catalogue.setRent(warp.name(), clock.getAsLong() + WarpRentRule.WEEK_MILLIS, false);
    }

    /**
     * Collects what has fallen due, and closes what could not be paid.
     *
     * <p>A closed warp is not tried again: it waits for its owner. Nothing happens while there is no
     * economy or it cannot answer — a missing bank is not a reason to close anybody's warp.
     *
     * @return how many warps changed state
     */
    public int sweep() {
        Money rent = rent();
        if (!rent.isPositive() || Economies.current().isEmpty()) {
            return 0;
        }
        long now = clock.getAsLong();
        int changed = 0;
        for (Warp warp : catalogue.all()) {
            OptionalLong until = warp.rentPaidUntil();
            if (until.isEmpty() || warp.isClosedForRent() || warp.owner().isEmpty()
                    || !rule.isDue(until.getAsLong(), now)) {
                continue;
            }
            UUID owner = warp.owner().get();
            EconomyResult paid = Fees.charge(owner, rent, "Rent for warp " + warp.name(), SOURCE);
            if (paid.succeeded()) {
                catalogue.setRent(warp.name(), rule.extended(until.getAsLong(), now), false);
                tell(owner, "warps.rent.collected", "name", warp.label(),
                        "price", Fees.format(paid.amount()), "until",
                        paidUntilText(catalogue.byName(warp.name()).orElse(warp)));
                changed++;
            } else if (paid.outcome() != EconomyResult.Outcome.UNAVAILABLE) {
                catalogue.setRent(warp.name(), until.getAsLong(), true);
                tell(owner, "warps.rent.closed", "name", warp.label(),
                        "price", Fees.format(Fees.quote(SOURCE, rent)));
                changed++;
            }
        }
        return changed;
    }

    /** Pays what keeps a closed warp shut and opens it. @return whether it was paid; a refusal has been said */
    public boolean pay(Player owner, String name) {
        Warp warp = catalogue.byName(name).orElse(null);
        if (warp == null || !warp.owner().map(owner.getUniqueId()::equals).orElse(false)) {
            messages.send(owner, "warps.unknown", "name", String.valueOf(name));
            return false;
        }
        if (!isOn()) {
            messages.send(owner, "warps.rent.disabled");
            return false;
        }
        if (warp.rentPaidUntil().isEmpty()) {
            messages.send(owner, "warps.rent.not-charged", "name", warp.label());
            return false;
        }
        if (!warp.isClosedForRent()) {
            messages.send(owner, "warps.rent.nothing-owed", "name", warp.label(),
                    "until", paidUntilText(warp));
            return false;
        }
        EconomyResult paid = Fees.charge(owner.getUniqueId(), rent(), "Rent for warp " + warp.name(), SOURCE);
        if (!paid.succeeded()) {
            switch (paid.outcome()) {
                case NOT_ENOUGH -> messages.send(owner, "warps.rent.cannot-afford",
                        "price", Fees.format(paid.amount()));
                case UNAVAILABLE -> messages.send(owner, "warps.rent.no-economy");
                default -> messages.send(owner, "warps.rent.refused");
            }
            return false;
        }
        catalogue.setRent(warp.name(), clock.getAsLong() + WarpRentRule.WEEK_MILLIS, false);
        messages.send(owner, "warps.rent.paid", "name", warp.label(), "price", Fees.format(paid.amount()),
                "until", paidUntilText(catalogue.byName(warp.name()).orElse(warp)));
        return true;
    }

    /** Says what rent each of this player's warps is on. */
    public void statusTo(Player owner) {
        if (!isOn()) {
            messages.send(owner, "warps.rent.disabled");
            return;
        }
        boolean any = false;
        for (Warp warp : catalogue.ownedBy(owner.getUniqueId())) {
            if (warp.rentPaidUntil().isEmpty()) {
                continue;
            }
            any = true;
            if (isClosed(warp)) {
                messages.send(owner, "warps.rent.status-closed", "name", warp.label(), "price", describeRent());
            } else {
                messages.send(owner, "warps.rent.status-open", "name", warp.label(),
                        "until", paidUntilText(warp), "price", describeRent());
            }
        }
        if (!any) {
            messages.send(owner, "warps.rent.none", "price", describeRent());
        }
    }

    /** Tells an owner who has just logged in about every warp of theirs that is closed. */
    public void remind(Player owner) {
        for (Warp warp : catalogue.ownedBy(owner.getUniqueId())) {
            if (isClosed(warp)) {
                messages.send(owner, "warps.rent.closed", "name", warp.label(),
                        "price", describeRent());
            }
        }
    }

    private void tell(UUID who, String key, Object... values) {
        Player player = online == null ? null : online.apply(who);
        if (player != null) {
            messages.send(player, key, values);
        }
    }

    @Override
    public String describe() {
        return "weekly rent on player-owned warps";
    }
}
