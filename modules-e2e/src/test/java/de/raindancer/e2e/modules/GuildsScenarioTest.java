package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guilds on a real server, with real clients, beside the real economy and chat plugins: founding, an invitation
 * accepted by its chat button, guild chat, the tag in front of a name in public chat, friendly fire refused by
 * Core's combat rules (and a stranger's hit landing), the bank through the economy, a rank refused, the menu, and
 * everything still there after a restart.
 */
@Tag("e2e")
class GuildsScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    private static boolean told(Bot bot, String text) {
        return bot.chatText().stream().anyMatch(line -> line.contains(text));
    }

    @Test
    @DisplayName("found, invite by button, chat, tag, friendly fire, bank, ranks, menu, mining, boost, contracts, restart")
    void guilds() {
        try (Server server = Server.start("guilds",
                List.of("guilds-standalone:RainsGuilds-.*", "economy-standalone:RainsEconomy-.*", "chat-standalone:RainsChat-.*"),
                List.of("Guilds are up", "The economy is up", "Chat is up"))) {
            Bot ada = server.player("Ada");
            Bot bo = server.player("Bo");
            Bot cy = server.player("Cy");

            // ---- founding, announced to everybody
            ada.run("guild create The Elves ELF");
            Await.until("Ada is told it worked", WAIT, () -> told(ada, "Done."));
            Await.until("Bo hears about it", WAIT, () -> told(bo, "A new guild"));
            cy.forgetChat();
            cy.run("guild create the elves XYZ");
            Await.until("the name is taken, whatever its case", WAIT, () -> told(cy, "already goes by that name"));

            // ---- an invitation, accepted by the button in chat, once
            ada.run("guild invite Bo");
            Await.until("Bo is invited", WAIT, () -> told(bo, "invites you to"));
            bo.clickButtonOn("invites you to", 0);
            Await.until("Bo is welcomed", WAIT, () -> told(bo, "Welcome to"));
            Await.until("Ada hears Bo joined", WAIT, () -> told(ada, "Bo joined the guild"));

            // ---- guild chat reaches members only; the tag goes in front of a name in public chat
            ada.forgetChat();
            cy.forgetChat();
            bo.run("guild c meet at the oak");
            Await.until("Ada hears the guild line", WAIT, () -> told(ada, "[ELF] Bo: meet at the oak"));
            Await.ticks(10);
            assertThat(told(cy, "meet at the oak")).as("a stranger does not hear guild chat").isFalse();
            bo.say("hello everybody");
            Await.until("the tag is in front of Bo's name in public chat", WAIT,
                    () -> cy.chatText().stream().anyMatch(line -> line.contains("[ELF]") && line.contains("hello everybody")));

            // ---- friendly fire: a guildmate's hit is refused by Core's combat, a stranger's lands
            server.console("gamemode survival Bo");
            server.console("tp Bo Ada");
            server.console("tp Cy Ada");
            Await.ticks(20);
            float before = bo.health();
            ada.attack(bo.id());
            Await.ticks(20);
            assertThat(bo.health()).as("a guildmate cannot hurt Bo").isEqualTo(before);
            cy.attack(bo.id());
            Await.until("a stranger's hit lands", WAIT, () -> bo.health() < before);

            // ---- the bank, through the real economy; a recruit may not take out
            server.console("eco give Ada 500");
            Await.ticks(10);
            ada.forgetChat();
            ada.run("guild deposit 200");
            Await.until("paid in", WAIT, () -> told(ada, "Done."));
            ada.forgetChat();
            ada.run("guild withdraw 50");
            Await.until("taken out", WAIT, () -> told(ada, "Done."));
            bo.forgetChat();
            bo.run("guild withdraw 1");
            Await.until("a recruit is refused", WAIT, () -> told(bo, "Your rank cannot do that"));
            ada.forgetChat();
            ada.run("guild withdraw 9999");
            Await.until("not more than is there", WAIT, () -> told(ada, "does not hold that much"));

            // ---- ranks: the leader promotes, the recruit cannot kick
            ada.forgetChat();
            ada.run("guild promote Bo");
            Await.until("promoted", WAIT, () -> told(ada, "Bo is Member now"));
            bo.forgetChat();
            bo.run("guild kick Ada");
            Await.until("refused", WAIT, () -> told(bo, "Your rank cannot do that"));

            // ---- the menu opens on the guild's page and goes to members
            ada.run("guild");
            ada.awaitWindow("The Elves");
            ada.click("Members");
            ada.awaitWindow("Members");
            ada.closeWindow();
            ada.run("guild");
            ada.awaitWindow("The Elves");
            ada.click("Log");
            ada.awaitWindow("Log");
            Await.until("the log is read from guilds.db and shown", WAIT,
                    () -> ada.window().flatMap(window -> window.slotNamed("Founded")).isPresent());
            ada.closeWindow();

            // ---- more ways to level up: a mined ore counts, staff boost, the week's contracts
            server.console("gamemode creative Ada");
            server.console("tp Ada 0.5 150 0.5");
            Await.ticks(10);
            server.console("setblock 1 150 0 minecraft:diamond_ore");
            Await.ticks(5);
            ada.dig(1, 150, 0);
            Await.until("the ore is gone", WAIT, () -> server.console("execute if block 1 150 0 minecraft:air").contains("passed"));
            ada.run("guild level");
            ada.awaitWindow("Guild level");
            Await.until("mining shows where the experience came from", WAIT, () -> ada.window()
                    .map(window -> window.top().values().stream().anyMatch(item -> item.name().contains("Where the experience came from")
                            && item.lore().stream().anyMatch(line -> line.contains("Mining"))))
                    .orElse(false));
            ada.closeWindow();
            bo.forgetChat();
            server.console("guild admin boost 50 1h");
            Await.until("everybody hears about the boost", WAIT, () -> told(bo, "Guild experience boost"));
            ada.run("guild contracts");
            ada.awaitWindow("Contracts");
            Await.until("three contracts for the week", WAIT, () -> ada.window()
                    .map(window -> window.top().values().stream().filter(item -> item.lore().stream()
                            .anyMatch(line -> line.contains("Pays"))).count() == 3).orElse(false));
            ada.closeWindow();

            // ---- everything is still there after a restart
            server.paper.restartKeepingBots();
            server.paper.awaitLog("Guilds are up: 1 guild", Duration.ofSeconds(60));
            cy.rejoin();
            Await.ticks(40);
            cy.forgetChat();
            cy.run("guild info ELF");
            Await.until("the guild is back, with both members and the bank", WAIT,
                    () -> told(cy, "The Elves") && told(cy, "2/"));

            assertThat(server.paper.errorsFrom("RainsGuilds")).as("errors from the guilds plugin").isEmpty();
            assertThat(server.paper.logLines(line -> line.contains("modules.guilds") && line.contains("ran on the thread running the world")))
                    .as("guilds.db is never touched from the thread running the world").isEmpty();
        }
    }
}
