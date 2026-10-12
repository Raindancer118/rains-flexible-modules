package de.raindancer.modules.ranks.store;

import de.raindancer.core.data.store.ShippedEntries;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.ui.text.Text;
import de.raindancer.modules.ranks.model.Rank;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The ranks there are: {@code ranks.yml}, which the owner edits by hand — written out the first time, after that a
 * newer version's ranks are merged in without undoing the owner's edits — and on top of it {@code custom-ranks.yml},
 * what staff made, changed or deleted in game. The overlay keeps the owner's file (and its comments) untouched.
 */
public final class RankCatalogue {

    /** How long a tag may be as shown. Above a head the whole name has 32 characters. */
    public static final int LONGEST_TAG = 20;

    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");

    private final YamlStore store;
    private final Supplier<InputStream> shipped;
    private final YamlStore custom;
    private volatile Map<String, Rank> ranks = Map.of();
    private volatile List<String> problems = List.of();
    private volatile ShippedEntries.Merged lastMerge;

    public RankCatalogue(YamlStore store, Supplier<InputStream> shipped) {
        this(store, shipped, null);
    }

    /** @param custom the in-game overlay; null for none (nothing can then be made in game) */
    public RankCatalogue(YamlStore store, Supplier<InputStream> shipped, YamlStore custom) {
        this.store = store;
        this.shipped = shipped;
        this.custom = custom;
    }

    /** Reads the file again. @return how many ranks it holds */
    public int reload() {
        lastMerge = ShippedEntries.bringUp(store.file(), shipped, "ranks", Set.of());
        List<String> found = new ArrayList<>(store.problems());
        Map<String, Rank> read = new LinkedHashMap<>(parse(store.read(), found));
        if (custom != null) {
            YamlConfiguration overlay = custom.read();
            custom.problems().forEach(problem -> found.add("custom-ranks.yml: " + problem));
            parse(overlay, found).forEach(read::put);
            overlay.getStringList("deleted").forEach(read::remove);
        }
        ranks = Map.copyOf(read);
        problems = List.copyOf(found);
        return ranks.size();
    }

    /** Makes or replaces a rank in game. @return false when it could not be saved, or there is no overlay */
    public synchronized boolean define(Rank rank) {
        if (custom == null || tagProblem(rank.tag()).isPresent() || !ID.matcher(rank.id()).matches()) {
            return false;
        }
        boolean saved = custom.update(yaml -> {
            String path = "ranks." + rank.id();
            yaml.set(path + ".title", rank.title());
            yaml.set(path + ".tag", rank.tag());
            yaml.set(path + ".weight", rank.weight());
            yaml.set(path + ".description", rank.description());
            yaml.set(path + ".team", rank.team());
            List<String> deleted = new ArrayList<>(yaml.getStringList("deleted"));
            deleted.remove(rank.id());
            yaml.set("deleted", deleted);
        });
        if (saved) {
            reload();
        }
        return saved;
    }

    /** Deletes a rank in game — one from ranks.yml as well. @return false when there was none or it was not saved */
    public synchronized boolean remove(String id) {
        if (custom == null || id == null || !ranks.containsKey(id.toLowerCase(Locale.ROOT))) {
            return false;
        }
        String gone = id.toLowerCase(Locale.ROOT);
        boolean saved = custom.update(yaml -> {
            yaml.set("ranks." + gone, null);
            List<String> deleted = new ArrayList<>(yaml.getStringList("deleted"));
            if (!deleted.contains(gone)) {
                deleted.add(gone);
            }
            yaml.set("deleted", deleted);
        });
        if (saved) {
            reload();
        }
        return saved;
    }

    /** Whether an id may name a rank. */
    public static boolean isId(String id) {
        return id != null && ID.matcher(id).matches();
    }

    /** By id. */
    public Map<String, Rank> byId() {
        return ranks;
    }

    /** Most senior first. */
    public List<Rank> all() {
        return ranks.values().stream().sorted(Comparator.comparingInt(Rank::weight).reversed()
                .thenComparing(Rank::id)).toList();
    }

    public Optional<Rank> find(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(ranks.get(id.toLowerCase(Locale.ROOT)));
    }

    public List<String> problems() {
        return problems;
    }

    public List<String> merged() {
        ShippedEntries.Merged merge = lastMerge;
        if (merge == null) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        merge.added().forEach(id -> lines.add("added " + id));
        merge.filled().forEach(field -> lines.add("filled in " + field));
        return lines;
    }

    static Map<String, Rank> parse(YamlConfiguration yaml, List<String> problems) {
        Map<String, Rank> read = new LinkedHashMap<>();
        ConfigurationSection all = yaml.getConfigurationSection("ranks");
        if (all == null) {
            return Map.of();
        }
        for (String key : all.getKeys(false)) {
            String id = key.toLowerCase(Locale.ROOT);
            ConfigurationSection each = all.getConfigurationSection(key);
            if (each == null || !ID.matcher(id).matches()) {
                problems.add("'" + key + "' is not a rank: ids are lowercase letters, digits, - and _.");
                continue;
            }
            String tag = each.getString("tag", "");
            Optional<String> wrong = tagProblem(tag);
            if (wrong.isPresent()) {
                problems.add(id + ": " + wrong.get());
                continue;
            }
            int weight = each.getInt("weight", 1);
            if (weight < 1 || weight > 99) {
                problems.add(id + ": weight " + weight + " is outside 1–99, so it counts as " + Math.clamp(weight, 1, 99) + ".");
                weight = Math.clamp(weight, 1, 99);
            }
            String title = each.getString("title", "");
            read.put(id, new Rank(id, title.isBlank() ? id : title, tag, weight, each.getStringList("description"),
                    each.getBoolean("team", false)));
        }
        return Map.copyOf(read);
    }

    /** Why a tag cannot be used, or empty when it can. */
    public static Optional<String> tagProblem(String tag) {
        if (tag == null || tag.isBlank()) {
            return Optional.of("it has no tag.");
        }
        Component parsed;
        try {
            parsed = Text.styleOnly().deserialize(tag);
        } catch (RuntimeException broken) {
            return Optional.of("the tag is not markup that can be read.");
        }
        String plain = Text.plain(parsed);
        if (plain.matches(".*</?[a-zA-Z_][^<>]*>.*")) {
            return Optional.of("the tag has a tag nothing recognises: " + plain);
        }
        if (plain.isBlank()) {
            return Optional.of("the tag shows nothing.");
        }
        if (plain.length() > LONGEST_TAG) {
            return Optional.of("the tag is " + plain.length() + " characters; " + LONGEST_TAG + " at most.");
        }
        if (hasClick(parsed)) {
            return Optional.of("a tag cannot be a button.");
        }
        return Optional.empty();
    }

    private static boolean hasClick(Component component) {
        ClickEvent click = component.clickEvent();
        return click != null || component.children().stream().anyMatch(RankCatalogue::hasClick);
    }
}
