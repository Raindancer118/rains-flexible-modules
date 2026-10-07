package de.raindancer.modules.essentials.service;

import de.raindancer.core.content.items.CustomItem;
import de.raindancer.core.content.items.CustomItems;
import de.raindancer.core.content.items.ItemAbilities;
import de.raindancer.core.content.items.ItemAbility;
import de.raindancer.core.content.items.ItemFactory;
import de.raindancer.core.content.items.ItemTrigger;
import de.raindancer.core.content.items.TaggedItems;
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

import java.util.List;
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
        CustomItems registered = items;
        CustomItem token = registered == null ? definition(kind)
                : registered.byKey(kind.key()).orElse(definition(kind));
        Optional<ItemStack> made = factory.create(token, amount);
        if (made.isEmpty()) {
            messages.send(giver, "essentials.tokens.cannot-make");
            return;
        }
        TaggedItems.handTo(to, made.get());
        String shown = kind == Kind.SKY ? "Clear Sky Token" : "Dawn Token";
        messages.send(giver, "essentials.tokens.given", "amount", amount, "token", shown,
                "player", to.getName());
        if (giver != to) {
            messages.send(to, "essentials.tokens.received", "amount", amount, "token", shown);
        }
    }

    @Override
    public String describe() {
        return "clear-sky and dawn tokens";
    }
}
