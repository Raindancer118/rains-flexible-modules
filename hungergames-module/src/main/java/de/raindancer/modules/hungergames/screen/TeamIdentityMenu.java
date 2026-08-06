package de.raindancer.modules.hungergames.screen;

import de.raindancer.core.social.team.Team;
import de.raindancer.core.social.team.TeamColour;
import de.raindancer.core.social.team.TeamEmblem;
import de.raindancer.core.social.team.TeamOutcome;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.modules.hungergames.service.RoundLogService;
import de.raindancer.modules.hungergames.store.GameSession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What one team looks like: its colour, its emblem, and the item it is drawn as.
 *
 * <h2>The gap this page fills</h2>
 * {@code GameSession.teamSetColour} existed and was called from exactly one place — the HTTP API. No screen
 * and no command reached it, so on a live server the only way to recolour a team was a REST request. A team's
 * colour is how forty tributes tell friend from foe across an arena, and it was effectively unchangeable.
 *
 * <p>The emblem was worse. Core can set one and nothing in this module ever asked, so the two hundred and
 * forty identities it offers were unreachable from the game: a tournament with more than sixteen teams had no
 * way to tell them apart, and one with fewer had a feature nobody could find.
 *
 * <h2>Why a page and not a command</h2>
 * The old plugin had {@code /hg team setcolor}, with the colour typed by hand — which is how that server
 * ended up with teams whose colours nobody could tell apart, because nothing showed you what was already
 * taken while you were choosing. This page shows every colour with the team holding it, so a clash is visible
 * before it is attempted rather than reported afterwards.
 *
 * <h2>Why taken identities are shown rather than hidden</h2>
 * Greyed out and labelled with who has them. Hiding them would make the grid change shape as teams are
 * founded, so the colour somebody was reaching for moves under their cursor — and it would leave them
 * wondering whether a colour is taken or simply not offered by this server.
 */
