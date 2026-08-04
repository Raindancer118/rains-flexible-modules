package de.raindancer.modules.moderation;

import de.raindancer.core.world.spawn.Wave;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.model.StaffRank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Who gets the world tools, and what the page is allowed to ask for.
 *
 * <p>The split was a product decision and it is the kind that rots quietly: a permission moved one
 * tier down is a line nobody reads twice, and the consequence — a trial mod who can drop forty
 * creatures on somebody — does not show up until it happens on a live server.
 */
class WorldToolsTest {

    private static final Path SCREEN =
            Path.of("src/main/java/de/raindancer/modules/moderation/screen/WorldToolsMenu.java");

    private static String screen() {
        try {
            return Files.readString(SCREEN);
        } catch (IOException unreadable) {
            throw new AssertionError("the world tools screen is gone", unreadable);
        }
    }

    @Test
    @DisplayName("burying ore is a mod's, and creatures are an admin's")
    void theTiers() {
        // Ore costs nothing anybody had and touches only ground the world generated. A wave arrives
        // around somebody who did not ask for it and can kill them.
        assertThat(StaffRank.MOD.nodes()).contains(ModerationPermission.SPAWN_ORE.node());
        assertThat(StaffRank.MOD.nodes()).doesNotContain(ModerationPermission.SPAWN_MOBS.node());

        assertThat(StaffRank.ADMIN.nodes()).contains(ModerationPermission.SPAWN_ORE.node());
        assertThat(StaffRank.ADMIN.nodes()).contains(ModerationPermission.SPAWN_MOBS.node());
    }

    @Test
    @DisplayName("a trial mod gets neither")
    void trialsGetNothing() {
        assertThat(StaffRank.TRIAL_MOD.nodes())
                .doesNotContain(ModerationPermission.SPAWN_ORE.node(),
                        ModerationPermission.SPAWN_MOBS.node());
    }

    @Test
    @DisplayName("both may be aimed at yourself, since that is where a moderator tests one")
    void aimableAtSelf() {
        // Neither is a punishment. A tool a moderator cannot try out on their own patch of ground is
        // one they will try out on somebody else's.
        assertThat(ModerationPermission.SPAWN_ORE.aimableAtSelf()).isTrue();
        assertThat(ModerationPermission.SPAWN_MOBS.aimableAtSelf()).isTrue();
    }

