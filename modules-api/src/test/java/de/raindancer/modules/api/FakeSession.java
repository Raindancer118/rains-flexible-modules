package de.raindancer.modules.api;

import de.raindancer.core.RainsCore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.chat.Chat;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * A context and its teardown, without a server behind either.
 *
 * <p>Everything a real context reaches for — the plugin, RainsCore, the logger — answers by throwing,
 * deliberately. A registry test that accidentally starts using one of those would then fail loudly
 * here rather than quietly needing a server, which is the failure mode that turns a fast test suite
 * into one nobody runs.
 */
final class FakeSession implements ModuleSession, ModuleContext {

    private final String moduleId;
    private final List<String> journal;
    int unwinds;

    FakeSession(String moduleId, List<String> journal) {
        this.moduleId = moduleId;
        this.journal = journal;
    }

    @Override
    public ModuleContext context() {
        return this;
    }

    @Override
    public void unwind() {
        unwinds++;
        journal.add("unwind:" + moduleId);
    }

    @Override
    public ModuleInfo info() {
        return ModuleInfo.of(moduleId, moduleId, "1.0.0");
    }

    @Override
    public Plugin plugin() {
        throw new UnsupportedOperationException("no server in a unit test");
    }

    @Override
    public RainsCore core() {
        throw new UnsupportedOperationException("no server in a unit test");
    }

    @Override
    public Path dataFolder() {
        return Path.of("target", "fake", moduleId);
    }

    @Override
    public LogChannel log() {
        throw new UnsupportedOperationException("no server in a unit test");
    }

    @Override
    public Chat chat() {
        throw new UnsupportedOperationException("no server in a unit test");
    }

    @Override
    public boolean isStandalone() {
        return false;
    }

    @Override
    public String hostName() {
        return "FakeHost";
    }

    @Override
    public void listener(Listener listener) {
        throw new UnsupportedOperationException("no server in a unit test");
    }

    @Override
    public void closeWith(AutoCloseable resource) {
        throw new UnsupportedOperationException("no server in a unit test");
    }

    @Override
    public <T> de.raindancer.core.data.settings.SettingsStore<T> settings(Class<T> type, T defaults) {
        throw new UnsupportedOperationException("no server in a unit test");
    }

    @Override
    public Optional<FlexModule> module(String id) {
        return Optional.empty();
    }
}
