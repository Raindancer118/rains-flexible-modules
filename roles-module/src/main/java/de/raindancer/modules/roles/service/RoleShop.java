package de.raindancer.modules.roles.service;

import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.roles.RolesSettings;
import de.raindancer.modules.roles.model.Ownership;
import de.raindancer.modules.roles.model.Role;
import de.raindancer.modules.roles.rules.AccessRule;
import de.raindancer.modules.roles.rules.RentRule;
import de.raindancer.modules.roles.store.ChoiceBook;
import de.raindancer.modules.roles.store.OwnedBook;
import de.raindancer.modules.roles.store.RoleCatalogue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Buying and renting roles. Only a role that costs something is ever touched: free roles stay open to everybody,
 * a bought role is kept for good, and only a rental can lapse, when its rent cannot be paid.
 */
public final class RoleShop implements IRolesService, RoleAccess {

    public static final String BUY = "roles.buy";
    public static final String RENT = "roles.rent";

    public enum Outcome { DONE, NOT_FOR_SALE, ALREADY_YOURS, CANNOT_AFFORD, NO_ECONOMY, NOT_SAVED, REFUSED }

    /** @param charged what was actually taken, after the price index and levers */
    public record Result(Outcome outcome, Money charged) {

        public boolean done() {
            return outcome == Outcome.DONE;
        }
    }

    /** What happened to one rental when its rent came due. */
    public record RentEvent(Role role, boolean paid, Money amount) {
    }

    private final RoleCatalogue catalogue;
    private final OwnedBook owned;
    private final ChoiceBook choices;
    private final LongSupplier clock;
    private final AccessRule access = new AccessRule();
    private final RentRule rent = new RentRule();
    private final Predicate<UUID> bypassing;

    /** @param bypassing staff who used /role bypass, who skip prices as they skip the wait */
    public RoleShop(RoleCatalogue catalogue, OwnedBook owned, ChoiceBook choices, LongSupplier clock,
                    Predicate<UUID> bypassing) {
        this.bypassing = bypassing;
        this.catalogue = catalogue;
        this.owned = owned;
        this.choices = choices;
        this.clock = clock;
    }

    @Override
    public void settings(RolesSettings settings) {
        // Prices are per role in roles.yml; nothing here reads the settings.
    }

    @Override
    public boolean may(UUID player, Role role) {
        return access.may(role, owned.of(player, role.id()), bypassing.test(player));
    }

    public Optional<Ownership> ownership(UUID player, Role role) {
        return owned.of(player, role.id());
    }

    public Result buy(UUID player, Role role) {
        if (!role.buyable()) {
            return new Result(Outcome.NOT_FOR_SALE, Money.ZERO);
        }
        Optional<Ownership> have = owned.of(player, role.id());
        if (have.isPresent() && have.get().kind() == Ownership.Kind.BOUGHT) {
            return new Result(Outcome.ALREADY_YOURS, Money.ZERO);
        }
        return pay(player, role, role.buyPrice(), "Role: " + role.title(), BUY,
                new Ownership(player, role.id(), Ownership.Kind.BOUGHT, 0));
    }

    public Result rent(UUID player, Role role) {
        if (!role.rentable()) {
            return new Result(Outcome.NOT_FOR_SALE, Money.ZERO);
        }
        if (owned.of(player, role.id()).isPresent()) {
            return new Result(Outcome.ALREADY_YOURS, Money.ZERO);
        }
        return pay(player, role, role.rentPrice(), "Role rent: " + role.title(), RENT,
                new Ownership(player, role.id(), Ownership.Kind.RENTED, clock.getAsLong() + RentRule.PERIOD.toMillis()));
    }

    private Result pay(UUID player, Role role, Money price, String reason, String source, Ownership granted) {
        EconomyResult paid = Fees.charge(player, price, reason, source);
        if (!paid.succeeded()) {
            return new Result(switch (paid.outcome()) {
                case NOT_ENOUGH -> Outcome.CANNOT_AFFORD;
                case UNAVAILABLE -> Outcome.NO_ECONOMY;
                default -> Outcome.REFUSED;
            }, Money.ZERO);
        }
        if (!owned.put(granted)) {
            Fees.refund(player, paid.amount(), reason + " (not saved)", source);
            return new Result(Outcome.NOT_SAVED, Money.ZERO);
        }
        return new Result(Outcome.DONE, paid.amount());
    }

    /** Ends a rental now, without a refund. @return whether there was one to end */
    public boolean cancel(UUID player, Role role) {
        Optional<Ownership> have = owned.of(player, role.id());
        if (have.isEmpty() || have.get().kind() != Ownership.Kind.RENTED || !owned.remove(player, role.id())) {
            return false;
        }
        dropChoice(player, role.id());
        return true;
    }

    /** Charges every rental of this player that has come due, and lapses the ones that cannot be paid. */
    public List<RentEvent> collect(UUID player) {
        List<RentEvent> events = new ArrayList<>();
        long now = clock.getAsLong();
        for (Ownership have : owned.of(player)) {
            if (have.kind() != Ownership.Kind.RENTED || !rent.due(have.dueAt(), now)) {
                continue;
            }
            Optional<Role> found = catalogue.find(have.role());
            if (found.isEmpty() || !found.get().forSale()) {
                continue;
            }
            Role role = found.get();
            if (!role.rentable()) {
                lapse(player, role);
                events.add(new RentEvent(role, false, Money.ZERO));
                continue;
            }
            EconomyResult paid = Fees.charge(player, role.rentPrice(), "Role rent: " + role.title(), RENT);
            if (paid.succeeded()) {
                if (owned.put(new Ownership(player, role.id(), Ownership.Kind.RENTED, rent.next(have.dueAt(), now)))) {
                    events.add(new RentEvent(role, true, paid.amount()));
                } else {
                    Fees.refund(player, paid.amount(), "Role rent (not saved)", RENT);
                }
            } else {
                lapse(player, role);
                events.add(new RentEvent(role, false, paid.amount()));
            }
        }
        return events;
    }

    private void lapse(UUID player, Role role) {
        if (owned.remove(player, role.id())) {
            dropChoice(player, role.id());
        }
    }

    private void dropChoice(UUID player, String role) {
        choices.of(player).filter(choice -> choice.role().equals(role)).ifPresent(choice -> choices.clear(player));
    }

    /** Rentals whose rent falls due within the warning window. */
    public List<Ownership> dueSoon(UUID player) {
        long now = clock.getAsLong();
        return owned.of(player).stream()
                .filter(have -> have.kind() == Ownership.Kind.RENTED && rent.warn(have.dueAt(), now)).toList();
    }

    public boolean saving() {
        return owned.readable();
    }

    public String describe() {
        return "buying and renting roles";
    }
}
