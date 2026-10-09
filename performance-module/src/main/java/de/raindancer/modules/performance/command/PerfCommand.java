package de.raindancer.modules.performance.command;

import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.performance.PerformanceServices;
import de.raindancer.modules.performance.model.Attribution;
import de.raindancer.modules.performance.model.Finding;
import de.raindancer.modules.performance.model.Fix;
import de.raindancer.modules.performance.model.Report;
import de.raindancer.modules.performance.model.TickWindow;
import de.raindancer.modules.performance.rules.LagRule;
import de.raindancer.modules.performance.service.Notifier;
import de.raindancer.modules.performance.util.PermissionNodes;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code /perf} — how the server is doing; {@code report} looks now; {@code reports} lists the last;
 * {@code show <n>}; {@code fix <n> <finding>|distance}; {@code undo}; {@code tp <n> <finding>};
 * {@code alerts} switches reports in chat off and on for yourself.
 */
public final class PerfCommand implements BasicCommand {

    private final Supplier<PerformanceServices> services;

    public PerfCommand(Supplier<PerformanceServices> services) {
        this.services = services;
    }

    @Override
    public void execute(CommandSourceStack source, String[] args) {
        PerformanceServices live = services.get();
        CommandSender sender = source.getSender();
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        if (Scheduling.isFolia() && !sub.equals("alerts")) {
            live.messages().send(sender, "performance.folia");
            return;
        }
        switch (sub) {
            case "status" -> status(live, sender);
            case "report" -> report(live, sender);
            case "reports" -> list(live, sender);
            case "show" -> withReport(live, sender, args, 1, report -> live.notifier().show(sender, report));
            case "fix" -> fix(live, sender, args);
            case "undo" -> undo(live, sender);
            case "tp" -> teleport(live, sender, args);
            case "alerts" -> alerts(live, sender);
            default -> live.messages().send(sender, "performance.usage");
        }
    }

    private void status(PerformanceServices live, CommandSender sender) {
        TickWindow window = live.window();
        LagRule.State state = new LagRule(live.settings().get().strainedMs(), live.settings().get().spikeMs()).judge(window);
        live.messages().send(sender, "performance.status", "tps", String.format(Locale.ROOT, "%.1f", window.tps()),
                "mean", String.format(Locale.ROOT, "%.1f", window.mean()),
                "p95", String.format(Locale.ROOT, "%.1f", window.percentile95()),
                "worst", String.format(Locale.ROOT, "%.0f", window.worst()),
                "state", state.name().toLowerCase(Locale.ROOT));
        List<Attribution.Share> ranked = live.samples().all().ranked();
        for (Attribution.Share share : ranked.subList(0, Math.min(5, ranked.size()))) {
            live.messages().sendPlain(sender, "performance.report-cause",
                    "percent", String.format(Locale.ROOT, "%.0f", share.percent()), "cause", share.cause().readable());
        }
    }

    private void report(PerformanceServices live, CommandSender sender) {
        if (!sender.hasPermission(PermissionNodes.INSPECT)) {
            live.messages().send(sender, "performance.not-allowed");
            return;
        }
        live.messages().send(sender, "performance.looking");
        Scheduling.global(live.plugin(), () -> {
            PerformanceServices now = services.get();
            LagRule.State state = new LagRule(now.settings().get().strainedMs(), now.settings().get().spikeMs())
                    .judge(now.window());
            List<Finding> findings = now.diagnosis().findings();
            Report report = now.diagnosis().report("asked for by " + sender.getName(), state, now.samples().all(), findings);
            Scheduling.async(now.plugin(), () -> now.reports().add(report));
            reply(now, sender, () -> now.notifier().show(sender, report));
        });
    }

    private void list(PerformanceServices live, CommandSender sender) {
        List<Report> recent = live.reports().recent();
        if (recent.isEmpty()) {
            live.messages().send(sender, "performance.no-reports");
            return;
        }
        for (Report report : recent.subList(0, Math.min(10, recent.size()))) {
            live.messages().sendPlain(sender, "performance.reports-line", "number", report.number(),
                    "trigger", report.trigger(), "findings", report.findings().size(),
                    "tps", String.format(Locale.ROOT, "%.1f", report.tps()));
        }
    }

