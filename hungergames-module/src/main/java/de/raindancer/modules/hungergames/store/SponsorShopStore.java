package de.raindancer.modules.hungergames.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Persists the sponsor shop's offers, {@code sponsor-shop.yml}, in the compact line syntax an owner types
 * rather than a nested structure for four fields that fit on one line each.
 *
 * <h2>Why this moved out of the settings record</h2>
 * The same reason as the border's phase list: {@code sponsors.shop.items} was 32 sibling keys' odd one out
 * in the source's config — a list of records, each with its own reward shape, rather than a single value an
 * owner tunes. See {@code MODULE-LAYOUT.md}'s note on why a settings schema has no component for that.
 *
 * <h2>Why a custom-item reward needs a set handed in, rather than looking one up itself</h2>
 * {@code ITEM:FIENDFINDER:1} names a custom item by its id, and whether that id exists is a question only
 * Core's item registry can answer. This store does not hold a reference to that registry: it would make a
 * store — meant to be exercised in a test with nothing but a temp directory — depend on Core's items having
 * already been loaded from a file of their own, in a particular order, before this one can even be
 * constructed. So the known ids are a parameter of {@link #parse} and {@link #validateList} instead. The
 * caller — a service that already holds both registries — is where "does this custom item exist" and
 * "does this shop entry name one" naturally meet, and it is the only place that has to.
 *
 * <h2>Why a bad line rejects the whole list</h2>
 * The same reasoning as {@link BorderPhaseStore}: a shop with one entry silently dropped is a shop an owner
 * believes has an entry it does not, and the failure is invisible until a player asks why the item they
 * configured is not for sale. Refusing the whole file — leaving whatever was there before untouched — and
 * reporting exactly which line is wrong is what lets it be fixed in one edit instead of discovered by a
 * complaint.
 */
public final class SponsorShopStore {

    private static final LogChannel log = Log.of("hungergames");

    /** A shop entry's reward. */
    public sealed interface Reward permits MaterialReward, EffectReward, CustomItemReward, PotionReward {
    }

    public record MaterialReward(Material material, int amount) implements Reward {
    }

    public record EffectReward(String effectName, int durationSeconds, int amplifier) implements Reward {
    }

    public record CustomItemReward(String customId, int amount) implements Reward {
    }

    /** A drinkable, splash or lingering potion built around one base potion type (e.g. {@code STRONG_HEALING}). */
    public record PotionReward(PotionVariant variant, String potionType, int amount) implements Reward {
    }

    /** A potion's variant and the material it is handed out as. */
    public enum PotionVariant {
        NORMAL(Material.POTION),
        SPLASH(Material.SPLASH_POTION),
        LINGERING(Material.LINGERING_POTION);

        private final Material material;

        PotionVariant(Material material) {
            this.material = material;
        }

        public Material material() {
            return material;
        }
    }

    /**
     * One parsed shop entry. {@code enabled=false} is a {@code #}-commented line: kept, but never offered —
     * how an owner turns an item on and off without losing the line's cost and enchantments to rewrite later.
     */
    public record ShopItem(String id, Reward reward, int cost, String displayName, boolean enabled,
                            List<String> enchantments) {
        public ShopItem {
            enchantments = List.copyOf(enchantments);
        }

        public ShopItem withEnchantments(List<String> newEnchantments) {
            return new ShopItem(id, reward, cost, displayName, enabled, newEnchantments);
        }
    }

    private final YamlStore store;
    private final List<String> problems = new ArrayList<>();

    public SponsorShopStore(Path file) {
        this.store = new YamlStore(file);
    }

    /** What was wrong with the file the last time {@link #load} refused it. Empty when it loaded cleanly. */
    public List<String> problems() {
        return List.copyOf(problems);
    }

    /**
     * The configured shop, in order. An absent file is an empty shop, not an error. A file that will not
     * parse — bad YAML, or one bad line — is reported through {@link #problems()} and left untouched: this
     * never writes, so there is nothing here that could overwrite it.
     */
    public List<ShopItem> load(Set<String> knownCustomItemIds) {
        synchronized (problems) {
            problems.clear();
        }
        if (!store.exists()) {
            return List.of();
        }
        YamlConfiguration yaml = store.read();
        if (!store.problems().isEmpty()) {
            carry();
            store.quarantine();
            return List.of();
        }
        List<String> lines = yaml.getStringList("items");
        List<ShopItem> items = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (String line : lines) {
            try {
                ShopItem item = parse(line, knownCustomItemIds);
                if (!seenIds.add(item.id())) {
                    note("duplicate shop id '" + item.id() + "' — the whole shop was rejected");
                    return List.of();
                }
                items.add(item);
            } catch (IllegalArgumentException broken) {
                note("'" + line + "' could not be parsed (" + broken.getMessage()
                        + ") — the whole shop was rejected rather than offering an incomplete one");
                return List.of();
            }
        }
        return items;
    }

    /** Writes the shop, each entry serialised back into the line syntax {@link #load} reads. */
    public boolean save(List<ShopItem> items) {
        List<String> lines = items.stream().map(SponsorShopStore::serialize).toList();
        return store.write(yaml -> yaml.set("items", lines));
    }

    // ---------------------------------------------------------------------------- syntax

    /**
     * Parses one line: {@code id|reward|cost|display name}, optionally prefixed with {@code #} to disable it.
     *
     * @throws IllegalArgumentException on any syntax error
     */
    public static ShopItem parse(String line, Set<String> knownCustomItemIds) {
        String body = line.trim();
        boolean enabled = true;
        if (body.startsWith("#")) {
            enabled = false;
            body = body.substring(1).trim();
        }
        String[] parts = body.split("\\|", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException(
                    "expected 'id|reward|cost|display name' (4 fields, found " + parts.length + ")");
        }
        String id = parts[0].trim();
        if (id.isEmpty()) {
            throw new IllegalArgumentException("id must not be empty");
        }
        // Reward field: base + optional enchantments, separated by '+', e.g. DIAMOND_SWORD:1+SHARPNESS:5
        String[] rewardTokens = parts[1].trim().split("\\+");
        Reward reward = parseReward(rewardTokens[0].trim(), knownCustomItemIds);
        List<String> enchantments = new ArrayList<>();
        for (int i = 1; i < rewardTokens.length; i++) {
            enchantments.add(parseEnchant(rewardTokens[i].trim()));
        }
        int cost;
        try {
            cost = Integer.parseInt(parts[2].trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("cost \"" + parts[2].trim() + "\" is not a number");
        }
        if (cost <= 0) {
            throw new IllegalArgumentException("cost must be > 0");
        }
        String displayName = parts[3].trim();
        if (displayName.isEmpty()) {
            throw new IllegalArgumentException("display name must not be empty");
        }
        return new ShopItem(id, reward, cost, displayName, enabled, enchantments);
    }

    /** Serialises an entry back into its config line, {@code #}-prefixed when disabled. */
    public static String serialize(ShopItem item) {
        StringBuilder reward = new StringBuilder(rewardToString(item.reward()));
        for (String enchant : item.enchantments()) {
            reward.append('+').append(enchant);
        }
        String line = item.id() + "|" + reward + "|" + item.cost() + "|" + item.displayName();
        return item.enabled() ? line : "#" + line;
    }

    /** Pure syntax check, for the screen that edits the shop before it is saved. */
    public static Optional<String> validateList(List<String> lines, Set<String> knownCustomItemIds) {
        Set<String> seenIds = new HashSet<>();
        for (String line : lines) {
            try {
                ShopItem item = parse(line, knownCustomItemIds);
                if (!seenIds.add(item.id())) {
                    return Optional.of("duplicate shop id: " + item.id());
                }
            } catch (IllegalArgumentException broken) {
                return Optional.of("\"" + line + "\": " + broken.getMessage());
            }
        }
        return Optional.empty();
    }

    private static String parseEnchant(String raw) {
        String[] parts = raw.split(":", -1);
        if (parts.length != 2 || parts[0].trim().isEmpty()) {
            throw new IllegalArgumentException("an enchantment needs 'KEY:LEVEL': " + raw);
        }
        parsePositiveInt(parts[1], "enchantment level");
        return parts[0].trim().toLowerCase(Locale.ROOT) + ":" + parts[1].trim();
    }

    private static String rewardToString(Reward reward) {
        return switch (reward) {
            case MaterialReward m -> m.material().name() + ":" + m.amount();
            case CustomItemReward c -> "ITEM:" + c.customId() + ":" + c.amount();
            case EffectReward e -> "EFFECT:" + e.effectName() + ":" + e.durationSeconds() + ":" + e.amplifier();
            case PotionReward p -> "POTION:" + p.variant().name() + ":" + p.potionType() + ":" + p.amount();
        };
    }

    /**
     * One spelling of a custom item's id, so two spellings of the same item are the same item.
     *
     * <h2>The bug this exists for, which took a live config to find</h2>
     * A shop line says {@code ITEM:SMOKE_BOMB:1} — screaming snake case, because that is how the old plugin
     * wrote every item name and therefore what is in every {@code config.yml} that already exists. Core
     * registers the item as {@code smoke-bomb}, hyphenated and lower case. Compared after nothing but an
     * upper-casing, that is {@code SMOKE_BOMB} against {@code SMOKE-BOMB}, which does not match.
     *
     * <p>And a line that cannot be parsed rejects <em>the whole shop file</em> — deliberately, so nobody is
     * offered half a shop. So eight of a live server's twelve entries failing meant the other four failed
     * too: no sponsor shop at all, for the thing tributes earn tokens towards all evening.
     *
     * <p>Being lenient here and nowhere else is the point. An id nobody registered is still refused; what is
     * forgiven is a separator, which nobody ever meant to be part of the name.
     */
    static String canonicalItemId(String id) {
        return id == null ? "" : id.strip().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static Reward parseReward(String raw, Set<String> knownCustomItemIds) {
        String[] parts = raw.split(":", -1);
        String head = parts[0].trim().toUpperCase(Locale.ROOT);

        if (head.equals("EFFECT")) {
            if (parts.length != 4) {
                throw new IllegalArgumentException("EFFECT needs 'EFFECT:TYPE:SECONDS:AMPLIFIER': " + raw);
            }
            String effect = parts[1].trim().toUpperCase(Locale.ROOT);
            if (effect.isEmpty()) {
                throw new IllegalArgumentException("effect type must not be empty");
            }
            int seconds = parsePositiveInt(parts[2], "effect duration");
            int amplifier = parseNonNegativeInt(parts[3], "effect amplifier");
            return new EffectReward(effect, seconds, amplifier);
        }

        if (head.equals("ITEM")) {
            if (parts.length != 3) {
                throw new IllegalArgumentException("ITEM needs 'ITEM:ID:AMOUNT': " + raw);
            }
            String written = parts[1].trim();
            String customId = canonicalItemId(written);
            // Matched against every known id in its own canonical form, so the file's spelling and the
            // registry's do not have to agree — see canonicalItemId for why they never did.
            String matched = null;
            for (String known : knownCustomItemIds) {
                if (canonicalItemId(known).equals(customId)) {
                    matched = known;
                    break;
                }
            }
            if (matched == null) {
                throw new IllegalArgumentException("unknown custom item: " + written);
            }
            // Stored as the registry spells it, not as the file did. A reward carrying a name nothing can
            // look up is a purchase that succeeds and hands over nothing, which is worse than a refusal
            // because there is no failure to report.
            return new CustomItemReward(matched, parsePositiveInt(parts[2], "amount"));
        }

        if (head.equals("POTION")) {
            if (parts.length != 4) {
                throw new IllegalArgumentException("POTION needs 'POTION:VARIANT:TYPE:AMOUNT': " + raw);
            }
            PotionVariant variant;
            try {
                variant = PotionVariant.valueOf(parts[1].trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "potion variant must be NORMAL, SPLASH or LINGERING: " + parts[1]);
            }
            String type = parts[2].trim().toUpperCase(Locale.ROOT);
            if (type.isEmpty()) {
                throw new IllegalArgumentException("potion type must not be empty");
            }
            return new PotionReward(variant, type, parsePositiveInt(parts[3], "amount"));
        }

        if (parts.length != 2) {
            throw new IllegalArgumentException("a material reward needs 'MATERIAL:AMOUNT': " + raw);
        }
        Material material = Material.matchMaterial(head);
        if (material == null) {
            throw new IllegalArgumentException("unknown material: " + head);
        }
        return new MaterialReward(material, parsePositiveInt(parts[1], "amount"));
    }

    private static int parsePositiveInt(String raw, String label) {
        int value = parseNonNegativeInt(raw, label);
        if (value <= 0) {
            throw new IllegalArgumentException(label + " must be > 0");
        }
        return value;
    }

    private static int parseNonNegativeInt(String raw, String label) {
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < 0) {
                throw new IllegalArgumentException(label + " must not be negative");
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(label + " \"" + raw.trim() + "\" is not a number");
        }
    }

    private void carry() {
        List<String> fromFile = store.problems();
        synchronized (problems) {
            problems.addAll(fromFile);
        }
    }

    private void note(String problem) {
        synchronized (problems) {
            problems.add(problem);
        }
        log.warn("sponsor-shop.yml: {}", problem);
    }
}
