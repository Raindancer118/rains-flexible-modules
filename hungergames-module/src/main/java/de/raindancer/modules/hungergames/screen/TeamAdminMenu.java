package de.raindancer.modules.hungergames.screen;

import de.raindancer.core.social.team.Team;
import de.raindancer.core.social.team.TeamOutcome;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.PaginatedMenu;
import de.raindancer.core.ui.prompt.ChatPrompts;
import de.raindancer.modules.hungergames.service.RoundLogService;
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

/**
 * Team administration: making and deleting teams, and spreading the teamless around.
 *
 * <h2>What stayed a command</h2>
 * Assigning one particular player to one particular team is still {@code /hg team assign} — see the source
 * plugin's own {@code TeamAdminMenu} javadoc, which drew the same line: a gamemaster fixing one tribute's
 * team mid-lobby is typing a name they already know, not hunting for it in a grid.
 *
 * <h2>Right-click deletes</h2>
 * The one dangerous thing on this page. It still goes through {@link ConfirmScreen}, even though the click
 * that opens the dialog is a right-click rather than a slot of its own — {@code ScreenGrammarTest} only
 * requires that a {@code danger(} button lead to a confirmation, not that every confirmation come from one;
 * this page has no other irreversible action to spend its one danger slot on, so the delete lives in the
 * grid and the lore says which click does it.
 */
public final class TeamAdminMenu extends PaginatedMenu<Team> implements IHungerGamesScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final GameSession session;
    private final TeamIdentityMenu.BadgeChooser badges;
    private final ChatPrompts prompts;
    private final RoundLogService roundLog;

    public TeamAdminMenu(Player viewer, Brand brand, Menu parent, GameSession session, ChatPrompts prompts,
                         RoundLogService roundLog, TeamIdentityMenu.BadgeChooser badges) {
        super(viewer, brand, parent);
        this.session = session;
        this.prompts = prompts;
        this.roundLog = roundLog;
        this.badges = badges;
    }

    /**
     * The same, for a host that has not wired an item chooser.
     *
     * <p>Refuses to choose rather than opening a page with a dead button on it, and says so. A badge is the
     * one part of a team's identity that needs Core's item chooser, and the colour and emblem halves work
     * without it — so a missing chooser costs one button, not the page.
     */
    public TeamAdminMenu(Player viewer, Brand brand, Menu parent, GameSession session, ChatPrompts prompts,
                         RoundLogService roundLog) {
        this(viewer, brand, parent, session, prompts, roundLog,
                (who, returnTo, chosen) -> who.sendMessage(MINI.deserialize(
                        "<red>This build has no item chooser wired, so a team's item cannot be changed here.")));
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Teams");
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
        return Icons.of(Material.BARRIER, "<gray>No teams yet", "<gray>Use \"Create team\" below.");
    }

    @Override
    protected ItemStack icon(Team team) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + team.colour().describe());
        if (team.members().isEmpty()) {
            lore.add("<dark_gray>No members.");
        } else {
            for (var member : team.members()) {
                String name = session.participants().nameOf(member).orElse("?");
                boolean alive = session.participants().isAlive(member);
                lore.add((alive ? "<gray>" : "<dark_red>") + "- " + name + (team.isCaptain(member) ? " (captain)" : ""));
            }
        }
        lore.add("");
        lore.add("<aqua>Left-click: colour, emblem and item");
        lore.add("<aqua>Right-click: delete");

        return Icons.of(team.badge(), "<white>" + team.display(), lore);
    }

    @Override
    protected void onClick(Team team, InventoryClickEvent event) {
        if (!event.isRightClick()) {
            // Left-click opens what a team looks like. Until this existed, the only way to recolour one was
            // an HTTP request, and its emblem could not be set from the game at all — see TeamIdentityMenu.
            new TeamIdentityMenu(viewer, brand(), this, session, team, roundLog, badges).open();
            return;
        }
        new ConfirmScreen(viewer, brand(), this, "<yellow>Delete " + team.display() + "?",
                List.of("<gray>Every member becomes teamless.", "<gray>The colour becomes free again."),
                () -> {
                    TeamOutcome outcome = session.teamDelete(team.id());
                    roundLog.log("ADMIN", viewer.getName() + " deleted team " + team.name());
                    tell(outcome.isSuccess() ? "<green>Team deleted." : "<red>Could not delete: " + outcome.key());
                    open();
                }).open();
    }

    @Override
    protected void render() {
        super.render();
        toolbar(2, Icons.of(Material.EMERALD, "<green>Create team",
                        "<gray>Asks for a name in chat, picks a free colour."),
                click -> askForTeamName());
        toolbar(6, Icons.of(Material.ENDER_PEARL, "<yellow>Assign teamless randomly",
                        "<gray>Spreads every tribute with no team", "<gray>across the existing ones."),
                click -> {
                    int assigned = session.teamAssignRandomly();
                    roundLog.log("ADMIN", viewer.getName() + " randomly assigned " + assigned + " tribute(s)");
                    tell("<green>" + assigned + " tribute(s) assigned.");
                    refresh();
                });
    }

    private void askForTeamName() {
        viewer.closeInventory();
        tell("<yellow>Type the new team's name in chat.");
        prompts.ask(viewer.getUniqueId(), "hungergames-team-admin", Duration.ofSeconds(60),
                typed -> {
                    String name = typed == null ? "" : typed.trim();
                    if (name.isEmpty()) {
                        tell("<red>Nothing usable was typed — no team created.");
                    } else {
                        var free = session.teams().availableColours();
                        var result = session.teamCreate(name,
                                free.isEmpty() ? de.raindancer.core.social.team.TeamColour.WHITE
                                        : free.iterator().next());
                        roundLog.log("ADMIN", viewer.getName() + " created team " + name);
                        tell(result.status().isSuccess() ? "<green>Team \"" + name + "\" created."
                                : "<red>Could not create the team: " + result.status().key());
                    }
                    open();
                },
                () -> tell("<red>Nothing was typed in time — no team created."));
    }

    private void tell(String miniMessage) {
        viewer.sendMessage(MINI.deserialize(miniMessage));
    }

    @Override
    public String describe() {
        return "creating, deleting and randomly filling this round's teams";
    }
}
