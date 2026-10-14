package de.raindancer.modules.twitch.service;

import de.raindancer.core.data.store.YamlStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TwitchLoginTest {

    @TempDir
    Path folder;

    @Test
    @DisplayName("a 1.x credentials.yml is read as it was written")
    void readsTheOldFile() throws Exception {
        Path file = folder.resolve("credentials.yml");
        Files.writeString(file, "client-id: ' abc123 '\nclient-secret: s3cr3t\n");
        TwitchLogin login = TwitchLogin.read(YamlStore.network(file));
        assertThat(login.present()).isTrue();
        assertThat(login.clientId()).isEqualTo("abc123");
        assertThat(login.clientSecret()).isEqualTo("s3cr3t");
        assertThat(login.toString()).doesNotContain("s3cr3t");
    }

    @Test
    @DisplayName("without a document a template is left, and it is not a login; an existing one is not overwritten")
    void template() throws Exception {
        YamlStore store = YamlStore.network(folder.resolve("credentials.yml"));
        TwitchLogin.ensureTemplate(store);
        assertThat(TwitchLogin.read(store).present()).isFalse();
        store.update(yaml -> yaml.set("client-id", "kept"));
        TwitchLogin.ensureTemplate(store);
        assertThat(TwitchLogin.read(store).clientId()).isEqualTo("kept");
    }
}
