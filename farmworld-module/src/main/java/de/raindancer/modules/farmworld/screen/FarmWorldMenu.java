package de.raindancer.modules.farmworld.screen;

import de.raindancer.core.world.time.Times;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.modules.farmworld.FarmWorldServices;
import de.raindancer.modules.farmworld.FarmWorldSettings;
import de.raindancer.modules.farmworld.model.FarmWorldView;
import de.raindancer.modules.farmworld.model.Scatter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One farm world: what it is, how long it has left, and the way in.
 *
 * <h2>Why the list does not send people straight there</h2>
 * Because going costs a warm-up and then a wait before they can go again, and both of those are decisions
 * somebody makes on information that does not fit in a lore line: how long the world has left, whether it
 * has its own nether, whether their dog is coming with them. A list that teleported on the first click would
 * be a list where the way to find those out is to go and see.
 *
 * <h2>The page is three things</h2>
 * What it is, the way in, and — for an admin — the door to changing it. Nothing else: a farm world has no
 * settings of its own that a player can act on, and a page padded out to look full is a page where the one
 * button that matters is harder to find.
 */
public final class FarmWorldMenu extends Menu implements IFarmWorldScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final FarmWorldServices services;
    private final String name;

    public FarmWorldMenu(FarmWorldServices services, Player viewer, Menu parent, String name) {
        super(viewer, services.brand(), parent);
        this.services = services;
        this.name = name;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>" + breadcrumb());
    }

    @Override
    public String breadcrumb() {
        return name == null ? "A farm world" : name;
    }

    @Override
    protected void render() {
        FarmWorldView farm = services.catalogue().byName(name).orElse(null);
        if (farm == null) {
            // Forgotten while the page was open — an admin on another screen, or a reload. Said rather than
            // drawn as an empty page, which reads as the menu being broken.
            band(MenuLayout.RULES, 4, Icons.of(Material.BARRIER, "<red>It is not there any more",
                    "<gray>Somebody took this farm world off the list",
                    "<gray>while you were looking at it."));
            return;
        }

        band(MenuLayout.WHO, 4, Icons.of(Material.GRASS_BLOCK, "<white>" + farm.name(), whatItIs(farm)));

        drawTheWayIn(farm);

        band(MenuLayout.RULES, 5, Icons.of(Material.LEAD, "<white>What comes with you",
                whatTravels(services.config())));

        if (services.access().mayManage(viewer::hasPermission)) {
            band(MenuLayout.LAND, 4, Icons.of(Material.COMPARATOR, "<white>Change this farm world",
                            "<gray>How often it is thrown away, how far it",
                            "<gray>reaches, and whether it has its own nether.",
                            "<dark_gray>Only you and the other admins see this."),
                    click -> services.screens().manage(viewer, farm.name()));
        }
    }

    /**
     * The button that actually sends somebody, greyed with the reason when it cannot.
     *
     * <p>Four reasons, each said differently, because each is a different thing for the player to do about
     * it: nothing, ask for the rank, come back in a minute, come back when the world is loaded. One
     * "you cannot go" covering all four is the message that produces a ticket.
     */
    private void drawTheWayIn(FarmWorldView farm) {
        String refusal = services.access().refusalKey(farm.name(), viewer::hasPermission);
        if (refusal != null) {
            // The reason is the module's own wording, flattened — so an owner who has reworded the refusal
            // sees their words on the greyed button as well as in chat.
            band(MenuLayout.RULES, 3, false, goButton(farm),
                    PlainTextComponentSerializer.plainText()
                            .serialize(services.messages().get(refusal, "name", farm.name())),
                    click -> {
                    });
            return;
        }
        if (!farm.loaded()) {
            band(MenuLayout.RULES, 3, false, goButton(farm),
                    "Its world is not loaded right now", click -> {
                    });
            return;
        }
        Optional<Duration> left = services.travelling().waits().remaining(viewer.getUniqueId());
        if (left.isPresent()) {
            band(MenuLayout.RULES, 3, false, goButton(farm),
                    "You can go again in " + Times.describe(left.get()), click -> {
                    });
            return;
        }
        band(MenuLayout.RULES, 3, goButton(farm), click -> {
            // Closed first: the trip can take a few seconds of standing still, and a window open over it is
            // a window the player has to shut before they can be seen to have moved.
            viewer.closeInventory();
            services.travelling().go(viewer, farm);
        });
    }

    private ItemStack goButton(FarmWorldView farm) {
        List<String> lore = new ArrayList<>();
        FarmWorldSettings now = services.config();
        Scatter scatter = now.scatter().within(farm.border().orElse(null));
        if (scatter.isOn()) {
            lore.add("<gray>Somewhere nobody has been, between");
            lore.add("<gray><white>" + scatter.nearest() + "</white> and <white>"
                    + scatter.furthest() + "</white> blocks from the middle.");
        } else {
            lore.add("<gray>To this farm world's own spawn.");
            lore.add("<dark_gray>Everybody arrives at the same place here,");
            lore.add("<dark_gray>so the ground around it will be bare.");
        }
        lore.add("");
        if (now.warmup() > 0) {
            lore.add("<gray>Stand still for <white>" + now.warmup() + "s</white> once you click.");
        }
        if (now.cooldown() > 0) {
            lore.add("<gray>Then <white>" + Times.describe(now.cooldownFor())
                    + "</white> before you can go again.");
        }
        lore.add("<gray>Click to go.");
        return Icons.of(Material.ENDER_PEARL, "<white>Go there", lore);
    }

    private List<String> whatItIs(FarmWorldView farm) {
        List<String> lore = new ArrayList<>();
        for (String world : farm.worlds()) {
            lore.add("<dark_gray>" + world);
        }
        farm.border().ifPresentOrElse(
                radius -> lore.add("<dark_gray>" + radius + " blocks from the middle"),
                () -> lore.add("<dark_gray>No border"));
        lore.add("");
        if (!farm.isScheduled()) {
            lore.add("<gray>Kept until somebody throws it away.");
            lore.add("<dark_gray>Which they can, at any time.");
            return lore;
        }
        farm.every().ifPresent(every ->
                lore.add("<dark_gray>Thrown away every " + Times.describe(every)));
        farm.untilRegenerated().ifPresentOrElse(
                left -> lore.add("<yellow>" + Times.describe(left) + " left"),
                () -> lore.add("<red>Due to be thrown away"));
        lore.add("<dark_gray>Everything in it goes when it is — what you");
        lore.add("<dark_gray>want to keep does not belong here.");
        return lore;
    }

    /**
     * What travels with the player, read off the settings.
     *
     * <p>On the page because it is the one thing people get wrong: somebody who walks their horse to the
     * portal and arrives without it assumes the plugin lost it.
     */
    private static List<String> whatTravels(FarmWorldSettings now) {
        List<String> lore = new ArrayList<>();
        if (!now.bringWhatYouLead()) {
            lore.add("<gray>Nothing. You arrive on your own.");
            lore.add("<dark_gray>Whatever you were leading stays where you were.");
            return lore;
        }
        lore.add("<gray>What you are leading, the boat you are");
        lore.add("<gray>towing, and whatever is riding with you.");
        if (now.bringNearbyPets()) {
            lore.add("<gray>Your own tame animals within <white>"
                    + now.bringRadius() + "</white> blocks as well.");
        }
        lore.add("<dark_gray>Never another player, and never somebody");
        lore.add("<dark_gray>else's animals, whatever the settings say.");
        lore.add("<dark_gray>At most " + now.bringAtMost() + " at once.");
        return lore;
    }

    @Override
    protected List<String> helpLines() {
        return services.messages().lines("farmworlds.manual.one",
                        "warmup", services.config().warmup(),
                        "cooldown", services.config().cooldown())
                .stream().map(MINI::serialize).toList();
    }

    @Override
    public String describe() {
        return "one farm world, and the way into it";
    }
}
