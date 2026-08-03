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
import de.raindancer.modules.moderation.store.ReportStorage;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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

    /** Set by every change, cleared by a save. Stops the timer writing an unchanged file every minute. */
    private final AtomicBoolean dirty = new AtomicBoolean();

    private volatile ModerationSettings settings;

    public ReportService(Plugin plugin, Server server, ReportRegistry reports, ReportStorage storage,
                         Audit audit, Messages messages, Chat chat, PendingNotices pending,
                         Supplier<ReportRule> rule,
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
        settings(settings);
    }

    /** Reads what is on disk into the registry. Called once, when the module starts. */
    public void load() {
        reports.clear();
        for (Report report : storage.load()) {
            reports.add(report);
        }
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
        if (mayFile(reporter, subject, text).isRefused()) {
            return Optional.empty();
        }
        Report filed = Report.filed(reports.nextId(), reporter, reporterName, subject, subjectName,
                text, Instant.now());
        reports.add(filed);
        changed();

        record("report-filed", reporter, reporterName, subject, subjectName, filed.id(), text);
        if (settings.notifyStaffOnReport()) {
            tellTheStaff("<white><reporter></white> reported <white><subject></white> "
                            + "<gray>(<id>)</gray><gray>: <text>",
                    Chat.arg("reporter", reporterName),
                    Chat.arg("subject", subjectName),
                    Chat.arg("id", filed.id()),
                    Chat.arg("text", text));
        }
        return Optional.of(filed);
    }

    /** Somebody is on it. @return whether there was an open report to pick up */
    public boolean claim(String id, UUID who, String name) {
        Optional<Report> found = reports.byId(id);
        if (found.isEmpty() || found.get().isClosed()) {
            return false;
        }
        Report claimed = found.get().claimedBy(who, name);
        reports.add(claimed);
        changed();
        record("report-claimed", who, name, claimed.subject(), claimed.subjectName(), id, null);
        tellTheStaff("<white><handler></white> has picked up <gray><id></gray>",
                Chat.arg("handler", name == null ? "the console" : name), Chat.arg("id", id));
        return true;
    }

    /** Handed back to the queue. @return whether there was a claimed report to hand back */
    public boolean release(String id) {
        Optional<Report> found = reports.byId(id);
        if (found.isEmpty() || found.get().isClosed()) {
            return false;
        }
        reports.add(found.get().released());
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
        reports.add(closed);
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

    /** Marks the queue as needing writing, and asks for it off the server thread. */
    public void changed() {
        dirty.set(true);
        Scheduling.async(plugin, this::flush);
    }

    /**
     * Writes if anything has changed.
     *
     * <p><b>Synchronised</b>, so two flushes cannot overlap. Without it the ordering of two writes is
     * whatever the scheduler decides: an older snapshot can finish after a newer one and put the file
     * back to what it said a moment ago. {@link de.raindancer.core.data.store.YamlStore} makes each
     * write atomic, which is a different promise from making two writes happen in order.
     *
     * <p>The flag is cleared before the snapshot rather than after: a change arriving during the write
     * then re-marks it and is picked up by the next pass, whereas clearing afterwards would swallow it.
     */
    public synchronized boolean flush() {
        if (!dirty.getAndSet(false)) {
            return false;
        }
        if (!storage.saveAll(reports.snapshot())) {
            dirty.set(true);    // it did not reach the disk; try again on the next pass
            return false;
        }
        return true;
    }

    /** Writes whatever is held, changed or not. For a shutdown, which has no next pass. */
    public synchronized boolean flushNow() {
        dirty.set(false);
        return storage.saveAll(reports.snapshot());
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
     * Puts a line in front of whoever may read reports.
     *
     * <p>Scheduled onto the global region: a report can arrive from a chat-triggered path, and reading
     * the online player list and asking each one for a permission is main-thread work. On Folia there
     * is no single main thread, and the global region is where a server-wide list is safe to touch.
     */
    private void tellTheStaff(String line, net.kyori.adventure.text.minimessage.tag.resolver.TagResolver...
            arguments) {
        Scheduling.global(plugin, () -> {
            List<Player> staff = new java.util.ArrayList<>();
            for (Player who : server.getOnlinePlayers()) {
                if (who.hasPermission(ModerationPermission.REPORTS.node())) {
                    staff.add(who);
                }
            }
            if (!staff.isEmpty()) {
                chat.broadcast(staff, line, arguments);
            }
        });
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
