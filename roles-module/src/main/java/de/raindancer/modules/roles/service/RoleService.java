package de.raindancer.modules.roles.service;

import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.text.Markup;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.roles.RolesSettings;
import de.raindancer.modules.roles.model.ChangeVerdict;
import de.raindancer.modules.roles.model.Choice;
import de.raindancer.modules.roles.model.Role;
import de.raindancer.modules.roles.rules.ChangeRule;
import de.raindancer.modules.roles.rules.TenureRule;
import de.raindancer.modules.roles.store.ChoiceBook;
import de.raindancer.modules.roles.store.RoleCatalogue;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Taking a role, changing it, and staff setting it for somebody. */
public final class RoleService implements IRolesService {

    private final Server server;
    private final RoleCatalogue catalogue;
    private final ChoiceBook choices;
    private final Messages messages;
    private final LongSupplier clock;
    private final ChangeRule rule = new ChangeRule();
    private final TenureRule tenure = new TenureRule();
    /** Staff currently skipping the wait. Not saved: a bypass left on is a bypass forgotten about. */
    private final Set<UUID> bypassing = ConcurrentHashMap.newKeySet();
    private volatile RolesSettings settings;
    private volatile RoleAccess access = RoleAccess.OPEN;

    public RoleService(Server server, RoleCatalogue catalogue, ChoiceBook choices, Messages messages,
                       LongSupplier clock, RolesSettings settings) {
        this.server = server;
        this.catalogue = catalogue;
        this.choices = choices;
        this.messages = messages;
        this.clock = clock;
        this.settings = settings == null ? RolesSettings.DEFAULTS : settings;
    }

    @Override
    public void settings(RolesSettings updated) {
        this.settings = updated == null ? RolesSettings.DEFAULTS : updated;
    }

    /** Hands in the shop that decides which roles are open. Without one every role is. */
    public void access(RoleAccess shop) {
        this.access = shop == null ? RoleAccess.OPEN : shop;
    }

    public boolean may(UUID player, Role role) {
        return access.may(player, role);
    }

    public List<Role> roles() {
        return catalogue.all();
    }

    public Optional<Role> role(String id) {
        return catalogue.find(id);
    }

    /** The role a player has, if it still exists in roles.yml. */
    public Optional<Role> roleOf(UUID player) {
        return choices.of(player).flatMap(choice -> catalogue.find(choice.role()));
    }

    public Optional<Choice> choiceOf(UUID player) {
        return choices.of(player);
    }

    public boolean bypassing(UUID player) {
        return bypassing.contains(player);
    }

    /** Switches the bypass for one member of staff. @return whether it is on now */
    public boolean toggleBypass(UUID player) {
        if (bypassing.remove(player)) {
            return false;
        }
        bypassing.add(player);
        return true;
    }

    public void forget(UUID player) {
        bypassing.remove(player);
    }

    /** Whether this player may take that role now, and if not, why. */
    public ChangeVerdict verdict(UUID player, Role wanted) {
        return rule.decide(choices.of(player), wanted.id(), clock.getAsLong(), settings.changeEvery(),
                bypassing.contains(player));
    }

    /** How long until this player may change again; zero when they may now. */
    public Duration left(UUID player) {
        return bypassing.contains(player) ? Duration.ZERO
                : rule.left(choices.of(player), clock.getAsLong(), settings.changeEvery());
    }

    public Duration holdsFor() {
        return settings.changeEvery();
    }

    /** A player taking a role for themselves, if they may. */
    public boolean choose(Player player, Role role) {
        if (!access.may(player.getUniqueId(), role)) {
            messages.send(player, "roles.locked", "role", new Markup(role.coloured()), "id", role.id());
            return false;
        }
        ChangeVerdict verdict = verdict(player.getUniqueId(), role);
        if (!verdict.allowed()) {
            switch (verdict.reason()) {
                case SAME -> messages.send(player, "roles.same", "article", role.article(), "role", new Markup(role.coloured()));
                default -> messages.send(player, "roles.wait", "left", Times.describe(verdict.left()));
            }
            return false;
        }
        long now = clock.getAsLong();
        if (!choices.put(new Choice(player.getUniqueId(), role.id(), now, now))) {
            messages.send(player, "roles.not-saved");
            return false;
        }
        // Staff trying roles out are not announced, not even their first.
        boolean bypass = verdict.reason() == ChangeVerdict.Reason.BYPASS || bypassing.contains(player.getUniqueId());
        if (bypass || settings.changeEvery().isZero()) {
            messages.send(player, "roles.chosen-whenever", "article", role.article(), "role", new Markup(role.coloured()));
        } else {
            messages.send(player, "roles.chosen", "article", role.article(), "role", new Markup(role.coloured()),
                    "wait", Times.describe(settings.changeEvery()));
        }
        if (settings.announce() && !bypass) {
            for (Player other : server.getOnlinePlayers()) {
                if (!other.equals(player)) {
                    messages.send(other, "roles.announce", "player", player.getName(),
                            "article", role.article(), "role", new Markup(role.coloured()));
                }
            }
        }
        return true;
    }

    /** Staff giving somebody a role, or none. Starts their wait as if they had chosen it. */
    public boolean set(UUID player, Optional<Role> role) {
        if (role.isEmpty()) {
            return choices.clear(player);
        }
        long now = clock.getAsLong();
        return choices.put(new Choice(player, role.get().id(), now, now));
    }

    /** Lets a player change straight away, keeping the role they have. */
    public boolean endWait(UUID player) {
        Optional<Choice> current = choices.of(player);
        return current.isEmpty() || choices.put(new Choice(player, current.get().role(),
                clock.getAsLong() - settings.changeEvery().toMillis(), current.get().heldSince()));
    }

    /** How strong this player's perks are by now, 0 to 1. */
    public double strength(UUID player) {
        RolesSettings live = settings;
        return choices.of(player).map(choice -> tenure.strength(choice.heldSince(), clock.getAsLong(),
                live.startShare(), live.fullAfterDays())).orElse(0.0);
    }

    /** A perk as a brand-new holder of the role has it. */
    public int perkFresh(int fullPercent) {
        RolesSettings live = settings;
        return tenure.scaled(fullPercent, tenure.strength(0, 0, live.startShare(), live.fullAfterDays()));
    }

    public int fullAfterDays() {
        return settings.fullAfterDays();
    }

    /** A perk as this player has it now. */
    public int perkNow(UUID player, int fullPercent) {
        return tenure.scaled(fullPercent, strength(player));
    }

    /** How long until this player's perks are full; zero when they are. */
    public Duration untilFull(UUID player) {
        RolesSettings live = settings;
        return choices.of(player).map(choice -> tenure.untilFull(choice.heldSince(), clock.getAsLong(),
                live.fullAfterDays())).orElse(Duration.ZERO);
    }

    public int reload() {
        return catalogue.reload();
    }

    public List<String> problems() {
        return catalogue.problems();
    }

    public boolean saving() {
        return choices.readable();
    }
}
