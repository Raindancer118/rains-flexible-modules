package de.raindancer.modules.claims.service;

import de.raindancer.core.social.economy.BuyableSlots;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.claims.ClaimSettings;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

/** Buying extra claim slots — Core's {@link BuyableSlots} with this module's words. */
public final class ClaimSlotService implements IClaimService {

    public static final String SOURCE = "claims.slot";

    private final BuyableSlots slots;
    private final Messages messages;
    private volatile ClaimSettings settings;

    public ClaimSlotService(BuyableSlots slots, Messages messages, ClaimSettings settings) {
        this.slots = slots;
        this.messages = messages;
        this.settings = settings;
    }

    @Override
    public void settings(ClaimSettings fresh) {
        this.settings = fresh;
    }

    /** Whether the owner switched buying on at all — the button is shown greyed otherwise. */
    public boolean switchedOn() {
        return settings.slotTerms().on();
    }

    public int bought(UUID player) {
        return slots.bought(player);
    }

    /** Why the next slot cannot be offered, in words for a greyed button; empty when it can. */
    public Optional<String> whyNot(UUID player) {
        return slots.why(player, settings.slotTerms()).map(why -> switch (why) {
            case OFF -> "Buying claim slots is switched off on this server.";
            case NO_PRICE -> "No price is set for a claim slot.";
            case MAXED -> "You have bought as many claim slots as anybody may.";
            default -> "";
        });
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
                messages.send(buyer, "slot.bought", "price", Fees.format(bought.paid()),
                        "count", String.valueOf(bought.owned()));
                return true;
            }
            case OFF -> messages.send(buyer, "slot.off");
            case NO_PRICE -> messages.send(buyer, "slot.no-price");
            case MAXED -> messages.send(buyer, "slot.maxed", "most", String.valueOf(settings.mostBoughtClaimSlots()));
            case NOT_ENOUGH -> messages.send(buyer, "slot.cannot-afford", "price", describeNextPrice(buyer.getUniqueId()));
            case NO_ECONOMY -> messages.send(buyer, "slot.no-economy");
            case NOT_SAVED -> messages.send(buyer, "slot.not-saved");
            case REFUSED -> messages.send(buyer, "slot.refused");
        }
        return false;
    }

    @Override
    public String describe() {
        return "buying extra claim slots";
    }
}
