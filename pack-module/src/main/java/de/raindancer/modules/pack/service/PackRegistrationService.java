package de.raindancer.modules.pack.service;

import de.raindancer.core.content.pack.HostedPack;
import de.raindancer.core.content.pack.ResourcePacks;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.pack.PackSettings;
import de.raindancer.modules.pack.rules.PublishedHashRule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Telling RainsCore about the pack this server wears.
 *
 * <h2>What this module is, in one paragraph</h2>
 * A player has one resource pack slot, so Core arbitrates it: plugins hand it their assets and it
 * decides what is sent. A whole pack hosted somewhere else is the other shape of the same thing, and it
 * goes through the same door — {@link ResourcePacks#host} — rather than round it. A module that called
 * {@code setResourcePack} itself would work perfectly until anything contributed assets, at which point
 * the two fight over that one slot and whoever sends last wins silently.
 *
 * <p>Core already sends the pack on join and already tracks who has it. So this module registers one
 * pack and stops. There is deliberately no listener, no command and no screen.
 *
 * <h2>Why the hash is fetched</h2>
 * A client caches a pack by its sha1, and the hash changes every time the pack is updated. Written into
 * a config file it has to be edited on every server on the day the pack changes, and the one nobody
 * edits carries on sending yesterday's — so the client either re-downloads on every join or refuses the
 * pack, with nothing saying why. The host publishes {@code sha1.txt} beside the file; reading it means
 * updating the pack is all there is to do.
 *
 * <p><b>Off the server's threads, always.</b> Fetching during {@code onEnable} would hold the boot up
 * for as long as somebody else's web server felt like taking, and a pack is not worth a server that
 * does not start.
 */
public final class PackRegistrationService implements IPackService {

    /** Long enough for a slow host, short enough that a dead one is not a wait. */
    private static final Duration PATIENCE = Duration.ofSeconds(10);

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final ResourcePacks packs;
    private final Plugin plugin;
    private final LogChannel log;
    private final PublishedHashRule published = new PublishedHashRule();

    private volatile PackSettings settings;

    public PackRegistrationService(ResourcePacks packs, Plugin plugin, LogChannel log,
                                   PackSettings settings) {
        this.packs = packs;
        this.plugin = plugin;
        this.log = log;
        this.settings = settings;
    }

    @Override
    public void settings(PackSettings fresh) {
        this.settings = fresh;
    }

    /**
     * Registers the pack, looking its hash up first when it has to.
     *
     * <p>Returns immediately either way. When the hash is configured the pack is live before this
     * returns; when it is looked up the registration happens once the answer arrives, which is a second
     * or two into the server's life and long before anybody has joined.
     */
    public void register() {
        PackSettings now = settings;
        if (!now.isConfigured()) {
            // A server that does not want a pack. Said once, at info: the alternative is an owner who
            // switched it off wondering whether the module is broken.
            log.info("No pack is configured, so none is sent.");
            return;
        }
        if (!now.sha1().isBlank() || !now.lookUpHash()) {
            registerWith(now, now.sha1());
            return;
        }
        // Off the server's threads. Nothing waits on this.
        Scheduling.async(plugin, () -> {
            String found = fetchHash(now).orElse("");
            if (found.isBlank()) {
                log.warn("Could not read the pack's hash from {}, so nothing is sent. Set it by hand "
                        + "in the settings, or fix the host.", now.hashUrl());
                return;
            }
            registerWith(now, found);
        });
    }

    /** Hands it to Core, which owns everything from here on. */
    private void registerWith(PackSettings now, String sha1) {
        packs.required(now.required());
        if (!now.prompt().isBlank()) {
            packs.prompt(promptOf(now));
        }
        HostedPack pack = HostedPack.at(now.name(), now.url(), sha1);
        if (packs.host(pack)) {
            log.info("'{}' is this server's pack: {}", pack.id(), pack.url());
        }
        // A refusal has already been logged by Core with the reason. Not repeated here: one line per
        // problem, from whoever actually knows what was wrong with it.
    }

    /**
     * Reads {@code sha1.txt} from beside the pack.
     *
     * <p>Every failure answers empty rather than throwing. A pack that cannot be looked up is a pack
     * that is not sent, which is a server without a texture pack — not a server that will not start.
     */
    private Optional<String> fetchHash(PackSettings now) {
        String where = now.hashUrl();
        if (where.isBlank()) {
            return Optional.empty();
        }
        try (HttpClient client = HttpClient.newBuilder()
                .connectTimeout(PATIENCE)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build()) {
            HttpResponse<String> answer = client.send(
                    HttpRequest.newBuilder(URI.create(where)).timeout(PATIENCE).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (answer.statusCode() != 200) {
                log.warn("{} answered {} rather than 200.", where, answer.statusCode());
                return Optional.empty();
            }
            // By file name, never the first line: a sha1.txt listing both the pack and its datapack
            // would otherwise hand back the datapack's hash — a perfectly valid hash for the wrong file,
            // which is a pack that silently never applies.
            return published.hashOf(answer.body(), now.fileName());
        } catch (IOException | IllegalArgumentException unreachable) {
            log.warn("Could not reach {}: {}", where, unreachable.toString());
            return Optional.empty();
        } catch (InterruptedException interrupted) {
            // The server is going down while this was in flight. Put the flag back and give up quietly.
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    private Component promptOf(PackSettings now) {
        try {
            return MINI.deserialize(now.prompt());
        } catch (RuntimeException unparseable) {
            // Somebody's markup, in a config file. Shown as plain text rather than costing them the
            // pack — and said out loud, because a prompt that quietly lost its colours is a thing
            // nobody reports and everybody notices.
            log.warn("The pack prompt is not valid markup, so it is shown as written: {}",
                    unparseable.toString());
            return Component.text(now.prompt());
        }
    }

    /** Takes it back, for a module being disabled. */
    public void unregister() {
        PackSettings now = settings;
        if (now.isConfigured() && packs.unhost(now.name())) {
            log.info("'{}' is no longer this server's pack.", now.name());
        }
    }

    @Override
    public String describe() {
        return "registering this server's hosted resource pack with RainsCore";
    }
}
