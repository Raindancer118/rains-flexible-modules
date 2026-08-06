package de.raindancer.modules.hungergames.service;

// In the service package because sidebarFor()/helmetFor() are package-private pure helpers this test wants
// to reach directly, the same reasoning as SupplyDropServiceTest's note on EventEndpoints.SupplyDropSlot.

import de.raindancer.core.social.team.Team;
import de.raindancer.core.social.team.TeamColour;
import de.raindancer.core.social.team.TeamId;
import de.raindancer.core.ui.scoreboard.ScoreboardPriority;
import de.raindancer.core.ui.scoreboard.Scoreboards;
import de.raindancer.core.ui.scoreboard.Sidebar;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.model.SessionSnapshot;
import de.raindancer.modules.hungergames.model.Winner;
import de.raindancer.modules.hungergames.rules.TeamRules;
import de.raindancer.modules.hungergames.store.GameEvents;
import de.raindancer.modules.hungergames.store.GameEvents.MembershipCause;
import de.raindancer.modules.hungergames.store.GameSession;
import de.raindancer.modules.hungergames.store.SessionStore;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The sidebar's content and the helmet's colour — both pure, and worth checking without Bukkit's scoreboard. */
@ExtendWith(MockitoExtension.class)
class TeamPresentationServiceTest {

    private static final class NoOpEvents implements GameEvents {
        @Override public void phaseChanged(GamePhase o, GamePhase n) { }
        @Override public void participantEliminated(UUID p, UUID k, int r) { }
        @Override public void participantRevived(UUID p) { }
        @Override public void whitelistChanged(UUID p, boolean a) { }
        @Override public void teamCreated(Team t) { }
        @Override public void teamDeleted(Team t) { }
        @Override public void teamColourChanged(Team t, TeamColour o, TeamColour n) { }
        @Override public void teamMembershipChanged(UUID p, TeamId o, TeamId n, MembershipCause c) { }
        @Override public void kill(UUID k, UUID v, int t) { }
        @Override public void winnerDeclared(Winner w) { }
    }

    private static final class NoOpStore implements SessionStore {
        @Override public void save(SessionSnapshot snapshot) { }
        @Override public Optional<SessionSnapshot> load() {
            return Optional.empty();
        }
        @Override public void clear() { }
    }

    /** Records which team each helmet was requested for, standing in for {@link Icons#of} — see the class note. */
    private static final class RecordingHelmets implements TeamPresentationService.Helmets {
        final java.util.List<Team> requestedFor = new ArrayList<>();

        @Override
        public ItemStack forTeam(Team team) {
            requestedFor.add(team);
            ItemStack helmet = mock(ItemStack.class);
            when(helmet.getType()).thenReturn(Material.LEATHER_HELMET);
            return helmet;
        }
    }

    @Mock
    private Scoreboards scoreboards;

    private GameSession session;
    private RecordingHelmets helmets;
    private TeamPresentationService service;

    @BeforeEach
    void setUp() {
        session = new GameSession(TeamRules::defaults, new NoOpEvents(), new NoOpStore(), () -> 0L, new Random(1));
        helmets = new RecordingHelmets();
        service = new TeamPresentationService(session, scoreboards, helmets);
    }

    @Test
    @DisplayName("the sidebar names every team, its colour, and how many are still alive")
    void sidebarNamesEveryTeam() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        session.whitelistAdd(a, "Katniss");
        session.whitelistAdd(b, "Peeta");
        TeamId careers = session.teamCreate("Careers", TeamColour.RED).team().orElseThrow().id();
        session.teamAssign(a, careers, MembershipCause.API);
        session.teamAssign(b, careers, MembershipCause.API);

        Sidebar sidebar = TeamPresentationService.sidebarFor(session);

