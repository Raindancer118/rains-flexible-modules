package de.raindancer.modules.moderation.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.modules.moderation.model.Report;
import de.raindancer.modules.moderation.model.ReportState;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * The reports, in the network's database.
 *
 * <h2>One document, changed entry by entry</h2>
 * Unlike claims, which get a document each: there are tens of reports rather than hundreds of claims, they
 * change in bursts rather than continuously, and the queue is read as a whole every time anybody looks
 * at it. Only the reports this server changed are written, so another server's changes to the others are
 * never put back to what this server last saw.
 *
 * <h2>The write itself is Core's</h2>
 * {@link YamlStore} owns the atomic replace and the merge with other servers' writes; this module does
 * not make it eight.
 */
public final class ReportStorage {

    private static final LogChannel log = Log.of("moderation");

    /** The file this version writes. Branch on it rather than guessing when the shape changes. */
    public static final int DATA_VERSION = 1;

    private final YamlStore store;
    private final DirtyKeys dirty = new DirtyKeys();
    private ReportRegistry registry;
    private NetworkBus.Subscription following = () -> { };

    /** The network's reports: every server files into, and reads, the same queue. */
    public ReportStorage(Path dataFolder) {
        this(YamlStore.network(dataFolder.resolve("reports.yml")));
    }

    ReportStorage(YamlStore store) {
        this.store = store;
    }

    /**
     * Keeps {@code registry} the queue the database holds: what this server changed is written alone, and
     * when another server changes the queue this server's changes go out first and theirs come in.
     */
    public synchronized void bind(ReportRegistry registry) {
        following.close();
        this.registry = registry;
        this.following = store.onChange(this::refresh);
    }

    public void close() {
        following.close();
    }

    /** A report this server changed; written with the next {@link #flush()}. */
    public synchronized void put(Report report) {
        registry.add(report);
        dirty.mark(report.id());
    }

    /**
     * Files a new one under the next free id. The id is taken in the database in the same step the report is
     * written, so two servers filing at once never both get R7; only when the database cannot be reached
     * is it numbered here, and written when it can.
     */
    public Report admit(Function<String, Report> build) {
        AtomicReference<Report> made = new AtomicReference<>();
        long above = registry.highest();
        boolean written = store.update(yaml -> {
            long next = Math.max(above, NumberedIds.highestIn(yaml.getConfigurationSection("reports"), ReportRegistry.PREFIX)) + 1;
            Report report = build.apply(ReportRegistry.PREFIX + next);
            write(yaml, report);
            made.set(report);
        });
        synchronized (this) {
            if (written) {
                registry.add(made.get());
                return made.get();
            }
            Report offline = build.apply(registry.nextId());
            put(offline);
            return offline;
        }
    }

    /** Writes what this server changed. @return whether it reached the database */
    public synchronized boolean flush() {
        return dirty.write(store, (yaml, id) -> {
            registry.byId(id).ifPresent(report -> write(yaml, report));
            yaml.set("version", DATA_VERSION);
        });
    }

    /** Writes what is changed, then takes the queue as the database holds it. */
    public synchronized void refresh() {
        if (flush()) {
            loadIfReadable().ifPresent(stored -> registry.replaceWith(stored, dirty::has));
        }
    }

    /** Where they are kept, for a diagnostic and for a test that wants to break the file. */
    public Path file() {
        return store.file();
    }

    /**
     * Everything stored.
     *
     * <p>An entry that will not read is skipped and named, and the rest still load. One report with a
     * mangled id must not cost the server its other forty — and a moderation queue that refuses to
     * load at all is one nobody notices is empty until a player asks what happened to their report.
     */
    public List<Report> load() {
        return loadIfReadable().orElseGet(ArrayList::new);
    }

    /** Empty when the document could not be read, which is not the same as having no reports. */
    public Optional<List<Report>> loadIfReadable() {
        return store.readIfReadable().map(this::parse);
    }

    private List<Report> parse(YamlConfiguration yaml) {
        ConfigurationSection root = yaml.getConfigurationSection("reports");
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

    /** Writes these, leaving every other report as it is. @return whether it reached the database */
    public boolean saveAll(Collection<Report> reports) {
        return store.update(yaml -> {
            yaml.set("version", DATA_VERSION);
            if (reports != null) {
                reports.forEach(report -> write(yaml, report));
            }
        });
    }

    private static void write(YamlConfiguration yaml, Report report) {
        String at = "reports." + report.id();
        yaml.set(at, null);
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
        yaml.set(at + ".closed-at", report.closedAt() == null ? null : report.closedAt().toString());
        yaml.set(at + ".preset", report.preset());
        place(yaml, at + ".reporter-at", report.reporterAt());
        place(yaml, at + ".subject-at", report.subjectAt());
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
                optionalInstant(entry.getString("closed-at")),
                entry.getString("preset"),
                place(entry.getConfigurationSection("reporter-at")),
                place(entry.getConfigurationSection("subject-at")));
    }

    private static void place(org.bukkit.configuration.ConfigurationSection yaml, String path,
                              de.raindancer.modules.moderation.model.ReportPlace where) {
        if (where == null) {
            return;
        }
        yaml.set(path + ".world", where.world());
        yaml.set(path + ".x", where.x());
        yaml.set(path + ".y", where.y());
        yaml.set(path + ".z", where.z());
    }

    /** Absent in reports from before places were kept; a broken one costs the place, not the report. */
    private static de.raindancer.modules.moderation.model.ReportPlace place(ConfigurationSection section) {
        if (section == null || section.getString("world") == null) {
            return null;
        }
        return new de.raindancer.modules.moderation.model.ReportPlace(section.getString("world"),
                section.getInt("x"), section.getInt("y"), section.getInt("z"));
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
