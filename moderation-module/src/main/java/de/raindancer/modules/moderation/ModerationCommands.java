package de.raindancer.modules.moderation;

import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.modules.api.ModuleCommand;
import de.raindancer.modules.moderation.command.HistoryCommand;
import de.raindancer.modules.moderation.command.InvseeCommand;
import de.raindancer.modules.moderation.command.KickCommand;
import de.raindancer.modules.moderation.command.LiftCommand;
import de.raindancer.modules.moderation.command.DemoteCommand;
import de.raindancer.modules.moderation.command.ModerationCommand;
import de.raindancer.modules.moderation.command.PromoteCommand;
import de.raindancer.modules.moderation.command.ProtectCommand;
import de.raindancer.modules.moderation.command.PunishCommand;
import de.raindancer.modules.moderation.command.SelfToolCommand;
import de.raindancer.modules.moderation.command.VitalsCommand;
import de.raindancer.modules.moderation.command.ReportCommand;
import de.raindancer.modules.moderation.command.ReportsCommand;
import de.raindancer.modules.moderation.command.StaffChatCommand;
import de.raindancer.modules.moderation.command.VanishCommand;
import de.raindancer.modules.moderation.command.WarnCommand;
import de.raindancer.modules.moderation.command.WorldToolCommands;
import de.raindancer.modules.moderation.command.WorldToolsCommand;
import de.raindancer.modules.moderation.model.ModerationPermission;

import java.util.List;

/**
 * The commands, built at bootstrap and pointed at services that do not exist yet.
 *
 * <h2>Why this class exists at all</h2>
 * Paper fires its {@code COMMANDS} lifecycle event during the bootstrap phase — before the plugin
 * object exists, let alone this module's services. A handler registered in {@code onEnable} never runs
 * at all: no warning, no exception, the command simply does not exist. So the handlers must be built
 * early and must not capture anything.
 *
 * <p>Hence the supplier. The commands hold a way to <em>ask</em> for the services, and {@link #ready}
 * fills it in when the module enables. Between the two, {@code ModuleCommands.guarded} answers with one
 * red line saying the module has not started rather than a {@link NullPointerException} in the console.
 *
 * <h2>Taking over the vanilla names</h2>
 * {@code /ban}, {@code /pardon} and {@code /kick} already exist. Registering ours under the same names
 * is deliberate: a server should have one ban command, not two that write to different places. What
 * keeps that honest is Core's {@code VanillaBanBridge} — every ban made here is written to the server's
 * own list as well, so vanilla {@code /banlist} still agrees and the ban survives this module being
 * removed.
 */
public final class ModerationCommands {

    private static volatile ModerationServices services;

    private ModerationCommands() {
    }

