package de.raindancer.modules.playerutils.screen;

import de.raindancer.core.moderation.players.BodyState;
import de.raindancer.core.platform.command.PlayerTargets;
import de.raindancer.core.ui.choose.AmountChooser;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.ui.profile.ProfileMenu;
import de.raindancer.modules.playerutils.PlayerUtilsServices;
import de.raindancer.modules.playerutils.model.Action;
import de.raindancer.modules.playerutils.model.Reading;
import de.raindancer.modules.playerutils.model.Verdict;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@code /player <name>}: every action for one player on one page, each greyed with the reason when the
 * viewer may not — and what state they are in right now on their head.
 */
public final class PlayerToolsMenu extends Menu implements IPlayerUtilsScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final PlayerUtilsServices services;
    private final UUID targetId;

    public PlayerToolsMenu(PlayerUtilsServices services, Player viewer, Menu parent, UUID target) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.targetId = target;
    }

    @Override
    protected Component title() {
        Player target = services.server().getPlayer(targetId);
        return MINI.deserialize("<dark_gray>" + de.raindancer.core.ui.text.Text.literal(
                target == null ? "Gone" : PlayerTargets.shownName(target)));
    }

    @Override
    public String breadcrumb() {
        return "Player tools";
    }

    @Override
    protected void render() {
        Player target = services.server().getPlayer(targetId);
        if (target == null) {
            band(MenuLayout.WHO, 4, Icons.of(Material.SKELETON_SKULL, "<gray>They left",
                    "<gray>Tools only work on somebody who is here."));
            band(MenuLayout.RULES, 4, Icons.of(Material.BOOK, "<white>Their profile", "",
                            "<dark_gray>Click to open it."),
                    click -> new ProfileMenu(viewer, brand(), this, targetId,
                            PlayerTargets.shownName(services.server().getOfflinePlayer(targetId))).open());
            return;
        }

        // Vitals
        simple(MenuLayout.WHO, 1, target, Action.HEAL, "<green>Heal", "Full health, fire out, bad effects off.");
        simple(MenuLayout.WHO, 2, target, Action.FEED, "<green>Feed", "Full hunger and saturation.");
        simple(MenuLayout.WHO, 3, target, Action.BREATHE, "<aqua>Breathe", "Full lungs.");
        band(MenuLayout.WHO, 4, head(target));
        simple(MenuLayout.WHO, 5, target, Action.EXTINGUISH, "<aqua>Put out", "No more fire.");
        amount(MenuLayout.WHO, 6, target, Action.DAMAGE, "<red>Damage", "Hearts to take — never all of them.",
                "Hearts", 5, 1, 19, value -> new String[]{String.valueOf(value)});
        amount(MenuLayout.WHO, 7, target, Action.STARVE, "<gold>Starve", "Set the hunger bar.",
                "Drumsticks", 0, 0, 10, value -> new String[]{String.valueOf(value)});

        // Movement
        boolean flying = target.getAllowFlight();
        button(MenuLayout.RULES, 1, target, Action.FLY,
                Icons.of(flying ? Material.ELYTRA : Material.FEATHER, flying ? "<green>Flight: on" : "<gray>Flight: off",
                        "<gray>Given flight lasts through relogs,", "<gray>deaths and gamemode changes.", "",
                        "<dark_gray>Click to " + (flying ? "take it away." : "give it.")),
                new String[]{flying ? "off" : "on"});
        BodyState body = services.actions().body(target).orElse(null);
        int walk = body == null ? 1 : services.speedRule().walkLevel(body.walkSpeed());
        int fly = body == null ? 1 : services.speedRule().flyLevel(body.flySpeed());
        amount(MenuLayout.RULES, 2, target, Action.SPEED, "<white>Walking speed: " + walk, "0 to 10, 1 is normal.",
                "Walking speed", walk, 0, 10, value -> new String[]{"walk", String.valueOf(value)});
        amount(MenuLayout.RULES, 3, target, Action.SPEED, "<white>Flying speed: " + fly, "0 to 10, 1 is normal.",
                "Flying speed", fly, 0, 10, value -> new String[]{"fly", String.valueOf(value)});
        int percent = body == null ? 100 : (int) Math.round(body.scale() * 100);
        amount(MenuLayout.RULES, 4, target, Action.SCALE, "<white>Size: " + percent + "%",
                "7% to 1600%, 100% is normal.", "Size in %", percent, 7, 1600,
                value -> new String[]{value + "%"});
        simpleWith(MenuLayout.RULES, 5, target, Action.LAUNCH, "<white>Launch up", "Straight up, power 2.",
                new String[]{"up", "2"});
        simpleWith(MenuLayout.RULES, 6, target, Action.LAUNCH, "<white>Launch forward",
                "The way they face, power 3.", new String[]{"forward", "3"});
        simple(MenuLayout.RULES, 7, target, Action.SPECTATE, "<light_purple>Spectate",
                "Watch through their eyes; /spectate stop to come back.");

        // Information and the rest
        amount(MenuLayout.LAND, 1, target, Action.DROWN, "<blue>Drown", "Empty lungs and water damage.",
                "Hearts", 2, 0, 19, value -> new String[]{String.valueOf(value)});
        amount(MenuLayout.LAND, 2, target, Action.IGNITE, "<gold>Set alight", "For this many seconds.",
                "Seconds", 5, 1, 300, value -> new String[]{String.valueOf(value)});
        Verdict effects = services.targeting().verdict(viewer, Action.EFFECTS, target);
        band(MenuLayout.LAND, 3, effects.allowed(),
                Icons.of(Material.POTION, "<light_purple>Effects", "<gray>What is on them, and taking it off.", "",
                        "<dark_gray>Click to see them."),
                reason(effects, target), click -> services.screens().effects(viewer, targetId));
        info(MenuLayout.LAND, 4, target, Action.POSITION, "<white>Position");
        info(MenuLayout.LAND, 5, target, Action.PING, "<white>Ping: " + target.getPing() + " ms");
        info(MenuLayout.LAND, 6, target, Action.STATUS, "<white>Full status");
        band(MenuLayout.LAND, 7, Icons.of(Material.BOOK, "<white>Profile", "<gray>Their public profile.", "",
                        "<dark_gray>Click to open it."),
                click -> new ProfileMenu(viewer, brand(), this, targetId, PlayerTargets.shownName(target)).open());

        Verdict boom = services.targeting().verdict(viewer, Action.EXPLODE, target);
        toolbar(3, boom.allowed(), Icons.of(Material.TNT, "<red>Explode",
                        "<gray>A bang where they stand, power 2.", "<gray>No blocks broken.", "",
                        "<dark_gray>Asks first."),
                reason(boom, target),
                click -> new ConfirmScreen(services, viewer, this, "<red>Blow up "
                        + de.raindancer.core.ui.text.Text.literal(PlayerTargets.shownName(target)) + "?",
                        List.of("<gray>A power 2 explosion where they stand.", "<gray>No blocks are broken."),
                        () -> {
                            run(target, Action.EXPLODE, new String[]{"2", "confirm"});
                            open();
                        }).open());

        Verdict wipe = services.targeting().verdict(viewer, Action.WIPE, target);
        if (wipe.allowed()) {
            danger(Icons.of(Material.LAVA_BUCKET, "<red>Wipe",
                            "<gray>Inventory, advancements and experience,", "<gray>gone for good.", "",
                            "<dark_gray>Asks first."),
                    click -> new ConfirmScreen(services, viewer, this, "<red>Wipe "
                            + de.raindancer.core.ui.text.Text.literal(PlayerTargets.shownName(target)) + "?",
                            List.of("<gray>Their inventory, advancements and experience", "<gray>are taken for good."),
                            () -> {
                                run(target, Action.WIPE, new String[]{"confirm"});
                                open();
                            }).open());
        }
    }

    private ItemStack head(Player target) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + de.raindancer.core.ui.text.Text.literal(target.getName())
                + (PlayerTargets.shownName(target).equals(target.getName()) ? "" : " <dark_gray>(real name)"));
        lore.add("");
        lore.add("<red>❤ <white>" + one(target.getHealth() / 2) + "<gray> hearts");
        lore.add("<gold>🍖 <white>" + target.getFoodLevel() / 2.0 + "<gray> drumsticks");
        lore.add("<aqua>☁ <white>" + target.getRemainingAir() + "<gray>/" + target.getMaximumAir() + " air");
        lore.add("<green>✦ <white>level " + target.getLevel());
        lore.add("<gray>" + target.getGameMode().name().toLowerCase() + ", in " + target.getWorld().getName());
        lore.add("<gray>" + target.getActivePotionEffects().size() + " effect(s), ping " + target.getPing() + " ms");
        return Icons.head(targetId, "<white>" + de.raindancer.core.ui.text.Text.literal(PlayerTargets.shownName(target)), lore);
    }

    private void simple(int band, int column, Player target, Action action, String name, String what) {
        simpleWith(band, column, target, action, name, what, new String[0]);
    }

    private void simpleWith(int band, int column, Player target, Action action, String name, String what,
                            String[] arguments) {
        button(band, column, target, action, Icons.of(action.icon(), name, "<gray>" + what, "",
                "<dark_gray>Click to do it."), arguments);
    }

    private void button(int band, int column, Player target, Action action, ItemStack icon, String[] arguments) {
        Verdict verdict = services.targeting().verdict(viewer, action, target);
        band(band, column, verdict.allowed(), icon, reason(verdict, target), click -> {
            run(target, action, arguments);
            refresh();
        });
    }

    private void amount(int band, int column, Player target, Action action, String name, String what,
                        String label, int start, int min, int max,
                        java.util.function.IntFunction<String[]> arguments) {
        Verdict verdict = services.targeting().verdict(viewer, action, target);
        band(band, column, verdict.allowed(),
                Icons.of(action.icon(), name, "<gray>" + what, "", "<dark_gray>Click to choose how much."),
                reason(verdict, target),
                click -> new AmountChooser(viewer, brand(), this, label, start, min, max, value -> {
                    run(target, action, arguments.apply(value));
                    open();
                }).open());
    }

    private void info(int band, int column, Player target, Action action, String name) {
        Verdict verdict = services.targeting().verdict(viewer, action, target);
        band(band, column, verdict.allowed(),
                Icons.of(action.icon(), name, "<gray>" + action.describe() + ".", "", "<dark_gray>Click to see it in chat."),
                reason(verdict, target),
                click -> {
                    viewer.closeInventory();
                    services.info().show(viewer, action, target, 0);
                });
    }

    private void run(Player target, Action action, String[] arguments) {
        if (!target.isOnline()) {
            services.messages().send(viewer, "playerutils.not-online", "player", PlayerTargets.shownName(target));
            return;
        }
        Reading reading = services.arguments().read(action, arguments);
        services.actions().attempt(viewer, action, target, reading);
    }

    private String reason(Verdict verdict, Player target) {
        if (verdict.allowed()) {
            return "";
        }
        Object[] values = java.util.Arrays.copyOf(verdict.values(), verdict.values().length + 2);
        values[values.length - 2] = "player";
        values[values.length - 1] = PlayerTargets.shownName(target);
        return PlainTextComponentSerializer.plainText().serialize(services.messages().get(verdict.key(), values));
    }

    private static String one(double value) {
        double rounded = Math.round(value * 10) / 10.0;
        return rounded == Math.rint(rounded) ? String.valueOf((long) rounded) : String.valueOf(rounded);
    }

    @Override
    protected List<String> helpLines() {
        return List.of("<gray>Every action here is also a command:",
                "<gray>/player <name> <action> …, or /heal, /fly, /launch …",
                "<gray>Greyed buttons say why when you hover them.");
    }

    @Override
    public String describe() {
        return "every action for one player";
    }
}
