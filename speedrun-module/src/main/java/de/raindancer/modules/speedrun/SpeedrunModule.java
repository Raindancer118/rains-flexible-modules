package de.raindancer.modules.speedrun;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;

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

    private static final ModuleInfo INFO = ModuleInfo.of("speedrun", "Speedrun", "1.0.0")
            .describedAs("A speedrun lobby: pick an advancement goal or a death policy from the "
                    + "compass's menu, then press the green block to race. A countdown freezes "
                    + "everyone first, and the lobby world resets once the last racer has left.")
            .by("Raindancer118");

    private SpeedrunLobby lobby;

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

        SettingsStore<SpeedrunSettings> settings = context.settings(SpeedrunSettings.class,
                SpeedrunSettings.DEFAULTS);
        lobby = new SpeedrunLobby(context.plugin(), settings, context.core().bossBars(),
                context.core().effects(), context.core().messages(), context.core().actionBars(),
                context.core().players());
        context.listener(new SpeedrunLobbyListener(lobby, new SpeedrunLobbyItems(context.plugin()),
                context.chat().brand(), context.core().messages()));

        context.log().info("Speedrun lobby is up: {}.",
                lobby.state().name().toLowerCase(Locale.ROOT));
    }

    @Override
    public void disable() {
        // Nothing to flush: the configuration is already on disk through its own settings store, and
        // a run in progress does not survive a restart either way — see SpeedrunLobby's own class
        // javadoc for why that is unchanged, deliberate scope rather than an oversight.
        //
        // The listener is unregistered by the context, in the reverse order it was registered.
    }

    /** The lobby on this server, for a host that wants to show its state. */
    public SpeedrunLobby lobby() {
        return lobby;
    }
}
