package de.raindancer.modules.api;

import de.raindancer.core.RainsCore;
import de.raindancer.core.data.settings.SettingsSchema;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.Log;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.chat.Chat;
import org.bukkit.Bukkit;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * A module's context on a real server, and the way to take it back.
 *
 * <p>The data folder is created here rather than lazily. A module that has to check whether its own folder
 * exists before every write has that check in five places and forgets it in the sixth; and if the folder
 * cannot be made at all, failing now — before the module has registered anything — is a clean failure
 * with an obvious cause, which is much better than a write failing an hour into the session.
 */
public final class LiveModuleSession implements ModuleSession {

    private final ModuleHost host;
    private final FlexModule module;
    private final Unwind unwind = new Unwind();
    private final Path dataFolder;
    private final LogChannel log;
    private final Live context = new Live();

    public LiveModuleSession(ModuleHost host, FlexModule module) {
        this.host = Objects.requireNonNull(host, "a session needs a host");
        this.module = Objects.requireNonNull(module, "a session needs a module");
        this.log = Log.of(module.info().id());
        this.dataFolder = host.layout().folderFor(module.info());
        try {
            Files.createDirectories(dataFolder);
        } catch (IOException cannot) {
            throw new UncheckedIOException(
                    "could not create the data folder for module '" + module.info().id() + "' at "
                            + dataFolder, cannot);
        }
    }

    @Override
    public ModuleContext context() {
        return context;
    }

    @Override
    public void unwind() {
        for (Throwable trouble : unwind.close()) {
            log.warn(trouble, "something a module registered could not be released");
        }
    }

    /**
     * Deliberately an inner class rather than {@code LiveModuleSession} implementing both interfaces: a
     * module holding its context must not be able to cast it to something with {@code unwind()} on it and
     * pull the rug out from under the host.
     */
    private final class Live implements ModuleContext {

        @Override
        public ModuleInfo info() {
            return module.info();
        }

        @Override
        public Plugin plugin() {
            return host.plugin();
        }

        @Override
        public RainsCore core() {
            return RainsCore.get();
        }

        @Override
        public Path dataFolder() {
            return dataFolder;
        }

        @Override
        public LogChannel log() {
            return log;
        }

        @Override
        public Chat chat() {
            return core().chatFor(module.info().name());
        }

        @Override
        public boolean isStandalone() {
            return host.isStandalone();
        }

        @Override
        public String hostName() {
            return host.name();
        }

        @Override
        public void listener(Listener listener) {
            Objects.requireNonNull(listener, "there is no point registering a null listener");
            // Before registering, not after. registerEvents walks the listener's methods and registers
            // them one at a time, so a listener with a bad handler signature can have three of its five
            // handlers attached and then throw — leaving a failed module still receiving events, with
            // nothing recorded to undo. Unregistering something that was never registered is a no-op,
            // so being early costs nothing and being late costs the isolation guarantee.
            unwind.add(() -> HandlerList.unregisterAll(listener));
            Bukkit.getPluginManager().registerEvents(listener, host.plugin());
        }

        @Override
        public void closeWith(AutoCloseable resource) {
            unwind.add(resource);
        }

        @Override
        public <T> SettingsStore<T> settings(Class<T> type, T defaults) {
            SettingsStore<T> store = core().settingsFor(SettingsSchema.of(type, defaults),
                    host.layout().configFor(module.info()));
            // Undone the same way a listener is. enable() can still throw after this line, and a module
            // that never finished starting must not leave its settings looking like a running feature in
            // Core's menu — see RainsCore.forgetSettings. Disabling later removes it for the same reason:
            // a module that has stopped is not one whose config a player should be able to open.
            unwind.add(() -> core().forgetSettings(store));
            return store;
        }

        @Override
        public Optional<FlexModule> module(String id) {
            return host.registry().enabled(id);
        }
    }
}
