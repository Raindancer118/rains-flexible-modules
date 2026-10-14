package de.raindancer.modules.cosmetics.store;

import de.raindancer.core.data.store.KeyedDocument;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.modules.cosmetics.model.ParticleChoice;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Wing combinations an op keeps for themselves: nobody else may wear the same kind of wings in the same
 * colours. A combination is the kind and its colours — whichever coloured particle draws them, in
 * whichever style, since those look alike — or, for wings without a colour, the kind and the particle.
 *
 * <p>Asked on every draw, hence the map in memory; the document is the copy that outlives a restart.
 *
 * <h2>On a network</h2>
 * One document for every server ({@code YamlStore.network}): a combination reserved anywhere is the owner's
 * everywhere. Reserving and releasing are decided against the document as it is <em>now</em>, one combination per
 * key ({@link KeyedDocument}), so two servers reserving the same wings at the same moment cannot both succeed.
 */
public final class WingReservations {

    public enum Outcome { RESERVED, ALREADY_YOURS, TAKEN, NOTHING_WORN, RELEASED, NOT_RESERVED, NOT_YOURS, NOT_SAVED }

    /** @param combo what {@link #combo} made of the wings; {@code shown} is it for people */
    public record Reservation(String combo, UUID owner, String ownerName, String shown) {
    }

    private final KeyedDocument<Reservation> document;

    public WingReservations(YamlStore store) {
        this.document = new KeyedDocument<>(store, "reservations",
                (yaml, key) -> parse(yaml.getConfigurationSection(key), YamlStore.fromPathPart(key)).orElse(null),
                WingReservations::put);
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
        return combo(wings).flatMap(combo -> document.get(YamlStore.asPathPart(combo)));
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
        Outcome[] outcome = new Outcome[1];
        boolean written = document.change(YamlStore.asPathPart(combo.get()), holder -> {
            if (holder != null) {
                outcome[0] = holder.owner().equals(who) ? Outcome.ALREADY_YOURS : Outcome.TAKEN;
                return holder;
            }
            outcome[0] = Outcome.RESERVED;
            return mine;
        });
        return written ? outcome[0] : Outcome.NOT_SAVED;
    }

    /** @param staff may free anybody's, not only their own */
    public Outcome release(UUID who, boolean staff, ParticleChoice wings) {
        Optional<String> combo = combo(wings);
        if (combo.isEmpty()) {
            return Outcome.NOT_RESERVED;
        }
        Outcome[] outcome = new Outcome[1];
        boolean written = document.change(YamlStore.asPathPart(combo.get()), holder -> {
            if (holder == null) {
                outcome[0] = Outcome.NOT_RESERVED;
                return null;
            }
            if (!staff && !holder.owner().equals(who)) {
                outcome[0] = Outcome.NOT_YOURS;
                return holder;
            }
            outcome[0] = Outcome.RELEASED;
            return null;
        });
        return written ? outcome[0] : Outcome.NOT_SAVED;
    }

    /** Frees everything reserved by whoever is called {@code ownerName}, in any case. @return how many were freed */
    public int releaseAllOf(String ownerName) {
        int freed = 0;
        for (var each : document.all().entrySet()) {
            if (!each.getValue().ownerName().equalsIgnoreCase(ownerName)) {
                continue;
            }
            boolean[] theirs = {false};
            boolean written = document.change(each.getKey(), holder -> {
                theirs[0] = holder != null && holder.ownerName().equalsIgnoreCase(ownerName);
                return theirs[0] ? null : holder;
            });
            if (written && theirs[0]) {
                freed++;
            }
        }
        return freed;
    }

    /** Every reservation, by owner then what it is. */
    public List<Reservation> all() {
        return document.all().values().stream()
                .sorted(Comparator.comparing(Reservation::ownerName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(Reservation::shown))
                .toList();
    }

    /** Reads the document; a read that fails keeps what is held and refuses changes until one succeeds. */
    public void load() {
        document.load();
    }

    /** Told when another server reserved or freed wings (read in already). Close to stop. */
    public NetworkBus.Subscription watch() {
        return document.onChange(changed -> { });
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

    private static void put(ConfigurationSection yaml, String at, Reservation held) {
        yaml.set(at + ".combo", held.combo());
        yaml.set(at + ".owner", held.owner().toString());
        yaml.set(at + ".owner-name", held.ownerName());
        yaml.set(at + ".shown", held.shown());
    }
}
