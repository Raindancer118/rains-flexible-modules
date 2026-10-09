package de.raindancer.modules.homes.service;

import de.raindancer.core.social.economy.EconomyResult;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.homes.HomeSettings;
import de.raindancer.modules.homes.rules.HomeSlotRule;
import de.raindancer.modules.homes.store.BoughtSlots;
import org.bukkit.entity.Player;

import java.util.UUID;

/** Buying extra home slots: charges, records the slot, and says what happened. */
public final class HomeSlotService implements IHomeService {

    /** The economy source of what a slot costs. */
    public static final String SOURCE = "homes.slot";

    private final BoughtSlots slots;
    private final HomeSlotRule rule;
    private final Messages messages;
    private volatile HomeSettings settings;

    public HomeSlotService(BoughtSlots slots, HomeSlotRule rule, Messages messages,
                           HomeSettings settings) {
        this.slots = slots;
        this.rule = rule;
        this.messages = messages;
        this.settings = settings;
    }

    @Override
    public void settings(HomeSettings fresh) {
        this.settings = fresh;
    }

    public boolean isOn() {
        return rule.isOn(Fees.amount(settings.slotPrice()));
    }

    public int bought(UUID player) {
        return slots.of(player);
    }

    public boolean isMaxed(UUID player) {
        return !rule.mayBuyAnother(slots.of(player), settings.mostBoughtSlots());
    }

    /** Whether the next slot can be offered to this player at all. */
    public boolean canOffer(UUID player) {
        return isOn() && !isMaxed(player);
    }

    /** The price as written, before the economy's price index. */
    public Money nextPrice(UUID player) {
        return rule.priceOfNext(Fees.amount(settings.slotPrice()), settings.slotPriceGrowthPercent(),
                slots.of(player));
    }

    /** What the next slot would cost this player now, as they should read it. */
    public String describeNextPrice(UUID player) {
        return Fees.format(Fees.quote(SOURCE, nextPrice(player)));
    }

    /** Buys one slot. @return whether it was bought; a refusal has been said */
    public boolean buy(Player buyer) {
        UUID who = buyer.getUniqueId();
        if (!isOn()) {
            return false;
        }
        if (isMaxed(who)) {
            messages.send(buyer, "homes.slot.maxed", "most", settings.mostBoughtSlots());
            return false;
        }
        EconomyResult charged = Fees.charge(who, nextPrice(who), "Extra home slot", SOURCE);
        if (!charged.succeeded()) {
            refuse(buyer, charged);
            return false;
        }
        if (!slots.add(who)) {
            Fees.refund(who, charged.amount(), "Extra home slot not saved", SOURCE);
            messages.send(buyer, "homes.slot.not-saved");
            return false;
        }
        messages.send(buyer, "homes.slot.bought", "price", Fees.format(charged.amount()),
                "count", slots.of(who));
        return true;
    }

    private void refuse(Player buyer, EconomyResult charged) {
        switch (charged.outcome()) {
            case NOT_ENOUGH -> messages.send(buyer, "homes.slot.cannot-afford",
                    "price", Fees.format(charged.amount()));
            case UNAVAILABLE -> messages.send(buyer, "homes.slot.no-economy");
            default -> messages.send(buyer, "homes.slot.refused");
        }
    }

    @Override
    public String describe() {
        return "buying extra home slots";
    }
}
