package de.raindancer.modules.wallsroads.util;

import de.raindancer.modules.wallsroads.WallsRoadsSettings;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one thing worth testing about a book: whether its pages fit, and whether it describes the
 * server it is actually on.
 */
class ManualBookTest {

    private static List<String> pagesOf(WallsRoadsSettings settings) {
        return new ManualBook(settings).pages().stream().map(ManualBook::plain).toList();
    }

    @Test
    @DisplayName("no page overflows what a book can show")
    void everyPageFits() {
        for (String page : pagesOf(WallsRoadsSettings.DEFAULTS)) {
            assertThat(ManualBook.wrappedLines(page))
                    .as("this page wraps past the end of the book:%n%s", page)
                    .isLessThanOrEqualTo(ManualBook.linesPerPage());
        }
    }

    @Test
    @DisplayName("the manual quotes this server's own thresholds, not the shipped defaults")
    void tellsTheTruthAboutThisServer() {
        WallsRoadsSettings changed = WallsRoadsSettings.DEFAULTS
                .withSeaTunnelMinLength(96).withSeaTunnelMinDepth(31);

        String everything = String.join("\n", pagesOf(changed));

        assertThat(everything).contains("96 blocks across").contains("31 blocks deep");
        assertThat(everything).doesNotContain("24 blocks across");
    }

    @Test
    @DisplayName("what it says about paying matches whether the server charges")
    void tellsTheTruthAboutCost() {
        assertThat(String.join("\n", pagesOf(WallsRoadsSettings.DEFAULTS.withChargeMaterials(false))))
                .contains("costs nothing");
        assertThat(String.join("\n", pagesOf(WallsRoadsSettings.DEFAULTS.withChargeMaterials(true))))
                .contains("from your inventory");
    }

    @Test
    @DisplayName("nothing that changes the world is click-to-run")
    void nothingDestructiveRunsOnAClick() {
        // A click in a book has no confirmation step between the page and the command, so only things
        // that open or begin something may fire outright. "road new" qualifies: it hands over a stick
        // and starts a marking, and nothing is built until the reader deliberately finishes it.
        List<Component> pages = new ManualBook(WallsRoadsSettings.DEFAULTS).pages();

        for (Component page : pages) {
            assertThat(clickToRun(page))
                    .allSatisfy(command -> assertThat(command)
                            .doesNotContain("remove").doesNotContain("delete")
                            .doesNotContain("teardown"));
        }
    }

    /** Every command this page would fire outright. */
    private static List<String> clickToRun(Component page) {
        List<String> commands = new java.util.ArrayList<>();
        collect(page, commands);
        return commands;
    }

    private static void collect(Component component, List<String> into) {
        var click = component.clickEvent();
        // Adventure carries a click's argument in a typed payload now, not a bare string; a run-command
        // click's is always the text kind.
        if (click != null
                && click.action() == net.kyori.adventure.text.event.ClickEvent.Action.RUN_COMMAND
                && click.payload() instanceof net.kyori.adventure.text.event.ClickEvent.Payload.Text text) {
            into.add(text.value());
        }
        component.children().forEach(child -> collect(child, into));
    }

    @Test
    @DisplayName("the scan is not vacuous — the book really does carry clickable commands")
    void theScanFindsSomething() {
        List<String> found = new java.util.ArrayList<>();
        new ManualBook(WallsRoadsSettings.DEFAULTS).pages().forEach(page -> collect(page, found));

        assertThat(found).isNotEmpty();
    }
}
