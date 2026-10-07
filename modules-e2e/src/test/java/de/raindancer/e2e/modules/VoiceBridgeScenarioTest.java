package de.raindancer.e2e.modules;

import de.raindancer.e2e.Bot;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RainsVoiceBridge next to the real Simple Voice Chat plugin, without a Discord token: the group is
 * made, the page and every command answer, and a player without the mod is told so instead of being
 * put in a group they cannot hear. Discord itself needs a real bot and is not part of this scenario.
 */
@Tag("e2e")
class VoiceBridgeScenarioTest {

    private static final String VOICECHAT =
            "https://cdn.modrinth.com/data/9eGKb6K1/versions/EJth3OAr/voicechat-bukkit-2.6.24.jar";

    @Test
    @DisplayName("without a token: group made, page opens, join refused for a player without the mod")
    void withoutDiscord() {
        try (Server server = Server.start("voicebridge", List.of("voicebridge-standalone:RainsVoiceBridge-.*"),
                List.of(voicechat()), List.of("Made the voice chat group 'Discord'"))) {
            Bot ada = server.admin("Ada");
            Bot bo = server.player("Bo");

            ada.runAndOpen("voicebridge", "» Discord");
            Bot.Window page = ada.window().orElseThrow();
            assertThat(page.slotNamed("Not connected")).isPresent();
            assertThat(page.slotNamed("Voice chat group")).isPresent();
            int join = page.slotNamed("Join the Discord group").orElseThrow();
            assertThat(page.top().get(join).lore()).anyMatch(line -> line.contains("Simple Voice Chat"));
            ada.closeWindow();

            ada.runAndExpect("voicebridge join", "You need the Simple Voice Chat mod");
            ada.runAndExpect("voicebridge leave", "You are not in the Discord group");
            ada.runAndExpect("voicebridge status", "No Discord bot token yet");
            ada.runAndExpect("vb reconnect", "Reconnecting to Discord");
            bo.runAndExpect("voicebridge reconnect", "Only staff can reconnect");
            bo.runAndExpect("discordvoice nonsense", "/voicebridge");

            assertThat(server.paper.errorsFrom("RainsCore", "RainsVoiceBridge", "voicechat")).isEmpty();
        }
    }

    private static Path voicechat() {
        Path jar = Path.of(System.getProperty("e2e.cache", "target/e2e-cache")).resolve("voicechat-bukkit-2.6.24.jar");
        if (Files.isRegularFile(jar)) {
            return jar;
        }
        try {
            Files.createDirectories(jar.getParent());
            HttpResponse<Path> response = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build()
                    .send(HttpRequest.newBuilder(URI.create(VOICECHAT)).build(), HttpResponse.BodyHandlers.ofFile(jar));
            assertThat(response.statusCode()).as("downloading Simple Voice Chat").isEqualTo(200);
            return jar;
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted downloading Simple Voice Chat", interrupted);
        }
    }
}
