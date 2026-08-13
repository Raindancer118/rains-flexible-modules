package de.raindancer.modules.api;

import de.raindancer.core.RainsCore;
import de.raindancer.core.data.settings.In;
import de.raindancer.core.data.settings.Settings;
import de.raindancer.core.data.settings.SettingsSchema;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.data.settings.Title;
import de.raindancer.core.data.settings.Topic;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A module's settings, across a session that does not end in it actually running.
 *
 * <h2>The bug this guards against</h2>
 * {@code RainsCore.settingsFor} adds a store to the combined {@code /settings} tree the moment it is
 * called — which is early in a module's {@code enable()}, before the rest of it has had a chance to
 * throw. Nothing used to undo that: a module that registered its settings and then failed further on
 * kept a page in the menu, "N settings, click to open" and all, for as long as the server ran — which
 * looks exactly like a module that is installed and running, to anybody who has not read the console.
 * The same gap meant a module that was cleanly disabled kept its page too.
 *
 * <p>The fix ties the registration to the same {@link Unwind} a listener already goes through: whatever
 * ends the session — a later line in {@code enable()} throwing, or an ordinary {@code disable()} — takes
 * the settings back out with it.
 */
class LiveModuleSessionTest {

    @Settings(id = "sample", topics = @Topic(path = "config/limits", title = "Limits"))
    public record SampleSettings(@In("config/limits") @Title("Whatever") int whatever) {
        static final SampleSettings DEFAULTS = new SampleSettings(1);
    }

    @TempDir
    Path dataRoot;

    @SuppressWarnings("unchecked")
    private ModuleContext contextFor(RainsCore core, SettingsStore<SampleSettings> store,
                                     LiveModuleSession[] sessionOut) {
        ModuleInfo info = ModuleInfo.of("sample", "Sample", "1.0.0");
        FlexModule module = mock(FlexModule.class);
        when(module.info()).thenReturn(info);

        ModuleHost host = mock(ModuleHost.class);
        when(host.layout()).thenReturn(ModuleLayout.owningFolder(dataRoot));

        doReturn(store).when(core).settingsFor(any(SettingsSchema.class), any(Path.class));

        LiveModuleSession session = new LiveModuleSession(host, module);
        sessionOut[0] = session;
        return session.context();
    }

    @Test
    @DisplayName("registering settings does not forget them while the module is still running")
    void notForgottenWhileRunning() {
        RainsCore core = mock(RainsCore.class);
        SettingsStore<SampleSettings> store = new SettingsStore<>(
                SettingsSchema.of(SampleSettings.class, SampleSettings.DEFAULTS),
                dataRoot.resolve("config.yml"));
        LiveModuleSession[] sessionOut = new LiveModuleSession[1];

        try (MockedStatic<RainsCore> rainsCore = mockStatic(RainsCore.class)) {
            rainsCore.when(RainsCore::get).thenReturn(core);

            ModuleContext context = contextFor(core, store, sessionOut);
            SettingsStore<SampleSettings> bound = context.settings(SampleSettings.class,
                    SampleSettings.DEFAULTS);

            assertThat(bound).isSameAs(store);
            verify(core, never()).forgetSettings(any());
        }
    }

    @Test
    @DisplayName("unwinding — enable() failing further on, or an ordinary disable — forgets them")
    void forgottenOnUnwind() {
        RainsCore core = mock(RainsCore.class);
        SettingsStore<SampleSettings> store = new SettingsStore<>(
                SettingsSchema.of(SampleSettings.class, SampleSettings.DEFAULTS),
                dataRoot.resolve("config.yml"));
        LiveModuleSession[] sessionOut = new LiveModuleSession[1];

        try (MockedStatic<RainsCore> rainsCore = mockStatic(RainsCore.class)) {
            rainsCore.when(RainsCore::get).thenReturn(core);

            ModuleContext context = contextFor(core, store, sessionOut);
            context.settings(SampleSettings.class, SampleSettings.DEFAULTS);

            // Whatever happened after this line in the module's own enable() — including the module
            // never finishing at all — the host unwinds the session on every path out.
            sessionOut[0].unwind();

            verify(core).forgetSettings(store);
        }
    }
}
