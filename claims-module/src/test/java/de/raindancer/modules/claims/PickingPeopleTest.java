package de.raindancer.modules.claims;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Choosing a person is a click, and choosing anything else may still be typing.
 *
 * <h2>Why the distinction is worth a test</h2>
 * Every screen that needed a player used to ask in chat. Typing a name is the worst way to answer this
 * particular question: the spelling has to be exact, a typo looks identical to somebody who has never joined,
 * a name that changed since they last logged in cannot be typed at all, and answering means closing the menu —
 * so whatever was half-configured is gone.
 *
 * <p>A duration and a reason are different. There is nothing to enumerate for "three days" or "because you
 * kept breaking my chests", so those stay prompts, and a ban screen legitimately does both: pick the person,
 * then type the reason. This test pins that split rather than banning prompts outright, because a rule that
 * said "no prompts in screens" would be wrong and would be worked around rather than followed.
 */
class PickingPeopleTest {

    private static final Path SCREENS =
            Path.of("src/main/java/de/raindancer/modules/claims/screen");

    private record Screen(String name, String body) {
    }

    private static List<Screen> screens() {
        try (Stream<Path> files = Files.list(SCREENS)) {
            List<Screen> found = new ArrayList<>();
            for (Path file : files.sorted().toList()) {
                found.add(new Screen(file.getFileName().toString().replace(".java", ""),
                        Files.readString(file)));
            }
            return found;
        } catch (IOException unreadable) {
            throw new AssertionError("could not read the screens", unreadable);
        }
    }

    @Test
    @DisplayName("the scan found the screens, so a move cannot quietly empty it")
    void theScanIsNotVacuous() {
        assertThat(screens()).extracting(Screen::name).contains("BansMenu", "MembersMenu");
    }

    @Test
    @DisplayName("nothing asks for a player's name in chat any more")
    void nobodyTypesANameAnyMore() {
        // The message keys that used to introduce a "now type who" prompt. A screen still sending one of these
        // is a screen that kept the old interaction.
        List<String> typedNames = new ArrayList<>();
        for (Screen screen : screens()) {
            for (String asking : List.of("ask-kick", "ask-owner-filter", "ask-transfer-target",
                    "ask-trust", "ask-ban\"", "ask-timeout\"")) {
                if (screen.body().contains(asking)) {
                    typedNames.add(screen.name() + " → " + asking);
                }
            }
        }
        assertThat(typedNames)
                .as("picking a person is a click; these still ask for one to be spelled out")
                .isEmpty();
    }

    @Test
    @DisplayName("every screen that picks a person uses Core's chooser rather than its own")
    void theChooserIsShared() {
        List<String> pickers = new ArrayList<>();
        for (Screen screen : screens()) {
            if (screen.body().contains("PlayerChooser")) {
                pickers.add(screen.name());
            }
        }
        assertThat(pickers)
                .as("if this is empty the chooser was replaced by something local and the ordering, the "
                        + "skinned heads and the search all went with it")
                .isNotEmpty();
    }

    @Test
    @DisplayName("a chooser is opened with a parent, so Back goes where it came from")
    void everyChooserKeepsItsBackButton() {
        // Learned the hard way: a menu opened with a null parent draws no Back button at all, which leaves the
        // player with Close as their only way out of a chooser they opened by accident.
        List<String> orphans = new ArrayList<>();
        for (Screen screen : screens()) {
            int at = 0;
            while ((at = screen.body().indexOf("new PlayerChooser(", at)) >= 0) {
                String call = screen.body().substring(at,
                        Math.min(screen.body().length(), at + 220));
                if (call.contains(", null,") && !call.contains("this,")) {
                    orphans.add(screen.name());
                }
                at++;
            }
        }
        assertThat(orphans)
                .as("a chooser with no parent has no Back button, so Close is the only way out")
                .isEmpty();
    }

    @Test
    @DisplayName("a duration and a reason may still be typed, because there is nothing to click")
    void thePromptsThatRemainAreNotAboutPeople() {
        String bans = screens().stream()
                .filter(screen -> screen.name().equals("BansMenu"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("BansMenu is gone"))
                .body();

        // Written as an assertion rather than left implicit: somebody tidying up later should be able to see
        // that these two prompts are deliberate, not leftovers from the conversion.
        assertThat(bans)
                .as("'three days' and 'because you kept breaking my chests' have nothing to enumerate")
                .contains("ask-timeout-duration")
                .contains("ask-ban-reason");
    }

    @Test
    @DisplayName("an owner is never offered where the old code refused one after the click")
    void ownersAreExcludedNotRefused() {
        // Kicking, barring, timing out and adding a co-owner all used to check isOwner() once the player was
        // already typed and refuse. A button that refuses is worse than a button that is not there, so the
        // exclusion moved to the front of the flow — into the list handed to the chooser — instead.
        for (String name : List.of("BansMenu", "MembersMenu")) {
            String body = screens().stream()
                    .filter(screen -> screen.name().equals(name))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(name + " is gone"))
                    .body();
            assertThat(body)
                    .as(name + " must exclude the claim's owners from whatever it hands the chooser")
                    .contains("claim.owners()");
        }
    }

    @Test
    @DisplayName("the name-resolving helpers went with the prompts that needed them")
    void deadResolversAreGone() {
        // resolveName/resolvePlayer existed to turn a typed name into a UUID. With nothing left to type in
        // these screens, a helper still sitting in the file is one nobody calls — exactly the kind of leftover
        // that makes the next reader wonder whether it is secretly still wired up somewhere.
        for (Screen screen : screens()) {
            if (screen.name().equals("BansMenu") || screen.name().equals("MembersMenu")) {
                assertThat(screen.body())
                        .as(screen.name() + " no longer needs to resolve a typed name")
                        .doesNotContain("resolveName(");
            }
            if (screen.name().equals("AdminClaimBrowserMenu")) {
                assertThat(screen.body())
                        .as("the owner filter and the transfer target are both chosen by click now")
                        .doesNotContain("resolvePlayer(");
            }
        }
    }
}
