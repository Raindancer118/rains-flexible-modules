package de.raindancer.modules.api;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * Where a module's files go.
 *
 * <p>Two answers, and which one applies is the whole point of this project. Shipped as its own plugin,
 * a module owns its data folder and its file is {@code plugins/RainsModeration/config.yml} — exactly
 * where somebody upgrading from a standalone plugin expects it. Hosted inside another plugin it cannot
 * have that folder, because the host's own {@code config.yml} is already in it, so it gets
 * {@code plugins/RainsSMPCore/modules/moderation/config.yml}.
 *
 * <p>The path is built from the module id, and that is why {@link ModuleInfo} is strict about ids: an
 * id of {@code ../../..} would put a module's config outside the plugins directory entirely.
 */
class ModuleLayoutTest {

    private static final Path ROOT = Path.of("plugins", "Host");

    @Test
    void aStandaloneModuleOwnsTheFolderItself() {
        ModuleLayout layout = ModuleLayout.owningFolder(ROOT);
        assertThat(layout.folderFor("moderation")).isEqualTo(ROOT);
        assertThat(layout.configFor("moderation")).isEqualTo(ROOT.resolve("config.yml"));
        assertThat(layout.isShared()).isFalse();
    }

    @Test
    void aHostedModuleGetsItsOwnCornerOfTheHostsFolder() {
        ModuleLayout layout = ModuleLayout.sharedFolder(ROOT);
        assertThat(layout.folderFor("moderation")).isEqualTo(ROOT.resolve("modules").resolve("moderation"));
        assertThat(layout.configFor("moderation"))
                .isEqualTo(ROOT.resolve("modules").resolve("moderation").resolve("config.yml"));
        assertThat(layout.isShared()).isTrue();
    }

    @Test
    void twoHostedModulesDoNotShareAFolder() {
        ModuleLayout layout = ModuleLayout.sharedFolder(ROOT);
        assertThat(layout.folderFor("moderation")).isNotEqualTo(layout.folderFor("farm-world"));
    }

    @Test
    void refusesAnIdThatIsNotOne() {
        ModuleLayout layout = ModuleLayout.sharedFolder(ROOT);
        assertThatIllegalArgumentException().isThrownBy(() -> layout.folderFor("../../etc"));
        assertThatIllegalArgumentException().isThrownBy(() -> layout.folderFor("Moderation"));
        assertThatIllegalArgumentException().isThrownBy(() -> layout.folderFor(""));
    }

    @Test
    void staysUnderTheRootWhateverItIsAsked() {
        ModuleLayout layout = ModuleLayout.sharedFolder(ROOT);
        // Compared as text rather than with startsWith(Path): AssertJ resolves real paths for that, and
        // these folders do not exist — the question here is what the path says, not what is on disk.
        assertThat(layout.folderFor("moderation").normalize().toString())
                .startsWith(ROOT.toString());
    }

    @Test
    void takesAModuleDirectlyToo() {
        ModuleInfo info = ModuleInfo.of("moderation", "Moderation", "1.0.0");
        ModuleLayout layout = ModuleLayout.sharedFolder(ROOT);
        assertThat(layout.folderFor(info)).isEqualTo(layout.folderFor("moderation"));
        assertThat(layout.configFor(info)).isEqualTo(layout.configFor("moderation"));
    }

    @Test
    void needsARoot() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> ModuleLayout.sharedFolder(null)))
                .isInstanceOf(NullPointerException.class);
    }
}
