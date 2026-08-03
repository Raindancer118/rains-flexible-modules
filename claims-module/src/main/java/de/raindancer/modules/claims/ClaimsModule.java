package de.raindancer.modules.claims;

import de.raindancer.modules.claims.listener.FenceListener;
import de.raindancer.modules.claims.listener.MovementListener;
import de.raindancer.modules.claims.listener.PlayerSessionListener;
import de.raindancer.modules.claims.listener.SelectionListener;
import de.raindancer.modules.claims.model.Claim;
import de.raindancer.modules.claims.rules.ClaimLandProvider;
import de.raindancer.modules.claims.rules.ClaimNames;
import de.raindancer.modules.claims.rules.ClaimRights;
import de.raindancer.modules.claims.rules.FeaturePolicies;
import de.raindancer.modules.claims.rules.Features;
import de.raindancer.modules.claims.selection.SelectionFlow;
import de.raindancer.modules.claims.selection.SelectionService;
import de.raindancer.modules.claims.selection.SelectionStick;
import de.raindancer.modules.claims.service.AmbienceService;
import de.raindancer.modules.claims.service.BroadcastService;
import de.raindancer.modules.claims.service.ClaimService;
import de.raindancer.modules.claims.service.CostService;
import de.raindancer.modules.claims.service.EntryFeeService;
import de.raindancer.modules.claims.service.EquipService;
import de.raindancer.modules.claims.service.EvictionService;
import de.raindancer.modules.claims.service.FenceService;
import de.raindancer.modules.claims.store.ClaimRegistry;
import de.raindancer.modules.claims.store.ClaimStorage;
import de.raindancer.modules.claims.store.ZoneRegistry;
import de.raindancer.modules.claims.store.ZoneStorage;
import de.raindancer.modules.claims.visual.BorderVisualizer;
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
    private ClaimRights rights;
    private de.raindancer.core.data.settings.SettingsStore<ClaimSettings> settings;
    private ZoneRegistry zones;
    private ZoneStorage zoneStorage;
    private CostService costs;
    private ClaimService claimService;
    private BorderVisualizer visualizer;
    private SelectionService selections;
    private SelectionStick stick;
    private SelectionFlow selectionFlow;
    private FenceService fences;
    private BroadcastService broadcasts;
    private EvictionService eviction;
    private EntryFeeService entryFees;
    private EquipService equipment;
    private AmbienceService ambience;
    private MovementListener movement;
    private ClaimServices services;

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

        // ── the product layer ─────────────────────────────────────────────────────────────────────────
        rights = new ClaimRights(land);
        settings = context.settings(ClaimSettings.class, ClaimSettings.DEFAULTS);
        zones = new ZoneRegistry();
        zoneStorage = new ZoneStorage(context.dataFolder());
        zoneStorage.loadAll().forEach(zones::add);

        costs = new CostService();
        claimService = new ClaimService(context.plugin(), claims, zones, storage, settings.current(),
                costs, rights);
        claimService.features(features);
        visualizer = new BorderVisualizer(context.plugin(), settings.current());
        selections = new SelectionService(settings.current());
        stick = new SelectionStick(context.plugin(), settings.current(), context.core().messages());
        fences = new FenceService(context.plugin(), settings.current(), claimService, features);
        claimService.fences(fences);
        broadcasts = new BroadcastService(context.plugin(), settings.current(), features,
                context.core().messages(), names);
        eviction = new EvictionService(context.plugin(), context.core().messages());
        entryFees = new EntryFeeService(context.plugin(), settings.current(),
                context.core().messages(), costs, claimService, land, features);
        equipment = new EquipService(settings.current(), features, land, claimService,
                context.core().messages(), names);
        ambience = new AmbienceService(context.plugin(), features, claims, land, claimService,
                settings.current(), context.core().messages(), names);
        ambience.equipService(equipment);

        selectionFlow = new SelectionFlow(context.plugin().getServer(), log, context.core().messages(),
                context.core().prompts(), selections, stick, claims, claimService, zones,
                this::saveZones, visualizer, rights, settings::current);

        services = new ClaimServices(context.plugin(), context.plugin().getServer(), log,
                context.core().messages(), context.chat().brand(), context.core().prompts(), land,
                land.flags(), features, claims, storage, zones, claimService, names, rights, provider,
                costs, selections, stick, selectionFlow, visualizer, fences, ambience, entryFees,
                eviction, equipment, broadcasts, settings::current, new LiveScreens(), () -> movement);
        movement = new MovementListener(services);
        ambience.movement(movement);

        // Every setting is a snapshot, so a reload hands each service a fresh one. Missing one of these is a
        // subsystem that keeps yesterday's numbers until the next restart, which is the sort of bug that gets
        // reported as "the config does not work".
        settings.onChange(fresh -> {
            claimService.settings(fresh);
            visualizer.settings(fresh);
            selections.settings(fresh);
            stick.settings(fresh);
            fences.settings(fresh);
            broadcasts.settings(fresh);
            entryFees.settings(fresh);
            equipment.settings(fresh);
            ambience.settings(fresh);
        });

        context.listener(movement);
        context.listener(new PlayerSessionListener(services));
        context.listener(new SelectionListener(services));
        context.listener(new FenceListener(services));

        // The commands were registered during bootstrap, long before any of this existed, and have been
        // answering "not started yet" until now. See ClaimCommands.
        ClaimCommands.ready(services);

        log.info("Claims are up: {} claim(s), {} no-claim zone(s).", claims.size(), zones.all().size());
    }

    /**
     * Opening the screens, which is the only thing in the module that knows the menu classes exist.
     *
     * <p>An inner class rather than eight lambdas at the construction site: it reads as a list of the screens
     * this module has, and a new screen is one method rather than one more constructor argument.
     */
    private final class LiveScreens implements ClaimScreensOpener {

        @Override
        public void claim(org.bukkit.entity.Player viewer, Claim claim) {
            new de.raindancer.modules.claims.screen.ClaimMenu(services, viewer, claim, null).open();
        }

        @Override
        public void list(org.bukkit.entity.Player viewer) {
            new de.raindancer.modules.claims.screen.ClaimListMenu(services, viewer, null).open();
        }

        @Override
        public void selection(org.bukkit.entity.Player viewer) {
            new de.raindancer.modules.claims.screen.SelectionMenu(services, viewer, null).open();
        }

        @Override
        public void fenceMaterial(org.bukkit.entity.Player viewer, Claim claim) {
            // Core's item chooser rather than a picker of our own: it already knows how to page through
            // every material, search them and show them as real items.
            new de.raindancer.core.ui.choose.ItemChooser(viewer, services.brand(), null,
                    "Fence material",
                    chosen -> {
                        fences.changeMaterial(claim, chosen, viewer);
                        claimService.saveAsync(claim);
                        claim(viewer, claim);
                    }).open();
        }

        @Override
        public void pantry(org.bukkit.entity.Player viewer, Claim claim) {
            new de.raindancer.modules.claims.screen.StoreMenu(services, viewer, claim, null,
                    de.raindancer.modules.claims.screen.StoreMenu.Kind.PANTRY).open();
        }

        @Override
        public void potionStore(org.bukkit.entity.Player viewer, Claim claim) {
            new de.raindancer.modules.claims.screen.StoreMenu(services, viewer, claim, null,
                    de.raindancer.modules.claims.screen.StoreMenu.Kind.POTIONS).open();
        }

        @Override
        public void titles(org.bukkit.entity.Player viewer, Claim claim) {
            new de.raindancer.modules.claims.screen.TitlesMenu(services, viewer, claim, null).open();
        }

        @Override
        public void admin(org.bukkit.entity.Player viewer) {
            new de.raindancer.modules.claims.screen.AdminMenu(services, viewer, null).open();
        }
    }

    /** Writes the no-claim zones. Small and rare, so it is written whole rather than incrementally. */
    private void saveZones() {
        try {
            zoneStorage.saveAll(zones.all());
        } catch (IOException cannot) {
            log.error(cannot, "Could not write the no-claim zones");
        }
    }

    /**
     * The commands, declared at bootstrap.
     *
     * <p>Paper wants them before anything is enabled, so they are built pointing at a supplier that is filled in
     * when this module starts. Until then the host's guard answers a player with one line saying so.
     */
    @Override
    public java.util.List<de.raindancer.modules.api.ModuleCommand> commands() {
        return ClaimCommands.declared();
    }

    @Override
    public void disable() {
        ClaimCommands.stopped();
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
