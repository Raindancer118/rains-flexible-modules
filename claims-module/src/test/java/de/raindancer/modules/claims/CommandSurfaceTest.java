package de.raindancer.modules.claims;

import de.raindancer.modules.api.ModuleCommand;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The words this plugin answers to, and what the bare command does.
 *
 * <h2>Why this is pinned</h2>
 * People do not read release notes; they type what they typed last week. Rain's Extended Claims has been
 * installed on servers for a long time, and every one of those players has {@code /claims} and {@code /rec}
 * in their fingers. A rebuild that quietly stops answering to a word is indistinguishable, from the other
 * side, from the plugin being broken.
 *
 * <p>The bare command is pinned for the same reason. In the old plugin {@code /claim} on its own always
 * opened the claim list — that was the front door, and every other route into the plugin hung off it. The
 * rebuild first answered "you are not standing in a claim", which is a refusal rather than a door, and then
 * opened the claim underfoot when there was one, which is a different door depending on where you stand.
 * Neither is what anybody's muscle memory expects.
 */
class CommandSurfaceTest {

    private static final Path CLAIM_COMMAND =
            Path.of("src/main/java/de/raindancer/modules/claims/command/ClaimCommand.java");

    private static String source(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException unreadable) {
            throw new AssertionError("could not read " + file, unreadable);
        }
    }

    private static ModuleCommand named(String name) {
        return ClaimCommands.declared().stream()
                .filter(command -> command.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no /" + name + " is declared at all"));
    }

    @Test
    @DisplayName("the words the old plugin answered to still work")
    void theOldNamesAreKept() {
        assertThat(named("claim").names())
                .as("/claims and /rec are in the fingers of everybody who has used this plugin")
                .contains("claim", "claims", "rec");

        assertThat(named("claimadmin").names())
                .as("an admin who types /reca on a server that has run this for years should not be told "
                        + "there is no such command")
                .contains("claimadmin", "reca", "recadmin");
    }

    @Test
    @DisplayName("bare /claim opens the claim list, as it always did")
    void theBareCommandOpensTheList() {
        String body = source(CLAIM_COMMAND);
        int bare = body.indexOf("args.length == 0");
        assertThat(bare).as("there is no zero-argument branch at all").isNotNegative();

        // Everything from the zero-argument check to the switch that handles the rest — a fixed character
        // window silently stops covering the branch the moment somebody adds a comment to it.
        int switchStarts = body.indexOf("switch (", bare);
        assertThat(switchStarts).as("no subcommand switch follows the bare branch").isGreaterThan(bare);
        String branch = body.substring(bare, switchStarts);
        assertThat(branch)
                .as("the front door of the plugin: it must not depend on where the player happens to stand")
                .contains("screens().list(");
        assertThat(branch)
                .as("opening the claim underfoot makes /claim mean two different things in two places")
                .doesNotContain("claimAround");
    }

    @Test
    @DisplayName("the everyday subcommands are all reachable")
    void nothingEverydayIsMissing() {
        String body = source(CLAIM_COMMAND);

        // Not the whole of the old surface — much of it is in the screens now, deliberately. These are the
        // ones with no other route: without 'stick' there is no way to mark out a claim at all, and without
        // 'accept' an entry-fee prompt cannot be answered.
        List<String> missing = new ArrayList<>();
        for (String word : List.of("help", "menu", "stick", "select", "hide", "cancel",
                "accept", "decline", "delete", "rename", "info", "show", "trust", "untrust",
                "ban", "unban")) {
            if (!body.contains("\"" + word + "\"")) {
                missing.add(word);
            }
        }
        assertThat(missing)
                .as("these have no route through the screens, so a missing one is a feature nobody can reach")
                .isEmpty();
    }

    @Test
    @DisplayName("an unknown subcommand says so and points somewhere")
    void aTypoIsAnswered() {
        String body = source(CLAIM_COMMAND);

        int fallback = body.indexOf("default ->");
        assertThat(fallback).isNotNegative();
        assertThat(body.substring(fallback, Math.min(body.length(), fallback + 300)))
                .as("a typo that produces silence reads as the plugin being broken")
                .contains("unknown-subcommand");
    }

    private static final Path ADMIN_COMMAND =
            Path.of("src/main/java/de/raindancer/modules/claims/command/ClaimAdminCommand.java");

    @Test
    @DisplayName("the capabilities that had no route at all are reachable again")
    void nothingIsUnreachable() {
        // Each of these was found by auditing the rewrite against the old plugin: the model method existed,
        // the storage read and wrote it, and NOTHING called it — so the feature was unreachable by command,
        // by clicking, and by editing config. That is the worst kind of missing, because the code looks
        // complete from every angle except using it.
        String player = source(CLAIM_COMMAND);
        String admin = source(ADMIN_COMMAND);

        List<String> unreachable = new ArrayList<>();
        for (String word : List.of("kick", "timeout", "owner")) {
            if (!player.contains("\"" + word + "\"")) {
                unreachable.add("/claim " + word);
            }
        }
        // The manual was a 1177-line book in the old plugin and simply absent from the rewrite.
        if (!player.contains("\"manual\"")) {
            unreachable.add("/claim manual");
        }
        for (String word : List.of("why", "stick", "save", "manual")) {
            if (!admin.contains("\"" + word + "\"")) {
                unreachable.add("/claimadmin " + word);
            }
        }
        assertThat(unreachable)
                .as("these existed in the model with no caller anywhere — a feature nobody can reach")
                .isEmpty();
    }

    @Test
    @DisplayName("the screens that reach the rest of it exist")
    void theClickOnlyRoutesExist() {
        // The other half of the audit. These are not commands on purpose — choosing an effect or authoring an
        // equip rule is clicking work — but each one was a toggle with nothing behind it: switched on, the
        // feature could never fire, because no screen could set what it needed.
        Path screens = Path.of("src/main/java/de/raindancer/modules/claims/screen");
        List<String> missing = new ArrayList<>();
        for (String screen : List.of("EffectsMenu", "EquipMenu", "MemberAdminMenu", "MemberGrantableMenu",
                "TitleLineMenu", "AdminClaimBrowserMenu")) {
            if (!java.nio.file.Files.isRegularFile(screens.resolve(screen + ".java"))) {
                missing.add(screen);
            }
        }
        assertThat(missing)
                .as("without these the matching feature has an on switch and nothing to switch on")
                .isEmpty();
    }

    @Test
    @DisplayName("module commands declare rec.use and rec.admin permission nodes")
    void commandPermissionsMatchRegisteredNodes() {
        assertThat(named("claim").permission()).isEqualTo("rec.use");
        assertThat(named("claimadmin").permission()).isEqualTo("rec.admin");
    }

    @Test
    @DisplayName("tab completion covers all primary subcommands")
    void tabCompletionCoversSubcommands() {
        String player = source(CLAIM_COMMAND);
        String admin = source(ADMIN_COMMAND);

        for (String word : List.of("create", "manual", "stick", "select", "delete", "rename", "hide", "accept", "decline")) {
            assertThat(player).contains("\"" + word + "\"");
        }
        for (String word : List.of("flags", "why", "stick", "save", "manual")) {
            assertThat(admin).contains("\"" + word + "\"");
        }
    }
}
