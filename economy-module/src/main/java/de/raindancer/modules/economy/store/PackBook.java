package de.raindancer.modules.economy.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.social.economy.Money;
import de.raindancer.core.ui.choose.Catalogue;
import de.raindancer.modules.economy.model.Pack;
import de.raindancer.modules.economy.model.PackItem;
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
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The packs the shop sells, from {@code packs.yml}. The shipped file is written out the first time and never
 * again, so an owner's edits survive every update.
 */
public final class PackBook {

    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");

    private final YamlStore store;
    private final Supplier<InputStream> shipped;
    private final Function<String, Optional<Money>> money;
    private volatile List<Pack> packs = List.of();

    /** @param money reads a price written in the file, in the server's currency */
    public PackBook(YamlStore store, Supplier<InputStream> shipped, Function<String, Optional<Money>> money) {
        this.store = store;
        this.shipped = shipped;
        this.money = money;
    }

    /** Reads the file again. @return how many packs it holds */
    public int reload() {
        if (!store.exists()) {
            try (InputStream in = shipped.get()) {
                if (in != null) {
                    Files.createDirectories(store.file().getParent());
                    Files.writeString(store.file(), new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            } catch (IOException failed) {
                // Read below as no packs; the shop simply shows none.
            }
        }
        packs = parse(store.read(), money);
        return packs.size();
    }

    public List<Pack> all() {
        return packs;
    }

    public Optional<Pack> find(String id) {
        return id == null ? Optional.empty()
                : packs.stream().filter(pack -> pack.id().equals(id.toLowerCase(Locale.ROOT))).findFirst();
    }

    public List<String> problems() {
        return store.problems();
    }

    static List<Pack> parse(YamlConfiguration yaml, Function<String, Optional<Money>> money) {
        List<Pack> read = new ArrayList<>();
        ConfigurationSection all = yaml.getConfigurationSection("packs");
        if (all == null) {
            return read;
        }
        for (String id : all.getKeys(false)) {
            ConfigurationSection section = all.getConfigurationSection(id);
            if (section == null || !ID.matcher(id).matches()) {
                continue;
            }
            List<PackItem> contents = new ArrayList<>();
            ConfigurationSection items = section.getConfigurationSection("contents");
            if (items != null) {
                for (String material : items.getKeys(false)) {
                    int amount = items.isInt(material) ? items.getInt(material) : 0;
                    if (amount > 0) {
                        contents.add(new PackItem(material, amount));
                    }
                }
            }
            String price = section.getString("price", "");
            Money fixed = price.isBlank() ? null : money.apply(price).orElse(null);
            String title = section.getString("title", Catalogue.readable(id.replace('-', '_').toUpperCase(Locale.ROOT)));
            read.add(new Pack(id, title, section.getString("icon", "chest").toUpperCase(Locale.ROOT),
                    section.getStringList("description"), contents, fixed, section.getBoolean("once", false)));
        }
        return read;
    }
}
