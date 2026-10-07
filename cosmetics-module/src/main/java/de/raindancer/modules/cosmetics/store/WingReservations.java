package de.raindancer.modules.cosmetics.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.modules.cosmetics.model.ParticleChoice;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Wing combinations an op keeps for themselves: nobody else may wear the same kind of wings in the same
 * colours. A combination is the kind and its colours — whichever coloured particle draws them, in
 * whichever style, since those look alike — or, for wings without a colour, the kind and the particle.
 *
 * <p>Asked on every draw, hence the map in memory; the file is the copy that outlives a restart.
 */
public final class WingReservations {

    public enum Outcome { RESERVED, ALREADY_YOURS, TAKEN, NOTHING_WORN, RELEASED, NOT_RESERVED, NOT_YOURS }

    /** @param combo what {@link #combo} made of the wings; {@code shown} is it for people */
    public record Reservation(String combo, UUID owner, String ownerName, String shown) {
    }

    private final YamlStore store;
    private final Map<String, Reservation> reserved = new ConcurrentHashMap<>();

    public WingReservations(Path file) {
        this.store = new YamlStore(file);
    }

    /** The combination these wings are, or empty for none worn. */
    public static Optional<String> combo(ParticleChoice wings) {
        if (wings == null || wings.isNone()) {
            return Optional.empty();
        }
        String kind = wings.shape().key();
        if (wings.colour() == null) {
            return Optional.of(kind + "|" + wings.particle().toLowerCase(Locale.ROOT));
        }
        return Optional.of(kind + "|" + hex(wings.colour()) + "|" + (wings.colourTo() == null ? "-" : hex(wings.colourTo())));
    }

    private static String shown(ParticleChoice wings) {
        String kind = wings.shape().title();
        if (wings.colour() == null) {
            return kind + " of " + wings.particle().toLowerCase(Locale.ROOT).replace('_', ' ');
        }
        return kind + " in " + hex(wings.colour()) + (wings.colourTo() == null ? "" : " → " + hex(wings.colourTo()));
    }

    private static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06X", rgb & 0xFFFFFF);
    }

    public Optional<Reservation> holderOf(ParticleChoice wings) {
        return combo(wings).map(reserved::get);
    }

    /** Whether {@code who} may wear these: free, or theirs. */
    public boolean mayWear(UUID who, ParticleChoice wings) {
        return holderOf(wings).map(held -> held.owner().equals(who)).orElse(true);
    }

    public Outcome reserve(UUID who, String name, ParticleChoice wings) {
        Optional<String> combo = combo(wings);
        if (combo.isEmpty()) {
            return Outcome.NOTHING_WORN;
        }
        Reservation mine = new Reservation(combo.get(), who, name, shown(wings));
        Reservation before = reserved.putIfAbsent(combo.get(), mine);
        if (before != null) {
            return before.owner().equals(who) ? Outcome.ALREADY_YOURS : Outcome.TAKEN;
        }
        flush();
        return Outcome.RESERVED;
    }

    /** @param staff may free anybody's, not only their own */
    public Outcome release(UUID who, boolean staff, ParticleChoice wings) {
        Optional<Reservation> held = holderOf(wings);
        if (held.isEmpty()) {
            return Outcome.NOT_RESERVED;
        }
        if (!staff && !held.get().owner().equals(who)) {
            return Outcome.NOT_YOURS;
        }
        reserved.remove(held.get().combo());
        String at = "reservations." + YamlStore.asPathPart(held.get().combo());
        store.update(yaml -> yaml.set(at, null));
        return Outcome.RELEASED;
    }

    /** Frees everything reserved by whoever is called {@code ownerName}, in any case. @return how many */
    public int releaseAllOf(String ownerName) {
        List<Reservation> theirs = reserved.values().stream()
                .filter(held -> held.ownerName().equalsIgnoreCase(ownerName)).toList();
        for (Reservation held : theirs) {
            reserved.remove(held.combo());
            String at = "reservations." + YamlStore.asPathPart(held.combo());
            store.update(yaml -> yaml.set(at, null));
        }
        return theirs.size();
    }

    /** Every reservation, by owner then what it is. */
    public List<Reservation> all() {
        return reserved.values().stream()
                .sorted(Comparator.comparing(Reservation::ownerName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Reservation::shown))
                .toList();
    }

    public void load() {
        reserved.clear();
        ConfigurationSection section = store.read().getConfigurationSection("reservations");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) {
                continue;
            }
            try {
                String combo = entry.getString("combo", YamlStore.fromPathPart(key));
                reserved.put(combo, new Reservation(combo, UUID.fromString(entry.getString("owner", "")),
                        entry.getString("owner-name", "?"), entry.getString("shown", combo)));
            } catch (IllegalArgumentException notAnId) {
                // Not held to, and left in the file as it is: writes touch only the entries they change.
            }
        }
    }

    /** An update, not a fresh write: a file that could not be read is left alone, not replaced by what we hold. */
    private void flush() {
        store.update(yaml -> write(yaml));
    }

    private void write(YamlConfiguration yaml) {
        for (Reservation held : reserved.values()) {
            String at = "reservations." + YamlStore.asPathPart(held.combo());
            yaml.set(at + ".combo", held.combo());
            yaml.set(at + ".owner", held.owner().toString());
            yaml.set(at + ".owner-name", held.ownerName());
            yaml.set(at + ".shown", held.shown());
        }
    }
}
