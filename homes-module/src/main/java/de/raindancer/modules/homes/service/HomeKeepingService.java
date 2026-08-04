package de.raindancer.modules.homes.service;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.homes.HomeSettings;
import de.raindancer.modules.homes.model.Home;
import de.raindancer.modules.homes.rules.HomeLimitRule;
import de.raindancer.modules.homes.rules.HomeNameRule;
import de.raindancer.modules.homes.store.HomeCatalogue;
import de.raindancer.modules.homes.util.PermissionNodes;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.Set;

/**
 * Setting, moving, renaming, re-iconing and deleting homes.
 *
 * <h2>Why every entrance comes through here</h2>
 * Because there are three of them — the commands, the menu and the chat prompt behind the rename — and
 * an invariant guarded at one is not guarded. "Is this a name a home can have", "have they got room
 * for another" and "are they already using that name" are asked here, once.
 *
 * <h2>The one rule worth reading twice</h2>
 * <b>Replacing a home somebody already has never counts against the limit</b>, even when the limit is
 * zero. Without that, an owner who lowers the number traps everybody who is now over it: they cannot
 * move a home, and moving is how a badly placed one is fixed, so the only thing left is to delete.
 */
public final class HomeKeepingService implements IHomeService {

    private final HomeCatalogue homes;
    private final HomeLimitRule limits;
    private final HomeNameRule names;
    private final Messages messages;

    private volatile HomeSettings settings;

    public HomeKeepingService(HomeCatalogue homes, HomeLimitRule limits, HomeNameRule names,
                              Messages messages, HomeSettings settings) {
        this.homes = homes;
        this.limits = limits;
        this.names = names;
        this.messages = messages;
        this.settings = settings;
    }

    @Override
    public void settings(HomeSettings fresh) {
        this.settings = fresh;
    }

    // ------------------------------------------------------------------------ how many

    /** How many this player may have, as a number. */
    public int limitFor(Player who) {
        return limits.limitFor(HomeLimitRule.grantsOf(who), who.isOp(),
                settings.operatorsBypass(), settings.homeLimit());
    }

    /** The same, as a player should read it — {@code ∞} rather than two billion. */
    public String describeLimitFor(Player who) {
        return limits.describeLimit(HomeLimitRule.grantsOf(who), who.isOp(),
                settings.operatorsBypass(), settings.homeLimit());
    }

    // ------------------------------------------------------------------------ setting one

    /**
     * Sets a home where this player is standing.
     *
     * @return the home, or empty when something refused it — which has already been said
     */
    public Optional<Home> set(Player owner, String typedName) {
        String name = names.orDefault(typedName);
        if (name == null) {
            messages.send(owner, "homes.bad-name", "rule", names.describe());
            return Optional.empty();
        }
        boolean replacing = homes.has(owner.getUniqueId(), name);

        if (!replacing) {
            Set<String> granted = HomeLimitRule.grantsOf(owner);
            int have = homes.count(owner.getUniqueId());
            if (!limits.isRoomFor(have, granted, owner.isOp(), settings.operatorsBypass(),
                    settings.homeLimit())) {
                // A limit of zero is a different sentence: "you already have 0 homes" reads as a bug,
                // and the answer to it is not /delhome.
                if (limitFor(owner) == 0) {
                    messages.send(owner, "homes.switched-off");
                } else {
                    messages.send(owner, "homes.too-many", "limit", limitFor(owner));
                }
                return Optional.empty();
            }
        }

        Optional<Home> saved = homes.set(owner.getUniqueId(), owner.getName(), name,
                owner.getLocation());
        if (saved.isEmpty()) {
            messages.send(owner, "homes.bad-name", "rule", names.describe());
            return saved;
        }
        messages.send(owner, replacing ? "homes.moved" : "homes.set",
                "name", name,
                "count", homes.count(owner.getUniqueId()),
                "limit", describeLimitFor(owner));
        return saved;
    }

    /** Forgets one. */
    public boolean delete(Player owner, String typedName) {
        String name = names.orDefault(typedName);
        if (name == null || !homes.delete(owner.getUniqueId(), name)) {
            unknown(owner, typedName);
            return false;
        }
        messages.send(owner, "homes.deleted", "name", name);
        return true;
    }

    /**
     * The same home under another name.
     *
     * <p>Three ways to fail and three different things to say, because "that did not work" is the
     * answer somebody tries four more spellings against.
     */
    public Optional<Home> rename(Player owner, String from, String to) {
        String wanted = names.normalise(to);
        if (wanted == null) {
            messages.send(owner, "homes.bad-name", "rule", names.describe());
            return Optional.empty();
        }
        Optional<Home> had = homes.find(owner.getUniqueId(), from);
        if (had.isEmpty()) {
            unknown(owner, from);
            return Optional.empty();
        }
        if (!had.get().name().equals(wanted) && homes.has(owner.getUniqueId(), wanted)) {
            messages.send(owner, "homes.name-taken", "name", wanted);
            return Optional.empty();
        }
        if (had.get().name().equals(wanted)) {
            // Renaming something to what it is already called. Not a failure and not worth a line:
            // the caller reopens the page and the name on it is the name they typed.
            return had;
        }

        Optional<Home> renamed = homes.rename(owner.getUniqueId(), from, wanted);
        renamed.ifPresent(home ->
                messages.send(owner, "homes.renamed", "old", had.get().name(), "new", home.name()));
        return renamed;
    }

    /** The block it shows as; null puts it back to being chosen by its world. */
    public boolean setIcon(Player owner, String name, String iconName) {
        if (!homes.setIcon(owner.getUniqueId(), name, iconName)) {
            unknown(owner, name);
            return false;
        }
        return true;
    }

    /**
     * "No home called that", with what they do have.
     *
     * <p>Somebody with no homes at all gets a different line: listing nothing and then suggesting they
     * check the spelling is unhelpful, and the useful sentence is how to make the first one.
     */
    public void unknown(Player owner, String typedName) {
        if (homes.count(owner.getUniqueId()) == 0) {
            messages.send(owner, "homes.none-yet");
            return;
        }
        messages.send(owner, "homes.no-such-home",
                "name", String.valueOf(typedName),
                "list", String.join(", ", homes.of(owner.getUniqueId()).stream()
                        .map(Home::name).toList()));
    }

    /** The permission a player needs to have any of this at all. */
    public static String permission() {
        return PermissionNodes.USE;
    }

    @Override
    public String describe() {
        return "setting, moving, renaming and deleting homes";
    }
}
