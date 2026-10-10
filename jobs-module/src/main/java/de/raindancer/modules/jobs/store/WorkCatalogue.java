package de.raindancer.modules.jobs.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.ui.choose.Catalogue;
import de.raindancer.core.ui.choose.ItemSelection;
import de.raindancer.modules.jobs.model.QuestTask;
import de.raindancer.modules.jobs.model.Work;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** The work orders can ask for, from orders.yml — written out once and never again. */
public final class WorkCatalogue {

    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");

    private final YamlStore store;
    private final Supplier<InputStream> shipped;
    private volatile de.raindancer.core.data.store.ShippedEntries.Merged lastMerge;
    private volatile List<Work> works = List.of();

    public WorkCatalogue(YamlStore store, Supplier<InputStream> shipped) {
        this.store = store;
        this.shipped = shipped;
    }

    public int reload() {
        // Written out once; after that, what a newer version ships is merged in without undoing the owner's edits.
        lastMerge = de.raindancer.core.data.store.ShippedEntries.bringUp(store.file(), shipped, "work", java.util.Set.of());
        works = parse(store.read());
        return works.size();
    }

    public List<Work> all() {
        return works;
    }

    public Optional<Work> find(String id) {
        return id == null ? Optional.empty()
                : works.stream().filter(each -> each.id().equals(id.toLowerCase(Locale.ROOT))).findFirst();
    }

    /** What the last read added or filled in from the shipped file — for a line in the log. */
    public List<String> merged() {
        var merge = lastMerge;
        if (merge == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        merge.added().forEach(id -> lines.add("added " + id));
        merge.filled().forEach(field -> lines.add("filled in " + field));
        return lines;
    }

    public List<String> problems() {
        return store.problems();
    }

    static List<Work> parse(YamlConfiguration yaml) {
        List<Work> read = new ArrayList<>();
        ConfigurationSection all = yaml.getConfigurationSection("work");
        if (all == null) {
            return read;
        }
        for (String id : all.getKeys(false)) {
            ConfigurationSection section = all.getConfigurationSection(id);
            if (section == null || !ID.matcher(id).matches()) {
                continue;
            }
            QuestTask task;
            try {
                task = QuestTask.valueOf(section.getString("task", "").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                continue;
            }
            ItemSelection things = ItemSelection.parse(section.getStringList("things"));
            if (things.isEmpty() && task != QuestTask.TRAVEL) {
                continue;
            }
            read.add(new Work(id, section.getString("name", Catalogue.readable(id.replace('-', '_').toUpperCase(Locale.ROOT))),
                    section.getString("icon", "paper").toUpperCase(Locale.ROOT), task, things,
                    section.getString("value", "1"), section.getDouble("rate", 60), section.getDouble("hardness", 0.5),
                    section.getString("most-per-unit", "")));
        }
        return read;
    }
}
