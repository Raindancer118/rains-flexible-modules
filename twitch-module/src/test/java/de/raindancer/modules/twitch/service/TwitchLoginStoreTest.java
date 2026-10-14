package de.raindancer.modules.twitch.service;

import de.raindancer.core.data.sql.CoreSchema;
import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.Secrets;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.testkit.TestDatabases;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("database")
class TwitchLoginStoreTest {

    @TempDir
    Path folder;

    private Database database;
    private Secrets secrets;
    private Path file;

    private TwitchLoginStore store(Map<String, String> environment) {
        if (database == null) {
            database = TestDatabases.open(folder.resolve("core.db"), CoreSchema.CORE);
            secrets = new Secrets(database, System::currentTimeMillis);
        }
        file = folder.resolve("credentials.yml");
        return new TwitchLoginStore(secrets, YamlStore.network(file), environment::get, Log.of("test"));
    }

    @AfterEach
    void close() {
        if (database != null) {
            database.close();
        }
    }

    @Test
    @DisplayName("an old credentials.yml moves into the secret and the document is left without the values")
    void migrates() throws Exception {
        Files.writeString(folder.resolve("credentials.yml"), "client-id: ' abc123 '\nclient-secret: s3cr3t\n");
        TwitchLoginStore store = store(Map.of());

        TwitchLogin login = store.read();

        assertThat(login.clientId()).isEqualTo("abc123");
        assertThat(login.clientSecret()).isEqualTo("s3cr3t");
        assertThat(store.source()).isEqualTo(TwitchLoginStore.Source.DATABASE);
        assertThat(secrets.text(TwitchLoginStore.SECRET)).contains("abc123\ns3cr3t");
        assertThat(Files.readString(file)).doesNotContain("s3cr3t").doesNotContain("abc123")
                .contains("/twitch login").contains("#");
        assertThat(store(Map.of()).read()).as("from the secret now").isEqualTo(login);
    }

    @Test
    @DisplayName("without a document or a secret there is no login, and a template without values is left")
    void nothing() throws Exception {
        TwitchLoginStore store = store(Map.of());
        store.ensureTemplate();

        assertThat(store.read().present()).isFalse();
        assertThat(store.source()).isEqualTo(TwitchLoginStore.Source.NONE);
        assertThat(Files.readString(file)).contains("RAINS_TWITCH_CLIENT_ID");
    }

    @Test
    @DisplayName("both environment variables win over the secret; one alone does not count")
    void environmentWins() {
        TwitchLoginStore store = store(Map.of());
        store.set("db-id", "db-secret");

        TwitchLogin env = store(Map.of(TwitchLoginStore.ENV_ID, " env-id ", TwitchLoginStore.ENV_SECRET, "env-secret")).read();
        assertThat(env.clientId()).isEqualTo("env-id");
        assertThat(env.clientSecret()).isEqualTo("env-secret");

        TwitchLoginStore half = store(Map.of(TwitchLoginStore.ENV_ID, "env-id"));
        assertThat(half.read().clientId()).isEqualTo("db-id");
        assertThat(half.source()).isEqualTo(TwitchLoginStore.Source.DATABASE);
    }

    @Test
    @DisplayName("set stores, clear removes")
    void setAndClear() {
        TwitchLoginStore store = store(Map.of());

        assertThat(store.set("id", "secret")).isTrue();
        assertThat(store.read().present()).isTrue();
        assertThat(store.clear()).isTrue();
        assertThat(store.read().present()).isFalse();
    }

    @Test
    @DisplayName("a document login that differs from the stored one is not overwritten into the secret, nor erased")
    void differingDocumentIsKept() throws Exception {
        Files.writeString(folder.resolve("credentials.yml"), "client-id: old\nclient-secret: oldsecret\n");
        TwitchLoginStore store = store(Map.of());
        store.set("new", "newsecret");

        assertThat(store.read().clientId()).isEqualTo("new");
        assertThat(Files.readString(file)).contains("oldsecret");
    }

    @Test
    @DisplayName("the login never prints its secret")
    void toStringHidesSecret() {
        assertThat(new TwitchLogin("abc", "s3cr3t").toString()).doesNotContain("s3cr3t").doesNotContain("abc");
        assertThat(TwitchLogin.decode("only-one-line").present()).isFalse();
    }
}
