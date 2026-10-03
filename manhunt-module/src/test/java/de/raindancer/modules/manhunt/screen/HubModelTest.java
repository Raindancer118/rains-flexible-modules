package de.raindancer.modules.manhunt.screen;

import de.raindancer.modules.manhunt.ManhuntServices;
import de.raindancer.modules.manhunt.setup.Preflight;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("the hub: one page, everything on it")
class HubModelTest {

    private static HubModel.View lobby(boolean admin) {
        return new HubModel.View(admin, false, false, true, HubModel.Side.NONE, 1, 3, 0, 1, "Kill the dragon",
                true, true, true);
    }

    private static Optional<HubModel.Button> button(List<HubModel.Button> buttons, String id) {
        return buttons.stream().filter(button -> button.id().equals(id)).findFirst();
    }

    @Test
    @DisplayName("an admin reaches every page and every action from it, without a single command")
    void adminHasEverything() {
        List<HubModel.Button> buttons = HubModel.buttons(lobby(true));

        assertThat(buttons).extracting(HubModel.Button::id).contains("sides", "balance", "random", "preflight",
                "start", "resume", "goal", "give", "settings", "leaderboard", "history", "setup",
                "stats", "trail", "sidebar", "announcements", "here");
        assertThat(button(buttons, "start").orElseThrow().target()).isEqualTo("manhunt start");
        assertThat(button(buttons, "sides").orElseThrow().page()).isEqualTo(ManhuntServices.Page.SIDES);
        assertThat(button(buttons, "give").orElseThrow().lockedBecause()).as("only during a hunt").isNotNull();
    }

    @Test
    @DisplayName("a player sees the simple version: a side, their stats, their switches — nothing of an admin's")
    void playerHasTheSimpleVersion() {
        List<HubModel.Button> buttons = HubModel.buttons(lobby(false));

        assertThat(buttons).extracting(HubModel.Button::id).contains("run", "hunt", "leaderboard", "history",
                "stats", "trail", "sidebar", "here")
                .doesNotContain("start", "balance", "setup", "give", "goal");
    }

    @Test
    @DisplayName("with something blocking the start, Start opens the pre-flight page instead of failing")
    void blockedStartGoesToPreflight() {
        HubModel.View blocked = new HubModel.View(true, false, false, true, HubModel.Side.NONE, 0, 4, 1, 0, "—",
                true, true, true);

        HubModel.Button start = button(HubModel.buttons(blocked), "start").orElseThrow();

        assertThat(start.page()).isEqualTo(ManhuntServices.Page.PREFLIGHT);
        assertThat(start.target()).isNull();
    }

    @Test
    @DisplayName("mid-hunt: no start, no balancing; compasses can be handed out; players switch only where allowed")
    void midHunt() {
        HubModel.View admin = new HubModel.View(true, true, false, true, HubModel.Side.HUNTER, 0, 0, 0, 0, "—",
                true, true, true);
        List<HubModel.Button> buttons = HubModel.buttons(admin);
        assertThat(button(buttons, "start").orElseThrow().lockedBecause()).isNotNull();
        assertThat(button(buttons, "balance").orElseThrow().lockedBecause()).isNotNull();
        assertThat(button(buttons, "give").orElseThrow().lockedBecause()).isNull();

        HubModel.View fixed = new HubModel.View(false, true, false, true, HubModel.Side.HUNTER, 0, 0, 0, 0, "—",
                true, true, true);
        assertThat(button(HubModel.buttons(fixed), "run").orElseThrow().lockedBecause()).isNotNull();
        HubModel.View switching = new HubModel.View(false, true, true, true, HubModel.Side.HUNTER, 0, 0, 0, 0, "—",
                true, true, true);
        assertThat(button(HubModel.buttons(switching), "run").orElseThrow().lockedBecause()).isNull();
    }

    @Test
    @DisplayName("with Runners hand-picked, Run is locked for players, saying who picks")
    void runnersLocked() {
        HubModel.View locked = new HubModel.View(false, false, false, false, HubModel.Side.NONE, 1, 1, 0, 0, "—",
                true, true, true);

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
    @DisplayName("each pre-flight fix is one click: a command or a page")
    void fixes() {
        for (Preflight.Fix fix : Preflight.Fix.values()) {
            HubModel.Click click = HubModel.fix(fix);
            if (fix == Preflight.Fix.NONE) {
                assertThat(click).isNull();
            } else {
                assertThat(click.command() != null || click.page() != null).as(fix.name()).isTrue();
            }
        }
        assertThat(HubModel.fix(Preflight.Fix.PICK_RANDOM_RUNNER).command()).isEqualTo("manhunt random 1");
        assertThat(HubModel.fix(Preflight.Fix.CHOOSE_GOAL).page()).isEqualTo(ManhuntServices.Page.GOAL);
    }

    @Test
    @DisplayName("on the sides page: left makes a Runner, right a Hunter, shift takes them off their side")
    void sidesClicks() {
        assertThat(HubModel.sideClick("Ben", false, false)).isEqualTo("manhunt assign Ben runner");
        assertThat(HubModel.sideClick("Ben", true, false)).isEqualTo("manhunt assign Ben hunter");
        assertThat(HubModel.sideClick("Ben", false, true)).isEqualTo("manhunt unassign Ben");
    }

    @Test
    @DisplayName("the wizard: a preset, the door, a goal, done — each step one click, and back a step at any time")
    void wizard() {
        assertThat(HubModel.wizardSteps()).containsExactly(HubModel.WizardStep.PRESET, HubModel.WizardStep.DOOR,
                HubModel.WizardStep.GOAL, HubModel.WizardStep.DONE);
        assertThat(HubModel.WizardStep.PRESET.next()).isEqualTo(HubModel.WizardStep.DOOR);
        assertThat(HubModel.WizardStep.DONE.next()).isEqualTo(HubModel.WizardStep.DONE);
        assertThat(HubModel.WizardStep.DOOR.previous()).isEqualTo(HubModel.WizardStep.PRESET);
        assertThat(HubModel.WizardStep.PRESET.previous()).isEqualTo(HubModel.WizardStep.PRESET);
    }
}
