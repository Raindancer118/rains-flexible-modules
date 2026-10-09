package de.raindancer.modules.invsnap.service;

import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.ItemValues;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.invsnap.InvSnapSettings;
import de.raindancer.modules.invsnap.rules.InsurancePremiumRule;
import de.raindancer.modules.invsnap.store.InsuredStore;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Death insurance: an insured player who pays the premium keeps their inventory.
 *
 * <p>Keeping the inventory also empties the event's drop list — {@code setKeepInventory(true)}
 * alone leaves the pre-computed drops in place, which would hand the player the items twice.
 */
public final class InsuranceService implements IInvSnapService {

    public static final String SOURCE = "invsnap.insurance";

    public enum Kind { NOT_APPLICABLE, KEPT, UNPAID }

    /** What came of a death; {@code paid} is what was actually charged, {@code refusal} why it was not. */
    public record Verdict(Kind kind, Money paid, EconomyResult.Outcome refusal) {
        static final Verdict NOTHING = new Verdict(Kind.NOT_APPLICABLE, Money.ZERO, null);
    }

    /** Takes the premium; the live one is {@link Fees#charge}. */
    @FunctionalInterface
    public interface Charger {
        EconomyResult charge(UUID payer, Money premium, String reason);
    }

    private final InsuredStore store;
    private final InsurancePremiumRule rule;
    private final Charger charger;
    private final Function<ItemStack, Optional<Money>> valuer;
    private volatile InvSnapSettings settings;

    public InsuranceService(InsuredStore store, InsurancePremiumRule rule, Charger charger,
                            Function<ItemStack, Optional<Money>> valuer, InvSnapSettings settings) {
        this.store = store;
        this.rule = rule;
        this.charger = charger;
        this.valuer = valuer;
        this.settings = settings;
    }

    public static InsuranceService live(InsuredStore store, InvSnapSettings settings) {
        return new InsuranceService(store, new InsurancePremiumRule(),
                (payer, premium, reason) -> Fees.charge(payer, premium, reason, SOURCE),
                ItemValues::valueOf, settings);
    }

    @Override
    public void settings(InvSnapSettings settings) {
        this.settings = settings;
    }

    public boolean isInsured(UUID player) {
        return store.isInsured(player);
    }

    public void setInsured(UUID player, boolean on) {
        store.set(player, on);
    }

    public boolean enabled() {
        return settings.insuranceEnabled();
    }

    /** What dying right now with this inventory would cost, before any price index or levers. */
    public Money premiumFor(Player player) {
        InvSnapSettings now = settings;
        return rule.premium(now.insurancePricePercent() > 0 ? inventoryValue(player) : Money.ZERO,
                now.insurancePricePercent(), Fees.amount(now.insurancePriceFlat()),
                Fees.amount(now.insuranceMost()));
    }

    /** Judges one death and, when it is insured and paid for, changes the event to keep the inventory. */
    public Verdict onDeath(PlayerDeathEvent event) {
        InvSnapSettings now = settings;
        Player player = event.getPlayer();
        if (!now.insuranceEnabled() || event.isCancelled() || event.getKeepInventory()
                || !store.isInsured(player.getUniqueId())
                || !now.insuresWorld(player.getWorld().getName())) {
            return Verdict.NOTHING;
        }

        Money premium = premiumFor(player);
        EconomyResult result = charger.charge(player.getUniqueId(), premium, "Death insurance");
        if (!result.succeeded()) {
            return new Verdict(Kind.UNPAID, result.amount().isPositive() ? result.amount() : premium,
                    result.outcome());
        }

        event.setKeepInventory(true);
        event.getDrops().clear();
        if (now.insuranceKeepXp()) {
            event.setKeepLevel(true);
            event.setDroppedExp(0);
        }
        return new Verdict(Kind.KEPT, result.amount(), null);
    }

    private Money inventoryValue(Player player) {
        Money total = Money.ZERO;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack == null) {
                continue;
            }
            Optional<Money> each = valuer.apply(stack);
            if (each.isPresent()) {
                total = total.plus(each.get().times(Math.max(1, stack.getAmount())));
            }
        }
        return total;
    }
}
