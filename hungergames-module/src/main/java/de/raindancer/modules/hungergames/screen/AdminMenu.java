package de.raindancer.modules.hungergames.screen;

import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.prompt.ChatPrompts;
import de.raindancer.modules.hungergames.model.BorderPhaseConfig;
import de.raindancer.modules.hungergames.model.GamePhase;
import de.raindancer.modules.hungergames.service.DeathmatchService;
import de.raindancer.modules.hungergames.service.GameControlService;
import de.raindancer.modules.hungergames.service.MannequinSimService;
import de.raindancer.modules.hungergames.service.MonsterWaveService;
import de.raindancer.modules.hungergames.service.PreflightCheckService;
import de.raindancer.modules.hungergames.service.RoundLogService;
import de.raindancer.modules.hungergames.service.SponsorTokenService;
import de.raindancer.modules.hungergames.service.SpectatorService;
import de.raindancer.modules.hungergames.service.SupplyDropService;
import de.raindancer.modules.hungergames.service.VirtualTime;
import de.raindancer.modules.hungergames.store.GameSession;
import de.raindancer.modules.hungergames.util.PermissionNodes;
import de.raindancer.core.ui.menu.Menu;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * {@code /hg admin} — the hub a whole tournament is run from.
 *
 * <h2>Why every tile is drawn, greyed or not</h2>
 * A gamemaster who cannot press the admin-only tiles still sees they exist, and why not — see
 * {@link IHungerGamesScreen}'s note on "greyed, never hidden". This is the largest menu the module opens
 * directly, and a page whose shape changes with who is looking is one nobody can be talked through over
 * voice while forty people wait.
 *
 * <h2>Why this holds every one of the module's services</h2>
 * Nothing here decides anything on its own; it only constructs whichever child page a tile names, and each
 * child needs its own subset of what this class was handed. Splitting that subset out per tile rather than
 * threading a single held-together bundle keeps every child's own constructor a plain, readable list of
 * what it actually uses — the same shape {@code ModerationScreen}'s services holder would have taken, if
 * this module had one built yet.
 *
 * <h2>What this hub deliberately does not link to</h2>
 * Teams, the sponsor shop and the border-conflict page are reached through
 * {@link de.raindancer.modules.hungergames.IHungerGamesScreensOpener}'s own doors
 * ({@code teams}, {@code shop}, {@code borderConflict}), not by a tile here — those pages belong to a
 * different wave of this port, and this hub does not need to know their class names to open correctly for
 * the tiles that <em>are</em> its own.
 */
