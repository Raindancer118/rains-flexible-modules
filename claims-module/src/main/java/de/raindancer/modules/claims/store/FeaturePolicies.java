package de.raindancer.modules.claims.store;

import de.raindancer.modules.claims.rules.FeatureRules;
import de.raindancer.modules.claims.model.ClaimFeature;
import de.raindancer.modules.claims.model.FeaturePolicy;
import java.util.EnumMap;
import java.util.Map;

/**
 * What the server owner has decided about the optional things a claim can do.
 *
 * <p>FeatureRules stayed with the claims module while flags went to Core, and the line between them is worth
 * stating: a <b>flag</b> says how the world behaves on protected ground — fire, decay, PvP — and any region
 * plugin would want the same list. A <b>feature</b> is a pantry, a bank, a fence, an entry fee. Those are
 * things a claim <em>has</em>, and an arena or a plot world would have no idea what to do with them.
 *
 * <p>Same shape as Core's {@code LandPolicies} on purpose, including the property that matters: setting
 * something back to its default forgets it, so a config file holds the two decisions somebody made rather than
 * all twenty-six at their default values — and a later version changing a default reaches the servers that
 * never disagreed with the old one.
 */
public final class FeaturePolicies {

    private final Map<ClaimFeature, FeaturePolicy> changed = new EnumMap<>(ClaimFeature.class);

    /** Everything as the features themselves say it should be. */
    public static FeaturePolicies builtIn() {
        return new FeaturePolicies();
    }

    public FeaturePolicy policy(ClaimFeature feature) {
        return changed.getOrDefault(feature, feature.builtInDefault());
    }

    /** @param policy null, or the feature's own default, forgets the decision */
    public void policy(ClaimFeature feature, FeaturePolicy policy) {
        if (policy == null || policy == feature.builtInDefault()) {
            changed.remove(feature);
        } else {
            changed.put(feature, policy);
        }
    }

    /** Whether anything at all differs from the built-in behaviour. */
    public boolean isUntouched() {
        return changed.isEmpty();
    }

    /** What a config file has to write down: only the decisions somebody actually made. */
    public Map<ClaimFeature, FeaturePolicy> changed() {
        return Map.copyOf(changed);
    }

    /** Reads a stored set of decisions back, replacing whatever was here. */
    public void restore(Map<ClaimFeature, FeaturePolicy> stored) {
        changed.clear();
        stored.forEach(this::policy);
    }

    /**
     * Reads one stored line, ignoring anything it does not recognise.
     *
     * <p>Ignoring rather than refusing: a feature removed in a later version leaves its line behind in
     * somebody's file, and a config that failed to load over it would take every other feature with it.
     */
    public void set(String featureKey, String policyKey) {
        ClaimFeature.byKey(featureKey).ifPresent(feature ->
                FeaturePolicy.byKey(policyKey).ifPresent(policy -> policy(feature, policy)));
    }
}
