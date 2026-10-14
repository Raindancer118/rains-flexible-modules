package de.raindancer.modules.cosmetics.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.modules.cosmetics.model.ParticleChoice;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
 * <p>Asked on every draw, hence the map in memory; the document is the copy that outlives a restart.
 *
 * <h2>On a network</h2>
 * One document for every server ({@code YamlStore.network}): a combination reserved anywhere is the owner's
 * everywhere. Reserving and releasing are decided against the document as it is <em>now</em>, inside one
 * {@link YamlStore#update}, so two servers reserving the same wings at the same moment cannot both succeed.
 * {@link #watch()} reloads when another server writes.
 */
public final class WingReservations {

    public enum Outcome { RESERVED, ALREADY_YOURS, TAKEN, NOTHING_WORN, RELEASED, NOT_RESERVED, NOT_YOURS, NOT_SAVED }

    /** @param combo what {@link #combo} made of the wings; {@code shown} is it for people */
    public record Reservation(String combo, UUID owner, String ownerName, String shown) {
    }

    private final YamlStore store;
    private final Map<String, Reservation> reserved = new ConcurrentHashMap<>();

    public WingReservations(YamlStore store) {
        this.store = store;
    }

    /** The combination these wings are, or empty for none worn. */
    public static Optional<String> combo(ParticleChoice wings) {
        if (wings == null || wings.isNone()) {
            return Optional.empty();
        }
        String kind = wings.shape().key();
        if (!coloured(wings)) {
            return Optional.of(kind + "|" + wings.particle().toLowerCase(Locale.ROOT));
        }
        return Optional.of(kind + "|" + hex(wings.colour()) + "|" + (wings.colourTo() == null ? "-" : hex(wings.colourTo())));
    }

    private static String shown(ParticleChoice wings) {
        String kind = wings.shape().title();
        if (!coloured(wings)) {
            return kind + " of " + wings.particle().toLowerCase(Locale.ROOT).replace('_', ' ');
        }
        return kind + " in " + hex(wings.colour()) + (wings.colourTo() == null ? "" : " → " + hex(wings.colourTo()));
    }

    /**
     * Whether the wings are their colours. Not just whether a colour is stored: one is left behind when dust
     * is swapped for flame, and counting it would let anybody wear flame wings somebody reserved.
     */
    private static boolean coloured(ParticleChoice wings) {
        return wings.colour() != null && de.raindancer.core.ui.effect.ParticleShows.takesColour(wings.particle());
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

    public synchronized Outcome reserve(UUID who, String name, ParticleChoice wings) {
        Optional<String> combo = combo(wings);
        if (combo.isEmpty()) {
            return Outcome.NOTHING_WORN;
        }
        Reservation mine = new Reservation(combo.get(), who, name, shown(wings));
        String at = at(combo.get());
        Outcome[] outcome = new Outcome[1];
        Reservation[] holder = new Reservation[1];
        boolean written = store.update(yaml -> {
            holder[0] = parse(yaml.getConfigurationSection(at), combo.get()).orElse(null);
            if (holder[0] != null) {
                outcome[0] = holder[0].owner().equals(who) ? Outcome.ALREADY_YOURS : Outcome.TAKEN;
                return;
            }
            put(yaml, mine);
            outcome[0] = Outcome.RESERVED;
        });
        if (!written) {
            return Outcome.NOT_SAVED;
        }
        reserved.put(combo.get(), holder[0] != null ? holder[0] : mine);
        return outcome[0];
    }

    /** @param staff may free anybody's, not only their own */
    public synchronized Outcome release(UUID who, boolean staff, ParticleChoice wings) {
        Optional<String> combo = combo(wings);
        if (combo.isEmpty()) {
            return Outcome.NOT_RESERVED;
        }
        String at = at(combo.get());
        Outcome[] outcome = new Outcome[1];
        Reservation[] holder = new Reservation[1];
        boolean written = store.update(yaml -> {
            holder[0] = parse(yaml.getConfigurationSection(at), combo.get()).orElse(null);
            if (holder[0] == null) {
                outcome[0] = Outcome.NOT_RESERVED;
            } else if (!staff && !holder[0].owner().equals(who)) {
                outcome[0] = Outcome.NOT_YOURS;
            } else {
                yaml.set(at, null);
                outcome[0] = Outcome.RELEASED;
            }
        });
        if (!written) {
            return Outcome.NOT_SAVED;
        }
        if (holder[0] == null || outcome[0] == Outcome.RELEASED) {
            reserved.remove(combo.get());
        } else {
            reserved.put(combo.get(), holder[0]);
        }
        return outcome[0];
    }

    /** Frees everything reserved by whoever is called {@code ownerName}, in any case. @return how many; none when it could not be saved */
    public synchronized int releaseAllOf(String ownerName) {
        List<String> freed = new ArrayList<>();
        boolean written = store.update(yaml -> {
            freed.clear();
            ConfigurationSection all = yaml.getConfigurationSection("reservations");
            if (all == null) {
                return;
            }
            for (String key : all.getKeys(false)) {
                ConfigurationSection entry = all.getConfigurationSection(key);
                if (entry != null && entry.getString("owner-name", "?").equalsIgnoreCase(ownerName)) {
                    freed.add(entry.getString("combo", YamlStore.fromPathPart(key)));
                    all.set(key, null);
                }
            }
        });
        if (!written) {
            return 0;
        }
        freed.forEach(reserved::remove);
        return freed.size();
    }

    /** Every reservation, by owner then what it is. */
    public List<Reservation> all() {
        return reserved.values().stream()
                .sorted(Comparator.comparing(Reservation::ownerName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Reservation::shown))
                .toList();
    }

    /** Reads the document; a read that fails keeps what is held. */
    public synchronized void load() {
        Optional<YamlConfiguration> read = store.readIfReadable();
        if (read.isEmpty()) {
            return;
        }
        Map<String, Reservation> fresh = new HashMap<>();
        ConfigurationSection section = read.get().getConfigurationSection("reservations");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                // An entry that is not readable is left in the document as it is: writes touch only the entries they change.
                parse(section.getConfigurationSection(key), YamlStore.fromPathPart(key))
                        .ifPresent(held -> fresh.put(held.combo(), held));
            }
        }
        reserved.keySet().retainAll(fresh.keySet());
        reserved.putAll(fresh);
    }

    /** Reloads when another server writes the document. Close to stop. */
    public NetworkBus.Subscription watch() {
        return store.onChange(this::load);
    }

    private static String at(String combo) {
        return "reservations." + YamlStore.asPathPart(combo);
    }

    private static Optional<Reservation> parse(ConfigurationSection entry, String fallbackCombo) {
        if (entry == null) {
            return Optional.empty();
        }
        try {
            String combo = entry.getString("combo", fallbackCombo);
            return Optional.of(new Reservation(combo, UUID.fromString(entry.getString("owner", "")),
                    entry.getString("owner-name", "?"), entry.getString("shown", combo)));
        } catch (IllegalArgumentException notAnId) {
            return Optional.empty();
        }
    }

    private static void put(YamlConfiguration yaml, Reservation held) {
        String at = at(held.combo());
        yaml.set(at + ".combo", held.combo());
        yaml.set(at + ".owner", held.owner().toString());
        yaml.set(at + ".owner-name", held.ownerName());
        yaml.set(at + ".shown", held.shown());
    }
}
