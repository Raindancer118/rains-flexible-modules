package de.raindancer.modules.twitch.service;

import de.raindancer.core.data.store.Secrets;
import de.raindancer.core.data.store.YamlStore;
import de.raindancer.core.platform.log.LogChannel;

import java.util.function.Function;

/**
 * Where the Twitch login comes from: the environment ({@value #ENV_ID} and {@value #ENV_SECRET}, both set), then the
 * network's secret {@value #SECRET}. A login a 1.x or 2.0 {@code credentials.yml} still holds is moved into the secret
 * and the document is rewritten without it.
 */
public final class TwitchLoginStore {

    public static final String ENV_ID = "RAINS_TWITCH_CLIENT_ID";
    public static final String ENV_SECRET = "RAINS_TWITCH_CLIENT_SECRET";
    public static final String SECRET = "twitch-login";

    public enum Source { ENVIRONMENT, DATABASE, NONE }

    private static final String HEADER = """
            The Twitch app login (https://dev.twitch.tv/console/apps) is not kept in this document: it lives in the
            network's secret store, which /documents cannot list or export.
            Set it from the server console: /twitch login <client-id> <client-secret>   (remove it: /twitch logout)
            Or set the environment variables RAINS_TWITCH_CLIENT_ID and RAINS_TWITCH_CLIENT_SECRET; they win.
            Only one server of the network, whichever holds the 'twitch' role, asks Twitch.""";

    private final Secrets secrets;
    private final YamlStore document;
    private final Function<String, String> environment;
    private final LogChannel log;
    private volatile Source source = Source.NONE;

    public TwitchLoginStore(Secrets secrets, YamlStore document, Function<String, String> environment, LogChannel log) {
        this.secrets = secrets;
        this.document = document;
        this.environment = environment;
        this.log = log;
    }

    public Source source() {
        return source;
    }

    /** The login now in force, after bringing an old document's login into the secret. */
    public TwitchLogin read() {
        migrate();
        String id = environment.apply(ENV_ID);
        String secret = environment.apply(ENV_SECRET);
        if (id != null && secret != null && !id.isBlank() && !secret.isBlank()) {
            source = Source.ENVIRONMENT;
            return new TwitchLogin(id.strip(), secret.strip());
        }
        TwitchLogin stored = secrets.text(SECRET).map(TwitchLogin::decode).orElse(TwitchLogin.NONE);
        source = stored.present() ? Source.DATABASE : Source.NONE;
        return stored.present() ? stored : TwitchLogin.NONE;
    }

    /** @return whether it was stored */
    public boolean set(String clientId, String clientSecret) {
        return secrets.putText(SECRET, new TwitchLogin(clientId.strip(), clientSecret.strip()).encode());
    }

    /** @return whether the database could be written */
    public boolean clear() {
        return secrets.remove(SECRET);
    }

    /** Leaves the explanation for an owner when there is no document yet. */
    public void ensureTemplate() {
        if (!document.exists()) {
            writeExplanation();
        }
    }

    private void migrate() {
        var yaml = document.readIfReadable();
        if (yaml.isEmpty()) {
            return;
        }
        TwitchLogin old = new TwitchLogin(yaml.get().getString("client-id", "").strip(),
                yaml.get().getString("client-secret", "").strip());
        if (!old.present()) {
            return;
        }
        var kept = secrets.text(SECRET);
        if (kept.isEmpty() && !secrets.putText(SECRET, old.encode())) {
            log.warn("The Twitch login in credentials.yml could not be moved into the database; it stays there for now.");
            return;
        }
        if (kept.isPresent() && !kept.get().equals(old.encode())) {
            log.warn("credentials.yml still holds a Twitch login that differs from the stored one; clear it by hand.");
            return;
        }
        if (writeExplanation()) {
            log.info("The Twitch login moved from credentials.yml into the network's secret store.");
        }
    }

    private boolean writeExplanation() {
        return document.write(yaml -> {
            yaml.options().header(HEADER);
            yaml.set("login", "not here; see the header");
        });
    }
}
