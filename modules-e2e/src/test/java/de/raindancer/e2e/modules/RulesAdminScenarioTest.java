package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** RainsEssentials' /rules (editing, presets) and /admin (a second inventory that survives a rejoin). */
@Tag("e2e")
class RulesAdminScenarioTest {

    @Test
    @DisplayName("rules are shown, added, removed and replaced by a preset; /admin swaps inventories and back")
    void rulesAndAdminMode() {
        try (Server server = Server.start("rules-admin", List.of("essentials-standalone:RainsEssentials-.*"),
                List.of("Essentials are up"))) {
            Bot ada = server.admin("Ada");
            Bot bo = server.player("Bo");

            // A first join is shown the rules of the default preset.
            bo.expectChat("The rules. Yes, all of them.");
            bo.expectChat("Be kind");

            ada.runAndExpect("rules add Test rule | Only in tests.", "Rule 9 added");
            bo.runAndExpect("rules", "Test rule");
            ada.runAndExpect("rules remove 9", "Removed the rule Test rule");
            bo.forgetChat();
            bo.run("rules");
            bo.expectNoChat("Test rule", Duration.ofSeconds(2));

            ada.runAndExpect("rules preset apply anarchy", "Click below");
            ada.runAndExpect("rules preset apply anarchy confirm", "are now those of anarchy");
            bo.runAndExpect("rules", "Anything goes");
            ada.runAndExpect("rules preset save e2e-set A test", "kept as the preset e2e-set");
            ada.runAndExpect("rules preset list", "e2e-set");

            // Single rules from a preset, by command and by clicking one in the preset's page.
            ada.runAndExpect("rules preset take friendly-smp 2", "Added No griefing as rule 4");
            ada.runAndExpect("rules preset take friendly-smp 2", "You already have No griefing");
            bo.runAndExpect("rules", "No griefing");
            ada.runAndOpen("rules edit", "Rules");
            ada.click("Presets");
            ada.awaitWindow("Rule presets");
            ada.click("pvp");
            ada.awaitWindow("Preset: pvp");
            ada.forgetChat();
            ada.click("No spawn killing");
            ada.expectChat("Added No spawn killing as rule 5");
            ada.closeWindow();

            // Nobody else gets into admin mode.
            bo.runAndExpect("admin", "You may not do that");

            // Admin mode: the survival side is packed away and comes back exactly.
            server.console("give Ada diamond 5");
            Await.until("Ada holds diamonds", Duration.ofSeconds(5),
                    () -> ada.carrying(item -> item.is("diamond")).isPresent());
            ada.runAndExpect("admin", "Admin mode on");
            ada.expectGameMode(GameMode.SURVIVAL);
            // God mode: damage does nothing.
            server.console("damage Ada 6");
            Await.ticks(10);
            assertThat(ada.health()).as("god mode in admin mode").isEqualTo(20f);
            Await.until("the diamonds are put away", Duration.ofSeconds(5),
                    () -> ada.carrying(item -> item.is("diamond")).isEmpty());
            ada.expectActionBar("ADMIN MODE");

            server.console("give Ada stick 3");
            Await.until("Ada holds sticks", Duration.ofSeconds(5),
                    () -> ada.carrying(item -> item.is("stick")).isPresent());
            // Admin items stay on the admin side: a drop is refused and the sticks stay.
            ada.hold(ada.hotbarSlotOf(item -> item.is("stick")));
            ada.forgetChat();
            ada.dropHeld();
            ada.expectChat("Admin items stay on the admin side");
            assertThat(ada.items().stream().filter(item -> item.is("stick")).mapToInt(Bot.Item::amount).sum())
                    .isEqualTo(3);
            ada.runAndExpect("admin", "Admin mode off");
            ada.expectGameMode(GameMode.SURVIVAL);
            Await.until("the diamonds are back, the sticks are not", Duration.ofSeconds(5),
                    () -> ada.carrying(item -> item.is("diamond")).isPresent()
                            && ada.carrying(item -> item.is("stick")).isEmpty());
            assertThat(ada.items().stream().filter(item -> item.is("diamond")).mapToInt(Bot.Item::amount).sum())
                    .isEqualTo(5);

            // In again: the admin side is as it was left. Then out and back in across a rejoin.
            ada.runAndExpect("admin", "Admin mode on");
            Await.until("the sticks are back", Duration.ofSeconds(5),
                    () -> ada.carrying(item -> item.is("stick")).isPresent());
            ada.leave();
            Bot again = server.player("Ada");
            again.expectChat("still in admin mode");
            again.runAndExpect("admin", "Admin mode off");
            Await.until("the diamonds are back after the rejoin", Duration.ofSeconds(5),
                    () -> again.carrying(item -> item.is("diamond")).isPresent()
                            && again.carrying(item -> item.is("stick")).isEmpty());

            assertThat(server.paper.errorsFrom("RainsCore", "RainsEssentials")).isEmpty();
        }
    }
}
