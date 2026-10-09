package de.raindancer.modules.jobs.command;

import de.raindancer.modules.jobs.JobsServices;
import de.raindancer.modules.jobs.model.Goal;
import de.raindancer.modules.jobs.model.GoalTemplate;
import de.raindancer.modules.jobs.screen.JobBoardMenu;
import de.raindancer.modules.jobs.util.PermissionNodes;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/** {@code /jobs} opens the board. Staff: {@code list}, {@code end <number>}, {@code new [kind]}, {@code reload}. */
public final class JobsCommand implements IJobsCommand {

    private final Supplier<JobsServices> services;

    public JobsCommand(Supplier<JobsServices> services) {
        this.services = services;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        JobsServices live = services.get();
        CommandSender sender = source.getSender();
        String first = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (first.isEmpty()) {
            if (sender instanceof Player player) {
                new JobBoardMenu(live, player, null).open();
            } else {
                live.messages().send(sender, "jobs.only-a-player");
            }
            return;
        }
        if (!sender.hasPermission(PermissionNodes.ADMIN)) {
            live.messages().send(sender, "jobs.usage");
            return;
        }
        switch (first) {
            case "list" -> {
                for (Goal goal : live.goals().goals()) {
                    live.messages().send(sender, "jobs.list-line", "number", String.valueOf(goal.number()),
                            "goal", goal.title(), "progress", String.valueOf(goal.progress()),
                            "amount", String.valueOf(goal.amount()));
                }
            }
            case "end" -> {
                long number = args.length > 1 ? parse(args[1]) : -1;
                live.messages().send(sender, number > 0 && live.goals().end(number) ? "jobs.ended" : "jobs.no-such",
                        "number", args.length > 1 ? args[1] : "?");
            }
            case "new" -> {
                Optional<GoalTemplate> template = args.length > 1
                        ? live.goals().templates().stream().filter(each -> each.id().equalsIgnoreCase(args[1])).findFirst()
                        : live.goals().templates().stream().filter(each -> live.goals().goals().stream()
                        .noneMatch(goal -> goal.template().equals(each.id()))).findFirst();
                if (template.isEmpty()) {
                    live.messages().send(sender, "jobs.no-kind", "kind", args.length > 1 ? args[1] : "?");
                    return;
                }
                live.goals().start(template.get()).ifPresentOrElse(
                        goal -> live.messages().send(sender, "jobs.put-up", "goal", goal.title(),
                                "amount", String.valueOf(goal.amount())),
                        () -> live.messages().send(sender, "jobs.not-saved"));
            }
            case "reload" -> {
                live.messages().send(sender, "jobs.reloaded", "count", String.valueOf(live.goals().reload()));
                live.goals().problems().forEach(sender::sendMessage);
            }
            default -> live.messages().send(sender, "jobs.usage-staff");
        }
    }

    private static long parse(String text) {
        try {
            return Long.parseLong(text.replace("#", ""));
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        if (!source.getSender().hasPermission(PermissionNodes.ADMIN)) {
            return List.of();
        }
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = new ArrayList<>();
        JobsServices live = services.get();
        if (args.length <= 1) {
            options.addAll(List.of("list", "end", "new", "reload"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("end")) {
            live.goals().goals().forEach(goal -> options.add(String.valueOf(goal.number())));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("new")) {
            live.goals().templates().forEach(each -> options.add(each.id()));
        }
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(typed)).toList();
    }

    @Override
    public String permission() {
        return PermissionNodes.USE;
    }
}
