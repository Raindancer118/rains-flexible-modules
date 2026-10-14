package de.raindancer.modules.claims.store;

import de.raindancer.core.data.sql.Database;
import de.raindancer.core.data.store.DocumentFolder;
import de.raindancer.core.data.store.Documents;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.testkit.TestDatabases;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.model.ClaimShape;
import de.raindancer.modules.claims.model.ClaimFeature;
import de.raindancer.modules.claims.model.FeaturePolicy;
import de.raindancer.modules.claims.model.NoClaimZone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The claims as documents in Core's database — and the 1.x files still lying in the folder. */
@Tag("database")
class ClaimStorageDocumentsTest {

    private static final UUID WORLD = UUID.fromString("66666666-7777-8888-9999-000000000000");

    @TempDir
    Path plugins;

    private Database database;
    private Documents documents;

    @BeforeEach
    void open() {
        database = TestDatabases.open(plugins.resolve("documents.db"), Documents.SCHEMA);
        documents = new Documents(database, null, plugins, "smp", System::currentTimeMillis);
    }

    @AfterEach
    void close() {
        database.close();
    }

    private Path claimsFolder() {
        return plugins.resolve("RainsExtendedClaims").resolve("claims");
    }

    private ClaimStorage storage() {
        return new ClaimStorage(DocumentFolder.on(claimsFolder(), documents, Documents.Scope.SERVER));
    }

    private static Claim claim(String name) {
        return new Claim(UUID.randomUUID(), name, WORLD, "world",
                ClaimShape.rectangle(0, 0, 9, 9, -64, 200), UUID.randomUUID());
    }

    @Test
    @DisplayName("a claim saved comes back whole, from the database and not from a file")
    void roundTrip() throws IOException {
        Claim claim = claim("Ahornhof");
        storage().save(claim);

        List<Claim> loaded = storage().loadAll();

        assertThat(loaded).hasSize(1);
        assertThat(loaded.get(0).id()).isEqualTo(claim.id());
        assertThat(loaded.get(0).name()).isEqualTo("Ahornhof");
        assertThat(loaded.get(0).owners()).isEqualTo(claim.owners());
        assertThat(claimsFolder().resolve(claim.id() + ".yml")).doesNotExist();
    }

    @Test
    @DisplayName("a deleted claim is gone from the listing")
    void delete() throws IOException {
        Claim kept = claim("kept");
        Claim gone = claim("gone");
        storage().save(kept);
        storage().save(gone);

        storage().delete(gone.id());

        assertThat(storage().loadAll()).extracting(Claim::name).containsExactly("kept");
    }

    @Test
    @DisplayName("a 1.x claim file is listed, brought in on its first read and renamed")
    void oldFileIsBroughtIn() throws IOException {
        Claim claim = claim("old");
        Files.createDirectories(claimsFolder());
        Path file = claimsFolder().resolve(claim.id() + ".yml");
        Files.writeString(file, ClaimStorage.describe(claim).saveToString());

        List<Claim> loaded = storage().loadAll();

        assertThat(loaded).extracting(Claim::id).containsExactly(claim.id());
        assertThat(file).doesNotExist();
        assertThat(storage().loadAll()).hasSize(1);
    }

    @Test
    @DisplayName("an unreadable claim is not loaded, not lost and not overwritten")
    void unreadableIsKept() throws IOException {
        Claim fine = claim("fine");
        storage().save(fine);
        String rubbish = "id: [unclosed\n  : : :\n";
        UUID broken = UUID.randomUUID();
        DocumentFolder folder = DocumentFolder.on(claimsFolder(), documents, Documents.Scope.SERVER);
        assertThat(folder.store(broken + ".yml").writeText(rubbish)).isTrue();

        List<Claim> loaded = storage().loadAll();

        assertThat(loaded).extracting(Claim::id).containsExactly(fine.id());
        assertThat(folder.names(".yml")).contains(broken + ".yml");
        assertThat(folder.store(broken + ".yml").readText()).contains(rubbish);
    }

    @Test
    @DisplayName("an unreadable 1.x file stays a file, listed and untouched")
    void unreadableFileStays() throws IOException {
        Files.createDirectories(claimsFolder());
        Path file = claimsFolder().resolve(UUID.randomUUID() + ".yml");
        Files.writeString(file, "id: [unclosed\n  : : :\n");

        assertThat(storage().loadAll()).isEmpty();

        assertThat(file).exists().hasContent("id: [unclosed\n  : : :\n");
    }

    @Test
    @DisplayName("the no-claim zones round trip through the database")
    void zones() throws IOException {
        YamlStore store = new YamlStore(plugins.resolve("RainsExtendedClaims").resolve("no-claim-zones.yml"),
                documents, Documents.Scope.SERVER);
        new ZoneStorage(store).saveAll(List.of(new NoClaimZone("spawn", WORLD, "world",
                ClaimShape.rectangle(0, 0, 9, 9, -64, 200), 1700000000000L)));

        List<NoClaimZone> loaded = new ZoneStorage(store).loadAll();

        assertThat(loaded).extracting(NoClaimZone::name).containsExactly("spawn");
        assertThat(plugins.resolve("RainsExtendedClaims").resolve("no-claim-zones.yml")).doesNotExist();
    }

    @Test
    @DisplayName("feature policies are saved, read, and removed when nothing differs any more")
    void featurePolicies() {
        YamlStore store = new YamlStore(plugins.resolve("RainsExtendedClaims").resolve("feature-policies.yml"),
                documents, Documents.Scope.SERVER);
        FeaturePolicies policies = FeaturePolicies.builtIn();
        policies.policy(ClaimFeature.PANTRY, FeaturePolicy.FORCED_OFF);
        FeaturePolicyStore saver = new FeaturePolicyStore(store);

        assertThat(saver.save(policies)).isTrue();
        assertThat(new FeaturePolicyStore(store).load().policy(ClaimFeature.PANTRY)).isEqualTo(FeaturePolicy.FORCED_OFF);

        policies.policy(ClaimFeature.PANTRY, ClaimFeature.PANTRY.builtInDefault());
        assertThat(saver.save(policies)).isTrue();
        assertThat(store.exists()).isFalse();
    }
}
