package de.raindancer.modules.moderation.command;

import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.util.Players;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;

import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /unban}, {@code /unmute}, {@code /unfreeze}.
 *
 * <h2>Lifting is not deleting</h2>
 * Core records the lifting <em>on</em> the punishment and leaves the punishment. Which is what makes a
 * second offence answerable — somebody whose first mute was lifted on appeal has still been muted once,
 * and their next one is still their second. A moderation plugin whose {@code /unban} removed the record
 * is one where every ladder starts again after every successful appeal.
 *
 * <h2>Why the same permission as handing it out</h2>
 * Somebody trusted to ban is trusted to unban. Splitting the two produces the arrangement where a
 * moderator can ban somebody and then cannot fix it themselves, which is worse in both directions.
 */
public final class LiftCommand extends StaffCommand {

    private final PunishmentKind kind;

    public LiftCommand(Supplier<ModerationServices> services, PunishmentKind kind,
                       ModerationPermission permission) {
        super(services, permission);
        this.kind = kind;
    }

    @Override
    public String describe() {
        return "ends somebody being " + kind.past() + ", leaving it on the record";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        ModerationServices moderation = services();

        if (args.length == 0) {
            moderation.messages().send(sender, "moderation.usage",
                    "usage", "/un" + kind.name().toLowerCase(Locale.ROOT) + " <player> [reason]");
            return;
        }
        Optional<OfflinePlayer> found = subject(sender, args[0]);
        if (found.isEmpty()) {
            return;
        }
        OfflinePlayer them = found.get();
        // The immunity check is deliberately still made. Lifting somebody else's punishment on an
        // account that may not be touched is the same decision as handing one out, in reverse.
        if (!mayAct(sender, them.getUniqueId())) {
            return;
        }
        // A mod may take back a day; a permanent ban is an admin's decision and undoing one is that
        // decision reversed. Asked before the lift, so the answer is a sentence rather than silence.
        if (kind == PunishmentKind.BAN) {
            boolean permanent = moderation.punishments().active(them.getUniqueId(), kind)
                    .map(one -> one.isPermanent()).orElse(false);
            var allowed = moderation.banLimitRule().mayLift(actorOf(sender), permanent);
            if (allowed.isRefused()) {
                allowed.refusal().ifPresent(key -> moderation.messages().send(sender, key));
                return;
            }
        }
        String why = reasonFrom(args, 1);

        boolean lifted = moderation.punishmentService().lift(actorOf(sender), actorNameOf(sender),
                them.getUniqueId(), Players.nameOf(them), kind, why);
        moderation.messages().send(sender,
                lifted ? "moderation.lifted" : "moderation.nothing-to-lift",
                "player", Players.nameOf(them), "what", kind.past());
    }
}
