package de.raindancer.modules.twitch;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.twitch.rules.LiveTrackerRule;
import de.raindancer.modules.twitch.service.Announcer;
import de.raindancer.modules.twitch.listener.TwitchWatchers;
import de.raindancer.modules.twitch.service.TwitchAdvancements;
import de.raindancer.modules.twitch.service.TwitchClient;
import de.raindancer.modules.twitch.service.TwitchLogin;
import de.raindancer.modules.twitch.service.TwitchLoginStore;
import de.raindancer.modules.twitch.service.TwitchPoller;
import de.raindancer.modules.twitch.service.WatchRole;
import de.raindancer.modules.twitch.util.PermissionNodes;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

import java.util.List;

/**
 * A banner in chat on every server of the network when a Twitch channel goes live, and another when the stream ends.
 *
 * <p>On a network exactly one server asks Twitch — whichever holds the {@code twitch} role, and another takes over when
 * it stops — and tells the rest over the network bus. What was announced is a document of the network; the login (an app login: it reads public live status and can
 * do nothing else) is a secret of the network, never a document.
 */
public final class TwitchModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("twitch", "Twitch", "0.4.0")
            .describedAs("A chat announcement on every server when the channel goes live and when it ends, and advancements for watching.")
            .by("Raindancer118");

    private LogChannel log;
    private ModuleContext context;
    private SettingsStore<TwitchSettings> settings;
    private Announcer announcer;
    private TwitchLoginStore logins;
    private YamlStore loginDocument;
    private YamlStore memory;
    private WatchRole role;
    private volatile TwitchLogin login = TwitchLogin.NONE;
    private volatile TwitchPoller poller;
    private ScheduledTask roleTimer;
    private ScheduledTask pollTimer;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        this.context = context;
        log = context.log();
        settings = context.settings(TwitchSettings.class, TwitchSettings.DEFAULTS);
        context.core().messages().defineFrom(TwitchModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);
        context.core().messages().seriousFrom(TwitchModule.class.getResourceAsStream("messages-serious.yml"));
        PermissionNodes.register(context.plugin().getServer());

        announcer = new Announcer(context.plugin(), context.plugin().getServer(), log, context.core().messages(),
                context.core(), settings::current);
        announcer.listen();
        context.closeWith(announcer::stop);
        TwitchAdvancements watching = new TwitchAdvancements(context.plugin(), context.plugin().getServer(),
                context.core().advancements(), settings::current);
        watching.define();
        announcer.onHeard(watching::heard);
        context.listener(new TwitchWatchers(watching));
        var minutes = Scheduling.globalTimer(context.plugin(), 1200, 1200, task -> watching.minute());
        if (minutes != null) {
            context.closeWith(minutes::cancel);
        }

        loginDocument = YamlStore.network(context.dataFolder().resolve("credentials.yml"));
        logins = new TwitchLoginStore(context.core().secrets(), loginDocument, System::getenv, log);
        memory = YamlStore.network(context.dataFolder().resolve("announced.yml"));
        role = new WatchRole(context.core().networkRoles().orElse(null),
                () -> login.present() && settings.current().enabled(), this::beginWatching, this::endWatching);
        context.closeWith(loginDocument.onChange(() -> Scheduling.async(context.plugin(), this::reloadLogin)));

        TwitchServices services = new TwitchServices(context.plugin(), context.plugin().getServer(), log,
                context.core().messages(), context.core(), settings::current, announcer, () -> poller, role,
                this::reloadLogin, logins);
        TwitchCommands.ready(services);
        logins.ensureTemplate();
        reloadLogin();
        roleTimer = Scheduling.asyncTimer(context.plugin(), 1, 5, task -> {
            try {
                role.tick();
            } catch (RuntimeException failed) {
                log.warn("The Twitch watch could not be checked: {}", failed.getMessage());
            }
        });
        log.info("Twitch announcements are up for '{}'{}.", settings.current().channelClean(),
                login.present() ? "" : " (listening only: no login: see /twitch login)");
    }

    /** Reads the login again and lets the watch follow it. */
    private void reloadLogin() {
        login = logins.read();
        role.refresh();
    }

    /** This server became the one that asks Twitch (or its login changed): a fresh poller on what was announced so far. */
    private synchronized void beginWatching() {
        stopTimer();
        TwitchLogin using = login;
        poller = new TwitchPoller(new TwitchClient(using.clientId(), using.clientSecret()),
                new LiveTrackerRule(TwitchPoller.remembered(memory)), announcer, settings::current, memory, log);
        TwitchPoller watching = poller;
        pollTimer = Scheduling.asyncTimer(context.plugin(), 5, settings.current().pollSecondsClamped(), task -> {
            try {
                watching.poll();
            } catch (RuntimeException failed) {
                log.warn("A look at Twitch failed: {}", failed.getMessage());
            }
        });
        log.info("This server asks Twitch for '{}' now.", settings.current().channelClean());
    }

    private synchronized void endWatching() {
        stopTimer();
        poller = null;
    }

    private void stopTimer() {
        if (pollTimer != null) {
            pollTimer.cancel();
            pollTimer = null;
        }
    }

    @Override
    public List<ModuleCommand> commands() {
        return TwitchCommands.declared();
    }

    @Override
    public void disable() {
        if (roleTimer != null) {
            roleTimer.cancel();
            roleTimer = null;
        }
        if (role != null) {
            role.stop();
        }
        TwitchCommands.stopped();
    }
}
