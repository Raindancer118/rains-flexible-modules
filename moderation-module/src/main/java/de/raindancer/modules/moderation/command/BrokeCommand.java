package de.raindancer.modules.moderation.command;

import de.raindancer.core.moderation.rules.ServerRule;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.screen.RuleBreachMenu;
import de.raindancer.modules.moderation.util.Players;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /broke <player> [rule] [what happened]} — the punishment the broken rule itself names, at the rung their
 * record has reached. Just a player opens the list of rules to pick from.
 */
public final class BrokeCommand extends StaffCommand {

    public BrokeCommand(Supplier<ModerationServices> services) {
        super(services, ModerationPermission.WARN);
    }

    @Override
    public String describe() {
        return "punishes somebody for breaking one of the server's rules";
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        CommandSender sender = source.getSender();
        ModerationServices moderation = services();
        if (args.length == 0) {
            moderation.messages().send(sender, "moderation.usage", "usage", "/broke <player> [rule number] [what happened]");
            return;
        }
        Optional<OfflinePlayer> found = subject(sender, args[0]);
        if (found.isEmpty()) {
            return;
        }
        OfflinePlayer them = found.get();
        String name = Players.nameOf(them);
        if (args.length == 1) {
            if (sender instanceof Player staff) {
                new RuleBreachMenu(moderation, staff, null, them.getUniqueId(), name).open();
            } else {
                moderation.messages().send(sender, "moderation.usage", "usage", "/broke <player> <rule number> [what happened]");
            }
            return;
        }
        Optional<ServerRule> rule = number(args[1]).flatMap(number -> moderation.ruleBreaches().rules().stream()
                .filter(each -> each.number() == number).findFirst());
        if (rule.isEmpty()) {
            moderation.messages().send(sender, "moderation.rules.no-such", "number", args[1]);
            return;
        }
        moderation.ruleBreaches().breakRule(sender, actorOf(sender), sender.getName(), them.getUniqueId(), name,
                rule.get(), reasonFrom(args, 2).equals("no reason given") ? "" : reasonFrom(args, 2));
    }

    private static Optional<Integer> number(String text) {
        try {
            return Optional.of(Integer.parseInt(text));
        } catch (NumberFormatException notANumber) {
            return Optional.empty();
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (args.length == 2) {
            return services().ruleBreaches().rules().stream().map(rule -> String.valueOf(rule.number()))
                    .filter(number -> number.startsWith(args[1])).toList();
        }
        return args.length <= 1 ? super.suggest(source, args) : List.of();
    }
}
