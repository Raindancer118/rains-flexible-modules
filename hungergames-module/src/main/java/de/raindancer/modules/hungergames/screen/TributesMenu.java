package de.raindancer.modules.hungergames.screen;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.choose.PlayerChooser;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.modules.hungergames.model.Participant;
import de.raindancer.modules.hungergames.store.GameSession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Every registered tribute, and the three things an admin does to one by hand: revive them, eliminate
 * them, or strike them from the tournament entirely.
 *
 * <h2>Why registering a new tribute opens Core's {@link PlayerChooser} rather than a typed name</h2>
 * The source engine asked for a name in chat and resolved it against Mojang's API — a name resolver this
 * module does not carry, and {@code ReuseTest} bans {@code getOfflinePlayer(} outright for the blocking
 * lookup it hides. {@link PlayerChooser} already answers the question this page actually needs answered —
 * "which real person" — from the server's own directory of everybody it has ever seen, without a network
 * call on this thread.
 *
 * <h2>Three clicks, three different weights</h2>
 * Left-click revives — an admin correction, undone as easily as it is done. Right-click and
 * shift-right-click are the two ways a tribute leaves the round for good: eliminated by hand (still
 * registered, just out) or struck from the tournament outright. Both are confirmed, because both are one
 * of the module's four irreversible actions — see {@code ConfirmScreen}'s own note on why an elimination
 * by hand costs a person the rest of the evening they turned up for.
 */
public final class TributesMenu extends PaginatedMenu<Participant> implements IHungerGamesScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final GameSession session;

    public TributesMenu(Player viewer, Brand brand, Menu parent, GameSession session) {
        super(viewer, brand, parent);
        this.session = session;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<gold>Tributes — <white>" + session.participants().aliveCount()
                + "/" + session.participants().all().size());
    }

    @Override
    public String breadcrumb() {
        return "Tributes";
    }

    @Override
    protected List<Participant> entries() {
        return sortedParticipants(session.participants().all());
    }

    /** Alive first, then alphabetically — pure, and the reason this ordering can be tested without a server. */
    public static List<Participant> sortedParticipants(java.util.Collection<Participant> all) {
        List<Participant> sorted = new ArrayList<>(all);
        sorted.sort(Comparator.comparing((Participant p) -> !p.isAlive())
                .thenComparing(p -> p.lastKnownName().toLowerCase(Locale.ROOT)));
        return sorted;
    }

    @Override
    protected ItemStack icon(Participant participant) {
        List<String> lore = new ArrayList<>();
        lore.add(participant.isAlive() ? "<green>Alive" : "<red>Eliminated");
        participant.teamId().flatMap(session.teams()::team)
                .ifPresent(team -> lore.add("<gray>Team: " + team.name()));
        lore.add("");
        if (!participant.isAlive()) {
            lore.add("<aqua>Left-click: revive them.");
        } else {
            lore.add("<yellow>Right-click: eliminate them by hand.");
        }
        lore.add("<dark_gray>Shift-right-click: remove from the tournament.");

        return Icons.head(participant.uuid(),
                (participant.isAlive() ? "<green>" : "<red>") + participant.lastKnownName(), lore);
    }

    @Override
    protected ItemStack emptyIcon() {
        return Icons.of(Material.BARRIER, "<gray>No tributes registered",
                "<gray>Use the button below to add the first one.");
    }

    @Override
    protected void onClick(Participant participant, InventoryClickEvent event) {
        if (event.isShiftClick() && event.isRightClick()) {
            new ConfirmScreen(viewer, brand(), this,
                    "<red>Remove " + participant.lastKnownName() + " from the tournament?",
                    List.of("<gray>They leave the whitelist and their team.",
                            "<gray>They can be re-registered afterwards, but that is a new tribute — "
                                    + "not this one, and not their kills."),
                    () -> {
                        session.whitelistRemove(participant.uuid());
                        refresh();
                    }).open();
            return;
        }
        if (event.isRightClick() && participant.isAlive()) {
            new ConfirmScreen(viewer, brand(), this,
                    "<red>Eliminate " + participant.lastKnownName() + " by hand?",
                    List.of("<gray>They are out of the round, in front of everybody watching.",
                            "<gray>A revive undoes their elimination, not the minutes they missed."),
                    () -> {
                        session.eliminate(participant.uuid(), null);
                        refresh();
                    }).open();
            return;
        }
        if (event.isLeftClick() && !participant.isAlive()) {
            session.revive(participant.uuid());
            refresh();
        }
    }

    @Override
    protected void render() {
        super.render();
        toolbar(2, Icons.of(Material.EMERALD, "<green>Register a tribute",
                        "<gray>Pick anybody the server has ever seen."),
                click -> new PlayerChooser(viewer, brand(), this, "Register a tribute",
                        session.participants().all().stream().map(Participant::uuid).toList(),
                        chosen -> {
                            session.whitelistAdd(chosen.id(), chosen.name());
                            refresh();
                        }).open());
    }

    @Override
    public String describe() {
        return "every registered tribute: revive, eliminate by hand, or remove from the tournament";
    }
}
