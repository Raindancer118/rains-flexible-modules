package de.raindancer.modules.pack;

import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.pack.service.PackRegistrationService;

/**
 * The one resource pack this server wears.
 *
 * <p>Shipped through the standard wrapper this is {@code RainsServerPack}, a plugin of its own. Hosted
 * inside another plugin it is one feature among several, and the code below cannot tell which.
 *
 * <h2>Why this module is so small, and why that is the point</h2>
 * A player has exactly one resource pack slot. RainsCore arbitrates it — plugins hand it their assets,
 * it decides what is sent, in what order, to whom, and whether they already have it. A whole pack
 * hosted somewhere else is the same slot from the other direction, so it goes through the same door:
 * {@code core.resourcePacks().host(...)}.
 *
 * <p>A module that called {@code setResourcePack} itself would be shorter still and would work
 * perfectly — right up until anything contributed assets to Core's pack, at which point the two fight
 * over that one slot, whoever sends last wins, and the loser's pack is silently gone. That is the exact
 * collision {@code core.content.pack} exists to prevent, and going round it is the one thing this
 * module must not do.
 *
 * <p>So Core sends the pack on join, tracks who has it, retries a failed download and leaves a refusal
 * alone. What is left here — the whole module — is <b>which pack, where, and how hard to insist.</b>
 * There is deliberately no listener, no command and no screen.
 */
public final class PackModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("serverpack", "Server Pack", "1.0.0")
            .describedAs("The one resource pack this server wears, hosted elsewhere and applied to "
                    + "everybody — through RainsCore, which owns the single pack slot")
            .by("Raindancer118");

    private LogChannel log;
    private SettingsStore<PackSettings> settings;
    private PackRegistrationService registration;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        log = context.log();
        settings = context.settings(PackSettings.class, PackSettings.DEFAULTS);

        // No messages.yml, and that is deliberate rather than missing. This module never speaks to a
        // player: what they see is the client's own download prompt, worded from the settings. Everything
        // else it has to say — a broken link, a hash that would not parse — is for the operator and goes
        // to the log. A wording file with one unused key in it is a file somebody later has to work out
        // the purpose of.

        registration = new PackRegistrationService(context.core().resourcePacks(),
                context.plugin(), log, settings.current());

        // A reload re-registers, so changing the link or the hash takes effect without a restart —
        // Core replaces a hosted pack registered under the same name rather than sending two.
        settings.onChange(fresh -> {
            registration.settings(fresh);
            registration.register();
        });

        // Returns immediately. When the hash has to be looked up the pack goes live a moment later,
        // off the server's threads — a boot must never wait on somebody else's web server.
        registration.register();
    }

    @Override
    public void disable() {
        if (registration != null) {
            // Taken back, so a host that stops this module stops sending its pack. Without it, Core
            // would go on offering a pack nothing is responsible for any more.
            registration.unregister();
        }
    }
}
