package de.raindancer.modules.claims;

import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.world.protection.Land;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Land claims, as a module.
 *
 * <p>Shipped through the standard wrapper this is {@code RainsClaims}, a plugin of its own. Hosted inside
 * {@code RainsSMPCore} it is one feature among several. The code below cannot tell which, and that is the whole
 * point of the arrangement.
 *
 * <h2>What enabling actually does</h2>
 * Loads the claims, then <b>registers itself with Core as the answer to "may this player do that here?"</b>.
 * That second step is what makes claims real to the rest of the server: from that moment a warp, a teleport
 * request, a ghast line and a farm-world regeneration all get a truthful answer, without any of them knowing
 * this module exists.
 *
 * <p>Stopping stands the provider down again, after which Core answers {@code UNKNOWN} rather than pretending
 * nothing is protected. A server that removes this module does not thereby make everybody's builds fair game.
 */
public final class ClaimsModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("claims", "Claims", "1.0.0")
            .describedAs("Land claims: who owns what, who may do what there, and the screens for it")
            .by("Raindancer118");

    private ClaimRegistry claims;
    private ClaimStorage storage;
    private ClaimLandProvider provider;
    private FeaturePolicies featurePolicies;
    private Features features;
    private ClaimNames names;
    private LogChannel log;
    private Land land;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        log = context.log();
        land = context.core().land();

        featurePolicies = FeaturePolicies.builtIn();
        features = new Features(featurePolicies);

        claims = new ClaimRegistry();
        storage = new ClaimStorage(context.dataFolder());
        try {
            storage.ensureDirectory();
        } catch (IOException cannot) {
            // Refused rather than carried on: a claims module that cannot reach its own directory would come
            // up with no claims at all, register itself as the authority, and answer "nothing is protected
            // here" for every block on the server.
            throw new UncheckedIOException("cannot reach the claims directory", cannot);
        }
        for (Claim claim : storage.loadAll()) {
            claims.add(claim);
        }
        log.info("{} claim(s) loaded.", claims.size());

        // Player names come from Core, which already knows everybody it has seen. A second name cache here
        // would be a second set of answers, drifting apart the first time somebody renames.
        names = new ClaimNames(claims, id -> context.core().identities().nameOf(id).orElse(null));

        provider = new ClaimLandProvider(claims);
        if (!land.provider(provider)) {
            // Something else already answers for land on this server. Refusing to start is the honest
            // outcome: two sets of rules over the same blocks cannot both be enforced, and the half of this
            // module that would still work would be the half that edits claims nothing obeys.
            throw new IllegalStateException(
                    "another plugin already answers land questions on this server ("
                            + land.provider().map(p -> p.name()).orElse("unknown") + ")");
        }
        context.closeWith(() -> land.withdraw(provider));
    }

    @Override
    public void disable() {
        if (storage != null && claims != null) {
            int failed = 0;
            for (Claim claim : claims.all()) {
                try {
                    storage.save(claim);
                } catch (IOException cannot) {
                    // One claim that will not write must not stop the other two hundred. A shutdown is the
                    // last chance these have to reach the disk.
                    failed++;
                    log.error(cannot, "Could not write claim {}", claim.name());
                }
            }
            if (failed > 0) {
                log.error("{} claim(s) could not be written on shutdown.", failed);
            }
        }
        // The provider is stood down by the context, in the reverse order everything was registered — see
        // ModuleContext.closeWith. Doing it here as well would be the same call twice.
    }

    /** The claims themselves, for the commands and the screens. */
    public ClaimRegistry claims() {
        return claims;
    }

    public ClaimStorage storage() {
        return storage;
    }

    public Features features() {
        return features;
    }

    public FeaturePolicies featurePolicies() {
        return featurePolicies;
    }

    public ClaimNames names() {
        return names;
    }

    public ClaimLandProvider provider() {
        return provider;
    }
}
