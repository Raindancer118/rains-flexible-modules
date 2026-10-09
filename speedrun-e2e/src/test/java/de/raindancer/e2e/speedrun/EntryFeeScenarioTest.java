package de.raindancer.e2e.speedrun;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * A race with a buy-in, on a server with RainsEconomy: everybody racing pays the entry fee at the start, the
 * house keeps its cut, and the rest is shared when the goal is reached.
 */
@Tag("e2e")
class EntryFeeScenarioTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    private static boolean said(Bot bot, String text) {
        return bot.chatText().stream().anyMatch(line -> line.contains(text));
    }

    @Test
    @DisplayName("an entry fee is taken at the start and the pot, less the house's cut, is paid at the goal")
    @Covers({"setting:economy.entry-fee", "setting:economy.house-cut-percent", "setting:economy.prize-split"})
    void entryFee() {
        try (Game game = Game.start("entry-fee", Map.of(
                "advancement-key", "minecraft:story/mine_stone",
                "restart-after-seconds", "5"), Map.of(), List.of("economy-standalone:RainsEconomy-.*"))) {
            game.server.awaitLog("The economy is up", Duration.ofSeconds(60));
            Bot ada = game.admin("Ada");
            Bot bo = game.player("Bo");
            Game.awaitLobbyItems(ada);
            Game.awaitLobbyItems(bo);
            game.set("economy.entry-fee", "100");
            game.set("economy.house-cut-percent", "10");
            game.set("economy.prize-split", "100");

            Game.startRun(ada);
            Game.awaitRacing(ada);
            Game.awaitRacing(bo);
            Await.until("Bo is told the fee was taken", WAIT, () -> said(bo, "Entry fee of ⛃100 paid"));
            bo.forgetChat();
            bo.run("balance");
            Await.until("and it left his account", WAIT, () -> said(bo, "You have ⛃900"));

            game.server.console("advancement grant Ada only minecraft:story/mine_stone");
            ada.expectChat("Run finished!");
            // A plain race that reached its goal shares the pot among everybody who raced: 200 less 10% is 90 each.
            Await.until("the pot is paid out", WAIT, () -> {
                bo.forgetChat();
                bo.run("balance");
                Await.ticks(10);
                return said(bo, "You have ⛃990");
            });
        }
    }
}
