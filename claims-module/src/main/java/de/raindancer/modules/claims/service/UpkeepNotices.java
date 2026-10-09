package de.raindancer.modules.claims.service;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.claims.ClaimServices;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;

/** Telling people about bills: the owners what they paid or missed, the admin what a bill-now came to. */
public final class UpkeepNotices {

    private UpkeepNotices() {
    }

    /** Each online owner hears about their own bill; offline owners see their arrears on the next join. */
    public static void tellOwners(ClaimServices services, List<UpkeepService.Billing> billings) {
        for (UpkeepService.Billing billing : billings) {
            Player owner = services.server().getPlayer(billing.owner());
            if (owner == null || billing.outcome() == UpkeepService.Outcome.UNAVAILABLE
                    || billing.outcome() == UpkeepService.Outcome.NOT_DUE) {
                continue;
            }
            boolean paid = billing.outcome() == UpkeepService.Outcome.PAID;
            Scheduling.entity(services.plugin(), owner, () -> {
                if (paid) {
                    services.messages().send(owner, "upkeep.paid-bill",
                            "amount", Fees.format(billing.amount()),
                            "chunks", String.valueOf(services.upkeep().chunksHeld(billing.owner())));
                } else {
                    services.messages().send(owner, "upkeep.missed", "amount", Fees.format(billing.amount()));
                }
            });
        }
    }

    /** Bills {@code owners} now, tells them, and tells {@code admin} what it came to. */
    public static void billNowAndReport(ClaimServices services, CommandSender admin, List<java.util.UUID> owners) {
        UpkeepService upkeep = services.upkeep();
        if (!upkeep.enabled()) {
            services.messages().send(admin, "upkeep.admin-off");
            return;
        }
        List<UpkeepService.Billing> billed = upkeep.billNow(owners);
        tellOwners(services, billed);
        report(services, admin, billed);
    }

    static void report(ClaimServices services, CommandSender admin, List<UpkeepService.Billing> billed) {
        if (billed.isEmpty()) {
            services.messages().send(admin, "upkeep.admin-nobody");
            return;
        }
        int paid = 0;
        int missed = 0;
        int failed = 0;
        Money took = Money.ZERO;
        Money owing = Money.ZERO;
        for (UpkeepService.Billing billing : billed) {
            switch (billing.outcome()) {
                case PAID -> {
                    paid++;
                    took = took.plus(billing.amount());
                }
                case ARREARS -> {
                    missed++;
                    owing = owing.plus(billing.amount());
                }
                case UNAVAILABLE -> failed++;
                case NOT_DUE -> {
                    // a bill of nothing, e.g. an operator paying 0%
                }
            }
        }
        services.messages().send(admin, "upkeep.admin-billed",
                "count", String.valueOf(billed.size()),
                "paid", String.valueOf(paid), "took", Fees.format(took),
                "missed", String.valueOf(missed), "owing", Fees.format(owing));
        if (failed > 0) {
            services.messages().send(admin, "upkeep.no-economy");
        }
    }
}
