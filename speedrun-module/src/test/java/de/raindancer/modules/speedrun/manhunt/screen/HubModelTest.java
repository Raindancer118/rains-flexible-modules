package de.raindancer.modules.speedrun.manhunt.screen;

import de.raindancer.modules.speedrun.manhunt.ManhuntServices;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Manhunt's own page: only what is Manhunt's")
class HubModelTest {

    private static HubModel.View lobby(boolean admin) {
        return new HubModel.View(admin, false, false, true, HubModel.Side.NONE, 1, 3, true, true);
    }

    private static Optional<HubModel.Button> button(List<HubModel.Button> buttons, String id) {
        return buttons.stream().filter(button -> button.id().equals(id)).findFirst();
    }

    @Test
    @DisplayName("an admin reaches every Manhunt action from it, without a single command")
    void adminHasEverything() {
        List<HubModel.Button> buttons = HubModel.buttons(lobby(true));

        assertThat(buttons).extracting(HubModel.Button::id).contains("sides", "balance", "random", "give",
                "settings", "trail", "announcements", "here");
        assertThat(button(buttons, "sides").orElseThrow().page()).isEqualTo(ManhuntServices.Page.SIDES);
        assertThat(button(buttons, "give").orElseThrow().lockedBecause()).as("only during a hunt").isNotNull();
        assertThat(button(buttons, "settings").orElseThrow().target()).isEqualTo("speedrun/manhunt");
    }

    /**
     * Starting, resuming, the goal, the pre-flight check, the setup assistant, stats, leaderboards,
     * history and where the splits are shown are the lobby's own pages — one of each, for every game.
     */
    @Test
    @DisplayName("nothing the lobby already has: no start, resume, goal, pre-flight, setup, stats, boards, history or HUD")
    void nothingTwice() {
        for (boolean admin : new boolean[]{true, false}) {
            assertThat(HubModel.buttons(lobby(admin))).extracting(HubModel.Button::id)
                    .doesNotContain("start", "resume", "goal", "preflight", "setup", "stats", "leaderboard",
                            "history", "sidebar");
        }
    }

    @Test
    @DisplayName("a player sees the simple version: a side and their switches — nothing of an admin's")
    void playerHasTheSimpleVersion() {
        List<HubModel.Button> buttons = HubModel.buttons(lobby(false));

        assertThat(buttons).extracting(HubModel.Button::id).contains("run", "hunt", "leave", "trail", "here")
                .doesNotContain("balance", "give", "sides", "settings");
    }

    @Test
    @DisplayName("mid-hunt: no balancing; compasses can be handed out; players switch only where allowed")
    void midHunt() {
        HubModel.View admin = new HubModel.View(true, true, false, true, HubModel.Side.HUNTER, 0, 0, true, true);
        List<HubModel.Button> buttons = HubModel.buttons(admin);
        assertThat(button(buttons, "balance").orElseThrow().lockedBecause()).isNotNull();
        assertThat(button(buttons, "give").orElseThrow().lockedBecause()).isNull();

        HubModel.View fixed = new HubModel.View(false, true, false, true, HubModel.Side.HUNTER, 0, 0, true, true);
        assertThat(button(HubModel.buttons(fixed), "run").orElseThrow().lockedBecause()).isNotNull();
        HubModel.View switching = new HubModel.View(false, true, true, true, HubModel.Side.HUNTER, 0, 0, true, true);
        assertThat(button(HubModel.buttons(switching), "run").orElseThrow().lockedBecause()).isNull();
    }

    @Test
    @DisplayName("with Runners hand-picked, Run is locked for players, saying who picks")
    void runnersLocked() {
        HubModel.View locked = new HubModel.View(false, false, false, false, HubModel.Side.NONE, 1, 1, true, true);

        assertThat(button(HubModel.buttons(locked), "run").orElseThrow().lockedBecause()).contains("admin");
    }

    @Test
    @DisplayName("every button fits the page: bands of at most seven, no two in one place")
    void fits() {
        for (boolean admin : new boolean[]{true, false}) {
            List<HubModel.Button> buttons = HubModel.buttons(lobby(admin));
            for (HubModel.Button button : buttons) {
                assertThat(button.column()).isBetween(1, 7);
            }
            assertThat(buttons.stream().map(button -> button.band() + ":" + button.column()).distinct().count())
                    .isEqualTo(buttons.size());
        }
    }

    @Test
    @DisplayName("on the sides page: left makes a Runner, right a Hunter, shift takes them off their side")
    void sidesClicks() {
        assertThat(HubModel.sideClick("Ben", false, false)).isEqualTo("manhunt assign Ben runner");
        assertThat(HubModel.sideClick("Ben", true, false)).isEqualTo("manhunt assign Ben hunter");
        assertThat(HubModel.sideClick("Ben", false, true)).isEqualTo("manhunt unassign Ben");
    }
}
