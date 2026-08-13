package de.raindancer.modules.mannequin;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.mannequin.listener.MannequinCombatListener;
import de.raindancer.modules.mannequin.listener.MannequinDeathListener;
import de.raindancer.modules.mannequin.listener.MannequinPickupListener;
import de.raindancer.modules.mannequin.listener.MannequinWorldListener;
import de.raindancer.modules.mannequin.model.Mannequin;
import de.raindancer.modules.mannequin.rules.ComboWindowRule;
import de.raindancer.modules.mannequin.rules.DurabilityRule;
import de.raindancer.modules.mannequin.rules.ShieldBlockRule;
import de.raindancer.modules.mannequin.rules.SignalStrengthRule;
import de.raindancer.modules.mannequin.screen.LoadoutScreen;
import de.raindancer.modules.mannequin.screen.MannequinListMenu;
import de.raindancer.modules.mannequin.screen.SkinScreen;
import de.raindancer.modules.mannequin.screen.StatsScreen;
import de.raindancer.modules.mannequin.service.MannequinCombatService;
import de.raindancer.modules.mannequin.service.MannequinEquipService;
import de.raindancer.modules.mannequin.service.MannequinPotionService;
import de.raindancer.modules.mannequin.service.MannequinRedstoneService;
import de.raindancer.modules.mannequin.service.MannequinService;
import de.raindancer.modules.mannequin.store.MannequinRegistry;
import de.raindancer.modules.mannequin.store.MannequinStore;
import de.raindancer.modules.mannequin.util.PermissionNodes;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.List;

/**
 * Training dummies, as a module.
 *
 * <p>Shipped through the standard wrapper this is {@code RainsMannequins}. Everything a mannequin
 * needs — the entity, the loadout, the invincibility, the combo tracking, the shield block, the
 * redstone pulse and the potion pickup — is wired here from the pure {@code rules}, the {@code
 * service} classes that act, and the two Bukkit listeners.
 */
