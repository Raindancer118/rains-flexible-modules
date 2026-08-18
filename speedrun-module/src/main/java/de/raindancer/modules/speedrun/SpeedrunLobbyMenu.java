package de.raindancer.modules.speedrun;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.choose.AmountChooser;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.ui.messages.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * The compass's screen: the current end conditions while the lobby is
 * {@link SpeedrunLobbyState#READY}, and a status page otherwise.
 *
 * <h2>Who may change what, and when</h2>
 * The goal and the death policy only while {@link SpeedrunLobbyState#READY} — those are read once,
 * when {@link SpeedrunLobby#start} arms a session's end conditions, so changing them mid-run would
 * silently do nothing to the run already in progress and only confuse whoever clicked. There is
 * nothing to grey for those two: a page with no editable buttons on it is a stronger guarantee than
 * one whose buttons refuse a click.
 *
 * <p>The creeper hazard — {@link #renderCreeperSettings()} — is the opposite: both listeners read
 * {@link SpeedrunSettings} fresh on every triggering event, so a change reaches a run already under
 * way immediately. Asked for explicitly, so an admin can turn the hazard down (or up) without waiting
 * for the race to end. Shown on every page regardless of {@link SpeedrunLobbyState} for that reason.
 */
public final class SpeedrunLobbyMenu extends Menu {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final SpeedrunLobby lobby;
    private final Messages messages;

    public SpeedrunLobbyMenu(SpeedrunLobby lobby, Messages messages, Brand brand, Player viewer,
                             Menu parent) {
        super(viewer, brand, parent);
        this.lobby = lobby;
        this.messages = messages;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Speedrun");
    }

    @Override
    public String breadcrumb() {
        return "Speedrun";
    }

    @Override
    protected void render() {
        switch (lobby.state()) {
            case READY -> renderReady();
            case COUNTDOWN -> renderCountdown();
            case RUNNING -> renderInProgress("Running");
            case PAUSED -> renderInProgress("Paused — nobody is here");
            case FINISHED -> renderFinished();
        }
        renderCreeperSettings();
    }

    private void renderCountdown() {
        band(MenuLayout.WHO, 4, Icons.of(Material.CLOCK, "<white>Starting…",
                "<gray>Everybody is frozen until it begins."));
    }

    private void renderReady() {
        SpeedrunSettings config = lobby.config();
        band(MenuLayout.WHO, 3,
                Icons.of(Material.WRITABLE_BOOK, "<white>Goal: " + goalLabel(config), advancementLore(config)),
                click -> new SpeedrunAdvancementChooser(lobby, messages, brand(), viewer, this).open());
        band(MenuLayout.WHO, 5,
                Icons.of(deathIcon(config.deathPolicy()), "<white>Death policy: " + config.deathPolicy(),
                        deathLore(config)),
                click -> {
                    lobby.settings().cycle("death-policy");
                    refresh();
                });
    }

    /**
     * The one part of this screen that is never lobby-only — see the class javadoc. Laid out in
     * {@link MenuLayout#RULES} and {@link MenuLayout#LAND}, which every other branch of {@link #render()}
     * leaves empty, so there is no collision with {@link #renderCountdown()}, {@link #renderInProgress}
     * or {@link #renderFinished()}.
     */
    private void renderCreeperSettings() {
        SpeedrunSettings config = lobby.config();
        band(MenuLayout.RULES, 3,
                Icons.of(config.creeperSpawnChanceOnBreakPercent() > 0 ? Material.CREEPER_HEAD : Material.BARRIER,
                        "<white>Creeper chance (break): " + config.creeperSpawnChanceOnBreakPercent() + "%",
                        "<gray>How often breaking a block spawns one.", "<dark_gray>Click to change."),
                click -> new AmountChooser(viewer, brand(), this, "Creeper chance on block break %",
                        config.creeperSpawnChanceOnBreakPercent(), 0, 100,
                        value -> {
                            lobby.settings().set("creeper-spawn-chance-on-break-percent", String.valueOf(value));
                            refresh();
                        }).open());
        band(MenuLayout.RULES, 5,
                Icons.of(Material.TNT,
                        "<white>Charged chance (break): " + config.chargedCreeperChanceOnBreakPercent() + "%",
                        "<gray>How often a block-break creeper is charged.", "<dark_gray>Click to change."),
                click -> new AmountChooser(viewer, brand(), this, "Charged chance on block break %",
                        config.chargedCreeperChanceOnBreakPercent(), 0, 100,
                        value -> {
                            lobby.settings().set("charged-creeper-chance-on-break-percent", String.valueOf(value));
                            refresh();
                        }).open());
        band(MenuLayout.LAND, 3,
                Icons.of(config.creeperSpawnChanceOnContainerPercent() > 0 ? Material.CREEPER_HEAD : Material.BARRIER,
                        "<white>Creeper chance (container): " + config.creeperSpawnChanceOnContainerPercent() + "%",
                        "<gray>How often opening one spawns a creeper.", "<dark_gray>Click to change."),
                click -> new AmountChooser(viewer, brand(), this, "Creeper chance on container open %",
                        config.creeperSpawnChanceOnContainerPercent(), 0, 100,
                        value -> {
                            lobby.settings().set("creeper-spawn-chance-on-container-percent",
                                    String.valueOf(value));
                            refresh();
                        }).open());
        band(MenuLayout.LAND, 5,
                Icons.of(Material.TNT,
                        "<white>Charged chance (container): " + config.chargedCreeperChanceOnContainerPercent() + "%",
                        "<gray>How often a container creeper is charged.", "<dark_gray>Click to change."),
                click -> new AmountChooser(viewer, brand(), this, "Charged chance on container open %",
                        config.chargedCreeperChanceOnContainerPercent(), 0, 100,
                        value -> {
                            lobby.settings().set("charged-creeper-chance-on-container-percent",
                                    String.valueOf(value));
                            refresh();
                        }).open());
    }

    private void renderInProgress(String label) {
        SpeedrunSession session = lobby.session().orElse(null);
        if (session == null) {
            return;
        }
        List<String> lore = new ArrayList<>();
        lore.add("<gray>" + session.participants().size() + " racing.");
        lore.add("<gray>" + formatted(session.elapsed()));
        band(MenuLayout.WHO, 4, Icons.of(Material.CLOCK, "<white>" + label, lore));
    }

    private void renderFinished() {
        SpeedrunSession session = lobby.session().orElse(null);
        if (session == null) {
            return;
        }
        SpeedrunOutcome outcome = session.outcome().orElse(null);
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Ended by: " + (outcome == null ? "?" : outcome.reason()));
        lore.add("<gray>Time: " + formatted(session.elapsed()));
        lore.add("");
        lore.add("<dark_gray>Resets once everybody here has left.");
        band(MenuLayout.WHO, 4, Icons.of(Material.NETHER_STAR, "<white>Finished!", lore));
    }

    private static String formatted(java.time.Duration elapsed) {
        long seconds = elapsed.getSeconds();
        return "%d:%02d".formatted(seconds / 60, seconds % 60);
    }

    private static Material deathIcon(SpeedrunDeathPolicy policy) {
        return policy == SpeedrunDeathPolicy.OFF ? Material.TOTEM_OF_UNDYING : Material.SKELETON_SKULL;
    }

    /** What the button's own name says — the whole point being that this is visible without a click. */
    private static String goalLabel(SpeedrunSettings config) {
        return config.hasAdvancementGoal()
                ? SpeedrunAdvancementChooser.friendlyName(config.advancementKey())
                : "<gray>None";
    }

    private static List<String> advancementLore(SpeedrunSettings config) {
        if (!config.hasAdvancementGoal()) {
            return List.of("<gray>None set.", "<gray>Click to pick one.");
        }
        return List.of("<gray>" + config.advancementKey(), "", "<gray>Click to change it.");
    }

    private static List<String> deathLore(SpeedrunDeathPolicy policy) {
        return switch (policy) {
            case OFF -> List.of("<gray>A death does not end the run.", "<gray>Click to cycle.");
            case ANY -> List.of("<gray>The first death ends it for everybody.", "<gray>Click to cycle.");
            case ALL -> List.of("<gray>Ends once every racer has died.", "<gray>Click to cycle.");
        };
    }

    private static List<String> deathLore(SpeedrunSettings config) {
        return deathLore(config.deathPolicy());
    }
}
