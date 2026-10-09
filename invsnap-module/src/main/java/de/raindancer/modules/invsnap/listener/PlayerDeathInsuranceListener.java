package de.raindancer.modules.invsnap.listener;

import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.invsnap.service.InsuranceService;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;

import java.util.UUID;

/**
 * Applies death insurance. {@code HIGH}: after other plugins have had their say on keepInventory
 * and cancelled the death where they manage inventories themselves, before the {@code MONITOR}
 * snapshot listener, which then records an inventory that is being kept.
 */
public final class PlayerDeathInsuranceListener implements IInvSnapListener {

    private final InsuranceService insurance;
    private final Messages messages;

    public PlayerDeathInsuranceListener(InsuranceService insurance, Messages messages) {
        this.insurance = insurance;
        this.messages = messages;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        InsuranceService.Verdict verdict = insurance.onDeath(event);
        switch (verdict.kind()) {
            case KEPT -> {
                String key = verdict.paid().isPositive() ? "invsnap.insurance.paid" : "invsnap.insurance.free";
                messages.send(event.getPlayer(), key, "price", Fees.format(verdict.paid()));
            }
            case UNPAID -> messages.send(event.getPlayer(), "invsnap.insurance.unpaid." + reason(verdict),
                    "price", Fees.format(verdict.paid() == null ? Money.ZERO : verdict.paid()));
            case NOT_APPLICABLE -> {
            }
        }
    }

    private static String reason(InsuranceService.Verdict verdict) {
        return switch (verdict.refusal()) {
            case NOT_ENOUGH -> "not-enough";
            case UNAVAILABLE -> "no-economy";
            default -> "refused";
        };
    }

    @Override
    public void forget(UUID player) {
        // Nothing remembered: the opt-in lives in the store, not here.
    }

    @Override
    public String describe() {
        return "death insurance: an insured player who pays the premium keeps their inventory";
    }
}
