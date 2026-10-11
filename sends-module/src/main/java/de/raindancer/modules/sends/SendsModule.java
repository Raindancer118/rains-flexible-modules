package de.raindancer.modules.sends;

import de.raindancer.core.data.nbt.ItemBytes;
import de.raindancer.core.data.settings.SettingsStore;
import de.raindancer.core.content.items.BoundItems;
import de.raindancer.core.content.items.InsuredItems;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.api.FlexModule;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.api.ModuleContext;
import de.raindancer.modules.api.ModuleInfo;
import de.raindancer.modules.sends.listener.SendJoinListener;
import de.raindancer.modules.sends.rules.SendableItemsRule;
import de.raindancer.modules.sends.screen.SendComposer;
import de.raindancer.modules.sends.screen.SendsMenu;
import de.raindancer.modules.sends.service.SendService;
import de.raindancer.modules.sends.store.SendBook;
import de.raindancer.modules.sends.store.SendsDatabase;
import de.raindancer.modules.sends.store.SqliteSendStorage;
import de.raindancer.modules.sends.util.PermissionNodes;
import org.bukkit.Server;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Sending items to another player.
 *
 * <p>Shipped through the standard wrapper this is {@code RainsSends}. {@code /send create} picks somebody and
 * opens a window to put items in; Send puts them to that player, who accepts with a click in chat, with
 * {@code /send accept}, or from {@code /sends} — which is also where somebody without room finds them.
 *
 * <h2>The one invariant</h2>
 * Every item is in exactly one place: in the sender's window, in a send, or in somebody's inventory. A send ends
 * once — accepted, or returned to its sender — and the items are given only after that end is on disk.
 */
public final class SendsModule implements FlexModule {

    private static final ModuleInfo INFO = ModuleInfo.of("sends", "Sends", "0.1.0")
            .describedAs("Send items to another player: put them in a window, they accept with a click.")
            .by("Raindancer118");

    /** How often runs out are looked for. Five seconds is late enough to cost nothing and early enough to read as on time. */
    private static final long SWEEP_TICKS = 100L;

    private LogChannel log;
    private SendsServices services;
    private SendService sends;
    private ExecutorService writer;

    @Override
    public ModuleInfo info() {
        return INFO;
    }

    @Override
    public void enable(ModuleContext context) {
        log = context.log();
        Server server = context.plugin().getServer();
        SettingsStore<SendsSettings> settings = context.settings(SendsSettings.class, SendsSettings.DEFAULTS);

        context.core().messages().defineFrom(
                SendsModule.class.getResourceAsStream("messages.yml"),
                context.chat().brand()::chatPrefix);
        context.core().messages().seriousFrom(SendsModule.class.getResourceAsStream("messages-serious.yml"));

        int registered = PermissionNodes.register(server);
        if (registered > 0) {
            log.info("{} permission(s) registered.", registered);
        }

        var database = context.core().databases().of(SendsDatabase.NAME, SendsDatabase.SCHEMA);
        writer = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "sends-writer");
            thread.setDaemon(true);
            return thread;
        });
        SendBook book = new SendBook(new SqliteSendStorage(database, new ItemBytes.OfTheServer()), writer,
                System::currentTimeMillis);
        offTheServerThread(book::load);

        sends = new SendService(context.plugin(), server, log, context.core().messages(), context.core().effects(),
                book, new SendableItemsRule(item -> BoundItems.isBound(item) || InsuredItems.isInsured(item)),
                System::currentTimeMillis, settings.current());
        settings.onChange(sends::settings);

        services = new SendsServices(context.plugin(), server, log, context.core().messages(),
                context.chat().brand(), context.core(), settings::current, settings, book, sends,
                new LiveScreens());

        context.listener(new SendJoinListener(services));
        SendsCommands.ready(services);

        var timer = Scheduling.globalTimer(context.plugin(), SWEEP_TICKS, SWEEP_TICKS, task -> sends.sweep());
        if (timer != null) {
            context.closeWith(timer::cancel);
        }
        sends.sweep();

        log.info("Sends are up: {} minute(s) to accept, at most {} waiting per player.",
                settings.current().expiryMinutes(), settings.current().mostWaitingClamped());
    }

    private static void offTheServerThread(Runnable work) {
        Thread thread = new Thread(work, "sends-load");
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Opening this module's screens, without the commands knowing the menu classes. */
    private final class LiveScreens implements ISendsScreensOpener {

        @Override
        public void sends(Player viewer) {
            new SendsMenu(services, viewer).open();
        }

        @Override
        public void choose(Player viewer) {
            new SendComposer(services, viewer).open();
        }

        @Override
        public void compose(Player viewer, UUID recipient, String recipientName) {
            new SendComposer(services, viewer, recipient, recipientName).open();
        }
    }

    @Override
    public List<ModuleCommand> commands() {
        return SendsCommands.declared();
    }

    /**
     * Stopping lets the writes and hand-overs on their way finish before the writer is closed — a send accepted a
     * moment ago must not end up delivered in the world and still pending on disk.
     */
    @Override
    public void disable() {
        SendsCommands.stopped();
        if (sends != null && !sends.awaitIdle(5, TimeUnit.SECONDS)) {
            log.error("Some sends were still being written when the module stopped.");
        }
        if (writer != null) {
            writer.shutdown();
            try {
                if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                    log.error("The last writes to sends.db did not finish.");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