    /**
     * What the module declares at bootstrap.
     *
     * <p>Cheap, repeatable and dependent on nothing — Paper may ask more than once, and it asks before
     * {@code Bukkit.getServer()} answers anything useful.
     */
    public static List<ModuleCommand> declared() {
        return List.of(
                ModuleCommand.of("mod", "Everything about a player, and the pages behind it",
                        new ModerationCommand(ModerationCommands::require)),

                // Guarded by TEMPBAN, the *lower* of the two ban nodes — deliberately. A mod holds
                // tempban and an admin holds both, so both reach the command, and BanLimitRule is what
                // decides how long each of them may ban for. Guarded by BAN instead, a mod would be
                // refused at the door and could not hand out the day they are trusted with.
                ModuleCommand.of("ban", "Bans somebody — mods for a limited time, admins for any",
                                new PunishCommand(ModerationCommands::require, PunishmentKind.BAN,
                                        ModerationPermission.TEMPBAN))
                        .aliased("tempban"),
                ModuleCommand.of("unban", "Lifts a ban, leaving it on the record",
                                new LiftCommand(ModerationCommands::require, PunishmentKind.BAN,
                                        ModerationPermission.TEMPBAN))
                        .aliased("pardon")
                        .taking("<player> — lift their ban")
                        .needing(ModerationPermission.TEMPBAN.node()),

                ModuleCommand.of("mute", "Stops somebody talking",
                                new PunishCommand(ModerationCommands::require, PunishmentKind.MUTE,
                                        ModerationPermission.MUTE))
                        .aliased("tempmute")
                        .taking("<player> [length] [reason] — 90m, 3d, perm")
                        .needing(ModerationPermission.MUTE.node()),
                ModuleCommand.of("unmute", "Lets them talk again",
                                new LiftCommand(ModerationCommands::require, PunishmentKind.MUTE,
                                        ModerationPermission.MUTE))
                        .taking("<player>")
                        .needing(ModerationPermission.MUTE.node()),

                ModuleCommand.of("freeze", "Stops somebody building while you talk to them",
                                new PunishCommand(ModerationCommands::require, PunishmentKind.FREEZE,
                                        ModerationPermission.FREEZE))
                        .taking("<player> [length] [reason]")
                        .needing(ModerationPermission.FREEZE.node()),
                ModuleCommand.of("unfreeze", "Lets them build again",
                                new LiftCommand(ModerationCommands::require, PunishmentKind.FREEZE,
                                        ModerationPermission.FREEZE))
                        .taking("<player>")
                        .needing(ModerationPermission.FREEZE.node()),

                ModuleCommand.of("kick", "Throws somebody off, once",
                                new KickCommand(ModerationCommands::require))
                        .taking("<player> [reason]")
                        .needing(ModerationPermission.KICK.node()),
                ModuleCommand.of("warn", "Puts a warning on somebody's record",
                                new WarnCommand(ModerationCommands::require))
                        .taking("<player> [reason]")
                        .needing(ModerationPermission.WARN.node()),
                ModuleCommand.of("history", "What has happened to somebody",
                                new HistoryCommand(ModerationCommands::require))
                        .taking("<player>")
                        .needing(ModerationPermission.HISTORY.node()),

                ModuleCommand.of("vanish", "Makes you invisible to players",
                                new VanishCommand(ModerationCommands::require))
                        .taking("[player] — somebody else, if you may")
                        .needing(ModerationPermission.VANISH.node()),
                ModuleCommand.of("invsee", "Opens somebody's inventory, online or not",
                                new InvseeCommand(ModerationCommands::require))
                        .taking("<player>")
                        .needing(ModerationPermission.INVSEE.node()),

                ModuleCommand.of("report", "Tells the staff about somebody",
                                new ReportCommand(ModerationCommands::require))
                        .taking("<player> <what happened>"),
                ModuleCommand.of("reports", "The report queue",
                                new ReportsCommand(ModerationCommands::require))
                        .taking("list — everything open", "<id> — one of them")
                        .needing(ModerationPermission.REPORTS.node()),
                ModuleCommand.of("staffchat", "Talks to the staff rather than the server",
                                new StaffChatCommand(ModerationCommands::require))
                        .taking("[message] — or nothing, to stay in it")
                        .needing(ModerationPermission.STAFF_CHAT.node()),

                // Typed, for the times somebody knows what they want: /vein diamond_ore 40 is one
                // line for four clicks and two screens, and an event is run twenty times over.
                ModuleCommand.of("vein", "Bury a vein of ore where you are looking",
                                new WorldToolCommands.Vein(ModerationCommands::require))
                        .taking("[ore] [size] — iron_ore 12 by default")
                        .needing(ModerationPermission.SPAWN_ORE.node()),
                ModuleCommand.of("mob", "Call up a pack or a wave where you are looking",
                                new WorldToolCommands.Mob(ModerationCommands::require))
                        .taking("pack <creature> [how many]",
                                "wave <creature> [how many] [packs] [seconds]",
                                "stop — ends your wave")
                        .needing(ModerationPermission.SPAWN_MOBS.node()),

                ModuleCommand.of("worldtools",
                                "Bury ore, or call up creatures, where you are looking",
                                new WorldToolsCommand(ModerationCommands::require))
                        .aliased("wtools")
                        .needing(ModerationPermission.SPAWN_ORE.node()),

                // The console's own pair. Not grantable, not in a menu, not a permission: the shield
                // that stops one moderator acting on another must not be handed out by the people it
                // is aimed at. See ProtectCommand.
                // Given a node nobody is ever granted, purely so the directory does not advertise a
                // console command to every player reading the book. See ProtectCommand: the command
                // itself refuses anybody who is not the console, node or no node.
                ModuleCommand.of("protect", "Protects an account from moderators — console only",
                                new ProtectCommand(ModerationCommands::require, true))
                        .taking("<player>")
                        .needing("rainsmoderation.console-only"),
                ModuleCommand.of("unprotect", "Takes that protection off again — console only",
                                new ProtectCommand(ModerationCommands::require, false))
                        .taking("<player>")
                        .needing("rainsmoderation.console-only"),

                // Ops only, and deliberately not grantable by any rank: a power that hands out powers
                // must not be one of the powers it hands out, or the lowest tier is one promotion away
                // from the highest.
                ModuleCommand.of("promote", "Makes somebody staff at one of the four ranks",
                                new PromoteCommand(ModerationCommands::require))
                        .taking("<player> [rank]")
                        // A node nothing registers and nothing grants, which is exactly the filter
                        // wanted: hasPermission answers true for an operator and false for everybody
                        // else, so the book shows these two to the people who can actually run them.
                        .needing("rainsmoderation.promote"),
                ModuleCommand.of("demote", "Takes somebody down a rank, or off the staff",
                                new DemoteCommand(ModerationCommands::require))
                        .taking("<player>")
                        .needing("rainsmoderation.promote"),

                // The tools a moderator points at themselves, or at somebody else by naming them.
                // /god toggles and /ungod switches off: "make sure this is off" is a thing somebody
                // needs to be able to say without checking first, and a toggle answers "it is on now".
                // Events rather than states, so not SelfToolCommand — see VitalsCommand.
                ModuleCommand.of("heal", "Restores somebody to full health",
                        new VitalsCommand(ModerationCommands::require, VitalsCommand.Vital.HEAL))
                        .taking("[player] — yourself if you name nobody")
                        .needing(ModerationPermission.HEAL.node()),
                ModuleCommand.of("feed", "Fills somebody's hunger bar",
                        new VitalsCommand(ModerationCommands::require, VitalsCommand.Vital.FEED))
                        .taking("[player] — yourself if you name nobody")
                        .needing(ModerationPermission.FEED.node()),
                ModuleCommand.of("hurt", "Takes half of somebody's health",
                        new VitalsCommand(ModerationCommands::require, VitalsCommand.Vital.HURT))
                        .taking("[player] — yourself if you name nobody")
                        .needing(ModerationPermission.HURT.node()),
                ModuleCommand.of("starve", "Empties most of somebody's hunger bar",
                        new VitalsCommand(ModerationCommands::require, VitalsCommand.Vital.STARVE))
                        .taking("[player] — yourself if you name nobody")
                        .needing(ModerationPermission.STARVE.node()),
                ModuleCommand.of("fly", "Lets somebody fly",
                        new SelfToolCommand(ModerationCommands::require,
                                SelfToolCommand.Tool.FLY, null))
                        .taking("[player] — yourself if you name nobody")
                        .needing(ModerationPermission.FLY.node()),
                ModuleCommand.of("god", "Makes somebody invulnerable",
                                new SelfToolCommand(ModerationCommands::require,
                                        SelfToolCommand.Tool.GOD, null))
                        .aliased("godmode")
                        .taking("[player] — yourself if you name nobody")
                        .needing(ModerationPermission.GOD.node()),
                ModuleCommand.of("ungod", "Makes them mortal again",
                        new SelfToolCommand(ModerationCommands::require,
                                SelfToolCommand.Tool.GOD, false))
                        .taking("[player]")
                        .needing(ModerationPermission.GOD.node()),
                ModuleCommand.of("instakill", "Everything they hit dies in one hit",
                                new SelfToolCommand(ModerationCommands::require,
                                        SelfToolCommand.Tool.INSTAKILL, null))
                        .aliased("oneshot")
                        .taking("[player]")
                        .needing(ModerationPermission.INSTAKILL.node()),
                ModuleCommand.of("instabreak", "Every block breaks the moment they hit it",
                                new SelfToolCommand(ModerationCommands::require,
                                        SelfToolCommand.Tool.INSTABREAK, null))
                        .aliased("fastbreak")
                        .taking("[player]")
                        .needing(ModerationPermission.INSTABREAK.node()));
    }

    /** Called when the module enables, after which the commands work. */
    static void ready(ModerationServices live) {
        services = live;
    }

    /** Called when it stops, so a command run afterwards refuses rather than using half-shut services. */
    static void stopped() {
        services = null;
    }

    /** Whether the module is up. For the guard, and for a diagnostic that asks why a command refused. */
    public static boolean isRunning() {
        return services != null;
    }

    /**
     * The services, or an exception naming the real problem.
     *
     * <p>Should be unreachable: the commands are guarded, so nobody can get this far while the module
     * is not running. If it ever does throw, the message is the useful half — "not started" rather than
     * a null dereference forty frames deep in a menu.
     */
    private static ModerationServices require() {
        ModerationServices live = services;
        if (live == null) {
            throw new IllegalStateException("the moderation module is not running");
        }
        return live;
    }
}
