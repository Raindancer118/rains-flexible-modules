package de.raindancer.modules.enchants;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.core.content.enchant.EnchantDef;
import de.raindancer.modules.enchants.listener.AirListener;
import de.raindancer.modules.enchants.listener.VeinAdvancementListener;
import de.raindancer.modules.enchants.rules.AirRule;
import de.raindancer.modules.enchants.service.AirService;
import de.raindancer.modules.enchants.listener.ElytraListener;
import de.raindancer.modules.enchants.listener.ShardListener;
import de.raindancer.modules.enchants.listener.TabListener;
import de.raindancer.modules.enchants.service.ShardDropService;
import de.raindancer.modules.enchants.service.ShardMobService;
import de.raindancer.modules.enchants.service.ShardPortalService;
import de.raindancer.modules.enchants.service.ShardWorldService;
import de.raindancer.modules.enchants.listener.ToolListener;
import de.raindancer.modules.enchants.service.ChestLinkService;
import de.raindancer.modules.enchants.service.ElytraService;
import de.raindancer.modules.enchants.service.ToolService;
import de.raindancer.modules.enchants.service.WornEffectService;
import de.raindancer.modules.enchants.store.ElytraStore;
import de.raindancer.modules.enchants.store.AirStore;
import de.raindancer.modules.enchants.util.PermissionNodes;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Rain's own enchantments — Veinminer (the enchantment itself; vein mining stays Veinminer's, gated by
 * veintoggle), Night Vision, Flight, Double Jump, Boost, Launch, Swiftness, Haste, Soulbound, Wisdom,
 * Excavator, Harvester, Smelting, Telekinesis and Chest Link — and the advancements for them. Auto Sell is
 * registered here too and enforced by the economy, which knows the prices.
 *
 * <p>Nothing here hands the enchantments out: no enchanting table, villager or loot entry. A boss drop
 * or a shop gives a book through {@code CustomEnchantments.book}.
 */
public final class EnchantsModule implements FlexModule {

    /** Worn effects are looked at every this-many ticks; an armour change is answered at once. */
    private static final int NIGHT_VISION_EVERY = 10;
    /** Ticks between two puffs of the shard world's haze. */
    private static final int HAZE_EVERY = 10;

    /** Chest Link drops are gathered this many ticks before they are sent. */
    private static final int LINK_EVERY = 20;

    private static final ModuleInfo INFO = ModuleInfo.of("enchants", "Enchants", "0.12.1")
            .describedAs("Sixteen real enchantments, their shards and the shard world, with advancements")
            .by("Raindancer118");

    private AirService air;
    private WornEffectService wornEffects;
    private ChestLinkService links;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public List<EnchantDef> enchantments() {
        return EnchantsDefinitions.all();
    }

    /** The shard world's purple biomes: a datapack, which has to be found before the server loads its worlds. */
    @Override
    public void bootstrap(io.papermc.paper.plugin.bootstrap.BootstrapContext context) {
        try {
            de.raindancer.modules.enchants.service.ShardBiomePack.register(context);
        } catch (java.io.IOException failed) {
            context.getLogger().error("The shard world's purple biomes could not be written; it keeps the game's colours.",
                    failed);
        }
    }

