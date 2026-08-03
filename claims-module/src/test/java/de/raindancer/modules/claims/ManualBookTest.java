package de.raindancer.modules.claims;

import de.raindancer.modules.claims.util.ManualBook;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The manual against the plugin it describes, rather than against itself.
 *
 * <h2>Why this is the important test in the class</h2>
 * A book is prose, and prose does not fail a build when it drifts from the code — it just quietly starts
 * lying to whoever reads it. The one thing here that would actually catch that is
 * {@link #everyCommandMentionedActuallyExists()}: it reads {@code /claim} and {@code /claimadmin}'s own
 * source for their case labels and checks every {@code /claim word} or {@code /claimadmin word} printed in
 * the book is one of them. Ported from Rain's Extended Claims, this module's command surface is far
 * narrower — most of what used to be a typed subcommand is a menu button now — so this is the test that
 * would have caught a straight copy-paste of the old prose.
 *
 * <p>{@link ManualBook.Availability#everything()} is used throughout: it is the edition with every feature
 * switched on, so it is both the longest the book ever gets and the one that exercises every page.
 */
class ManualBookTest {

    private static final Path CLAIM_COMMAND =
            Path.of("src/main/java/de/raindancer/modules/claims/command/ClaimCommand.java");
    private static final Path CLAIM_ADMIN_COMMAND =
            Path.of("src/main/java/de/raindancer/modules/claims/command/ClaimAdminCommand.java");

    private static String source(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException unreadable) {
            throw new AssertionError("could not read " + file, unreadable);
        }
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private static List<String> plainPages(ManualBook.Edition edition) {
        ManualBook book = new ManualBook(ManualBook.Availability.everything(), edition);
        List<String> pages = new ArrayList<>();
        for (Component page : book.pages()) {
            pages.add(plain(page));
        }
        return pages;
    }

    @Test
    @DisplayName("both editions actually have pages")
    void bothEditionsHavePages() {
        assertThat(new ManualBook(ManualBook.Availability.everything(), ManualBook.Edition.PLAYER).pages())
                .as("a player opening the book to nothing is indistinguishable from a broken book")
                .isNotEmpty();
        assertThat(new ManualBook(ManualBook.Availability.everything(), ManualBook.Edition.ADMIN).pages())
                .isNotEmpty();
    }

    /**
     * No page overruns the client's page.
     *
     * <p>Minecraft silently truncates a written book page that draws past its bottom — there is no
     * exception and no log line, the text simply stops. {@link ManualBook#wrappedLines(String)} is the
     * exact function {@code spread()} budgets pages against internally, mirrored here because the budget
     * itself ({@code LINES_PER_PAGE = 14}, {@code CHARS_PER_LINE = 19} in {@code ManualBook.java}) is
     * private — this test reads the same source those numbers came from rather than inventing its own.
     *
     * <p>The 256-character figure is the coarser, better-known version of the same limit: at 19 characters
     * a line and 14 lines a page that is 266 characters, so anything comfortably under 256 plain characters
     * is nowhere near the wrapped-line budget either. Both are checked, because a handful of long unbroken
     * words could pass one and fail the other.
     */
    @Test
    @DisplayName("no page overruns a written book page")
    void noPageOverrunsTheBook() {
        int linesPerPage = 14;
        int practicalCharLimit = 256;

        for (ManualBook.Edition edition : ManualBook.Edition.values()) {
            List<String> pages = plainPages(edition);
            for (int index = 0; index < pages.size(); index++) {
                String page = pages.get(index);
                int wrapped = 0;
                for (String line : page.split("\n", -1)) {
                    wrapped += ManualBook.wrappedLines(line);
                }
                int at = index;
                assertThat(wrapped)
                        .as("%s page %d draws %d lines, more than a book page holds:%n%s",
                                edition, at, wrapped, page)
                        .isLessThanOrEqualTo(linesPerPage);
                assertThat(page.length())
                        .as("%s page %d is %d plain characters, well past the practical page limit",
                                edition, at, page.length())
                        .isLessThanOrEqualTo(practicalCharLimit + linesPerPage);
            }
        }
    }

    /**
     * Every command the book tells somebody to type or click actually exists.
     *
     * <p>This is the test that matters. The book is prose describing a command surface, and prose does not
     * fail to compile when the surface underneath it changes — it just becomes a lie the next time somebody
     * reads it. So this reads {@code ClaimCommand} and {@code ClaimAdminCommand} the same way
     * {@code CommandSurfaceTest} does — as source, for their case labels — and checks every
     * {@code /claim <word>} and {@code /claimadmin <word>} printed anywhere in either edition names a
     * subcommand that switch statement actually handles.
     */
    @Test
    @DisplayName("every /claim and /claimadmin word in the book is a real subcommand")
    void everyCommandMentionedActuallyExists() {
        String claimSource = source(CLAIM_COMMAND);
        String adminSource = source(CLAIM_ADMIN_COMMAND);

        // No whitespace between "claim" and "admin" in the literal command, so this never matches inside
        // "/claimadmin" by accident — there is nothing to disambiguate. Only a literal space, not "\s": the
        // command and the word naming its subcommand must be on the one drawn line, or it is not a
        // reference to a subcommand at all — it is a run() button on one line followed by unrelated prose
        // on the next, which the page layout is free to put anywhere.
        Pattern claimWord = Pattern.compile("/claim +([a-zA-Z][\\w-]*)");
        Pattern adminWord = Pattern.compile("/claimadmin +([a-zA-Z][\\w-]*)");

        Set<String> missingFromClaim = new LinkedHashSet<>();
        Set<String> missingFromAdmin = new LinkedHashSet<>();

        for (ManualBook.Edition edition : ManualBook.Edition.values()) {
            for (String page : plainPages(edition)) {
                Matcher admin = adminWord.matcher(page);
                while (admin.find()) {
                    String word = admin.group(1);
                    if (!adminSource.contains("\"" + word + "\"")) {
                        missingFromAdmin.add(word);
                    }
                }
                // Strip what was already matched as /claimadmin so its trailing word is never re-read as
                // though it followed a bare /claim.
                String withoutAdmin = page.replaceAll("/claimadmin +[a-zA-Z][\\w-]*", "");
                Matcher claim = claimWord.matcher(withoutAdmin);
                while (claim.find()) {
                    String word = claim.group(1);
                    if (!claimSource.contains("\"" + word + "\"")) {
                        missingFromClaim.add(word);
                    }
                }
            }
        }

        assertThat(missingFromClaim)
                .as("the book tells somebody to type /claim <word>, but ClaimCommand has no such case — "
                        + "a manual naming a dead command is worse than no manual")
                .isEmpty();
        assertThat(missingFromAdmin)
                .as("the book tells somebody to type /claimadmin <word>, but ClaimAdminCommand has no such "
                        + "case")
                .isEmpty();
    }

    /**
     * The feature this module deliberately does not have gets no page.
     *
     * <p>Rain's Extended Claims had towns; this rebuild was ported "not the towns part" on purpose. A page
     * that survived the port by accident would send a player looking for {@code /town}, which does not
     * exist here and never will.
     */
    @Test
    @DisplayName("the book never mentions towns")
    void neverMentionsTowns() {
        for (ManualBook.Edition edition : ManualBook.Edition.values()) {
            for (String page : plainPages(edition)) {
                assertThat(page.toLowerCase(java.util.Locale.ROOT))
                        .as("%s page mentions towns, a feature this module does not have: %s", edition, page)
                        .doesNotContain("town");
            }
        }
    }
}
