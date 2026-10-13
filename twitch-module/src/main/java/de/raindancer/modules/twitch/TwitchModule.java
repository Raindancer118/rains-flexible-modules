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
import de.raindancer.modules.twitch.service.TwitchClient;
import de.raindancer.modules.twitch.service.TwitchPoller;
import de.raindancer.modules.twitch.util.PermissionNodes;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A banner in chat on every server of the network when a Twitch channel goes live, and another when the stream ends.
 *
 * <p>Only a server that has a Twitch login in {@code credentials.yml} asks Twitch; the rest of the network just listens
 * on the link. The login is an app login (client credentials): it reads public live status and can do nothing else.
 */
public final class TwitchModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("twitch", "Twitch", "0.1.2")
            .describedAs("A chat announcement on every server when the channel goes live, and when the stream ends.")
            .by("Raindancer118");

    private LogChannel log;
    private ModuleContext context;
    private SettingsStore<TwitchSettings> settings;
    private Announcer announcer;
    private volatile TwitchPoller poller;
    private ScheduledTask timer;

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

        TwitchServices services = new TwitchServices(context.plugin(), context.plugin().getServer(), log,
                context.core().messages(), context.core(), settings::current, announcer, () -> poller, this::startWatching);
        TwitchCommands.ready(services);
        startWatching();
        log.info("Twitch announcements are up for '{}'{}.", settings.current().channelClean(),
                poller == null ? " (listening only: no login in credentials.yml)" : "");
    }

    /** Reads the login and starts or stops the watch accordingly. */
    private synchronized void startWatching() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        poller = null;
        Path file = context.dataFolder().resolve("credentials.yml");
        YamlConfiguration login = readLogin(file);
        String id = login.getString("client-id", "").strip();
        String secret = login.getString("client-secret", "").strip();
        if (id.isEmpty() || secret.isEmpty()) {
            return;
        }
        YamlStore memory = new YamlStore(context.dataFolder().resolve("announced.yml"));
        poller = new TwitchPoller(new TwitchClient(id, secret),
                new LiveTrackerRule(TwitchPoller.remembered(memory)), announcer, settings::current, memory, log);
        TwitchPoller watching = poller;
        int every = settings.current().pollSecondsClamped();
        timer = Scheduling.asyncTimer(context.plugin(), 5, every, task -> {
            try {
                watching.poll();
            } catch (RuntimeException failed) {
                log.warn("A look at Twitch failed: {}", failed.getMessage());
            }
        });
    }

    private YamlConfiguration readLogin(Path file) {
        if (!Files.exists(file)) {
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, "# The Twitch app login for the watch: https://dev.twitch.tv/console/apps\n"
                        + "# An app login only reads public data. Leave empty on servers that should only listen.\n"
                        + "# Not synced to other servers and not shown in menus; keep this file private.\n"
                        + "client-id: ''\nclient-secret: ''\n");
            } catch (java.io.IOException unwritable) {
                log.warn("credentials.yml could not be created: {}", unwritable.getMessage());
            }
        }
        return YamlConfiguration.loadConfiguration(file.toFile());
    }

    @Override
    public List<ModuleCommand> commands() {
        return TwitchCommands.declared();
    }

    @Override
    public void disable() {
        if (timer != null) {
            timer.cancel();
            timer = null;
        }
        TwitchCommands.stopped();
    }
}
