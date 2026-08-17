package de.raindancer.modules.claims.store;

import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.claims.model.ClaimFeature;
import de.raindancer.modules.claims.model.FeaturePolicy;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

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
 * <h2>What gets written</h2>
 * Only what differs from the built-in behaviour. A file naming all of a version's features at their
 * default policy is unreadable for the one or two lines that matter, and it freezes those defaults:
 * improve one in a release and every existing server keeps the old value forever, because their file
 * spells it out.
 *
 * <p>Mirrors {@code LandPolicyStore} in RainsCore on purpose — same shape, same reasons — for the flags
 * this module does not own.
 *
 * <h2>What a bad line costs</h2>
 * A feature this version does not have, and a policy word nobody recognises, are both skipped rather than
 * guessed at or thrown over. One stale key must not cost an admin every other decision in the file.
 */
public final class FeaturePolicyStore {

    private static final LogChannel log = Log.of("land");
    private static final String FEATURES = "features";

    private final Path file;

    public FeaturePolicyStore(Path dataFolder) {
        this.file = dataFolder.resolve("feature-policies.yml");
    }

    /** What the file says, or everything as it ships when there is no file. */
    public FeaturePolicies load() {
        FeaturePolicies policies = FeaturePolicies.builtIn();
        if (!Files.isRegularFile(file)) {
            return policies;
        }

        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file));
        } catch (Exception broken) {
            log.warn("{} could not be read ({}). Every feature is at its built-in policy until this is "
                            + "fixed — your saved feature decisions are NOT being applied.",
                    file.getFileName(), broken.getMessage());
            return policies;
        }

        ConfigurationSection features = yaml.getConfigurationSection(FEATURES);
        if (features == null) {
            return policies;
        }
        for (String key : features.getKeys(false)) {
            Optional<ClaimFeature> feature = ClaimFeature.byKey(key);
            if (feature.isEmpty()) {
                // A feature this version no longer has. Left in the file, ignored here.
                log.debug("{} names a feature this version does not have ({}); ignoring it.",
                        file.getFileName(), key);
                continue;
            }
            String policyKey = features.getString(key);
            FeaturePolicy.byKey(policyKey).ifPresentOrElse(
                    found -> policies.policy(feature.get(), found),
                    () -> log.warn("{}: '{}' is not a policy I know for feature '{}'. Leaving it as it "
                                    + "ships rather than guessing.",
                            file.getFileName(), policyKey, key));
        }
        return policies;
    }

    /** Writes the decisions down, and removes the file when there are none left. */
    public void save(FeaturePolicies policies) throws IOException {
        Map<ClaimFeature, FeaturePolicy> changed = policies.changed();
        if (changed.isEmpty()) {
            // Not an empty file: no file. An admin who undid every change should not be left with
            // something on disk implying they made one.
            Files.deleteIfExists(file);
            return;
        }

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
                "What this server decided about the claim features.",
                "",
                "Only decisions that differ from the built-in behaviour are written here, so a feature",
                "that is absent behaves as it ships — and improving a built-in default in a later release",
                "reaches this server rather than being overruled by a line nobody meant to write.",
                "",
                "  available | forced-on | forced-off",
                "",
                "Set through the admin's \"What this claim can do\" screen rather than by hand, unless you",
                "prefer a file."));

        // Written in declaration order, so the file reads in the same order as the screen an admin
        // just used.
        for (ClaimFeature feature : ClaimFeature.values()) {
            FeaturePolicy policy = changed.get(feature);
            if (policy != null) {
                yaml.set(FEATURES + "." + feature.key(), policy.key());
            }
        }

        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, yaml.saveToString());
    }
}
