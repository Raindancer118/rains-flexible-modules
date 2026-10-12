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
import de.raindancer.modules.enchants.service.NightVisionService;
import de.raindancer.modules.enchants.store.AirStore;
import de.raindancer.modules.enchants.util.PermissionNodes;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Rain's own enchantments: Veinminer (the enchantment itself; vein mining stays Veinminer's, gated by
 * veintoggle), Night Vision, Flight and Double Jump — and the advancements for them.
 *
 * <p>Nothing here hands the enchantments out: no enchanting table, villager or loot entry. A boss drop
 * or a shop gives a book through {@code CustomEnchantments.book}.
 */
public final class EnchantsModule implements FlexModule {

    /** Night Vision is looked at every this-many ticks; an armour change is answered at once. */
    private static final int NIGHT_VISION_EVERY = 10;

    private static final ModuleInfo INFO = ModuleInfo.of("enchants", "Enchants", "0.1.0")
            .describedAs("Veinminer, Night Vision, Flight and Double Jump as real enchantments, with advancements")
            .by("Raindancer118");

    private AirService air;
    private NightVisionService nightVision;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public List<EnchantDef> enchantments() {
        return EnchantsDefinitions.all();
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
        nightVision = new NightVisionService(advancements);

        context.listener(new AirListener(air, nightVision));
        context.listener(new VeinAdvancementListener(advancements));
        EnchantsCommands.ready(new EnchantsServices(context.plugin().getServer(), context.core().messages()));

        int[] beat = {0};
        var timer = Scheduling.globalTimer(context.plugin(), 1, 1, task -> {
            boolean checkNightVision = beat[0]++ % NIGHT_VISION_EVERY == 0;
            for (Player player : Bukkit.getOnlinePlayers()) {
                Scheduling.entity(context.plugin(), player, () -> {
                    air.tick(player);
                    if (checkNightVision) {
                        nightVision.sync(player);
                    }
                });
            }
        });
        context.closeWith(timer::cancel);
        context.log().info("Enchants are up: {} enchantment(s) registered.", EnchantsDefinitions.all().size());
    }

    @Override
    public List<ModuleCommand> commands() {
        return EnchantsCommands.declared();
    }

    @Override
    public void disable() {
        if (air != null) {
            air.releaseAll();
        }
        if (nightVision != null) {
            Bukkit.getOnlinePlayers().forEach(nightVision::release);
        }
        EnchantsCommands.stopped();
    }
}
