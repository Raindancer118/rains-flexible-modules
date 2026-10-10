package de.raindancer.modules.jobs.command;

import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.modules.jobs.JobsServices;
import de.raindancer.modules.jobs.screen.OrderScreens;
import de.raindancer.modules.jobs.screen.QuestMenu;
import de.raindancer.modules.jobs.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/** {@code /quests} opens today's quests; {@code ask [amount]} and {@code cancel} for orders. Staff: {@code give <player> <quest>}, {@code reset <player>}, {@code reload}. */
public final class QuestsCommand implements IJobsCommand {

    private final Supplier<JobsServices> services;

    public QuestsCommand(Supplier<JobsServices> services) {
        this.services = services;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        JobsServices live = services.get();
        CommandSender sender = source.getSender();
        String first = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (first.isEmpty()) {
            if (!(sender instanceof Player player)) {
                live.messages().send(sender, "jobs.only-a-player");
            } else if (!live.quests().settings().enabled()) {
                live.messages().send(sender, "jobs.quest.switched-off");
            } else {
                new QuestMenu(live, player, null).open();
            }
            return;
        }
        if (first.equals("ask") || first.equals("cancel")) {
            if (!(sender instanceof Player player)) {
                live.messages().send(sender, "jobs.only-a-player");
            } else if (first.equals("cancel")) {
                live.messages().send(player, live.orders().cancel(player) ? "jobs.order.cancelled" : "jobs.order.not-running");
            } else if (args.length < 2) {
                OrderScreens.ask(live, player, null);
            } else {
                OrderScreens.answer(live, player, null, String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
            }
            return;
        }
        if (!sender.hasPermission(PermissionNodes.ADMIN)) {
            live.messages().send(sender, "jobs.quest.usage");
            return;
        }
        switch (first) {
            case "reset" -> {
                if (args.length < 2) {
                    live.messages().send(sender, "jobs.quest.usage-staff");
                    return;
                }
                Optional<OfflinePlayer> found = PlayerTargets.find(live.server(), sender, args[1]);
                if (found.isEmpty()) {
                    live.messages().send(sender, "jobs.quest.never-here", "player", args[1]);
                    return;
                }
                boolean done = live.quests().reset(found.get().getUniqueId());
                live.messages().send(sender, done ? "jobs.quest.reset" : "jobs.quest.not-saved",
                        "player", PlayerTargets.shownName(found.get()));
            }
            case "give" -> {
                if (args.length < 3) {
                    live.messages().send(sender, "jobs.quest.usage-staff");
                    return;
                }
                Optional<OfflinePlayer> found = PlayerTargets.find(live.server(), sender, args[1]);
                if (found.isEmpty()) {
                    live.messages().send(sender, "jobs.quest.never-here", "player", args[1]);
                    return;
                }
                String name = PlayerTargets.shownName(found.get());
                live.quests().give(found.get().getUniqueId(), args[2]).ifPresentOrElse(
                        quest -> live.messages().send(sender, "jobs.quest.given", "player", name, "quest",
                                live.quests().template(quest).map(each -> each.title()).orElse(args[2]),
                                "amount", String.valueOf(quest.amount())),
                        () -> live.messages().send(sender, "jobs.quest.not-given", "player", name, "quest", args[2]));
            }
            case "reload" -> {
                live.messages().send(sender, "jobs.quest.reloaded", "count", String.valueOf(live.quests().reload()));
                live.messages().send(sender, "jobs.order.reloaded", "count", String.valueOf(live.orders().reload()));
                de.raindancer.modules.jobs.JobsModule.rewatch(live.quests(), live.plugin());
                live.quests().problems().forEach(sender::sendMessage);
            }
            default -> live.messages().send(sender, "jobs.quest.usage-staff");
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        if (args.length <= 1) {
            options.addAll(List.of("ask", "cancel"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("ask")) {
            options.addAll(List.of("1000", "10k", "1m", "100m"));
        }
        if (!source.getSender().hasPermission(PermissionNodes.ADMIN)) {
            return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
        }
        if (args.length <= 1) {
            options.addAll(List.of("give", "reset", "reload"));
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("reset") || args[0].equalsIgnoreCase("give"))) {
            services.get().server().getOnlinePlayers().forEach(player -> options.add(player.getName()));
        } else if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            services.get().quests().templates().forEach(each -> options.add(each.id()));
        }
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
    }

    @Override
    public String permission() {
        return PermissionNodes.USE;
    }
}
