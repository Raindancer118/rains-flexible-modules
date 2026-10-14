package de.raindancer.modules.essentials.service;

import de.raindancer.core.content.items.CustomItem;
import de.raindancer.core.content.items.CustomItems;
import de.raindancer.core.content.items.ItemAbilities;
import de.raindancer.core.content.items.ItemAbility;
import de.raindancer.core.content.items.ItemFactory;
import de.raindancer.core.content.items.ItemTrigger;
import de.raindancer.core.content.items.TaggedItems;
import de.raindancer.core.platform.network.NetworkBus;
import de.raindancer.core.platform.network.NetworkCalls;
import de.raindancer.core.platform.network.NetworkPlayers;
import de.raindancer.core.platform.network.NetworkServers;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.essentials.EssentialsSettings;
import de.raindancer.modules.essentials.rules.SkyTokenRule;
import de.raindancer.modules.essentials.rules.SkyTokenRule.Outcome;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.Locale;
import java.util.Optional;

/**
 * Two tokens only ops hand out: one clears the sky over the world its holder stands in, one skips the
 * night there. Core carries the items — the glow, the right click, keeping them out of crafting grids —
 * and spends one when it worked; when it would do nothing it declines, and the token stays.
 */
public final class SkyTokenService implements IEssentialsService {

    public static final String PLUGIN = "essentials";

    /** The two kinds, by what an op types. */
    public enum Kind {
        SKY("sky-token"),
        DAWN("dawn-token");

        private final String id;

        Kind(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public String key() {
            return PLUGIN + ":" + id;
        }

        public static Optional<Kind> of(String typed) {
            if (typed == null) {
                return Optional.empty();
            }
            return switch (typed.trim().toLowerCase(Locale.ROOT)) {
                case "sky", "weather", "clear", "sun" -> Optional.of(SKY);
                case "dawn", "night", "day", "morning" -> Optional.of(DAWN);
                default -> Optional.empty();
            };
        }
    }

    /** How long a cleared sky stays clear: one whole day and night. */
    static final int CLEAR_FOR_TICKS = 24_000;

    private final Plugin plugin;
    private final Server server;
    private final Messages messages;
    private final ItemFactory factory;
    private final SkyTokenRule rule = new SkyTokenRule();
    private volatile CustomItems items;

    public SkyTokenService(Plugin plugin, Server server, Messages messages, ItemFactory factory) {
        this.plugin = plugin;
        this.server = server;
        this.messages = messages;
        this.factory = factory;
    }

    @Override
    public void settings(EssentialsSettings settings) {
        // Nothing to read: what a token does is the token.
    }

    public static CustomItem definition(Kind kind) {
        return switch (kind) {
            case SKY -> CustomItem.builder(PLUGIN, kind.id())
                    .material(Material.SUNFLOWER)
                    .name("<gradient:#8FD3FF:#FFE066><bold>Clear Sky Token</bold></gradient>")
                    .lore(List.of(
                            "<gray>Chases the rain and the thunder",
                            "<gray>out of the sky you stand under.",
                            "",
                            "<white>Right-click <gray>to use it.",
                            "<dark_gray>Kept if the sky is already clear."))
                    .glowing(true)
                    .ability(kind.id())
                    .notAnIngredient()
                    .build();
            case DAWN -> CustomItem.builder(PLUGIN, kind.id())
                    .material(Material.CLOCK)
                    .name("<gradient:#3B2F8F:#FF9A5A><bold>Dawn Token</bold></gradient>")
                    .lore(List.of(
                            "<gray>Skips the night where you are,",
                            "<gray>straight to the next sunrise.",
                            "",
                            "<white>Right-click <gray>to use it.",
                            "<dark_gray>Kept if it is already day."))
                    .glowing(true)
                    .ability(kind.id())
                    .notAnIngredient()
                    .build();
        };
    }

    /** Tells Core about both tokens and what clicking each does. */
    public void register(CustomItems items, ItemAbilities abilities) {
        this.items = items;
        for (Kind kind : Kind.values()) {
            items.defineIfAbsent(definition(kind));
            abilities.register(ItemAbility.builder(PLUGIN, kind.id())
                    .on(ItemTrigger.RIGHT_CLICK)
                    .describedAs(kind == Kind.SKY ? "Clear the sky in this world" : "Skip the night in this world")
                    .consumesItem()
                    .attempts(use -> {
                        Player player = server.getPlayer(use.player());
                        return player != null && use(player, kind);
                    })
                    .build());
        }
    }