public final class MannequinModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("mannequin", "Mannequin", "1.2.0")
            .describedAs("Training dummies an owner spawns and dresses: real health that can be "
                    + "brought down and respawns identically afterwards, every hit tracked, "
                    + "blocking with a shield, and never leaving anything obtainable behind.")
            .by("Raindancer118");

    /**
     * How often the periodic upkeep runs — the shield-block check and the consumable sweep both
     * ride the same timer, a few times a second being plenty for either.
     */
    private static final long UPKEEP_TICKS = 10L;

    /**
     * How close a dropped potion or golden apple has to land to count as "at its feet" — a
     * mannequin never walks to fetch one, so this only needs to cover an item thrown or dropped
     * right beside it.
     */
    private static final double CONSUMABLE_RANGE = 1.5;

    private LogChannel log;
    private SettingsStore<MannequinSettings> settings;
    private MannequinRegistry registry;
    private MannequinService mannequins;
    private MannequinPotionService potions;
    private MannequinServices services;
    private final ShieldBlockRule shieldBlockRule = new ShieldBlockRule();

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        log = context.log();
        Server server = context.plugin().getServer();
        settings = context.settings(MannequinSettings.class, MannequinSettings.DEFAULTS);

        context.core().messages().defineFrom(
                MannequinModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);

        int registered = PermissionNodes.register(server);
        if (registered > 0) {
            log.info("{} permission(s) registered.", registered);
        }

        registry = new MannequinRegistry();
        MannequinStore store = new MannequinStore(context.dataFolder());
        for (Mannequin mannequin : store.loadAll()) {
            registry.put(mannequin);
        }

        MannequinEquipService equip = new MannequinEquipService(new DurabilityRule(), settings.current());
        MannequinRedstoneService redstone = new MannequinRedstoneService(context.plugin(), settings.current());
        potions = new MannequinPotionService(settings.current());
        MannequinCombatService combat = new MannequinCombatService(registry, equip, redstone,
                context.core().actionBars(), new ComboWindowRule(),
                new SignalStrengthRule(), settings.current());
        MannequinService.DelayedScheduler delayedScheduler = (delayTicks, task) ->
                Scheduling.globalLater(context.plugin(), delayTicks, task);
        mannequins = new MannequinService(context.plugin(), log, registry, store, equip,
                delayedScheduler, settings.current());

        settings.onChange(equip::settings);
        settings.onChange(redstone::settings);
        settings.onChange(potions::settings);
        settings.onChange(combat::settings);
        settings.onChange(mannequins::settings);

        // Every world already loaded by the time this module starts gets its mannequins spawned now;
        // worlds that load later are handled by MannequinWorldListener.
        for (org.bukkit.World world : server.getWorlds()) {
            mannequins.spawnAllIn(world);
        }

        context.listener(new MannequinCombatListener(registry, combat));
        context.listener(new MannequinDeathListener(registry, mannequins));
        context.listener(new MannequinPickupListener(registry, potions));
        context.listener(new MannequinWorldListener(mannequins));

        services = new MannequinServices(context.plugin(), server, log, context.core().messages(),
                context.chat().brand(), context.core().actionBars(), settings::current, settings,
                registry, mannequins, equip, redstone, potions, combat, new LiveScreens());

        MannequinCommands.ready(services);

        var upkeepTimer = Scheduling.globalTimer(context.plugin(), UPKEEP_TICKS, UPKEEP_TICKS,
                task -> periodicUpkeep());
        if (upkeepTimer != null) {
            context.closeWith(upkeepTimer::cancel);
        }

        log.info("Mannequins are up: {} loaded, combo window {}ms, one-shot threshold {}.",
                registry.size(), settings.current().comboWindow(), settings.current().oneShotThresholdDamage());
    }

    /**
     * Everything a live mannequin needs done to it on a schedule rather than in response to an
     * event: raising a shield it holds, and consuming a potion or golden apple dropped near it.
     * One timer rather than two, since both walk the same {@code registry.all()} on the same tick.
     */
    private void periodicUpkeep() {
        MannequinSettings current = settings.current();
        for (Mannequin mannequin : registry.all()) {
            mannequins.liveEntity(mannequin.id())
                    .filter(org.bukkit.entity.Mannequin.class::isInstance)
                    .map(org.bukkit.entity.Mannequin.class::cast)
                    .ifPresent(entity -> {
                        if (current.blockingEnabled() && mannequin.blocksWithShield()) {
                            maybeRaiseShield(entity, current);
                        }
                        consumeNearbyConsumables(entity);
                    });
        }
    }

    /**
     * A mannequin is inert rather than a pathing mob, so it never walks over to a dropped potion
     * or golden apple the way a real mob's own AI would — {@code EntityPickupItemEvent} alone
     * never fired for one. This is the fix: found here on a schedule instead, and consumed through
     * the exact same {@link MannequinPotionService#tryConsume} the reactive listener also uses.
     */
    private void consumeNearbyConsumables(org.bukkit.entity.Mannequin entity) {
        for (org.bukkit.entity.Entity nearby
                : entity.getNearbyEntities(CONSUMABLE_RANGE, CONSUMABLE_RANGE, CONSUMABLE_RANGE)) {
            if (nearby instanceof org.bukkit.entity.Item item) {
                potions.tryConsume(entity, item);
            }
        }
    }

    private void maybeRaiseShield(org.bukkit.entity.Mannequin entity, MannequinSettings current) {
        ItemStack offHand = entity.getEquipment().getItemInOffHand();
        boolean hasShield = offHand != null && offHand.getType() == org.bukkit.Material.SHIELD;
        boolean alreadyBlocking = entity.isHandRaised();
        boolean attackerNearby = entity.getWorld()
                .getNearbyEntities(entity.getLocation(), current.shieldRange(), current.shieldRange(),
                        current.shieldRange())
                .stream()
                .anyMatch(Player.class::isInstance);

        if (shieldBlockRule.shouldRaiseShield(hasShield, current.blockingEnabled(), alreadyBlocking,
                attackerNearby)) {
            entity.startUsingItem(EquipmentSlot.OFF_HAND);
        }
    }

    /** Opening this module's screens, without the command or a listener knowing the menu classes. */
    private final class LiveScreens implements IMannequinScreensOpener {

        @Override
        public void list(Player viewer) {
            // null parent: this is an entry point from a command, and Core draws no Back button on
            // a parentless menu — right, since there is nowhere behind it to go back to.
            new MannequinListMenu(services, viewer, null).open();
        }

        @Override
        public void loadout(Player viewer, Mannequin mannequin) {
            new LoadoutScreen(services, viewer, mannequin, null).open();
        }

        @Override
        public void skin(Player viewer, Mannequin mannequin) {
            new SkinScreen(services, viewer, mannequin, null).open();
        }

        @Override
        public void stats(Player viewer, Mannequin mannequin) {
            new StatsScreen(services, viewer, mannequin, null).open();
        }
    }

    @Override
    public List<ModuleCommand> commands() {
        return MannequinCommands.declared();
    }

    @Override
    public void disable() {
        MannequinCommands.stopped();
        // Live entities are simply left in the world — a module stopping does not mean the training
        // room should vanish. The stored records are exactly what re-populates registry on the next
        // enable(), through MannequinStore#loadAll.
    }
}
