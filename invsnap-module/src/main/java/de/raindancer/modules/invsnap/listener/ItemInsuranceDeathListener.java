package de.raindancer.modules.invsnap.listener;

import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.invsnap.service.ItemInsuranceService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.UUID;
import java.util.function.Function;

/**
 * Takes insured items out of a death's drops. {@code HIGHEST}: after death insurance (HIGH) has decided
 * whether the inventory is kept, so a kept inventory is not also given the item back.
 */
public final class ItemInsuranceDeathListener implements IInvSnapListener {

    private final ItemInsuranceService insurance;
    private final Messages messages;
    private final Function<UUID, Player> online;

    public ItemInsuranceDeathListener(ItemInsuranceService insurance, Messages messages,
                                      Function<UUID, Player> online) {
        this.insurance = insurance;
        this.messages = messages;
        this.online = online;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        ItemInsuranceService.Death death = insurance.onDeath(event);
        Player dier = event.getPlayer();
        if (death.ownReturned() > 0) {
            messages.send(dier, "invsnap.item.kept", "count", String.valueOf(death.ownReturned()));
        }
        if (death.claim() != null) {
            if (death.claim().succeeded()) {
                messages.send(dier, "invsnap.item.claim.paid", "price", Fees.format(death.claim().amount()));
            } else {
                messages.send(dier, "invsnap.item.claim.unpaid", "price", Fees.format(death.claimWritten()));
            }
        }
        for (UUID owner : death.otherOwners()) {
            Player lender = online.apply(owner);
            if (lender != null && !lender.isDead() && insurance.deliver(lender) > 0) {
                messages.send(lender, "invsnap.item.lent-came-back");
            }
        }
    }

    @Override
    public void forget(UUID player) {
        // Nothing remembered.
    }

    @Override
    public String describe() {
        return "item insurance: insured items leave a death's drops and go back to their owner";
    }
}