    /** Uses one token's effect where {@code player} stands. False, and said why, when there is nothing to do. */
    boolean use(Player player, Kind kind) {
        World world = player.getWorld();
        boolean hasSky = world.getEnvironment() == World.Environment.NORMAL;
        Outcome outcome = kind == Kind.SKY
                ? rule.clearSky(hasSky, world.hasStorm(), world.isThundering())
                : rule.skipNight(hasSky, world.getTime());
        if (outcome != Outcome.GO) {
            messages.send(player, switch (outcome) {
                case NO_SKY -> "essentials.tokens.no-sky";
                case ALREADY_CLEAR -> "essentials.tokens.already-clear";
                default -> "essentials.tokens.already-day";
            });
            return false;
        }
        // A world's weather and time are the global region's on Folia, whoever's thread asked.
        Scheduling.global(plugin, () -> {
            if (kind == Kind.SKY) {
                world.setStorm(false);
                world.setThundering(false);
                world.setClearWeatherDuration(CLEAR_FOR_TICKS);
            } else {
                world.setFullTime(rule.nextMorning(world.getFullTime()));
            }
        });
        String line = kind == Kind.SKY ? "essentials.tokens.sky-cleared" : "essentials.tokens.dawn-came";
        for (Player there : world.getPlayers()) {
            messages.send(there, line, "player", player.getName());
        }
        return true;
    }

    /** Hands {@code amount} tokens to somebody; what does not fit is dropped at their feet. */
    public void give(CommandSender giver, Player to, Kind kind, int amount) {
        if (!hand(to, kind, amount)) {
            messages.send(giver, "essentials.tokens.cannot-make");
            return;
        }
        messages.send(giver, "essentials.tokens.given", "amount", amount, "token", shown(kind),
                "player", to.getName());
        if (giver != to) {
            messages.send(to, "essentials.tokens.received", "amount", amount, "token", shown(kind));
        }
    }

    private boolean hand(Player to, Kind kind, int amount) {
        CustomItems registered = items;
        CustomItem token = registered == null ? definition(kind)
                : registered.byKey(kind.key()).orElse(definition(kind));
        Optional<ItemStack> made = factory.create(token, amount);
        if (made.isEmpty()) {
            return false;
        }
        TaggedItems.handTo(to, made.get());
        return true;
    }

    private static String shown(Kind kind) {
        return kind == Kind.SKY ? "Clear Sky Token" : "Dawn Token";
    }

    // ----------------------------------------------------------------------------- another server

    /** What a server is asked to hand over: who, which token, how many. */
    public static final String GIVE = "essentials:token-give";

    private volatile NetworkPlayers players;
    private volatile NetworkCalls calls;
    private volatile NetworkBus.Subscription answering;
    private volatile Duration timeout = Duration.ofSeconds(4);

    /** Lets tokens be handed to players of other servers, and answers the other servers' asking. */
    public void connect(NetworkPlayers directory, NetworkCalls network) {
        this.players = directory;
        this.calls = network;
        this.answering = network.answerFrom(GIVE, this::answerGive);
    }

    public void disconnect() {
        NetworkBus.Subscription current = answering;
        if (current != null) {
            current.close();
        }
        answering = null;
        players = null;
        calls = null;
    }

    void timeout(Duration fresh) {
        this.timeout = fresh;
    }

    /**
     * Hands tokens to somebody on another server, asked of that server: it checks the player is there, and the
     * answer decides what {@code giver} is told.
     *
     * @return whether they were handed over
     */
    public CompletableFuture<Boolean> giveElsewhere(CommandSender giver, NetworkWho.Found to, Kind kind, int amount) {
        NetworkCalls network = calls;
        if (network == null) {
            messages.send(giver, "essentials.tokens.not-online", "player", to.name());
            return CompletableFuture.completedFuture(false);
        }
        return network.ask(to.server(), GIVE, Map.of("player", to.id().toString(), "kind", kind.name(),
                "amount", String.valueOf(amount)), timeout).thenApply(answer -> {
            Optional<Map<String, String>> given = answer.filter(reply -> "true".equals(reply.get("ok")));
            if (given.isEmpty()) {
                messages.send(giver, "essentials.tokens.not-online", "player", to.name());
                return false;
            }
            messages.send(giver, "essentials.tokens.given", "amount", amount, "token", shown(kind),
                    "player", given.get().getOrDefault("name", to.name()));
            return true;
        });
    }

    private CompletableFuture<Map<String, String>> answerGive(String from, Map<String, String> question) {
        NetworkPlayers network = players;
        Map<String, String> no = Map.of("ok", "false");
        if (network == null || network.servers().stream().map(NetworkServers.Server::id).noneMatch(from::equals)) {
            return CompletableFuture.completedFuture(no);
        }
        Player to;
        Kind kind;
        int amount;
        try {
            to = server.getPlayer(UUID.fromString(question.getOrDefault("player", "")));
            kind = Kind.valueOf(question.getOrDefault("kind", ""));
            amount = Math.max(1, Math.min(64, Integer.parseInt(question.getOrDefault("amount", "1"))));
        } catch (IllegalArgumentException garbled) {
            return CompletableFuture.completedFuture(no);
        }
        if (to == null) {
            return CompletableFuture.completedFuture(no);
        }
        CompletableFuture<Map<String, String>> done = new CompletableFuture<>();
        Player target = to;
        Scheduling.entity(plugin, target, () -> {
            if (!hand(target, kind, amount)) {
                done.complete(no);
                return;
            }
            messages.send(target, "essentials.tokens.received", "amount", amount, "token", shown(kind));
            done.complete(Map.of("ok", "true", "name", target.getName()));
        }, () -> done.complete(no));
        return done;
    }

    @Override
    public String describe() {
        return "clear-sky and dawn tokens";
    }
}
