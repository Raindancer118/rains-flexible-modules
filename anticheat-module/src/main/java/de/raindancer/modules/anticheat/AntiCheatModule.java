package de.raindancer.modules.anticheat;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.anticheat.listener.CombatListener;
import de.raindancer.modules.anticheat.listener.ConnectionListener;
import de.raindancer.modules.anticheat.listener.IAntiCheatListener;
import de.raindancer.modules.anticheat.listener.InventoryListener;
import de.raindancer.modules.anticheat.listener.MovementListener;
import de.raindancer.modules.anticheat.listener.WorldListener;
import de.raindancer.modules.anticheat.rules.ActionRule;
import de.raindancer.modules.anticheat.service.AlertService;
import de.raindancer.modules.anticheat.service.ClickService;
import de.raindancer.modules.anticheat.service.CombatService;
import de.raindancer.modules.anticheat.service.EspShield;
import de.raindancer.modules.anticheat.service.IAntiCheatService;
import de.raindancer.modules.anticheat.service.MovementEngine;
import de.raindancer.modules.anticheat.service.PacketTap;
import de.raindancer.modules.anticheat.service.PunishService;
import de.raindancer.modules.anticheat.service.Tracks;
import de.raindancer.modules.anticheat.service.ViolationService;
import de.raindancer.modules.anticheat.service.WorldService;
import de.raindancer.modules.anticheat.store.EvidenceLog;
import de.raindancer.modules.anticheat.util.PermissionNodes;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * Server-side cheat detection. Movement is judged once a tick on each player's own thread against
 * vanilla physics; attacks, digging and placing as they happen; the client's packets, where the tap
 * fits the server, on the network thread without ever touching the world.
 */
public final class AntiCheatModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("anticheat", "Anti-Cheat", "0.6.0")
            .describedAs("Server-side anti-cheat: movement, combat, world, inventory and packet checks")
            .by("Raindancer118");

    private PacketTap tap;
    private EvidenceLog evidence;
    private de.raindancer.modules.anticheat.store.ReplayStore replays;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        SettingsStore<AntiCheatSettings> settings = context.settings(AntiCheatSettings.class, AntiCheatSettings.DEFAULTS);
        var messages = context.core().messages();
        messages.defineFrom(AntiCheatModule.class.getResourceAsStream("messages.yml"), context.chat().brand()::chatPrefix);
        messages.seriousFrom(AntiCheatModule.class.getResourceAsStream("messages-serious.yml"));
        int registered = PermissionNodes.register(context.plugin().getServer());
        if (registered > 0) {
            context.log().info("{} permission(s) registered.", registered);
        }

        Tracks tracks = new Tracks(System::currentTimeMillis);
        AlertService alerts = new AlertService(context.plugin().getServer(), messages, context.log(), System::currentTimeMillis);
        evidence = new EvidenceLog(context.dataFolder(), settings.current().evidencePerPlayer());
        evidence.load();
        replays = new de.raindancer.modules.anticheat.store.ReplayStore(context.dataFolder(),
                new de.raindancer.modules.anticheat.model.Replays(5));
        replays.load();
        PunishService punishments = new PunishService(context.plugin(), context.core(), messages, alerts);
        ViolationService violations = new ViolationService(new ActionRule(), alerts, punishments, evidence);
        violations.replaysTo(replays);
        MovementEngine engine = new MovementEngine(context.plugin(), context.log(), tracks, violations);
        CombatService combat = new CombatService(tracks, violations);
        WorldService world = new WorldService(tracks, violations);
        ClickService clicks = new ClickService();
        tap = new PacketTap(tracks, clicks, context.log());
        engine.probesWith(tap);
        EspShield shield = new EspShield(context.plugin(), context.log(),
                id -> context.core().vanish() != null && context.core().vanish().isVanished(id));
        shield.tapWith(tap);
        tap.shieldWith(shield);

        List<IAntiCheatService> all = List.of(tracks, alerts, punishments, violations, engine, combat, world, clicks, tap, shield);
        all.forEach(service -> service.settings(settings.current()));
        settings.onChange(fresh -> all.forEach(service -> service.settings(fresh)));

        AntiCheatServices services = new AntiCheatServices(context.plugin(), context.plugin().getServer(), context.core(),
                context.log(), messages, context.chat(), settings::current, settings, tracks, violations, alerts, punishments,
                evidence, replays, engine, combat, world, clicks, tap, shield);

        List<IAntiCheatListener> listeners = new ArrayList<>();
        listeners.add(new MovementListener(services));
        listeners.add(new CombatListener(services));
        listeners.add(new WorldListener(services));
        listeners.add(new InventoryListener(services));
        listeners.add(new ConnectionListener(services, listeners));
        listeners.forEach(context::listener);

        // Players already online when the module starts (a reload) get what joining would have given them.
        for (Player online : context.plugin().getServer().getOnlinePlayers()) {
            Scheduling.entity(context.plugin(), online, () -> {
                tracks.of(online).entityId = online.getEntityId();
                alerts.joined(online);
                tap.inject(online);
                engine.start(online);
            });
        }

        var writing = Scheduling.asyncTimer(context.plugin(), 60, 60, task -> {
            evidence.flush();
            replays.flush();
        });
        context.closeWith(writing::cancel);
        context.closeWith(evidence::flush);
        context.closeWith(replays::flush);
        context.closeWith(() -> context.plugin().getServer().getOnlinePlayers().forEach(tap::eject));

        shield.start();
        context.closeWith(shield::stop);
        AntiCheatCommands.ready(services);
        context.log().info("Anti-cheat is up: {} checks, packet tap {}.", de.raindancer.modules.anticheat.model.CheckType.values().length,
                settings.current().packetTap() ? "on" : "off");
    }

    @Override
    public List<ModuleCommand> commands() {
        return AntiCheatCommands.declared();
    }

    @Override
    public void disable() {
        AntiCheatCommands.stopped();
    }
}
