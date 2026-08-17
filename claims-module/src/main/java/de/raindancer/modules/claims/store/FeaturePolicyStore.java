package de.raindancer.modules.claims.store;

import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.claims.model.ClaimFeature;
import de.raindancer.modules.claims.model.FeaturePolicy;
import org.bukkit.configuration.ConfigurationSection;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The feature decisions an admin made, on disk.
 *
 * <p>{@link FeaturePolicies} lives in memory and starts from what each feature says about itself. Without
 * this, switching a feature off in the admin's "What this claim can do" screen lasted until the next
 * restart — which is worse than not offering the setting at all, because the server then behaves
 * differently after a restart than it did before one and nothing says why.
 *
 * <h2>Reading and writing go through Core</h2>
 * The write-to-a-temporary-then-move dance is {@link YamlStore}'s, the same as every other store in this
 * reactor — this class only decides <em>what</em> a feature-policy file means, never how a byte reaches
 * disk safely. Mirrors {@code LandPolicyStore} in RainsCore on purpose — same shape, same reasons — for
 * the flags this module does not own.
 *
 * <h2>What gets written</h2>
 * Only what differs from the built-in behaviour. A file naming all of a version's features at their
 * default policy is unreadable for the one or two lines that matter, and it freezes those defaults:
 * improve one in a release and every existing server keeps the old value forever, because their file
 * spells it out.
 *
 * <h2>What a bad line costs</h2>
 * A feature this version does not have, and a policy word nobody recognises, are both skipped rather than
 * guessed at or thrown over. One stale key must not cost an admin every other decision in the file.
 */
public final class FeaturePolicyStore {

    private static final LogChannel log = Log.of("land");
    private static final String FEATURES = "features";

    private final YamlStore store;

    public FeaturePolicyStore(Path dataFolder) {
        this.store = new YamlStore(dataFolder.resolve("feature-policies.yml"));
    }

    /** What the file says, or everything as it ships when there is no file. */
    public FeaturePolicies load() {
        FeaturePolicies policies = FeaturePolicies.builtIn();
        if (!store.exists()) {
            return policies;
        }

        ConfigurationSection features = store.read().getConfigurationSection(FEATURES);
        // A file that would not parse is reported by the store rather than thrown, and read as empty.
        // Empty and unreadable look identical from here, so the difference has to come from the store.
        if (!store.problems().isEmpty()) {
            log.warn("feature-policies.yml could not be read ({}). Every feature is at its built-in "
                            + "policy until this is fixed — your saved feature decisions are NOT being applied.",
                    String.join("; ", store.problems()));
            return policies;
        }
        if (features == null) {
            return policies;
        }
        for (String key : features.getKeys(false)) {
            Optional<ClaimFeature> feature = ClaimFeature.byKey(key);
            if (feature.isEmpty()) {
                // A feature this version no longer has. Left in the file, ignored here.
                log.debug("feature-policies.yml names a feature this version does not have ({}); "
                        + "ignoring it.", key);
                continue;
            }
            String policyKey = features.getString(key);
            FeaturePolicy.byKey(policyKey).ifPresentOrElse(
                    found -> policies.policy(feature.get(), found),
                    () -> log.warn("feature-policies.yml: '{}' is not a policy I know for feature '{}'. "
                            + "Leaving it as it ships rather than guessing.", policyKey, key));
        }
        return policies;
    }

    /**
     * Writes the decisions down, and removes the file when there are none left.
     *
     * @return whether it reached disk
     */
    public boolean save(FeaturePolicies policies) {
        Map<ClaimFeature, FeaturePolicy> changed = policies.changed();
        if (changed.isEmpty()) {
            // Not an empty file: no file. An admin who undid every change should not be left with
            // something on disk implying they made one. YamlStore has no notion of that — every other
            // store built on it only ever grows and shrinks entries within one file — so this one case
            // stays here rather than in Core.
            try {
                Files.deleteIfExists(store.file());
                return true;
            } catch (IOException cannot) {
                log.error(cannot, "Could not remove feature-policies.yml");
                return false;
            }
        }

        return store.write(yaml -> {
            yaml.options().setHeader(List.of(
                    "What this server decided about the claim features.",
                    "",
                    "Only decisions that differ from the built-in behaviour are written here, so a",
                    "feature that is absent behaves as it ships — and improving a built-in default in a",
                    "later release reaches this server rather than being overruled by a line nobody",
                    "meant to write.",
                    "",
                    "  available | forced-on | forced-off",
                    "",
                    "Set through the admin's \"What this claim can do\" screen rather than by hand, "
                            + "unless you prefer a file."));
            // Written in declaration order, so the file reads in the same order as the screen an
            // admin just used.
            for (ClaimFeature feature : ClaimFeature.values()) {
                FeaturePolicy policy = changed.get(feature);
                if (policy != null) {
                    yaml.set(FEATURES + "." + feature.key(), policy.key());
                }
            }
        });
    }
}
