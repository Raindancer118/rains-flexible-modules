package de.raindancer.modules.roles;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.social.economy.PriceModifiers;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.core.ui.profile.ProfileExtensions;
import de.raindancer.modules.roles.listener.AbilityListener;
import de.raindancer.modules.roles.listener.RoleListener;
import de.raindancer.core.social.roles.HeldRole;
import de.raindancer.core.social.roles.PlayerRoles;
import de.raindancer.core.social.roles.RoleSource;
import de.raindancer.modules.roles.screen.RoleProfileButton;
import de.raindancer.modules.roles.service.RolePrices;
import de.raindancer.modules.roles.service.RoleService;
import de.raindancer.modules.roles.service.RolePurchase;
import de.raindancer.modules.roles.service.RoleShop;
import de.raindancer.modules.roles.store.OwnedBook;
import de.raindancer.core.platform.util.Scheduling;
import org.bukkit.entity.Player;
import de.raindancer.modules.roles.store.ChoiceBook;
import de.raindancer.modules.roles.store.RoleCatalogue;
import de.raindancer.modules.roles.util.PermissionNodes;

import java.util.List;

/**
 * Roles players pick for themselves. A role's perks reach the shop through Core's {@link PriceModifiers},
 * so this module neither needs the economy nor is needed by it.
 */
public final class RolesModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("roles", "Roles", "0.6.0")
            .describedAs("Pick a role with /role — a cook, a builder, an explorer… — pay less in the shop for what it works with, and do it a little better in the game")
            .by("Raindancer118");

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        SettingsStore<RolesSettings> settings = context.settings(RolesSettings.class, RolesSettings.DEFAULTS);
        context.core().messages().defineFrom(RolesModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);
        context.core().messages().seriousFrom(RolesModule.class.getResourceAsStream("messages-serious.yml"));
        int registered = PermissionNodes.register(context.plugin().getServer());
        if (registered > 0) {
            context.log().info("{} permission(s) registered.", registered);
        }

        RoleCatalogue catalogue = new RoleCatalogue(new YamlStore(context.dataFolder().resolve("roles.yml")),
                () -> RolesModule.class.getResourceAsStream("roles.yml"));
        int roles = catalogue.reload();
        catalogue.problems().forEach(problem -> context.log().warn("roles.yml: {}", problem));
        ChoiceBook choices = new ChoiceBook(new YamlStore(context.dataFolder().resolve("choices.yml")));
        choices.load();
        if (!choices.readable()) {
            context.log().error("choices.yml could not be read. Nobody can change role until it is fixed — "
                    + "saving now would replace everybody's choices.");
        }

        RoleService service = new RoleService(context.plugin().getServer(), catalogue, choices,
                context.core().messages(), System::currentTimeMillis, settings.current());
        settings.onChange(service::settings);
        OwnedBook owned = new OwnedBook(new YamlStore(context.dataFolder().resolve("owned.yml")));
        owned.load();
        if (!owned.readable()) {
            context.log().error("owned.yml could not be read. Nobody can buy or rent a role until it is fixed — "
                    + "saving now would replace everybody's purchases.");
        }
        RoleShop shop = new RoleShop(catalogue, owned, choices, System::currentTimeMillis, service::bypassing,
                () -> settings.current().sellRoles());
        service.access(shop);
        RolesServices services = new RolesServices(context.plugin(), context.plugin().getServer(), context.core(),
                context.log(), context.core().messages(), context.chat().brand(), settings::current, service, shop,
                new RolePurchase(shop, service, context.core().messages()));

        RolePrices prices = new RolePrices(service, settings::current);
        PriceModifiers.provide(context.plugin(), prices);
        context.closeWith(() -> PriceModifiers.retract(prices));

        context.listener(new RoleListener(services));
        AbilityListener abilities = new AbilityListener(services);
        context.listener(abilities);
        service.onChange(abilities::refresh);
        settings.onChange(changed -> context.plugin().getServer().getOnlinePlayers().forEach(abilities::refresh));
        context.closeWith(abilities::strip);
        // Ore a player put down brings no luck; Core remembers where ores are placed.
        Object ores = de.raindancer.core.world.blocks.PlacedBlocks.watch(context.plugin(),
                java.util.Arrays.stream(org.bukkit.Material.values())
                        .filter(material -> !material.isLegacy() && material.isBlock() && material.name().endsWith("_ORE"))
                        .toList());
        context.closeWith(() -> de.raindancer.core.world.blocks.PlacedBlocks.unwatch(ores));
        RoleSource source = player -> service.roleOf(player)
                .map(role -> new HeldRole(role.id(), role.title(), role.colour()));
        PlayerRoles.provide(context.plugin(), source);
        context.closeWith(() -> PlayerRoles.retract(source));
        // Rent runs on real time, so it is collected here as well as at join: a server nobody leaves would never see a join.
        // Speed and reach grow with the role, so they are put on again at the same time.
        var rentTimer = Scheduling.globalTimer(context.plugin(), 20L * 60, 20L * 60 * 5, task -> {
            for (Player online : context.plugin().getServer().getOnlinePlayers()) {
                Scheduling.entity(context.plugin(), online, () -> RoleListener.collectRent(services, online));
                abilities.refresh(online);
            }
        });
        if (rentTimer != null) {
            context.closeWith(rentTimer::cancel);
        }
        RoleProfileButton profile = new RoleProfileButton(() -> services);
        ProfileExtensions.register(profile);
        context.closeWith(() -> ProfileExtensions.unregister(profile));
        RolesCommands.ready(services);
        context.log().info("Roles are up: {} role(s), {} player(s) have one.", roles, choices.count());
    }

    @Override
    public List<ModuleCommand> commands() {
        return RolesCommands.declared();
    }

    @Override
    public void disable() {
        RolesCommands.stopped();
    }
}
