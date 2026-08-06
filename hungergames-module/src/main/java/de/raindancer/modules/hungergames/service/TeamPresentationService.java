package de.raindancer.modules.hungergames.service;

import de.raindancer.core.social.team.Team;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.scoreboard.ScoreboardPriority;
import de.raindancer.core.ui.scoreboard.Scoreboards;
import de.raindancer.core.ui.scoreboard.Sidebar;
import de.raindancer.modules.hungergames.HungerGamesSettings;
import de.raindancer.modules.hungergames.store.GameSession;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * The round's visible team identity: a colour-matched leather helmet for every tribute, and a sidebar
 * naming every team's colour and how many of its tributes are still standing.
 *
 * <h2>Why there is no coloured nametag here</h2>
 * The source engine coloured a tribute's floating nametag by putting them on a vanilla
 * {@code org.bukkit.scoreboard.Team}, reached through {@code Bukkit.getScoreboardManager()} — exactly the
 * pattern {@code ReuseTest} forbids, in favour of {@link Scoreboards}. But {@link Scoreboards} is a
 * per-player sidebar service (see its own class note), not a nametag-colour service, and RainsCore has no
 * equivalent for the latter: a floating nametag's colour is tied to the vanilla scoreboard-team mechanism
 * at the protocol level, and there is no Adventure-only substitute for it. So this wave drops nametag
 * colouring rather than reinventing the vanilla-team machinery {@code ReuseTest} was written to catch, and
 * leans on the helmet — visible from any angle, including from behind — as the round's primary colour
 * signal. Restoring coloured nametags would mean Core growing that capability first.
 *
 * <h2>Why the sidebar is one pure method plus one thin apply</h2>
 * {@link #sidebarFor} takes nothing but a {@link GameSession} and returns a {@link Sidebar} value — no
 * player, no {@link Scoreboards}, nothing Bukkit at all — so {@code TeamPresentationServiceTest} can check
 * exactly what a tribute would see without a server. {@link #show} is the one line that hands that value
 * to {@link Scoreboards}, which is where the arbitration between this and every other plugin's sidebar
 * actually happens; this class has no opinion about priority beyond asking for one.
 *
 * <h2>Why the helmet is built behind a {@link Helmets} seam rather than called as a static helper</h2>
 * {@link Icons#of} builds a real {@code ItemStack}, which asks Paper's material registry for the
 * item type — a registry that only exists once a server has actually bootstrapped one. Calling it directly
 * from this class would mean {@code giveTeamHelmets} could never run under a plain JUnit test, the same way
 * no other module in this repository unit-tests an {@code ItemStack} built through {@code Icons} or
 * {@code Menu} — see e.g. every module's {@code ScreenGrammarTest}, which scans source text rather than
 * running it for exactly this reason. Putting the one Bukkit-registry-touching line behind {@link Helmets}
 * means {@link #giveTeamHelmets} itself — which alive, online tribute gets a helmet at all — is still
 * ordinary logic a test can drive with a two-line fake, and {@link Helmets#usingIcons()} is what production
 * wiring hands in instead.
 */
public final class TeamPresentationService implements IHungerGamesService {

    /** The priority this round's sidebar is shown at — see {@link Scoreboards}' own arbitration note. */
    public static final ScoreboardPriority PRIORITY = ScoreboardPriority.NORMAL;

    private static final String OWNER = "hungergames-teams";

    /** Builds the one physical item this class hands out — see the class note on why this is a seam. */
    @FunctionalInterface
    public interface Helmets {
        ItemStack forTeam(Team team);
    }

    private final GameSession session;
    private final Scoreboards scoreboards;
    private final Helmets helmets;

    public TeamPresentationService(GameSession session, Scoreboards scoreboards, Helmets helmets) {
        this.session = session;
        this.scoreboards = scoreboards;
        this.helmets = helmets;
    }

    /** Nothing here reads a setting today — see {@link IHungerGamesService}'s note on implementing it empty. */
    @Override
    public void settings(HungerGamesSettings settings) {
        // intentionally empty
    }

    // ==================== helmets ====================

    /** Gives every alive, online tribute a helmet dyed in their team's colour. */
    public void giveTeamHelmets(Function<UUID, Player> online) {
        for (UUID alive : session.participants().alive()) {
            Player player = online.apply(alive);
            if (player == null) {
                continue;
            }
            session.teams().teamOf(alive)
                    .ifPresent(team -> player.getInventory().setHelmet(helmets.forTeam(team)));
        }
    }

    /**
     * The real {@link Helmets}: a leather helmet dyed and named for the team, built from {@link Icons} —
     * never a raw {@code new ItemStack(...)} — for production wiring to hand in.
     */
    public static Helmets usingIcons() {
        return team -> {
            ItemStack helmet = Icons.of(Material.LEATHER_HELMET, team.name() + " Helmet");
            if (helmet.getItemMeta() instanceof LeatherArmorMeta meta) {
                meta.setColor(team.colour().armourColour());
                helmet.setItemMeta(meta);
            }
            return helmet;
        };
    }

    // ==================== sidebar ====================

    /** Shows this round's team sidebar to one player. */
    public void show(UUID player) {
        scoreboards.show(player, OWNER, sidebarFor(session), PRIORITY);
    }

    /** Stops showing this round's sidebar to one player — e.g. once the round has finished. */
    public void hide(UUID player) {
        scoreboards.clear(player, OWNER);
    }

    /**
     * The sidebar's content: every team, its colour, and how many of its tributes are still alive out of
     * how many it started with. Pure given a session — see the class note on why this is worth testing on
     * its own.
     */
    static Sidebar sidebarFor(GameSession session) {
        List<Team> teams = session.teams().all().stream()
                .sorted(Comparator.comparing(Team::name))
                .toList();
        List<Component> lines = new ArrayList<>();
        for (Team team : teams) {
            long alive = team.members().stream().filter(session.participants()::isAlive).count();
            lines.add(Component.text(team.name() + ": ", team.colour().namedTextColour())
                    .append(Component.text(alive + "/" + team.size(), NamedTextColor.WHITE)));
        }
        if (lines.isEmpty()) {
            lines.add(Component.text("No teams yet", NamedTextColor.GRAY));
        }
        return Sidebar.of(Component.text("Hunger Games", NamedTextColor.GOLD), lines);
    }

    @Override
    public String describe() {
        return "team helmets and the team sidebar";
    }
}
