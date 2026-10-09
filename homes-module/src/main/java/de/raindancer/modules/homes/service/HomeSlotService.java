package de.raindancer.modules.homes.service;

import de.raindancer.core.social.economy.BuyableSlots;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.homes.HomeSettings;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

/** Buying extra home slots — Core's {@link BuyableSlots} with this module's words. */
public final class HomeSlotService implements IHomeService {

    /** The economy source of what a slot costs. */
    public static final String SOURCE = "homes.slot";

    private final BuyableSlots slots;
    private final Messages messages;
    private volatile HomeSettings settings;

    public HomeSlotService(BuyableSlots slots, Messages messages, HomeSettings settings) {
        this.slots = slots;
        this.messages = messages;
        this.settings = settings;
    }

    @Override
    public void settings(HomeSettings fresh) {
        this.settings = fresh;
    }

    public boolean isOn() {
        BuyableSlots.Terms terms = settings.slotTerms();
        return terms.on() && terms.price().isPositive();
    }

    public int bought(UUID player) {
        return slots.bought(player);
    }

    public boolean isMaxed(UUID player) {
        return slots.why(player, settings.slotTerms()).filter(why -> why == BuyableSlots.Outcome.MAXED).isPresent();
    }

    /** Whether the next slot can be offered to this player at all. */
    public boolean canOffer(UUID player) {
        return slots.why(player, settings.slotTerms()).isEmpty();
    }

    /** Why the next slot cannot be offered, in words for a greyed button; empty when it can. */
    public Optional<String> whyNot(UUID player) {
        return slots.why(player, settings.slotTerms()).map(why -> switch (why) {
            case OFF -> "Buying home slots is switched off on this server.";
            case NO_PRICE -> "No price is set for a home slot.";
            case MAXED -> "You have bought as many slots as anybody may.";
            default -> "";
        });
    }

    /** The price as written, before the economy's price index. */
    public Money nextPrice(UUID player) {
        return slots.nextPrice(player, settings.slotTerms());
    }

    /** What the next slot would cost this player now, as they should read it. */
    public String describeNextPrice(UUID player) {
        return Fees.format(slots.quote(player, settings.slotTerms()));
    }

    /** Buys one slot. @return whether it was bought; a refusal has been said */
    public boolean buy(Player buyer) {
        BuyableSlots.Purchase bought = slots.buy(buyer.getUniqueId(), settings.slotTerms());
        switch (bought.outcome()) {
            case BOUGHT -> {
                messages.send(buyer, "homes.slot.bought", "price", Fees.format(bought.paid()), "count", bought.owned());
                return true;
            }
            case MAXED -> messages.send(buyer, "homes.slot.maxed", "most", settings.mostBoughtSlots());
            case NOT_ENOUGH -> messages.send(buyer, "homes.slot.cannot-afford", "price", describeNextPrice(buyer.getUniqueId()));
            case NO_ECONOMY -> messages.send(buyer, "homes.slot.no-economy");
            case NOT_SAVED -> messages.send(buyer, "homes.slot.not-saved");
            case REFUSED -> messages.send(buyer, "homes.slot.refused");
            case OFF, NO_PRICE -> {
            }
        }
        return false;
    }

    @Override
    public String describe() {
        return "buying extra home slots";
    }
}