public final class TeamIdentityMenu extends Menu implements IHungerGamesScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /** Where the colours start, and where the emblems start. Two rows, one for each half of an identity. */
    private static final int COLOURS_FROM = 0;
    private static final int EMBLEMS_FROM = 18;

    /** Choosing the item a team is drawn as — Core's item chooser, handed in so this page needs no server. */
    @FunctionalInterface
    public interface BadgeChooser {
        void choose(Player viewer, Menu returnTo, java.util.function.Consumer<Material> chosen);
    }

    private final GameSession session;
    private final Team team;
    private final RoundLogService roundLog;
    private final BadgeChooser badges;

    public TeamIdentityMenu(Player viewer, Brand brand, Menu parent, GameSession session, Team team,
                            RoundLogService roundLog, BadgeChooser badges) {
        super(viewer, brand, parent, 4);
        this.session = session;
        this.team = team;
        this.roundLog = roundLog;
        this.badges = badges;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>" + team.name() + " — how it looks");
    }

    @Override
    public String breadcrumb() {
        return "Identity";
    }

    /**
     * Whether this page may change anything at all.
     *
     * <p>Teams freeze at a configurable phase, and once they have, every button here is shown greyed with the
     * reason on it rather than left clickable and silently refused. A button that looks live and does nothing
     * is the worse of the two, because the only way to find out is to press it during a countdown.
     */
    private boolean editable() {
        return !session.teams().isFrozen();
    }

    private void tell(String miniMessage) {
        viewer.sendMessage(MINI.deserialize(miniMessage));
    }

    /**
     * A colour's name as somebody would say it.
     *
     * <p>{@code describe()} is the fuller sentence Core writes for a settings page; a grid of sixteen wants
     * two words. Derived rather than added to Core as a second accessor, because "what to call a colour in a
     * grid" is this page's problem and not every plugin's.
     */
    private static String readable(TeamColour colour) {
        String words = colour.name().toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }

    /**
     * The wool block a colour is drawn as.
     *
     * <p>Through the dye colour rather than a map of sixteen entries: Bukkit already names them the same way,
     * and a hand-written table is a table that goes stale the moment a colour is added to Core.
     */
    private static Material wool(TeamColour colour) {
        Material found = Material.matchMaterial(colour.dyeColour().name() + "_WOOL");
        return found == null ? Material.WHITE_WOOL : found;
    }

    @Override
    protected void render() {
        Team current = session.teams().team(team.id()).orElse(team);

        for (TeamColour colour : TeamColour.values()) {
            int slot = COLOURS_FROM + colour.ordinal();
            set(slot, colourIcon(current, colour), click -> pickColour(current, colour));
        }

        // Only the visible emblems, and NONE first — a team with no emblem is a normal team told apart by
        // colour, not one in an incomplete state.
        List<TeamEmblem> offered = new ArrayList<>();
        offered.add(TeamEmblem.NONE);
        offered.addAll(TeamEmblem.visible());
        for (int i = 0; i < offered.size() && EMBLEMS_FROM + i < 27; i++) {
            TeamEmblem emblem = offered.get(i);
            set(EMBLEMS_FROM + i, emblemIcon(current, emblem), click -> pickEmblem(current, emblem));
        }

        toolbar(4, Icons.of(current.badge(), "<yellow>The item this team is drawn as",
                        editable()
                                ? List.of("<gray>Click to choose one.",
                                        "<dark_gray>Two teams may share an item — the colour",
                                        "<dark_gray>and emblem are what tell them apart.")
                                : List.of("<red>Teams are frozen for this round.")),
                click -> {
                    if (!editable()) {
                        tell("<red>Teams are frozen for this round.");
                        return;
                    }
                    badges.choose(viewer, this, material -> applyBadge(current, material));
                });
    }

    private ItemStack colourIcon(Team current, TeamColour colour) {
        boolean mine = current.colour() == colour;
        String holder = holderOf(colour, current);
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + colour.describe());
        if (mine) {
            lore.add("<green>This team's colour.");
        } else if (holder != null) {
            lore.add("<red>Held by " + holder + ".");
            lore.add("<dark_gray>Available again if they take an emblem");
            lore.add("<dark_gray>this team does not have.");
        } else if (!editable()) {
            lore.add("<red>Teams are frozen for this round.");
        } else {
            lore.add("<yellow>Click to take it.");
        }
        ItemStack icon = Icons.of(wool(colour), "<white>" + readable(colour), lore);
        return mine ? icon : (holder == null ? icon : Icons.locked(icon, "Held by " + holder));
    }

    private ItemStack emblemIcon(Team current, TeamEmblem emblem) {
        boolean mine = current.emblem() == emblem;
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + emblem.describe());
        if (mine) {
            lore.add("<green>This team's emblem.");
        } else if (!editable()) {
            lore.add("<red>Teams are frozen for this round.");
        } else {
            lore.add("<yellow>Click to take it.");
        }
        return Icons.of(emblem.suggestedBadge(),
                "<white>" + (emblem.isVisible() ? emblem.glyph() + " " : "") + emblem.title(), lore);
    }

    /** Which other team holds that colour with this team's emblem, or null. */
    private String holderOf(TeamColour colour, Team current) {
        for (Team other : session.teams().all()) {
            if (!other.id().equals(current.id()) && other.colour() == colour
                    && other.emblem() == current.emblem()) {
                return other.name();
            }
        }
        return null;
    }

    private void pickColour(Team current, TeamColour colour) {
        TeamOutcome outcome = session.teamSetColour(current.id(), colour);
        report(outcome, current.name() + " is now " + readable(colour));
    }

    private void pickEmblem(Team current, TeamEmblem emblem) {
        TeamOutcome outcome = session.teamSetEmblem(current.id(), emblem);
        report(outcome, current.name() + " now wears " + emblem.title());
    }

    private void applyBadge(Team current, Material material) {
        TeamOutcome outcome = session.teamSetBadge(current.id(), material);
        report(outcome, current.name() + " is drawn as "
                + material.name().toLowerCase(Locale.ROOT).replace('_', ' '));
    }

    /**
     * Says what happened, and never swallows a refusal.
     *
     * <p>{@code TeamOutcome} is the reason, and it is shown rather than turned into "that did not work" —
     * "another team already has that" and "teams are frozen" send somebody to two different places.
     */
    private void report(TeamOutcome outcome, String whatHappened) {
        if (outcome.isSuccess()) {
            roundLog.log("ADMIN", viewer.getName() + ": " + whatHappened);
            tell("<green>" + whatHappened + ".");
        } else {
            tell("<red>" + reasonFor(outcome));
        }
        refresh();
    }

    /** A refusal in a sentence somebody can act on. */
    static String reasonFor(TeamOutcome outcome) {
        return switch (outcome) {
            case COLOUR_TAKEN -> "Another team already looks exactly like that.";
            case FROZEN -> "Teams are frozen for this round.";
            case NO_SUCH_TEAM -> "That team no longer exists.";
            default -> "That could not be done (" + outcome.key() + ").";
        };
    }

    @Override
    public String describe() {
        return "one team's colour, emblem and item";
    }
}
