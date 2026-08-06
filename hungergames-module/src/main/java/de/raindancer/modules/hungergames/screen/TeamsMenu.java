package de.raindancer.modules.hungergames.screen;

import de.raindancer.core.social.team.Team;
import de.raindancer.core.social.team.TeamColour;
import de.raindancer.core.social.team.TeamEmblem;
import de.raindancer.core.social.team.TeamOutcome;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.core.ui.prompt.ChatPrompts;
import de.raindancer.modules.hungergames.rules.TeamRules;
import de.raindancer.modules.hungergames.store.GameEvents.MembershipCause;
import de.raindancer.modules.hungergames.store.GameSession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Picking a team — for a tribute standing in the lobby, not for an admin.
 *
 * <h2>Where the colour picker went</h2>
 * It did not move, it stopped existing: {@link TeamColour} is Core's, {@code Teams.create} already gives a
 * fresh team the first colour nobody else is holding, and there is no page in this module that asks a
 * player to pick one — see the module's porting brief. The one place a colour was ever chosen by hand in
 * the source plugin was inside a captain's own settings, which is a feature this port does not carry
 * forward for tributes (an admin can still recolour a team from {@link TeamAdminMenu} once it exists).
 *
 * <h2>The badge is drawn, not yet chosen here</h2>
 * {@link Team#badge()} and {@link Team#display()} are read on every button — a team's row is its own badge
 * with its own name, emblem included. Letting a team's members choose that badge through Core's item
 * chooser needs a way to write it back onto the roster, and {@code Teams} does not yet expose one: it can
 * be read from a {@link Team}, but nothing on {@code Teams} sets it after a team already exists. That is
 * tracked as a follow-up in Core rather than fudged here with a second, private notion of "this team's
 * item" that {@code Teams} itself would not agree with.
 */
public final class TeamsMenu extends PaginatedMenu<Team> implements IHungerGamesScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final GameSession session;
    private final Supplier<TeamRules> rules;
    private final ChatPrompts prompts;

    public TeamsMenu(Player viewer, Brand brand, GameSession session, Supplier<TeamRules> rules,
                     ChatPrompts prompts) {
        super(viewer, brand, null);
        this.session = session;
        this.rules = rules;
        this.prompts = prompts;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Choose a team");
    }

    @Override
    public String breadcrumb() {
        return "Teams";
    }

    @Override
    protected List<Team> entries() {
        return session.teams().all();
    }

    @Override
    protected ItemStack emptyIcon() {
        // Asked exactly the way the button is — see mayStartOne. This used to check only whether players may
        // create teams at all, so a locked round showed "use the button below" with no button below it, and
        // the one thing the page had to say was a lie.
        return Icons.of(Material.BARRIER, "<gray>No teams yet",
                mayStartOne()
                        ? "<gray>Use <white>Create a team</white>, at the bottom left."
                        : rules.get().playersCanCreateTeams()
                                ? "<gray>Too late to start one — teams are settled for this round."
                                : "<gray>A gamemaster makes the teams. Wait for one to appear.");
    }

    @Override
    protected ItemStack icon(Team team) {
        TeamRules r = rules.get();
        boolean isMember = team.isMember(viewer.getUniqueId());
        boolean full = r.maxTeamSize() > 0 && team.size() >= r.maxTeamSize();

        List<String> lore = new ArrayList<>();
        lore.add("<gray>Members: " + team.size() + (r.maxTeamSize() > 0 ? "/" + r.maxTeamSize() : ""));
        for (var member : team.members()) {
            String name = session.participants().nameOf(member).orElse("?");
            boolean captain = team.isCaptain(member);
            lore.add("<dark_gray> - " + name + (captain ? " (captain)" : ""));
        }
        lore.add("");
        if (isMember) {
            lore.add("<green>Your team.");
        } else if (full) {
            lore.add("<red>Full.");
        } else if (!eligible(r)) {
            lore.add("<red>Teams are locked for this round.");
        } else {
            lore.add("<aqua>Click to join.");
        }

        return Icons.of(team.badge(), "<white>" + team.display()
                + "</white> <dark_gray>(" + team.colour().describe() + ")", lore);
    }

    @Override
    protected void onClick(Team team, InventoryClickEvent event) {
        if (team.isMember(viewer.getUniqueId())) {
            return;
        }
        TeamOutcome outcome = session.teamAssign(viewer.getUniqueId(), team.id(), MembershipCause.PLAYER);
        tell(outcome.isSuccess()
                ? "<green>You joined " + team.display() + "."
                : "<red>Could not join: " + outcome.key());
        refresh();
    }

    @Override
    protected void render() {
        super.render();
        TeamRules r = rules.get();
        Optional<Team> ownTeam = session.teams().teamOf(viewer.getUniqueId());

        // Always drawn, greyed with the reason when it cannot be used.
        //
        // It used to be drawn only when it worked, which is the worse of the two: a player looking for
        // "Create a team" and not finding it does not learn that teams are settled for this round — they
        // learn that the page is different from the one they remember. It also moved everything after it, so
        // the button somebody was reaching for ended up somewhere else under their cursor.
        ItemStack createButton = Icons.of(Material.NETHER_STAR, "<green>Create a team",
                "<gray>You will be asked for a name in chat.",
                session.teams().availableColours().isEmpty()
                        ? "<red>No colour is free — this will be refused."
                        : "<dark_gray>" + session.teams().availableColours().size() + " colour(s) free.");

        toolbar(2, mayStartOne(), createButton,
                r.playersCanCreateTeams()
                        ? "Teams are settled for this round."
                        : "A gamemaster makes the teams here.",
                click -> askForTeamName());

        if (ownTeam.isPresent()) {
            toolbar(6, Icons.of(Material.RED_BED, "<yellow>Leave " + ownTeam.get().display(),
                            "<gray>You can join another team afterwards."),
                    click -> {
                        TeamOutcome outcome = session.teamRemovePlayer(viewer.getUniqueId(), MembershipCause.PLAYER);
                        tell(outcome.isSuccess() ? "<yellow>You left your team."
                                : "<red>Could not leave: " + outcome.key());
                        refresh();
                    });
        }
    }

    private boolean eligible(TeamRules r) {
        return !r.isLocked(session.phase());
    }

    /**
     * Whether this viewer can start a team right now.
     *
     * <p>One method, asked by both the button and the empty page, because they were two conditions describing
     * one thing and they disagreed: the empty page checked only whether players may create teams at all, so a
     * round whose teams had already been settled said "use the button below" with nothing below it. A page
     * whose only sentence is wrong is worse than a page with no sentence.
     */
    private boolean mayStartOne() {
        TeamRules r = rules.get();
        return r.playersCanCreateTeams() && eligible(r);
    }

    private void askForTeamName() {
        viewer.closeInventory();
        tell("<yellow>Type your team's name in chat.");
        prompts.ask(viewer.getUniqueId(), "hungergames-teams", Duration.ofSeconds(60),
                typed -> {
                    String name = typed == null ? "" : typed.trim();
                    if (name.isEmpty()) {
                        tell("<red>Nothing usable was typed — no team created.");
                    } else {
                        var result = session.teamCreate(name, firstFreeColour());
                        if (result.status().isSuccess()) {
                            session.teamAssign(viewer.getUniqueId(), result.team().orElseThrow().id(),
                                    MembershipCause.PLAYER);
                            tell("<green>Team \"" + name + "\" created — you're on it.");
                        } else {
                            tell("<red>Could not create the team: " + result.status().key());
                        }
                    }
                    open();
                },
                () -> tell("<red>Nothing was typed in time — no team created."));
    }

    /** Any colour nobody else is holding — {@code Teams.create} still needs one named, not merely "some". */
    private TeamColour firstFreeColour() {
        var free = session.teams().availableColours();
        return free.isEmpty() ? TeamColour.WHITE : free.iterator().next();
    }

    /**
     * How many (colour, emblem) identities remain unused right now — the real ceiling {@link TeamEmblem}
     * raises this module to, even though team creation can still only reach the sixteen colours until Core
     * lets a team's emblem be set after it exists. Pure, for {@code TeamsMenuTest}.
     */
    static int freeIdentityCount(List<Team> existing) {
        java.util.Set<Team.Identity> taken = new java.util.HashSet<>();
        for (Team team : existing) {
            taken.add(team.identity());
        }
        return TeamEmblem.distinctIdentities() - taken.size();
    }

    private void tell(String miniMessage) {
        viewer.sendMessage(MINI.deserialize(miniMessage));
    }

    @Override
    public String describe() {
        return "picking a team, for a tribute in the lobby";
    }
}
