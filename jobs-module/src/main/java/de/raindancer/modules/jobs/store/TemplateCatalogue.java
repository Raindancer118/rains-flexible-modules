package de.raindancer.modules.jobs.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.ui.choose.Catalogue;
import de.raindancer.core.ui.choose.ItemSelection;
import de.raindancer.modules.jobs.model.GoalKind;
import de.raindancer.modules.jobs.model.GoalTemplate;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** The kinds of goal the board can put up, from jobs.yml — written out once and never again. */
public final class TemplateCatalogue {

    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");

    private final YamlStore store;
    private final Supplier<InputStream> shipped;
    private volatile List<GoalTemplate> templates = List.of();

    public TemplateCatalogue(YamlStore store, Supplier<InputStream> shipped) {
        this.store = store;
        this.shipped = shipped;
    }

    public int reload() {
        if (!store.exists()) {
            try (InputStream in = shipped.get()) {
                if (in != null) {
                    Files.createDirectories(store.file().getParent());
                    Files.writeString(store.file(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            } catch (IOException failed) {
                // Read below as no goals; the board says so.
            }
        }
        templates = parse(store.read());
        return templates.size();
    }

    public List<GoalTemplate> all() {
        return templates;
    }

    public Optional<GoalTemplate> find(String id) {
        return id == null ? Optional.empty()
                : templates.stream().filter(each -> each.id().equals(id.toLowerCase(Locale.ROOT))).findFirst();
    }

    public List<String> problems() {
        return store.problems();
    }

    static List<GoalTemplate> parse(YamlConfiguration yaml) {
        List<GoalTemplate> read = new ArrayList<>();
        ConfigurationSection all = yaml.getConfigurationSection("goals");
        if (all == null) {
            return read;
        }
        for (String id : all.getKeys(false)) {
            ConfigurationSection section = all.getConfigurationSection(id);
            if (section == null || !ID.matcher(id).matches()) {
                continue;
            }
            GoalKind kind;
            try {
                kind = GoalKind.valueOf(section.getString("kind", "deliver").toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                continue;
            }
            ItemSelection items = ItemSelection.parse(section.getStringList("items"));
            if (items.isEmpty()) {
                continue;
            }
            int start = Math.max(1, section.getInt("start", 100));
            int least = Math.clamp(section.getInt("least", Math.max(1, start / 5)), 1, start);
            int most = Math.max(start, section.getInt("most", start * 20));
            read.add(new GoalTemplate(id,
                    section.getString("title", Catalogue.readable(id.replace('-', '_').toUpperCase(Locale.ROOT))),
                    section.getString("icon", kind == GoalKind.FISH ? "fishing_rod" : "chest").toUpperCase(Locale.ROOT),
                    kind, items, start, least, most, Math.clamp(section.getInt("days", 5), 1, 30)));
        }
        return read;
    }
}
