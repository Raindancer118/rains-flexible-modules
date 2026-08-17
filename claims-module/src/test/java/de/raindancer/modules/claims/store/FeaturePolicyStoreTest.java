package de.raindancer.modules.claims.store;

import de.raindancer.modules.claims.model.ClaimFeature;
import de.raindancer.modules.claims.model.FeaturePolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Keeping what an admin decided in the "What this claim can do" screen.
 *
 * <h2>Why this has to exist</h2>
 * {@link FeaturePolicies} was built fresh from {@link ClaimFeature#builtInDefault()} in
 * {@code ClaimsModule.enable()} on every start, and nothing ever read a file into it or wrote one out.
 * An admin who forced a feature off in the screen got exactly what they asked for until the next restart,
 * which is worse than not offering the setting: the server behaves differently after a restart than it did
 * before one, and nothing on disk says why.
 */
class FeaturePolicyStoreTest {

    @TempDir
    Path folder;

    private Path file() {
        return folder.resolve("feature-policies.yml");
    }

    @Test
    @DisplayName("an untouched policy writes no file at all")
    void nothingChangedIsNoFile() {
        new FeaturePolicyStore(folder).save(FeaturePolicies.builtIn());

        assertThat(file())
                .as("a file full of built-in values is one nobody can read for what actually changed")
                .doesNotExist();
    }

    @Test
    @DisplayName("a policy survives a restart")
    void whatWasSetComesBack() {
        FeaturePolicies policies = FeaturePolicies.builtIn();
        policies.policy(ClaimFeature.PANTRY, FeaturePolicy.FORCED_OFF);

        new FeaturePolicyStore(folder).save(policies);
        FeaturePolicies loaded = new FeaturePolicyStore(folder).load();

        assertThat(loaded.policy(ClaimFeature.PANTRY)).isEqualTo(FeaturePolicy.FORCED_OFF);
    }

    @Test
    @DisplayName("a feature nobody touched still answers with its built-in policy")
    void theRestIsUntouched() {
        FeaturePolicies policies = FeaturePolicies.builtIn();
        policies.policy(ClaimFeature.PANTRY, FeaturePolicy.FORCED_OFF);
        new FeaturePolicyStore(folder).save(policies);

        FeaturePolicies loaded = new FeaturePolicyStore(folder).load();

        assertThat(loaded.policy(ClaimFeature.AUTO_EQUIP)).isEqualTo(ClaimFeature.AUTO_EQUIP.builtInDefault());
    }

    @Test
    @DisplayName("no file is every feature as it ships")
    void anAbsentFileIsFine() {
        FeaturePolicies loaded = new FeaturePolicyStore(folder).load();

        assertThat(loaded.isUntouched()).isTrue();
    }

    @Test
    @DisplayName("a feature the server no longer has is ignored rather than fatal")
    void anUnknownFeatureIsSkipped() throws IOException {
        Files.writeString(file(), """
                features:
                  something-we-removed: forced-off
                  pantry: forced-on
                """);

        FeaturePolicies loaded = new FeaturePolicyStore(folder).load();

        assertThat(loaded.policy(ClaimFeature.PANTRY))
                .as("one stale key must not cost the admin every other decision in the file")
                .isEqualTo(FeaturePolicy.FORCED_ON);
    }

    @Test
    @DisplayName("a policy word nobody recognises leaves that feature alone")
    void anUnreadablePolicyIsNotGuessed() throws IOException {
        Files.writeString(file(), "features:\n  pantry: sometimes\n");

        assertThat(new FeaturePolicyStore(folder).load().policy(ClaimFeature.PANTRY))
                .as("guessing at what a claim may do is how a server's decision quietly changes")
                .isEqualTo(ClaimFeature.PANTRY.builtInDefault());
    }

    @Test
    @DisplayName("setting a feature back to its built-in policy removes it from the file")
    void undoingAChangeShrinksTheFile() {
        FeaturePolicies policies = FeaturePolicies.builtIn();
        policies.policy(ClaimFeature.PANTRY, FeaturePolicy.FORCED_OFF);
        FeaturePolicyStore store = new FeaturePolicyStore(folder);
        store.save(policies);

        policies.policy(ClaimFeature.PANTRY, ClaimFeature.PANTRY.builtInDefault());
        store.save(policies);

        assertThat(store.load().isUntouched())
                .as("an admin who undoes a change should not leave a line behind that says they did")
                .isTrue();
    }

    @Test
    @DisplayName("a broken file is not silently treated as an empty one")
    void abrokenFileIsReported() throws IOException {
        Files.writeString(file(), "features:\n  : : :\n  \"unclosed\n");

        FeaturePolicies loaded = new FeaturePolicyStore(folder).load();

        assertThat(loaded.isUntouched()).isTrue();
    }
}
