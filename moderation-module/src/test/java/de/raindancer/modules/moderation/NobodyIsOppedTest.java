package de.raindancer.modules.moderation;

import de.raindancer.modules.moderation.model.StaffRank;
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
 * That being staff is not being op.
 *
 * <h2>Why this is a test and not a note</h2>
 * Because op is not a permission — it is <em>every</em> permission, present and future, in this plugin
 * and in every other one on the server, plus the vanilla commands: {@code /op}, {@code /stop},
 * {@code /gamerule}, world editing, and the console-level things this module deliberately reserves to
 * the server owner. A moderator who is op can promote themselves to admin, which makes all four tiers
 * decorative; they can also turn off the very setting that limits them.
 *
 * <p>So the tiers grant <em>nodes</em>, and the one thing that must never happen is a well-meaning line
 * of code reaching for {@code setOp(true)} because it is quicker than working out which node was
 * missing. That line would pass every other test in this repository.
 *
 * <p>Admins are not op either. There is a setting for a server that wants it — some owners run their
 * admins as co-owners — and it is off, so the decision has to be made deliberately rather than
 * inherited.
 */
class NobodyIsOppedTest {

    private static final Path ROOT = Path.of("src/main/java/de/raindancer/modules/moderation");

    private record Source(String name, String body) {
    }

    private static List<Source> module() {
        try (Stream<Path> files = Files.walk(ROOT)) {
            List<Source> found = new ArrayList<>();
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                found.add(new Source(ROOT.relativize(file).toString(), Files.readString(file)));
            }
            return found;
        } catch (IOException unreadable) {
            throw new AssertionError("could not read the module", unreadable);
        }
    }

    @Test
    @DisplayName("the scan reads the module, so it cannot pass by looking at nothing")
    void theScanIsNotVacuous() {
        assertThat(module()).hasSizeGreaterThan(25);
    }

    @Test
    @DisplayName("no preset grants op, because op is not a permission")
    void noRankGrantsOp() {
        // There is no node that means op, so the only way a rank could confer it is by somebody
        // deciding to call setOp somewhere — which the next test is about. This one is the belt: if
        // anybody ever invents a node called "op" and puts it in a tier, it fails here.
        for (StaffRank rank : StaffRank.values()) {
            assertThat(rank.nodes())
                    .as("%s", rank)
                    .noneMatch(node -> node.equalsIgnoreCase("op")
                            || node.endsWith(".op")
                            || node.startsWith("minecraft.command.op"));
        }
    }

    @Test
    @DisplayName("only the one place that is allowed to may op anybody")
    void nothingElseCallsSetOp() {
        // A moderator who is op holds every permission of every plugin on the server, plus /stop and
        // /op itself — so they can promote themselves and switch off whatever limits them. The setting
        // that lets an owner op their admins deliberately lives in StaffService and nowhere else.
        List<String> opping = new ArrayList<>();
        for (Source source : module()) {
            if (source.body().contains("setOp(") && !source.name().endsWith("StaffService.java")) {
                opping.add(source.name());
            }
        }

        assertThat(opping)
                .as("op is not a permission, it is every permission — including the ones this module "
                        + "reserves to the server owner. Grant a node instead")
                .isEmpty();
    }

    @Test
    @DisplayName("promoting somebody never ops them unless the setting says so")
    void theSettingIsOffByDefault() {
        assertThat(ModerationSettings.DEFAULTS.adminsAreOp())
                .as("an admin who is op by default is an admin who can /stop the server and op "
                        + "themselves further, which nobody asked for by installing this")
                .isFalse();
    }

    @Test
    @DisplayName("the setting only ever reaches admins")
    void onlyAdminsCanEverBeOpped() {
        // Read from the source rather than by running it: the branch is one line in StaffService and
        // the thing worth pinning is that it is guarded by the rank, not only by the setting. A version
        // that checked the setting alone would op a trial.
        String service = module().stream()
                .filter(source -> source.name().endsWith("StaffService.java"))
                .findFirst().orElseThrow().body();

        // Checked as two exact properties rather than by looking at the surrounding lines, because a
        // window of N characters is an arbitrary proxy that passes or fails on how long a comment is.
        //
        // One: setOp is only ever handed the decision, never a bare true.
        assertThat(service)
                .as("StaffService should be the one place that ops anybody")
                .contains("setOp(");
        assertThat(service)
                .as("setOp must be passed the guarded decision, never a literal — setOp(true) is how a "
                        + "trial ends up running the server")
                .doesNotContain("setOp(true)")
                .contains("setOp(shouldBeOp)");

        // Two: that decision is the setting AND the rank. Either alone is a bug — the setting alone
        // would op every trial, and the rank alone would op admins on a server that never asked.
        int decision = service.indexOf("boolean shouldBeOp");
        assertThat(decision).as("the decision should be one named boolean").isNotNegative();
        String howItIsDecided = service.substring(decision,
                service.indexOf(';', decision));
        assertThat(howItIsDecided)
                .as("being opped has to require the setting *and* the ADMIN rank")
                .contains("adminsAreOp")
                .contains("ADMIN");
    }

    @Test
    @DisplayName("the commands that hand out ranks are not themselves grantable")
    void promotionIsNotAPermissionAnybodyCanBeGiven() {
        // The whole scheme rests on this: a power that hands out powers must not be one of the powers
        // it hands out, or the lowest tier is one promotion away from the highest.
        String promote = de.raindancer.modules.moderation.command.PromoteCommand.USE;

        for (StaffRank rank : StaffRank.values()) {
            assertThat(rank.nodes())
                    .as("%s must not be able to promote anybody", rank)
                    .doesNotContain(promote);
        }
        assertThat(StaffRank.everyGrantableNode()).doesNotContain(promote);
    }
}
