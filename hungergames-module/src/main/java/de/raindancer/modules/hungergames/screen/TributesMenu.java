package de.raindancer.modules.hungergames.screen;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.choose.PlayerChooser;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.prompt.ChatPrompts;
import de.raindancer.modules.hungergames.store.TributeRoster;
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
    private final ChatPrompts prompts;
    private final TributeRoster roster;

    public TributesMenu(Player viewer, Brand brand, Menu parent, GameSession session,
                        ChatPrompts prompts, TributeRoster roster) {
        super(viewer, brand, parent);
        this.session = session;
        this.prompts = prompts;
        this.roster = roster;
    }

    /**
     * The same, without the two ways of adding somebody the server has never seen.
     *
     * <p>Kept so a host that has wired neither still gets a working page — the picker and every per-tribute
     * action work without them. Both extra buttons simply are not drawn, rather than being drawn dead.
     */
    public TributesMenu(Player viewer, Brand brand, Menu parent, GameSession session) {
        this(viewer, brand, parent, session, null, null);
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

        // Three ways in, because a tournament's tributes arrive three ways: somebody standing here, a name off
        // a sheet, or forty names pasted into a file the night before.
        toolbar(1, Icons.of(Material.PLAYER_HEAD, "<green>Pick somebody",
                        List.of("<gray>Anybody this server has seen before.",
                                "<dark_gray>Most of a sign-up sheet has not been here —",
                                "<dark_gray>use \"By name\" for them.")),
                click -> new PlayerChooser(viewer, brand(), this, "Register a tribute",
                        session.participants().all().stream().map(Participant::uuid).toList(),
                        chosen -> registerByName(chosen.name()))
                        .open());

        toolbar(3, Icons.of(Material.NAME_TAG, "<green>By name",
                        List.of("<gray>Somebody who has never been here.",
                                "<gray>You will be asked for the name in chat.",
                                "<dark_gray>They become a real tribute when they first join.")),
                click -> askForAName());

        if (roster != null) {
            toolbar(5, Icons.of(Material.WRITABLE_BOOK, "<yellow>Read " + TributeRoster.FILE_NAME,
                            List.of("<gray>The whole sign-up sheet, in one go.",
                                    "<gray>Paste the names into the file, then click this.",
                                    "<dark_gray>Reading it twice is harmless.",
                                    "<dark_gray>" + roster.file().getFileName())),
                    click -> readTheRoster());
        }
    }

    /**
     * Registers one name, whoever it belongs to.
     *
     * <p>One method for all three routes, so a tribute added by hand, by picker or by file is the same tribute
     * — the derived id is the same function {@code /allow} uses, and a name registered twice by two routes
     * would otherwise be two people, one of whom can never be matched to a player.
     */
    private void registerByName(String name) {
        if (!TributeRoster.isPlausibleName(name)) {
            tell("<red>" + name + " is not a Minecraft name.");
            return;
        }
        boolean added = session.whitelistAdd(TributeRoster.derivedIdFor(name), name.strip());
        if (added && roster != null) {
            // Written to the sheet as well, so the file and the register do not drift apart the first time
            // somebody uses both.
            roster.remember(name);
        }
        tell(added ? "<green>✔ " + name.strip() + " is a tribute."
                : "<yellow>" + name.strip() + " was already a tribute.");
        open();
    }

    /**
     * Asks for a name in chat.
     *
     * <p>Chat rather than a picker, because the whole point is somebody the picker cannot offer: a player list
     * only knows who has been here, and most of a sign-up sheet has not.
     */
    private void askForAName() {
        if (prompts == null) {
            tell("<red>This build has no chat prompt wired, so a name cannot be typed here.");
            return;
        }
        viewer.closeInventory();
        tell("<yellow>Type the tribute's name in chat. <gray>Say <white>cancel</white> to stop.</gray>");
        prompts.ask(viewer.getUniqueId(), "hungergames-tributes", java.time.Duration.ofSeconds(60),
                typed -> {
                    String name = typed == null ? "" : typed.strip();
                    if (name.isEmpty() || name.equalsIgnoreCase("cancel")) {
                        open();
                        return;
                    }
                    registerByName(name);
                },
                () -> {
                    tell("<gray>Nothing was typed, so nobody was registered.");
                    open();
                });
    }

    /** Reads the sheet, and says what it found — including what it could not use. */
    private void readTheRoster() {
        roster.createIfMissing();
        TributeRoster.Report report = roster.load();

        int added = 0;
        for (TributeRoster.Entry entry : report.found()) {
            if (session.whitelistAdd(entry.derivedId(), entry.name())) {
                added++;
            }
        }
        if (report.found().isEmpty() && report.problems().isEmpty()) {
            tell("<yellow>" + TributeRoster.FILE_NAME + " is empty. <gray>Paste your names into "
                    + roster.file().getFileName() + " and click again.</gray>");
        } else {
            tell("<green>✔ " + added + " new tribute(s) from " + TributeRoster.FILE_NAME
                    + " <gray>(" + report.found().size() + " name(s) on the sheet)</gray>");
        }
        // Every problem, individually. A count of skipped names is a count somebody has to go and diff.
        report.problems().forEach(problem -> tell("<yellow>⚠ " + problem));
        open();
    }

    private void tell(String miniMessage) {
        viewer.sendMessage(MINI.deserialize(miniMessage));
    }

    @Override
    public String describe() {
        return "every registered tribute: revive, eliminate by hand, or remove from the tournament";
    }
}
