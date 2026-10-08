package de.raindancer.e2e.modules;

import de.raindancer.e2e.Await;
import de.raindancer.e2e.Bot;
import de.raindancer.e2e.Downloads;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The economy next to VaultUnlocked, the Vault that is still maintained: Vault names Rain's economy as the
 * one it hands to every Vault plugin, and money moved there is the same money /balance shows.
 */
@Tag("e2e")
class EconomyVaultScenarioTest {

    private static final String VAULT_URL =
            "https://cdn.modrinth.com/data/ayRaM8J7/versions/qZgRzoYs/VaultUnlocked-2.20.3.jar";
    private static final String VAULT_SHA1 = "daeb8d9dd3ba7bfbdfe51da01c7ccc6c8db47b3f";

    @Test
    @DisplayName("Vault hands out Rain's economy")
    void vault() {
        Path vault = Downloads.pinned("VaultUnlocked-2.20.3.jar", VAULT_URL, VAULT_SHA1);
        try (Server server = Server.start("economy-vault", List.of("economy-standalone:RainsEconomy-.*"),
                List.of(vault), List.of("The economy is up"))) {
            Bot ada = server.admin("Ada");
            String info = Await.value("Vault reports its economy", Duration.ofSeconds(30), () -> {
                String said = server.console("vault-info");
                return said.contains("RainsEconomy") ? said : null;
            });
            assertThat(info).contains("RainsEconomy");
            ada.run("balance");
            Await.until("the account Vault sees is the one /balance shows", Duration.ofSeconds(15),
                    () -> ada.chatText().stream().anyMatch(line -> line.contains("⛃1,000")));
            assertThat(server.paper.logLines(line -> line.contains("Exception"))).as("nothing threw").isEmpty();
        }
    }
}
