package de.raindancer.modules.performance.service;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.profile.PlayerSwitch;
import de.raindancer.modules.performance.PerformanceSettings;
import de.raindancer.modules.performance.model.Attribution;
import de.raindancer.modules.performance.model.Finding;
import de.raindancer.modules.performance.model.Fix;
import de.raindancer.modules.performance.model.Report;
import de.raindancer.modules.performance.util.PermissionNodes;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.Locale;

/**
 * Tells staff about a report: a few lines in chat with buttons that run {@code /perf} — so whatever a
 * button does, the command can do too, from the console as well. The whole report goes to the console.
 */
public final class Notifier implements IPerformanceService {

    /** Staff who do not want reports in chat turn this off with {@code /perf alerts}. */
    public static final PlayerSwitch ALERTS = new PlayerSwitch("rainsperformance", "alerts", true);

    private final Plugin plugin;
    private final Server server;
    private final Messages messages;
    private final LogChannel log;

    public Notifier(Plugin plugin, Server server, Messages messages, LogChannel log) {
        this.plugin = plugin;
        this.server = server;
        this.messages = messages;
        this.log = log;
    }

    @Override
    public void settings(PerformanceSettings settings) {
        // Nothing to swap: who is told is a permission and a switch, not a setting.
    }

    /** To every member of staff who wants reports, and the console. */
    public void everyone(Report report) {
        log.warn("{}", String.join(System.lineSeparator(), report.lines()));
        for (Player staff : server.getOnlinePlayers()) {
            if (staff.hasPermission(PermissionNodes.NOTIFY) && ALERTS.isOn(staff)) {
                Scheduling.entity(plugin, staff, () -> show(staff, report));
            }
        }
    }

    /** One report, as chat lines with buttons. */
    public void show(Audience to, Report report) {
        messages.send(to, "performance.report-head", "number", report.number(), "trigger", report.trigger(),
                "tps", String.format(Locale.ROOT, "%.1f", report.tps()), "mean", String.format(Locale.ROOT, "%.1f", report.meanMs()),
                "worst", String.format(Locale.ROOT, "%.0f", report.worstMs()));
        for (Attribution.Share share : report.causes().subList(0, Math.min(3, report.causes().size()))) {
            messages.sendPlain(to, "performance.report-cause", "percent", String.format(Locale.ROOT, "%.0f", share.percent()),
                    "cause", share.cause().readable());
        }
        for (int i = 0; i < report.findings().size() && i < 5; i++) {
            Finding finding = report.findings().get(i);
            int n = i + 1;
            Component line = messages.get("performance.report-finding", "n", n, "count", finding.count(),
                    "kind", finding.what(), "world", finding.where().world(), "x", finding.where().blockX(),
                    "z", finding.where().blockZ(), "claim", finding.landName(),
                    "owners", finding.names().isEmpty() ? "-" : String.join(", ", finding.names()));
            line = line.append(Component.space()).append(button("performance.button-go",
                    "/perf tp " + report.number() + " " + n, NamedTextColor.AQUA));
            if (finding.fixes().stream().anyMatch(fix -> fix.isAction() && fix.action() != Fix.Action.TELL_OWNER)) {
                line = line.append(Component.space()).append(button("performance.button-fix",
                        "/perf fix " + report.number() + " " + n, NamedTextColor.GOLD));
            } else {
                line = line.append(Component.space()).append(button("performance.button-tell",
                        "/perf fix " + report.number() + " " + n, NamedTextColor.YELLOW));
            }
            to.sendMessage(line);
        }
        if (report.findings().size() > 5) {
            messages.sendPlain(to, "performance.report-more", "count", report.findings().size() - 5,
                    "number", report.number());
        }
        for (Fix fix : report.advice()) {
            if (fix.action() == Fix.Action.SIMULATION_DISTANCE) {
                to.sendMessage(messages.get("performance.advice-distance", "from", report.simulationDistance(),
                        "to", fix.amount()).append(Component.space()).append(button("performance.button-apply",
                        "/perf fix " + report.number() + " distance", NamedTextColor.GOLD)));
            } else if (fix.action() == Fix.Action.SUSPECT_PLUGIN) {
                messages.sendPlain(to, "performance.advice-plugin", "plugin", fix.what(), "percent", fix.amount());
            }
        }
    }

    private Component button(String key, String command, NamedTextColor color) {
        return messages.get(key).colorIfAbsent(color)
                .clickEvent(ClickEvent.runCommand(command))
                .hoverEvent(HoverEvent.showText(Component.text(command, NamedTextColor.GRAY)));
    }

    @Override
    public String describe() {
        return "tells staff about a report, with buttons";
    }
}
