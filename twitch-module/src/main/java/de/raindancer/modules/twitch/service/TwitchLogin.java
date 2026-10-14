package de.raindancer.modules.twitch.service;

/**
 * The Twitch app login. It is kept in the network's secret store (never in a document, which {@code /documents}
 * can list and export); an app login only reads public data, but it still must not be handed around.
 */
public record TwitchLogin(String clientId, String clientSecret) {

    public static final TwitchLogin NONE = new TwitchLogin("", "");

    /** How the login is kept in a secret: the id, a line break, the secret. */
    public String encode() {
        return clientId + "\n" + clientSecret;
    }

    public static TwitchLogin decode(String stored) {
        if (stored == null) {
            return NONE;
        }
        String[] lines = stored.split("\n", 2);
        return lines.length < 2 ? NONE : new TwitchLogin(lines[0].strip(), lines[1].strip());
    }

    public boolean present() {
        return !clientId.isEmpty() && !clientSecret.isEmpty();
    }

    @Override
    public String toString() {
        return "TwitchLogin[" + (present() ? "set" : "empty") + "]";
    }
}
