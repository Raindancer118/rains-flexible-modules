package de.raindancer.modules.speedrun;

import de.raindancer.core.ui.menu.Menu;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * The way a module built on this one puts a button of its own on the compass' screen.
 *
 * <h2>Why the registry points this way round</h2>
 * The dependency runs {@code manhunt-module → speedrun-module} and only that way: Manhunt compiles
 * against this module's engine, and this module has never heard of Manhunt. So a button on
 * {@link SpeedrunLobbyMenu} that opens Manhunt cannot be written there — the class does not exist on
 * this side of the arrow, and making it exist would be a dependency cycle. What can exist is an
 * empty shelf: a companion offers itself when it enables, withdraws itself when it disables, and
 * {@link SpeedrunLobbyMenu} renders whatever happens to be on the shelf at the moment somebody opens
 * it. On a server with no companion installed the shelf is empty and the page is exactly what it was.
 *
 * <h2>Static, like {@link SpeedrunCommands}</h2>
 * The same reason and the same shape: the thing doing the offering ({@code ManhuntModule.enable})
 * and the thing doing the reading (a menu built per click) never meet, and neither of them can be
 * handed the other. {@link #withdraw} is not optional politeness — a module that unloads and leaves
 * its entry behind hands the next clicker a button into a plugin that is no longer there.
 */
public final class SpeedrunCompanions {

    /** Opening a companion's own screen, with this page as the one its Back button returns to. */
    @FunctionalInterface
    public interface Opener {

        void open(Player viewer, Menu parent);
    }

    /**
     * One button.
     *
     * @param id     the offering module's own name, and the key it withdraws by
     * @param label  the button's title, without markup of its own — see {@link SpeedrunLobbyMenu}
     * @param lore   one line under it saying what is through the door, MiniMessage
     * @param icon   what the button is made of
     * @param opener what a click does
     */
    public record Companion(String id, String label, String lore, Material icon, Opener opener) {

        public Companion {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(opener, "opener");
            if (id.isBlank()) {
                throw new IllegalArgumentException("A companion needs an id to be withdrawn by.");
            }
        }
    }

    /** Sorted by id, so two installed companions are always drawn in the same two places. */
    private static final Map<String, Companion> OFFERED = new ConcurrentSkipListMap<>();

    private SpeedrunCompanions() {
    }

    /** A companion module is up and wants a button. Offering the same id again replaces it. */
    public static void offer(Companion companion) {
        Objects.requireNonNull(companion, "companion");
        OFFERED.put(companion.id(), companion);
    }

    /** A companion module is going away and its button must go with it. */
    public static void withdraw(String id) {
        if (id != null) {
            OFFERED.remove(id);
        }
    }

    /** Every button to draw right now, in id order. */
    public static List<Companion> offered() {
        return List.copyOf(OFFERED.values());
    }

    /** Empties the shelf — for tests, and for a full reload. */
    public static void clear() {
        OFFERED.clear();
    }
}
