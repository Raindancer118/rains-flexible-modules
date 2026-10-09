package de.raindancer.modules.roles;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.social.economy.PriceModifiers;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.roles.listener.RoleListener;
import de.raindancer.modules.roles.service.RolePrices;
import de.raindancer.modules.roles.service.RoleService;
import de.raindancer.modules.roles.store.ChoiceBook;
import de.raindancer.modules.roles.store.RoleCatalogue;
import de.raindancer.modules.roles.util.PermissionNodes;

import java.util.List;

/**
 * Roles players pick for themselves. A role's perks reach the shop through Core's {@link PriceModifiers},
 * so this module neither needs the economy nor is needed by it.
 */
public final class RolesModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("roles", "Roles", "0.1.0")
            .describedAs("Pick a role with /role — a cook, a builder, an explorer… — and pay less in the shop for what it works with")
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
        RolesServices services = new RolesServices(context.plugin(), context.plugin().getServer(), context.core(),
                context.log(), context.core().messages(), context.chat().brand(), settings::current, service);

        RolePrices prices = new RolePrices(service, settings::current);
        PriceModifiers.provide(context.plugin(), prices);
        context.closeWith(() -> PriceModifiers.retract(prices));

        context.listener(new RoleListener(services));
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
