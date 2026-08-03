package de.raindancer.modules.moderation.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.moderation.model.Report;
import de.raindancer.modules.moderation.model.ReportState;
import org.bukkit.configuration.ConfigurationSection;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The reports, on disk.
 *
 * <h2>One file, written whole</h2>
 * Unlike claims, which get a file each: there are tens of reports rather than hundreds of claims, they
 * change in bursts rather than continuously, and the queue is read as a whole every time anybody looks
 * at it. A file per report would be a directory of thousands of two-line files within a year.
 *
 * <h2>The write itself is Core's</h2>
 * {@link YamlStore} owns the write-to-a-temporary-then-move dance, so a server killed mid-save has
 * either the old file or the new one and never half of each. That was written seven times inside
 * RainsCore before it was written once; this module does not make it eight.
 */
public final class ReportStorage {

    private static final LogChannel log = Log.of("moderation");

    /** The file this version writes. Branch on it rather than guessing when the shape changes. */
    public static final int DATA_VERSION = 1;

    private final YamlStore store;

    public ReportStorage(Path dataFolder) {
        this.store = new YamlStore(dataFolder.resolve("reports.yml"));
    }

    /** Where they are kept, for a diagnostic and for a test that wants to break the file. */
    public Path file() {
        return store.file();
    }

    /**
     * Everything on disk.
     *
     * <p>An entry that will not read is skipped and named, and the rest still load. One report with a
     * mangled id must not cost the server its other forty — and a moderation queue that refuses to
     * load at all is one nobody notices is empty until a player asks what happened to their report.
     */
    public List<Report> load() {
        ConfigurationSection root = store.read().getConfigurationSection("reports");
        List<Report> reports = new ArrayList<>();
        if (root == null) {
            return reports;
        }
        List<String> unreadable = new ArrayList<>();
        for (String id : root.getKeys(false)) {
            ConfigurationSection entry = root.getConfigurationSection(id);
            if (entry == null) {
                unreadable.add(id);
                continue;
            }
            try {
                reports.add(read(id, entry));
            } catch (RuntimeException broken) {
                unreadable.add(id);
            }
        }
        if (!unreadable.isEmpty()) {
            log.error("{} report(s) could not be read and have been skipped: {}. The file was left "
                    + "untouched.", unreadable.size(), String.join(", ", unreadable));
        }
        return reports;
    }

    /** Writes the lot. @return whether it reached the disk */
    public boolean saveAll(Collection<Report> reports) {
        return store.write(yaml -> {
            yaml.set("version", DATA_VERSION);
            if (reports == null) {
                return;
            }
            for (Report report : reports) {
                String at = "reports." + report.id();
                yaml.set(at + ".reporter", asText(report.reporter()));
                yaml.set(at + ".reporter-name", report.reporterName());
                yaml.set(at + ".subject", report.subject().toString());
                yaml.set(at + ".subject-name", report.subjectName());
                yaml.set(at + ".text", report.text());
                yaml.set(at + ".at", report.at().toString());
                yaml.set(at + ".state", report.state().name().toLowerCase(Locale.ROOT));
                yaml.set(at + ".handler", asText(report.handler()));
                yaml.set(at + ".handler-name", report.handlerName());
                yaml.set(at + ".outcome", report.outcome());
                yaml.set(at + ".closed-at", report.closedAt() == null ? null
                        : report.closedAt().toString());
            }
        });
    }

    private static Report read(String id, ConfigurationSection entry) {
        return new Report(id,
                uuid(entry.getString("reporter")),
                entry.getString("reporter-name"),
                requiredUuid(entry.getString("subject")),
                entry.getString("subject-name"),
                entry.getString("text"),
                instant(entry.getString("at")),
                state(entry.getString("state")),
                uuid(entry.getString("handler")),
                entry.getString("handler-name"),
                entry.getString("outcome"),
                optionalInstant(entry.getString("closed-at")));
    }

    private static String asText(UUID id) {
        return id == null ? null : id.toString();
    }

    /** A uuid that may be absent — the console files reports too. A mangled one is not absent. */
    private static UUID uuid(String text) {
        return text == null || text.isBlank() ? null : UUID.fromString(text);
    }

    private static UUID requiredUuid(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("a report with no subject");
        }
        return UUID.fromString(text);
    }

    private static Instant instant(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("a report with no time on it");
        }
        return Instant.parse(text);
    }

    private static Instant optionalInstant(String text) {
        return text == null || text.isBlank() ? null : Instant.parse(text);
    }

    private static ReportState state(String text) {
        if (text == null || text.isBlank()) {
            // An entry whose state is missing is treated as waiting rather than as dealt with: the
            // cost of showing a moderator something already handled is a wasted minute, and the cost
            // of the other mistake is a report nobody ever sees.
            return ReportState.OPEN;
        }
        return ReportState.valueOf(text.trim().toUpperCase(Locale.ROOT));
    }
}