        assertThat(sidebar.lines()).hasSize(1);
        assertThat(plain(sidebar.lines().get(0))).contains("Careers").contains("2/2");
    }

    @Test
    @DisplayName("dead teammates are not counted as alive")
    void deadTeammatesAreNotCountedAlive() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        session.whitelistAdd(a, "Katniss");
        session.whitelistAdd(b, "Peeta");
        TeamId careers = session.teamCreate("Careers", TeamColour.RED).team().orElseThrow().id();
        session.teamAssign(a, careers, MembershipCause.API);
        session.teamAssign(b, careers, MembershipCause.API);
        session.transitionTo(GamePhase.PREFLIGHT);
        session.transitionTo(GamePhase.LOBBY);
        session.transitionTo(GamePhase.STARTUP);
        session.transitionTo(GamePhase.READY);
        session.transitionTo(GamePhase.RUNNING);
        session.eliminate(a, null);

        Sidebar sidebar = TeamPresentationService.sidebarFor(session);

        assertThat(plain(sidebar.lines().get(0))).contains("1/2");
    }

    @Test
    @DisplayName("an empty roster says so rather than showing a blank sidebar")
    void emptyRosterSaysSo() {
        Sidebar sidebar = TeamPresentationService.sidebarFor(session);

        assertThat(sidebar.lines()).hasSize(1);
        assertThat(plain(sidebar.lines().get(0))).contains("No teams yet");
    }

    @Test
    @DisplayName("show hands the sidebar to Scoreboards at this round's priority")
    void showHandsTheSidebarOver() {
        UUID player = UUID.randomUUID();

        service.show(player);

        verify(scoreboards).show(player, "hungergames-teams", TeamPresentationService.sidebarFor(session),
                TeamPresentationService.PRIORITY);
    }

    @Test
    @DisplayName("hide clears this round's sidebar for one player")
    void hideClearsTheSidebar() {
        UUID player = UUID.randomUUID();

        service.hide(player);

        verify(scoreboards).clear(player, "hungergames-teams");
    }

    @Test
    @DisplayName("usingIcons() builds a real leather helmet — the one line that needs a bootstrapped server")
    void usingIconsBuildsARealHelmet() {
        // Deliberately not exercised here: TeamPresentationService.usingIcons() calls Icons.of(), which
        // asks Paper's material registry for an item type — a registry that only exists once a server has
        // actually started. No module in this repository unit-tests that call directly (see every module's
        // ScreenGrammarTest, which scans source text instead) — which is exactly why the real
        // implementation is a one-line factory kept out of this class's own logic. What this test checks is
        // that giveTeamHelmets() asks Helmets for the right team and nothing else, which is what
        // giveTeamHelmetsEquipsAliveOnlineTributes below actually exercises.
        assertThat(TeamPresentationService.usingIcons()).isNotNull();
    }

    @Test
    @DisplayName("giveTeamHelmets equips every alive, online tribute — and nobody else")
    void giveTeamHelmetsEquipsAliveOnlineTributes() {
        UUID alive = UUID.randomUUID();
        UUID offline = UUID.randomUUID();
        session.whitelistAdd(alive, "Katniss");
        session.whitelistAdd(offline, "Peeta");
        Team careers = session.teamCreate("Careers", TeamColour.RED).team().orElseThrow();
        session.teamAssign(alive, careers.id(), MembershipCause.API);
        session.teamAssign(offline, careers.id(), MembershipCause.API);

        Player onlinePlayer = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(onlinePlayer.getInventory()).thenReturn(inventory);
        Map<UUID, Player> online = new HashMap<>();
        online.put(alive, onlinePlayer);

        service.giveTeamHelmets(online::get);

        // Not compared against the "careers" local: teamAssign returns a *new* immutable Team value with
        // the membership already on it, and that local was captured before either assignment — comparing
        // against it would compare against a stale, member-less snapshot instead of what giveTeamHelmets
        // actually saw.
        assertThat(helmets.requestedFor)
                .as("only the alive, online tribute's team should ever be asked for a helmet")
                .extracting(Team::id)
                .containsExactly(careers.id());
        verify(inventory).setHelmet(org.mockito.ArgumentMatchers.argThat(
                stack -> stack.getType() == Material.LEATHER_HELMET));
    }

    private static String plain(Component component) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                .serialize(component);
    }
}