    private void fix(PerformanceServices live, CommandSender sender, String[] args) {
        if (!sender.hasPermission(PermissionNodes.FIX)) {
            live.messages().send(sender, "performance.not-allowed");
            return;
        }
        withReport(live, sender, args, 1, report -> {
            String which = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "";
            if (which.equals("distance")) {
                Optional<Fix> distance = report.advice().stream()
                        .filter(fix -> fix.action() == Fix.Action.SIMULATION_DISTANCE).findFirst();
                if (distance.isEmpty()) {
                    live.messages().send(sender, "performance.nothing-to-fix");
                    return;
                }
                live.fixes().simulationDistance(distance.get().amount(), sender);
                live.messages().send(sender, "performance.fixed-distance", "to", distance.get().amount());
                return;
            }
            Optional<Finding> finding = parse(which).flatMap(report::finding);
            if (finding.isEmpty()) {
                live.messages().send(sender, "performance.no-such-finding", "number", report.number());
                return;
            }
            World world = live.diagnosis().worldOf(finding.get().where().world());
            if (world == null) {
                live.messages().send(sender, "performance.fix-unloaded");
                return;
            }
            live.fixes().apply(finding.get(), world, sender, result -> reply(services.get(), sender, () ->
                    services.get().messages().send(sender, (String) result[0],
                            java.util.Arrays.copyOfRange(result, 1, result.length))));
        });
    }

    private void undo(PerformanceServices live, CommandSender sender) {
        if (!sender.hasPermission(PermissionNodes.FIX)) {
            live.messages().send(sender, "performance.not-allowed");
            return;
        }
        live.messages().send(sender, live.fixes().undoDistance(sender) ? "performance.undone" : "performance.nothing-to-undo");
    }

    private void teleport(PerformanceServices live, CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "performance.only-a-player");
            return;
        }
        withReport(live, sender, args, 1, report -> {
            Optional<Finding> finding = parse(args.length >= 3 ? args[2] : "").flatMap(report::finding);
            World world = finding.map(found -> live.diagnosis().worldOf(found.where().world())).orElse(null);
            if (finding.isEmpty() || world == null) {
                live.messages().send(sender, "performance.no-such-finding", "number", report.number());
                return;
            }
            double[] spot = finding.get().where().spotOf(finding.get().what());
            player.teleportAsync(new Location(world, spot[0], spot[1] + 1, spot[2]));
        });
    }

    private void alerts(PerformanceServices live, CommandSender sender) {
        if (!(sender instanceof Player player)) {
            live.messages().send(sender, "performance.only-a-player");
            return;
        }
        boolean on = Notifier.ALERTS.toggle(player);
        live.messages().send(sender, on ? "performance.alerts-on" : "performance.alerts-off");
    }

    private static void withReport(PerformanceServices live, CommandSender sender, String[] args, int at,
                                   java.util.function.Consumer<Report> then) {
        Optional<Report> report = parse(args.length > at ? args[at] : "").flatMap(live.reports()::get);
        if (report.isEmpty()) {
            live.messages().send(sender, "performance.no-such-report");
            return;
        }
        then.accept(report.get());
    }

    private static Optional<Integer> parse(String number) {
        try {
            return Optional.of(Integer.parseInt(number.replace("#", "")));
        } catch (NumberFormatException notANumber) {
            return Optional.empty();
        }
    }

    /** Back to whoever asked, on their own thread — a player is ticked by their region, the console by the global one. */
    private static void reply(PerformanceServices live, CommandSender sender, Runnable say) {
        if (sender instanceof Player player) {
            Scheduling.entity(live.plugin(), player, say);
        } else {
            Scheduling.global(live.plugin(), say);
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack source, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length <= 1) {
            options.addAll(List.of("status", "report", "reports", "show", "fix", "undo", "tp", "alerts"));
        } else if (args.length == 2 && List.of("show", "fix", "tp").contains(args[0].toLowerCase(Locale.ROOT))) {
            services.get().reports().recent().forEach(report -> options.add(String.valueOf(report.number())));
        }
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.startsWith(typed)).toList();
    }

    @Override
    public String permission() {
        return PermissionNodes.INSPECT;
    }
}