    @Test
    @DisplayName("the command opens the page and is guarded by the lower of the two nodes")
    void theCommand() {
        // Guarding on spawn.mobs would refuse a mod at the door and hide the ore vein they are
        // allowed to use; the buttons inside are what tell the two ranks apart.
        ModuleCommand command = ModerationCommands.declared().stream()
                .filter(one -> one.name().equals("worldtools"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("/worldtools is not declared"));

        assertThat(command.handler().permission())
                .isEqualTo(ModerationPermission.SPAWN_ORE.node());
        assertThat(command.names()).contains("wtools");
    }

    @Test
    @DisplayName("every creature button asks for the mob node, not the ore one")
    void theScreenGuardsEachHalfSeparately() {
        String body = screen();

        // The failure this catches is one letter wide: a creature button that asks mayOre reads
        // perfectly and hands a wave to every mod on the server.
        for (String action : List.of("Send a pack", "Start a wave", "What turns up")) {
            int at = body.indexOf(action);
            assertThat(at).as("%s is gone from the page", action).isNotNegative();
            String around = body.substring(Math.max(0, at - 400), at);
            assertThat(around)
                    .as("the button that does '%s' is not guarded by mayMobs", action)
                    .contains("mayMobs");
        }
        int ore = body.indexOf("Bury a vein");
        assertThat(body.substring(Math.max(0, ore - 300), ore)).contains("mayOre");
    }

    @Test
    @DisplayName("reopening the page after a chooser carries what was already chosen")
    void theChooserDoesNotResetThePage() {
        // A chooser closes the window on its way out, so the page has to be opened again — and the
        // first version opened a *plain* new one, setting the creature on the instance that was going
        // away. Every choice was thrown out and every wave was zombies: the defaults, faithfully.
        String body = screen();
        int at = body.indexOf("MobChooser.anything");
        assertThat(at).as("the mob chooser is gone from the page").isNotNegative();
        assertThat(body)
                .as("the page filters the creature list again — every mob should be offered, and "
                        + "the drawers are what make that readable")
                .doesNotContain("MobChooser.toFight");

        String callback = body.substring(at, Math.min(body.length(), at + 1400));
        assertThat(callback)
                .as("the mob chooser does not reopen the page carrying its state")
                .contains("reopenCarrying()");

        // And the one place that rebuilds it carries every value. A field added to this page and
        // forgotten here is the same bug again, one value at a time.
        int carry = body.indexOf("private void reopenCarrying()");
        assertThat(carry).as("nothing rebuilds the page any more").isNotNegative();
        String rebuild = body.substring(carry, Math.min(body.length(), carry + 700));
        assertThat(rebuild)
                .contains("again.creature = creature")
                .contains("again.packSize = packSize")
                .contains("again.packs = packs")
                .contains("again.everySeconds = everySeconds")
                .contains("again.ore = ore")
                .contains("again.veinSize = veinSize");
    }

    @Test
    @DisplayName("the page never falls back to acting underfoot")
    void aimingIsRequired() {
        // Falling back to the player's own position buries a vein under somebody who was aiming at the
        // sky, and they will never find it.
        assertThat(screen())
                .as("something acts without checking what is aimed at")
                .contains("nothing-aimed-at");
        assertThat(screen()).contains("getTargetBlockExact");
    }

    @Test
    @DisplayName("the typed commands exist, and are guarded by what they do")
    void theTypedCommands() {
        var declared = ModerationCommands.declared();
        var vein = declared.stream().filter(one -> one.name().equals("vein")).findFirst();
        var mob = declared.stream().filter(one -> one.name().equals("mob")).findFirst();

        assertThat(vein).as("/vein is not declared").isPresent();
        assertThat(mob).as("/mob is not declared").isPresent();
        // The same split as the page: ore is a mod's, creatures are an admin's. A typed form that
        // skipped the tier would be the whole permission decision undone by a second door.
        assertThat(vein.orElseThrow().handler().permission())
                .isEqualTo(ModerationPermission.SPAWN_ORE.node());
        assertThat(mob.orElseThrow().handler().permission())
                .isEqualTo(ModerationPermission.SPAWN_MOBS.node());
    }

    @Test
    @DisplayName("every amount on the page goes through Core's chooser")
    void amountsUseTheSharedScreen() {
        // Nudge buttons are forty clicks to reach sixty, which is what was on this page and what got
        // it complained about. AmountChooser is ±1, ±10, ±100, both ends, and an explicit Accept.
        String body = screen();

        assertThat(body).contains("AmountChooser");
        assertThat(body)
                .as("a hand-rolled stepper is back on this page")
                .doesNotContain("veinSize + 4")
                .doesNotContain("packSize + 2")
                .doesNotContain("everySeconds - 5");
    }

    @Test
    @DisplayName("a wave the page can build is one Core is willing to run")
    void thePageCannotAskForTheImpossible() {
        // The screen's own ceilings are Core's, named rather than copied — a second set of numbers is
        // the set that goes stale, and the one that goes stale is always the bigger one.
        assertThat(screen()).contains("Wave.MOST_PER_PACK");
        assertThat(screen()).contains("Wave.MOST_PACKS");
        assertThat(screen()).contains("OreVein.MOST_BLOCKS");

        // And Core clamps anyway, so even a page that forgot could not get a wave of six hundred out.
        assertThat(Wave.of(List.of("zombie"), 10_000, 10_000, 8, 20L).total())
                .isEqualTo(Wave.MOST_PACKS * Wave.MOST_PER_PACK);
    }
}