public final class AdminMenu extends Menu implements IHungerGamesScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final GameSession session;
    private final GameControlService gameControl;
    private final PreflightCheckService preflight;
    private final Supplier<List<BorderPhaseConfig>> borderPhases;
    private final DeathmatchService deathmatch;
    private final Supplier<de.raindancer.modules.hungergames.HungerGamesSettings> settings;
    private final SupplyDropService supplyDrops;
    private final MonsterWaveService monsterWaves;
    private final MannequinSimService simulation;
    private final SpectatorService spectator;
    private final GamemasterMenu.Gamemasters gamemasters;
    private final ChatPrompts prompts;
    private final RoundLogService roundLog;
    private final VirtualTime virtualTime;
    private final SponsorTokenService sponsorTokens;

    public AdminMenu(Player viewer, Brand brand, GameSession session, GameControlService gameControl,
                     PreflightCheckService preflight, Supplier<List<BorderPhaseConfig>> borderPhases,
                     DeathmatchService deathmatch,
                     Supplier<de.raindancer.modules.hungergames.HungerGamesSettings> settings,
                     SupplyDropService supplyDrops, MonsterWaveService monsterWaves,
                     MannequinSimService simulation, SpectatorService spectator,
                     GamemasterMenu.Gamemasters gamemasters, ChatPrompts prompts, RoundLogService roundLog,
                     VirtualTime virtualTime, SponsorTokenService sponsorTokens) {
        super(viewer, brand, null);
        this.session = session;
        this.gameControl = gameControl;
        this.preflight = preflight;
        this.borderPhases = borderPhases;
        this.deathmatch = deathmatch;
        this.settings = settings;
        this.supplyDrops = supplyDrops;
        this.monsterWaves = monsterWaves;
        this.simulation = simulation;
        this.spectator = spectator;
        this.gamemasters = gamemasters;
        this.prompts = prompts;
        this.roundLog = roundLog;
        this.virtualTime = virtualTime;
        this.sponsorTokens = sponsorTokens;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<gold>HungerGames Admin");
    }

    @Override
    public String breadcrumb() {
        return "Admin";
    }

    @Override
    protected void render() {
        boolean admin = PermissionNodes.isAdmin(viewer);
        boolean gamemaster = admin || gamemasters.isGamemaster(viewer.getUniqueId());
        GamePhase phase = session.phase();

        set(4, statusItem(phase));

        tile(10, Material.CLOCK, "Game control", true,
                click -> new GameControlMenu(viewer, brand(), this, session, gameControl, preflight,
                        borderPhases, simulation, monsterWaves).open(),
                "Status, init, start-up, start,", "end and reset.");

        tile(12, Material.PLAYER_HEAD, "Tributes", admin,
                click -> new TributesMenu(viewer, brand(), this, session).open(),
                "Register, revive, eliminate,", "or remove a tribute.");

        tile(14, Material.COMPASS, "Gamemasters", gamemaster,
                click -> new GamemasterMenu(viewer, brand(), this, gamemasters, session, spectator, prompts)
                        .open(),
                "Name list, activation,", "and mode.");

        tile(16, Material.ENDER_EYE, "Spectate", true,
                click -> new SpectateMenu(viewer, brand(), session, spectator).open(),
                "Teleport to a living tribute.");

        tile(29, Material.CHEST_MINECART, "Supply drops", true,
                click -> new SupplyDropMenu(viewer, brand(), this, supplyDrops, settings).open(),
                supplyDrops.statusLine(), "Manual drop, schedule status.");

        tile(31, Material.NETHERITE_SWORD, "Deathmatch", true,
                click -> new DeathmatchMenu(viewer, brand(), this, deathmatch, settings).open(),
                deathmatch.state().toString(), "Trigger, or cancel the warning.");

        tile(33, Material.ZOMBIE_HEAD, "Monster waves", true,
                click -> new MonsterWaveMenu(viewer, brand(), this, monsterWaves).open(),
                monsterWaves.activeSeries() + " running series.", "Start a wave at your position.");

        tile(37, Material.ARMOR_STAND, "Test simulation", admin,
                click -> new SimulationMenu(viewer, brand(), this, simulation, session).open(),
                simulation.mannequinCount() + " mannequin(s) active.", "Rehearse a round alone.");

        tile(39, Material.REDSTONE, "System & debug", admin,
                click -> new SystemDebugMenu(viewer, brand(), this, session, roundLog, virtualTime,
                        sponsorTokens).open(),
                "Round speed, the round log,", "sponsor-token test.");
    }

    private void tile(int slot, Material icon, String label, boolean allowed,
                      java.util.function.Consumer<org.bukkit.event.inventory.InventoryClickEvent> onClick,
                      String... lore) {
        List<String> full = new ArrayList<>(List.of(lore));
        if (!allowed) {
            full.add("<red>Requires " + PermissionNodes.ADMIN + " or " + PermissionNodes.GAMEMASTER);
        }
        var button = Icons.of(allowed ? icon : Material.GRAY_STAINED_GLASS_PANE,
                (allowed ? "<yellow>" : "<dark_gray>") + label, full);
        if (allowed) {
            set(slot, button, onClick);
        } else {
            set(slot, Icons.locked(button, "Not yours to open"));
        }
    }

    private org.bukkit.inventory.ItemStack statusItem(GamePhase phase) {
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Phase: " + phaseColour(phase) + phase);
        lore.add("<gray>Tributes: <yellow>" + session.participants().aliveCount() + " alive / "
                + session.participants().all().size() + " registered");
        session.winner().ifPresent(winner -> lore.add("<gold>A winner has been decided — reset for a "
                + "rematch."));
        return Icons.of(Material.NETHER_STAR, "<gold>HungerGames — Status", lore);
    }

    /** The phase's own colour, for the status head — pure, and worth testing on its own. */
    public static String phaseColour(GamePhase phase) {
        return switch (phase) {
            case NOT_INITIALIZED -> "<dark_gray>";
            case PREFLIGHT, LOBBY, STARTUP -> "<yellow>";
            case READY -> "<aqua>";
            case RUNNING -> "<green>";
            case FINISHED -> "<gold>";
        };
    }

    @Override
    public String describe() {
        return "the hub a whole tournament is run from";
    }
}
