package de.raindancer.modules.moderation.util;

import de.raindancer.core.social.economy.Fees;
import de.raindancer.core.social.economy.Money;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.FineRecord;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.moderation.service.FineService;
import org.bukkit.command.CommandSender;

import java.util.UUID;

/** What to say to a moderator after a fine, the same from the command and from the screen. */
public final class FineTalk {

    private FineTalk() {
    }

    public static void revoked(ModerationServices moderation, CommandSender to, UUID actor, String actorName,
                               FineRecord fine, String targetName) {
        FineService.RevokeStatus status = moderation.fines().revoke(actor, actorName, fine.id(), targetName);
        switch (status) {
            case DONE -> moderation.messages().send(to, "moderation.fine.revoked", "player", targetName,
                    "amount", Fees.format(Money.of(fine.paid())));
            case ALREADY, NOT_FOUND -> moderation.messages().send(to, "moderation.fine.revoke-already", "player", targetName);
            case REFUND_FAILED -> moderation.messages().send(to, "moderation.fine.revoke-failed", "player", targetName);
        }
    }

    public static void tell(Messages messages, CommandSender to, String player, FineService.Result result) {
        switch (result.status()) {
            case DONE -> messages.send(to, result.charge().debt().minor() > 0 ? "moderation.fine.done-debt"
                            : "moderation.fine.done", "player", player,
                    "amount", Fees.format(result.charge().charged()),
                    "owed", Fees.format(result.charge().debt()));
            case NO_ECONOMY -> messages.send(to, "moderation.fine.no-economy");
            case CANNOT_PAY -> messages.send(to, "moderation.fine.cannot-pay", "player", player);
            case TOO_MUCH -> messages.send(to, "moderation.fine.too-much-for-you", "detail",
                    result.detail() == null ? "" : result.detail());
            case INVALID -> messages.send(to, "moderation.fine.bad-amount", "text", "");
        }
    }
}
