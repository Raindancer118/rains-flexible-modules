package de.raindancer.modules.roles.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.social.economy.TradeSide;
import de.raindancer.core.ui.choose.Catalogue;
import de.raindancer.core.ui.choose.Category;
import de.raindancer.modules.roles.model.Perk;
import de.raindancer.modules.roles.model.Role;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The roles there are, from {@code roles.yml}. The shipped file is written out the first time and never
 * again, so an owner's edits survive every update.
 */
public final class RoleCatalogue {

    /**
     * The most any perk may change a price, in percent. The shop pays 40% of an item's worth by default;
     * a discount past 60% would let a player buy and sell straight back at a profit, so this stays well
     * under it.
     */
    public static final int LARGEST_PERK = 50;

    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final Pattern HEX = Pattern.compile("#[0-9a-fA-F]{6}");

    private final YamlStore store;
    private final Supplier<InputStream> shipped;
    private volatile List<Role> roles = List.of();

    public RoleCatalogue(YamlStore store, Supplier<InputStream> shipped) {
        this.store = store;
        this.shipped = shipped;
    }

    /** Reads the file again. @return how many roles it holds */
    public int reload() {
        if (!store.exists()) {
            try (InputStream in = shipped.get()) {
                if (in != null) {
                    Files.createDirectories(store.file().getParent());
                    Files.writeString(store.file(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            } catch (IOException failed) {
                // Read below as no roles; /role says there are none.
            }
        }
        roles = parse(store.read());
        return roles.size();
    }

    public List<Role> all() {
        return roles;
    }

    public Optional<Role> find(String id) {
        return id == null ? Optional.empty()
                : roles.stream().filter(role -> role.id().equals(id.toLowerCase(Locale.ROOT))).findFirst();
    }

    public List<String> problems() {
        return store.problems();
    }

    static List<Role> parse(YamlConfiguration yaml) {
        List<Role> read = new ArrayList<>();
        ConfigurationSection all = yaml.getConfigurationSection("roles");
        if (all == null) {
            return read;
        }
        for (String id : all.getKeys(false)) {
            if (!ID.matcher(id).matches()) {
                continue;
            }
            ConfigurationSection section = all.getConfigurationSection(id);
            List<Perk> perks = new ArrayList<>();
            if (section != null) {
                for (Map<?, ?> written : section.getMapList("perks")) {
                    perk(written).ifPresent(perks::add);
                }
            }
            String colour = section == null ? "" : section.getString("colour", "");
            read.add(new Role(id,
                    section == null ? readable(id) : section.getString("title", readable(id)),
                    section == null ? "PAPER" : section.getString("icon", "paper").toUpperCase(Locale.ROOT),
                    HEX.matcher(colour).matches() ? colour.toLowerCase(Locale.ROOT) : "#ffffff",
                    section == null ? List.of() : section.getStringList("description"), perks));
        }
        return read;
    }

    private static String readable(String id) {
        return Catalogue.readable(id.replace('-', '_').toUpperCase(Locale.ROOT));
    }

    private static Optional<Perk> perk(Map<?, ?> written) {
        TradeSide side;
        Object amount;
        if (written.containsKey("buy")) {
            side = TradeSide.BUY;
            amount = written.get("buy");
        } else if (written.containsKey("sell")) {
            side = TradeSide.SELL;
            amount = written.get("sell");
        } else {
            return Optional.empty();
        }
        if (!(amount instanceof Number number)) {
            return Optional.empty();
        }
        // Owners write "buy: 25" as often as "buy: -25"; both mean a quarter off.
        int size = Math.min(LARGEST_PERK, Math.abs(number.intValue()));
        if (size == 0) {
            return Optional.empty();
        }
        List<Category> categories = new ArrayList<>();
        for (String name : strings(written.get("categories"))) {
            try {
                categories.add(Category.valueOf(name.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException unknown) {
                // An unknown drawer is left out rather than costing the whole role.
            }
        }
        Object says = written.get("says");
        return Optional.of(new Perk(side, side == TradeSide.BUY ? -size : size, categories,
                strings(written.get("items")), strings(written.get("except")), says == null ? "" : says.toString()));
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(java.util.Objects::nonNull).map(Object::toString).toList();
    }
}
