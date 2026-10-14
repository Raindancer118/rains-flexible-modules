package de.raindancer.modules.moderation;

import de.raindancer.core.RainsCore;
import de.raindancer.core.data.settings.Setting;
import de.raindancer.core.data.settings.SettingsSchema;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.LogChannel;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Before 2.0 the anti-cheat was a plugin of its own with its own settings ({@code RainsAntiCheat/config.yml}). Its
 * part of the moderation module's settings starts from what the owner had set there: every value they changed is
 * copied over once, at the layer it was set at. The old document is left as it is.
 */
final class LegacySettings {

    private static final String DONE = "moved-into-moderation.yml";

    private LegacySettings() {
    }

    /** Once per server: copies what is there, then remembers that it did. */
    static void carryOver(RainsCore core, Path formerFolder, SettingsStore<ModerationSettings> into, LogChannel log) {
        YamlStore done = new YamlStore(formerFolder.resolve(DONE));
        if (done.exists()) {
            return;
        }
        Path old = formerFolder.resolve("config.yml");
        if (!new YamlStore(old).exists()) {
            done.write(yaml -> yaml.set("moved", true));
            return;
        }
        SettingsStore<AntiCheatSettings> before = core.settingsFor(
                SettingsSchema.of(AntiCheatSettings.class, AntiCheatSettings.DEFAULTS), old);
        try {
            int moved = carryOver(before, into);
            if (moved > 0) {
                log.info("{} anti-cheat setting(s) from {} are the moderation module's now.", moved, old);
            }
            done.write(yaml -> yaml.set("moved", true));
        } finally {
            core.forgetSettings(before);
        }
    }

    /** @return how many values were copied */
    static int carryOver(SettingsStore<AntiCheatSettings> before, SettingsStore<ModerationSettings> into) {
        int moved = 0;
        for (Setting<?> setting : before.schema().settings()) {
            Object was = setting.valueIn(before.current());
            if (Objects.equals(was, setting.valueIn(AntiCheatSettings.DEFAULTS))) {
                continue;
            }
            if (into.set("anticheat." + setting.key(), before.display(setting.key()), before.layerOf(setting.key()))) {
                moved++;
            }
        }
        if (moved > 0) {
            into.save();
        }
        return moved;
    }
}
