package de.raindancer.modules.twitch.service;

import de.raindancer.core.data.store.YamlStore;

/**
 * The Twitch app login, kept as a document of the network (so every server has it, and a 1.x credentials.yml is
 * brought in by Core on first use). An app login only reads public data.
 */
public record TwitchLogin(String clientId, String clientSecret) {

    public static final TwitchLogin NONE = new TwitchLogin("", "");

    public static TwitchLogin read(YamlStore store) {
        var yaml = store.read();
        return new TwitchLogin(yaml.getString("client-id", "").strip(), yaml.getString("client-secret", "").strip());
    }

    /** Leaves a template for an owner to fill in, when there is no document yet. */
    public static void ensureTemplate(YamlStore store) {
        if (!store.exists()) {
            store.write(yaml -> {
                yaml.options().header("The Twitch app login for the watch: https://dev.twitch.tv/console/apps\n"
                        + "An app login only reads public data. Shared by every server of the network;\n"
                        + "only one of them, whichever holds the 'twitch' role, asks Twitch.");
                yaml.set("client-id", "");
                yaml.set("client-secret", "");
            });
        }
    }

    public boolean present() {
        return !clientId.isEmpty() && !clientSecret.isEmpty();
    }

    @Override
    public String toString() {
        return "TwitchLogin[" + (present() ? "set" : "empty") + "]";
    }
}