    @Override
    public void enable(ModuleContext context) {
        SettingsStore<EnchantsSettings> settings = context.settings(EnchantsSettings.class, EnchantsSettings.DEFAULTS);
        context.core().messages().defineFrom(EnchantsModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);
        context.core().messages().seriousFrom(EnchantsModule.class.getResourceAsStream("messages-serious.yml"));
        PermissionNodes.register(context.plugin().getServer());

        EnchantAdvancements advancements = new EnchantAdvancements(context.core().advancements());
        advancements.define();
        air = new AirService(new AirRule(), new AirStore(), settings::current, advancements,
                context.core().effects(), context.core().messages());
        context.closeWith(air.follow(context.core()));
        wornEffects = new WornEffectService(advancements);
        ElytraService elytra = new ElytraService(new ElytraStore(), settings::current, advancements,
                context.core().effects(), context.core().messages());
        ToolService tools = new ToolService(context.plugin(), settings::current, advancements,
                context.core().messages());
        tools.learnRecipes();
        // Data packs may add furnace recipes after the plugins are enabled.
        Scheduling.globalLater(context.plugin(), 1, tools::learnRecipes);
        links = new ChestLinkService(context.plugin(), context.core().land(), advancements, context.core().messages(),
                context.core().effects());

        context.listener(new AirListener(air, wornEffects));
        context.listener(new VeinAdvancementListener(advancements));
        context.listener(new ElytraListener(elytra));
        context.listener(new de.raindancer.modules.enchants.listener.HeatSeekingListener(
                new de.raindancer.modules.enchants.service.HeatSeekingService(context.plugin(), context.core().combat(),
                        context.core().messages(), context.core().effects(), advancements)));
        context.listener(new ToolListener(tools, links));
        context.listener(new TabListener(advancements));
        ShardWorldService shardWorlds = new ShardWorldService(settings::current, context.log());
        shardWorlds.ensure();
        context.listener(new de.raindancer.modules.enchants.listener.ThorListener(
                new de.raindancer.modules.enchants.service.ThorService(context.plugin(), advancements), shardWorlds));
        ShardPortalService portals = new ShardPortalService(context.plugin(), context.core().land(), shardWorlds, settings::current,
                advancements, context.core().messages(), context.core().effects());
        ShardMobService shardMobs = new ShardMobService(context.plugin(), shardWorlds);
        context.listener(new de.raindancer.modules.enchants.listener.CrystalWitherListener(context.plugin(), shardWorlds,
                shardMobs, context.core().land()));
        de.raindancer.modules.enchants.listener.ShardBiomeListener purple =
                new de.raindancer.modules.enchants.listener.ShardBiomeListener(shardWorlds);
        context.listener(purple);
        // The chunks loaded before this listener was: the spawn's, a forced one.
        shardWorlds.world().ifPresent(world -> {
            for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
                purple.convert(chunk);
            }
        });
        context.listener(new ShardListener(shardWorlds, portals,
                new ShardDropService(shardWorlds, settings::current, advancements, context.core().messages()),
                shardMobs));
        EnchantsCommands.ready(new EnchantsServices(context.plugin().getServer(), context.core().messages(), shardMobs));

        int[] beat = {0};
        java.util.random.RandomGenerator hazeDice = new java.util.SplittableRandom();
        var timer = Scheduling.globalTimer(context.plugin(), 1, 1, task -> {
            int now = beat[0]++;
            boolean checkNightVision = now % NIGHT_VISION_EVERY == 0;
            if (now % LINK_EVERY == 0) {
                links.flush();
            }
            for (Player player : Bukkit.getOnlinePlayers()) {
                Scheduling.entity(context.plugin(), player, () -> {
                    air.tick(player);
                    elytra.tick(player);
                    if (checkNightVision) {
                        wornEffects.sync(player);
                    }
                    if (now % HAZE_EVERY == 0) {
                        shardWorlds.haze(player, hazeDice);
                    }
                });
            }
        });
        context.closeWith(timer::cancel);
        // The shard world's weather, rolled every five minutes (the world's own clock stands still at midnight).
        java.util.random.RandomGenerator weatherDice = new java.util.SplittableRandom();
        var weather = Scheduling.globalTimer(context.plugin(), 20L, 6_000L,
                task -> shardWorlds.world().ifPresent(world -> shardWorlds.rollWeather(world, weatherDice)));
        if (weather != null) {
            context.closeWith(weather::cancel);
        }
        context.log().info("Enchants are up: {} enchantment(s) registered.", EnchantsDefinitions.all().size());
    }

    @Override
    public List<ModuleCommand> commands() {
        return EnchantsCommands.declared();
    }

    @Override
    public void disable() {
        if (links != null) {
            links.flush();
        }
        if (air != null) {
            air.releaseAll();
        }
        if (wornEffects != null) {
            Bukkit.getOnlinePlayers().forEach(wornEffects::release);
        }
        EnchantsCommands.stopped();
    }
}
