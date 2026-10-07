package de.raindancer.modules.speedrun;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.world.safety.Spot;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.speedrun.manhunt.ManhuntGame;
import de.raindancer.modules.speedrun.manhunt.ManhuntMigration;
import de.raindancer.modules.speedrun.util.PermissionNodes;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.Locale;

/**
 * "RainsSpeedrun", as a module.
 *
 * <h2>Why this used to live in RainsCore, and why it does not any more</h2>
 * The whole feature — engine and lobby together — was briefly built into RainsCore itself. A join
 * handler in it cleared a player's inventory on every single join, anywhere on the server, whenever
 * the lobby happened to be {@code READY} — which is true almost all the time — because it checked
 * only the feature's state and never the player's location. A player lost real gear to that. Nothing
 * that acts on its own like this belongs in the one plugin every Rain's install carries; it belongs
 * here, in a module a server owner has to choose to install.
 */
public final class SpeedrunModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("speedrun", "Speedrun", "1.29.2")
            .describedAs("A speedrun lobby: pick a game, an advancement goal and a death policy "
                    + "from the compass's menu, then press the green block to race. A countdown "
                    + "freezes everyone first, and the lobby world resets once the last racer has "
                    + "left. Another module can add a game of its own to this same lobby — see "
                    + "SpeedrunMode.")
            .by("Raindancer118");

    private SpeedrunLobby lobby;
    private ManhuntGame manhunt;
    private SpeedrunHistory history;
    private volatile io.papermc.paper.threadedregions.scheduler.ScheduledTask moving;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        // The module's own wording, offered as a floor below anything the owner has written — see
        // ChainedModule's own note on why this is defineFrom rather than Messages.load: there is one
        // Messages on the server and it is Core's, so loading would throw away everybody else's lines.
        context.core().messages().defineFrom(
                SpeedrunModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);

        // Before anything reads a file: Manhunt's data from the plugin it used to be, carried over once.
        ManhuntMigration.run(context.dataFolder());

        SettingsStore<SpeedrunSettings> settings = context.settings(SpeedrunSettings.class,
                SpeedrunSettings.DEFAULTS);
        lobby = new SpeedrunLobby(context.plugin(), settings, context.core().bossBars(),
                context.core().effects(), context.core().messages(), context.core().actionBars(),
                context.core().players());
        // Main thread only, same as everything else here in enable() — creating a world is a
        // main-thread operation in Paper, and nobody is on yet for it to visibly stall.
        lobby.ensureWorldExists();
        // A latecomer is put on solid ground near the start line: Core's safety search, which brings
        // the ground in off the main thread rather than generating it under everybody.
        lobby.placeLatecomersWith(around -> context.core().safety()
                .findSafe(new Spot(around.getWorld().getName(), around.getBlockX(), around.getBlockY(),
                        around.getBlockZ()), 8)
                .thenApply(found -> found.map(spot -> new Location(around.getWorld(), spot.x() + 0.5, spot.y(),
                        spot.z() + 0.5, around.getYaw(), around.getPitch())).orElse(null)));
        // History, the HUD and the chat buttons: the lobby works without them (a test builds one
        // bare), so they are handed in rather than built inside it.
        Executor disk = task -> Scheduling.async(context.plugin(), task);
        // Every run in Core's run history; the standings of games with sides beside it. An old
        // history.yml is shown at once and moved in once Core has read its own — see
        // SpeedrunHistoryMigration.
        SpeedrunHistory history = new SpeedrunHistory(context.core().runHistory(SpeedrunHistory.GAME),
                new YamlStore(context.dataFolder().resolve("standings.yml")), disk);
        SpeedrunHistoryMigration.show(context.dataFolder(), history);
        SpeedrunPlayerPrefs prefs = new SpeedrunPlayerPrefs(new YamlStore(context.dataFolder().resolve("players.yml")), disk);
        prefs.load();
        SpeedrunHud hud = new SpeedrunHud(context.core().scoreboards(), context.core().bossBars(), prefs,
                lobby::config, SpeedrunTimerDisplay.viaScheduling(context.plugin()));
        lobby.equip(new SpeedrunToolkit(context.plugin(), context.chat().brand(), context.chat(),
                context.core().messages(), context.core().settingsNavigation(), context.core().prompts(),
                context.core().buttons(), context.core().effects(), history, prefs, hud));
        this.history = history;
        moveOldHistory(context, history);
        // Core's copy of "does this run rank" follows rank-edited-runs as it changes.
        settings.onChange(fresh -> Scheduling.async(context.plugin(), history::rerank));
        SpeedrunLobbyItems lobbyItems = new SpeedrunLobbyItems(context.plugin());
        lobby.takeLobbyItemsWith(id -> {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                Scheduling.entity(context.plugin(), player,
                        () -> lobbyItems.take(player));
            }
        });
        SpeedrunLobbyListener listener = new SpeedrunLobbyListener(context.plugin(), lobby,
                lobbyItems, context.chat().brand(), context.core().messages());
        context.listener(listener);
        // Portal travel out of a runtime-made world falls back to the server's own dimensions, which
        // is how a racer walked out of a nether portal into the server's overworld mid-run.
        context.listener(new SpeedrunPortalListener(lobby));
        // The other way out of the run's worlds: a death in its nether or end put the racer back at
        // the server's own spawn, because a runtime-made world is never the primary level. See
        // SpeedrunRespawnListener.
        context.listener(new SpeedrunRespawnListener(lobby));
        // Nobody can be hurt, and nothing explodes, in a lobby that is not racing yet — for the life
        // of the module rather than of a session, because the whole point of it is the gap between
        // two sessions. See SpeedrunLobbySafetyListener.
        context.listener(new SpeedrunLobbySafetyListener(lobby));
        // What a racer is allowed to set off during the run itself, per dimension — a ruleset, not a
        // safety rail, and off nobody's by default. See SpeedrunExplosivesListener.
        context.listener(new SpeedrunExplosivesListener(lobby, context.core().messages()));
        // Whoever stayed in the lobby world while a run finished and it reset around them gets the
        // items the moment there is something to do with them again, rather than needing to leave and
        // come back — neither onJoin nor onWorldChange fires for somebody who never actually moved.
        lobby.onReady(listener::giveItemsToEveryoneInLobby);

        // Before anything asks. An unregistered permission resolves to "operators only", which would
        // refuse /lemmemove and /speedrunspectate — both meant for anybody racing — to ordinary players.
        int registered = PermissionNodes.register(context.plugin().getServer());
        if (registered > 0) {
            context.log().info("{} permission(s) registered.", registered);
        }
        // The commands were registered during bootstrap, long before any of this existed, and have
        // been answering "not running yet" until now. See SpeedrunCommands.
        SpeedrunCommands.ready(new SpeedrunAdminServices(lobby, context.core().messages()));
        SpeedrunControl.ready(lobby);

        // Manhunt, the game built in: after the lobby, because it is offered to it.
        manhunt = new ManhuntGame();
        manhunt.enable(context, lobby);

        context.log().info("Speedrun lobby is up: {}.",
                lobby.state().name().toLowerCase(Locale.ROOT));
    }

    /**
     * Tries the move of an old history.yml once a second, off the server's threads, until Core's
     * history is loaded and it is done — or there turns out to be nothing to move.
     */
    private void moveOldHistory(ModuleContext context, SpeedrunHistory history) {
        moving = Scheduling.asyncTimer(context.plugin(), 1L, 1L, task -> {
            SpeedrunHistoryMigration.Result result = SpeedrunHistoryMigration.migrate(context.dataFolder(), history);
            if (result != SpeedrunHistoryMigration.Result.WAITING) {
                task.cancel();
                context.log().info("{} past run(s) in the speedrun history.", history.size());
            }
        });
    }

    @Override
    public void disable() {
        if (moving != null) {
            moving.cancel();
            moving = null;
        }
        if (manhunt != null) {
            // First: the game is withdrawn while the lobby it was offered to is still there.
            manhunt.disable();
            manhunt = null;
        }
        if (lobby != null) {
            lobby.shutdown();
        }
        SpeedrunCommands.stopped();
        SpeedrunControl.stopped();
        // Whatever game mode a module offered goes with this module, not with theirs: on a reload
        // this one may come back before they do, and a shelf that survived the unload would hand the
        // next start to a plugin that has not been rebuilt yet.
        SpeedrunModes.clear();
        if (history != null) {
            // Whatever Core has not written yet — its database is still open while modules stop.
            history.flush();
            history = null;
        }
        // Nothing else to flush: the configuration is already on disk through its own settings store, and
        // a run in progress does not survive a restart either way — see SpeedrunLobby's own class
        // javadoc. shutdown() above only clears what Core would otherwise keep showing.
        //
        // The listener is unregistered by the context, in the reverse order it was registered.
    }

    @Override
    public List<ModuleCommand> commands() {
        List<ModuleCommand> all = new ArrayList<>(SpeedrunCommands.declared());
        all.addAll(ManhuntGame.commands());
        return all;
    }

    /** The lobby on this server, for a host that wants to show its state. */
    public SpeedrunLobby lobby() {
        return lobby;
    }
}
