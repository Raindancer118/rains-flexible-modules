package de.raindancer.modules.moderation.service;

import de.raindancer.core.moderation.audit.Audit;
import de.raindancer.core.moderation.audit.AuditEntry;
import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.moderation.ModerationSettings;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.model.Report;
import de.raindancer.modules.moderation.rules.ReportRule;
import de.raindancer.modules.moderation.store.ReportRegistry;
import de.raindancer.modules.moderation.store.PendingNotices;
import de.raindancer.modules.moderation.util.StaffAlerts;
import de.raindancer.modules.moderation.store.ReportStorage;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * The report queue: filing one, picking it up, and closing it.
 *
 * <h2>Why filing is two calls and not one</h2>
 * {@link #mayFile} asks the rule; {@link #file} does it. Split because the answer to the first is worth
 * having on its own — the command tells the player <em>why</em> not, and a screen could grey the button
 * before it is pressed. A single method that both judged and acted would be a rule nothing could ask
 * speculatively, which is the thing {@code IModerationRule} exists to prevent.
 *
 * <h2>Writing</h2>
 * Every change marks the queue dirty and asks for a save off the server thread. Reports are small, they
 * change in bursts, and a queue that only reaches the disk on shutdown is one crash away from a player
 * asking what happened to the report they filed an hour ago.
 */
public final class ReportService implements IModerationService {

    private final Plugin plugin;
    private final Server server;
    private final ReportRegistry reports;
    private final ReportStorage storage;
    private final Audit audit;
    private final Messages messages;
    private final Chat chat;
    private final PendingNotices pending;
    private final Supplier<ReportRule> rule;
    private final StaffAlerts alerts;

    private volatile ModerationSettings settings;

    public ReportService(Plugin plugin, Server server, ReportRegistry reports, ReportStorage storage,
                         Audit audit, Messages messages, Chat chat, PendingNotices pending,
                         Supplier<ReportRule> rule, StaffAlerts alerts,
                         ModerationSettings settings) {
        this.plugin = plugin;
        this.server = server;
        this.reports = reports;
        this.storage = storage;
        this.audit = audit;
        this.messages = messages;
        this.chat = chat;
        this.pending = pending;
        this.rule = rule;
        this.alerts = alerts;
        this.storage.bind(reports);
        settings(settings);
    }

    /** Reads the queue into the registry. Called once, when the module starts. */
    public void load() {
        storage.refresh();
    }

    /** Whether this report may be filed, and why not when it may not. */
    public Verdict mayFile(UUID reporter, UUID subject, String text) {
        if (!settings.reportsEnabled()) {
            return Verdict.refused("moderation.report.switched-off");
        }
        return rule.get().mayFile(reporter, subject, text, reports.by(reporter), Instant.now());
    }

    /**
     * Files one, having already asked {@link #mayFile}.
     *
     * <p>Asks again anyway. The two calls are separated by a chat prompt or a click, and "the rule said
     * yes a moment ago" is exactly the window a player finds when they want to flood the queue.
     */
    public Optional<Report> file(UUID reporter, String reporterName, UUID subject, String subjectName,
                                 String text) {
        return file(reporter, reporterName, subject, subjectName, text, null, null, null);
    }

    /**
     * The same, filed under a preset and with where both stood — what the report card's severity, chat and
     * "go there" buttons are built from.
     */
    public Optional<Report> file(UUID reporter, String reporterName, UUID subject, String subjectName,
                                 String text, de.raindancer.modules.moderation.model.ReportPreset preset,
                                 de.raindancer.modules.moderation.model.ReportPlace reporterAt,
                                 de.raindancer.modules.moderation.model.ReportPlace subjectAt) {
        if (mayFile(reporter, subject, text).isRefused()) {
            return Optional.empty();
        }
        Instant now = Instant.now();
        Report filed = storage.admit(id -> Report.filed(id, reporter, reporterName, subject, subjectName,
                text, now).withPreset(preset == null ? null : preset.name()).withPlaces(reporterAt, subjectAt));

        record("report-filed", reporter, reporterName, subject, subjectName, filed.id(), text);
        if (settings.notifyStaffOnReport()) {
            // Excludes the subject even when they hold the reports permission themselves — a trial mod
            // reporting a mod must not out itself in the very chat line meant to tell the rest of staff.
            tellTheStaff(subject, "<white><reporter></white> reported <white><subject></white> "
                            + "<gray>(<id>)</gray><gray>: <text> <click:run_command:'/mod report <id>'>"
                            + "<hover:show_text:'<gray>Open the report card'><aqua>[Open]</aqua></hover></click>",
                    java.util.Map.of("reporter", String.valueOf(filed.reporterName()),
                            "subject", String.valueOf(subjectName), "id", filed.id(), "text", text));
        }
        return Optional.of(filed);
    }

    /**
     * Where a player stands, for a report — only when this thread may read them (on Folia an online player
     * elsewhere belongs to another region), otherwise unknown.
     */
    public static de.raindancer.modules.moderation.model.ReportPlace placeOf(org.bukkit.entity.Player player) {
        if (player == null || !org.bukkit.Bukkit.isOwnedByCurrentRegion(player)) {
            return null;
        }
        org.bukkit.Location at = player.getLocation();
        return at.getWorld() == null ? null : new de.raindancer.modules.moderation.model.ReportPlace(
                at.getWorld().getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ());
    }

    private final de.raindancer.modules.moderation.rules.AutoReportRule automatic = new de.raindancer.modules.moderation.rules.AutoReportRule();

    /**
     * Files a report a machine made, as "the console": {@code "<kind>: <what>"}. Never a second while an unfinished
     * one of the same kind about the same player exists. Handed to Core's {@code AutoReports}.
     */
    public synchronized de.raindancer.core.moderation.reports.AutoReports.Result fileAutomatic(UUID subject, String subjectName,
                                                                                                String kind, String text) {
        if (!settings.reportsEnabled()) {
            return de.raindancer.core.moderation.reports.AutoReports.Result.REFUSED;
        }
        if (automatic.alreadyWaiting(subject, kind, reports.about(subject))) {
            return de.raindancer.core.moderation.reports.AutoReports.Result.DUPLICATE;
        }
        return file(null, null, subject, subjectName, automatic.text(kind, text)).isPresent()
                ? de.raindancer.core.moderation.reports.AutoReports.Result.FILED
                : de.raindancer.core.moderation.reports.AutoReports.Result.REFUSED;
    }

    /** Somebody is on it. @return whether there was an open report to pick up */
    public boolean claim(String id, UUID who, String name) {
        Optional<Report> found = reports.byId(id);
        if (found.isEmpty() || found.get().isClosed()) {
            return false;
        }
        Report claimed = found.get().claimedBy(who, name);
        storage.put(claimed);
        changed();
        record("report-claimed", who, name, claimed.subject(), claimed.subjectName(), id, null);
        tellTheStaff(claimed.subject(), "<white><handler></white> has picked up <gray><id></gray>",
                java.util.Map.of("handler", name == null ? "the console" : name, "id", id));
        return true;
    }

    /** Handed back to the queue. @return whether there was a claimed report to hand back */
    public boolean release(String id) {
        Optional<Report> found = reports.byId(id);
        if (found.isEmpty() || found.get().isClosed()) {
            return false;
        }
        storage.put(found.get().released());
        changed();
        return true;
    }

    /** Looked at, and something was done. */
    public boolean resolve(String id, UUID who, String name, String outcome) {
        return close(id, who, name, outcome, true);
    }

    /** Looked at, and there was nothing in it. */
    public boolean reject(String id, UUID who, String name, String why) {
        return close(id, who, name, why, false);
    }

    private boolean close(String id, UUID who, String name, String said, boolean dealtWith) {
        Optional<Report> found = reports.byId(id);
        if (found.isEmpty() || found.get().isClosed()) {
            return false;
        }
        Report original = found.get();
        Report closed = dealtWith
                ? original.resolved(who, name, said, Instant.now())
                : original.rejected(who, name, said, Instant.now());
        storage.put(closed);
        changed();

        record(dealtWith ? "report-resolved" : "report-rejected", who, name, closed.subject(),
                closed.subjectName(), id, said);
        tellTheReporter(closed, dealtWith);
        return true;
    }

    /** What is waiting, for the line somebody coming on shift is shown. */
    public int waitingCount() {
        return reports.waitingCount();
    }

    /** Everything about somebody, for their page. */
    public List<Report> about(UUID subject) {
        return reports.about(subject);
    }

    // ---------------------------------------------------------------------------- writing

    /** Asks for what this server changed to be written, off the server thread. */
    public void changed() {
        Scheduling.async(plugin, storage::flush);
    }

    /**
     * Writes what this server changed.
     *
     * <p>Only the reports that changed are written, and onto what the database holds now, so a server filing
     * or closing one never puts another server's changes back. The storage serialises flushes, so an older
     * write cannot land after a newer one.
     */
    public boolean flush() {
        return storage.flush();
    }

    /** For a shutdown, which has no next pass. */
    public boolean flushNow() {
        return storage.flush();
    }

    // ---------------------------------------------------------------------------- telling people

    private void tellTheReporter(Report closed, boolean dealtWith) {
        if (!settings.tellReporterWhenClosed()) {
            return;
        }
        UUID reporter = closed.reporter();
        if (reporter == null) {
            return;
        }
        String key = dealtWith ? "moderation.report.was-dealt-with"
                : "moderation.report.was-rejected";
        Player here = server.getPlayer(reporter);
        if (here != null) {
            messages.send(here, key, "id", closed.id(), "outcome", closed.outcome());
            return;
        }
        // Kept rather than dropped. A report is usually dealt with an hour after it was filed, so the
        // reporter being gone is the ordinary case — and "nobody ever came back to me" is exactly why
        // people stop filing them.
        pending.keep(reporter, key,
                java.util.Map.of("id", closed.id(), "outcome", closed.outcome()));
    }

    /**
     * Puts a line in front of whoever may read reports, on this server and on the others.
     *
     * @param exclude never told, even if they hold the reports permission themselves — the whole point
     *                of a report is that the person it is about does not find out from it, and staff
     *                reporting staff is exactly the case a plain permission check would miss
     */
    private void tellTheStaff(UUID exclude, String line, java.util.Map<String, String> arguments) {
        alerts.tell(new StaffAlerts.Alert(ModerationPermission.REPORTS.node(), exclude, line, arguments));
    }

    /**
     * Who actually gets told, out of everybody online — separated from {@link #tellTheStaff} so the one
     * rule that matters here, "never the subject", is a plain list computation a test can call without a
     * server, rather than something only provable by reading code alongside {@code Scheduling.global}.
     */
    static List<Player> whoToTell(Collection<? extends Player> online, UUID exclude) {
        return StaffAlerts.whoToTell(online, exclude, ModerationPermission.REPORTS.node());
    }

    private void record(String action, UUID actor, String actorName, UUID subject, String subjectName,
                        String id, String detail) {
        if (!settings.auditEverything()) {
            return;
        }
        AuditEntry.Builder entry = AuditEntry.of("moderation", action)
                .by(actor, actorName)
                .to(subject, subjectName)
                .with("report", id);
        if (detail != null && !detail.isBlank()) {
            entry = entry.saying(detail);
        }
        audit.record(entry);
    }

    @Override
    public void settings(ModerationSettings settings) {
        this.settings = settings == null ? ModerationSettings.DEFAULTS : settings;
    }

    @Override
    public String describe() {
        return "the report queue: filing one, picking it up, and closing it";
    }
}
