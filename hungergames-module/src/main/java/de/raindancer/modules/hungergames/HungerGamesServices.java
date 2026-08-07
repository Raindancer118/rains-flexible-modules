package de.raindancer.modules.hungergames;

import de.raindancer.core.content.items.CustomItems;
import de.raindancer.core.content.items.ItemFactory;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.hungergames.service.ChatChannelService;
import de.raindancer.modules.hungergames.service.DeathmatchService;
import de.raindancer.modules.hungergames.service.GameControlService;
import de.raindancer.modules.hungergames.service.PreflightCheckService;
import de.raindancer.modules.hungergames.store.GameSession;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

/**
 * Everything the module has built, in one place, so a command or a screen can be handed what it needs.
 *
 * <h2>Why this is not the god object it replaces</h2>
 * The thing before it was the {@code JavaPlugin} subclass, reached through a static {@code getInstance()}
 * from every command and every menu. Every command therefore depended on all of it, and none of them could
 * be constructed without a server — which is why the old plugin had no tests for any of them.
 *
 * <p>The difference is that this is <em>data</em>: a record of collaborators, constructed once by the module
 * and handed over. A test builds one with fakes in the two fields it cares about. Nothing here is static,
 * nothing reaches back into Bukkit, and anything needing only one of these still says so in its own
 * constructor — this is for the handful of things that genuinely coordinate half the module.
 *
 * <h2>Deliberately small</h2>
 * The module has thirty-six services and this names five of them. That is not an oversight: the rest are
 * reached by the screens and listeners that own them, each taking exactly what it needs, and a record naming
 * all thirty-six would be a list every command implicitly depends on. What is here is what a <em>command</em>
 * needs, which is the run-up, the round, and a way to speak.
 *
 * @param settings read through a supplier, not captured: a reload has to change what happens next, not what
 *                 happens after the next restart
 * @param screens  opening a screen, as an interface — so nothing here depends on the menus, and so the
 *                 commands can be built at bootstrap when no menu could exist yet
 */
public record HungerGamesServices(
        Plugin plugin,
        Server server,
        LogChannel log,
        Messages messages,
        Chat chat,
        Brand brand,

        GameSession session,
        GameControlService control,
        PreflightCheckService preflight,
        DeathmatchService deathmatch,
        ChatChannelService chatChannels,

        Supplier<HungerGamesSettings> settings,
        IHungerGamesScreensOpener screens,

        /**
         * Core's item registry, so {@code /hg give} can hand one over.
         *
         * <p>The registry rather than this module's four item services: the command's whole job is "make me
         * the thing called <name>", and the registry is what knows the names. Going through the services
         * would mean the command had to know which of the four owns which item — a fact that changes every
         * time an item moves and is invisible when it does.
         */
        CustomItems items,

        /** What turns one of those definitions into a stack somebody can hold. */
        ItemFactory itemFactory) {

    /** The settings as they are right now. */
    public HungerGamesSettings config() {
        return settings.get();
    }
}
