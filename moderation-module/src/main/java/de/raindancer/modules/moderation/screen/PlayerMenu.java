package de.raindancer.modules.moderation.screen;

import de.raindancer.core.moderation.invsee.Access;
import de.raindancer.core.moderation.punishment.Punishment;
import de.raindancer.core.moderation.punishment.PunishmentKind;
import de.raindancer.core.ui.menu.Icons;
import de.raindancer.core.ui.menu.Menu;
import de.raindancer.core.ui.menu.MenuLayout;
import de.raindancer.core.world.time.Times;
import de.raindancer.modules.moderation.ModerationServices;
import de.raindancer.modules.moderation.model.ModerationPermission;
import de.raindancer.modules.moderation.model.Sentence;
import de.raindancer.core.platform.util.Scheduling;
import de.raindancer.modules.moderation.model.PlayerOverview;
import de.raindancer.modules.moderation.service.LogSearchService;
import de.raindancer.modules.moderation.service.PlayerOverviewService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One player, and everything a moderator can do about them.
 *
 * <h2>Why there is a hub at all</h2>
 * Because the question a moderator actually has is "what is going on with this person", and answering
 * it from commands means four of them and remembering all four. Everything here is one click from the
 * name: what is in force, what is on the record, what the staff have written, and every button that
 * would change any of it.
 *
 * <h2>Everything is shown, including what this viewer may not press</h2>
 * A trial helper opening this sees the ban button greyed with the reason. That is deliberate: it tells
 * them what exists, what they will be trusted with next, and — the practical half — that the button is
 * there at all, so nobody has to be told "the third one along, unless you cannot see it".
 */
public final class PlayerMenu extends ModerationScreen {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private final UUID subject;
    private final String subjectName;
    /** Null until gathered; see {@link #open()}. */
    private volatile PlayerOverview overview;

    public PlayerMenu(ModerationServices services, Player viewer, Menu parent, UUID subject,
                      String subjectName) {
        super(services, viewer, parent);
        this.subject = subject;
        this.subjectName = subjectName == null || subjectName.isBlank() ? "somebody" : subjectName;
    }

    @Override
    protected Component title() {
        return MINI.deserialize("<dark_gray>Moderation — <white>" + subjectName);
    }

    @Override
    public String breadcrumb() {
        return subjectName;
    }

    /**
     * Gathers the overview first — part of it lives in databases, read off the server's threads, and where an
     * online player stands is read on their own — then opens on the viewer's thread. Asked afresh on every open,
     * so coming Back from a punishment shows it.
     */
    @Override
    public void open() {
        new PlayerOverviewService(services()).gather(subject, subjectName, gathered -> Scheduling.entity(
                services().plugin(), viewer, () -> {
                    overview = gathered;
                    super.open();
                }));
    }

    /**
     * The same slots every time, so a hand learns them:
     * <pre>
     *  row 0   ·  [status] [standing]  ·  [head]  ·  [record] [x-ray]  ·
     *  row 1  Look into: record, reports, mining, attempts, chat, notes, audit
     *  row 2  Punish:    warn, kick, mute, freeze, ban, fine, broke a rule
     *  row 3  Practical: inventory, go to them, fines, tools, staff rank, ·, refresh
     *  row 5  back · home · · [ban for ever] · · help · close
     * </pre>
     * Every band row is filled in all seven columns, which keeps Core from centring a shorter one.
     */
    @Override
    protected void render() {
        PlayerOverview seen = overview != null ? overview : PlayerOverview.of(subject, subjectName).build();
        Instant now = Instant.now();
        java.time.ZoneId zone = LogSearchService.ZONE;
        // Each part of the overview only for whoever may already read it behind its own door.
        de.raindancer.modules.moderation.model.OverviewAccess access = de.raindancer.modules.moderation.model
                .OverviewAccess.of(this::may, viewer.hasPermission(TELEPORT), viewer.isOp());

        List<String> presence = new ArrayList<>(seen.presenceLore(zone, now, access));
        if (services().vanish().isVanished(subject)) {
            presence.add("<dark_gray>Vanished");
        }
        set(1, Icons.of(seen.presence().icon(), seen.presence().title(), presence));
        set(2, Icons.of(seen.standing().icon(), seen.standing().title(), seen.standingLore(now)));
        set(MenuLayout.HEADER_SUBJECT, Icons.head(subject, "<white>" + subjectName, seen.identityLore(zone, access)));
        set(6, Icons.of(Material.WRITABLE_BOOK, "<yellow>Record", seen.recordLore(access)));
        set(7, Icons.of(Material.DIAMOND_ORE, "<yellow>X-ray", seen.xrayLore(access)));

        section(MenuLayout.WHO, Material.LIGHT_BLUE_STAINED_GLASS_PANE, "<aqua>Look into");
        section(MenuLayout.RULES, Material.RED_STAINED_GLASS_PANE, "<red>Punish");
        section(MenuLayout.LAND, Material.LIME_STAINED_GLASS_PANE, "<green>Practical");

        // ── look into ──────────────────────────────────────────────────────────────────────────
        band(MenuLayout.WHO, 1, may(ModerationPermission.HISTORY),
                Icons.of(Material.BOOK, "<yellow>Record", access.record() ? "<gray>" + seen.recordEntries()
                                + " entries, every kind." : "<gray>Every kind of entry.",
                        "<dark_gray>Newest first."),
                "For whoever may read a record",
                click -> new HistoryMenu(services(), viewer, this, subject, subjectName).open());
        band(MenuLayout.WHO, 2, may(ModerationPermission.REPORTS),
                Icons.of(Material.PAPER, "<yellow>Reports",
                        access.reports() ? "<gray>" + seen.openReports() + " open of " + seen.reports() + " about them."
                                : "<gray>Reports about them."),
                "For whoever may read reports",
                click -> new ReportsMenu(services(), viewer, this).open());
        band(MenuLayout.WHO, 3, may(ModerationPermission.REPORTS),
                Icons.of(Material.DIAMOND_PICKAXE, "<yellow>Mining evidence",
                        access.reports() ? "<gray>" + seen.xray().finds() + " vein(s) and bait(s) on record."
                                : "<gray>Veins and baits on record.",
                        "<dark_gray>The x-ray tests, and where each find was."),
                "For whoever may read reports",
                click -> new XrayReviewMenu(services(), viewer, this, subject, subjectName).open());
        band(MenuLayout.WHO, 4, may(ModerationPermission.ATTEMPTS),
                Icons.of(Material.TRIPWIRE_HOOK, "<yellow>Attempts while punished",
                        access.attempts() ? "<gray>" + seen.attempts() + " kept: joins, chat, building."
                                : "<gray>Joins, chat, building while punished.",
                        "<dark_gray>/attempts searches everybody's."),
                "For whoever may see attempts",
                click -> new AttemptsMenu(services(), viewer, this, subject, subjectName).open());
        band(MenuLayout.WHO, 5, may(ModerationPermission.CHATLOG),
                Icons.of(Material.OAK_HANGING_SIGN, "<yellow>Chat", "<gray>Their public chat, newest first,",
                        "<gray>and a search through it."),
                "For whoever may read the chat log",
                click -> new ChatLogMenu(services(), viewer, this, subject, subjectName, "").open());
        band(MenuLayout.WHO, 6, may(ModerationPermission.NOTES),
                Icons.of(Material.WRITABLE_BOOK, "<yellow>Staff notes",
                        access.notes() ? "<gray>" + seen.notes() + " written." : "<gray>What staff wrote.",
                        "<dark_gray>They never see these."),
                "For whoever may read the notes",
                click -> new NotesMenu(services(), viewer, this, subject, subjectName).open());
        band(MenuLayout.WHO, 7, may(ModerationPermission.HISTORY),
                Icons.of(Material.OAK_SIGN, "<yellow>Audit trail", "<gray>Joins, teleports, gamemode,",
                        "<gray>every moderation action.", "<dark_gray>Newest first."),
                "For whoever may read a record",
                click -> new AuditMenu(services(), viewer, this, subject, subjectName).open());

        // ── punish: one door per kind, what is in force on each ────────────────────────────────
        category(1, PunishmentKind.WARNING, ModerationPermission.WARN);
        category(2, PunishmentKind.KICK, ModerationPermission.KICK);
        category(3, PunishmentKind.MUTE, ModerationPermission.MUTE);
        category(4, PunishmentKind.FREEZE, ModerationPermission.FREEZE);
        category(5, PunishmentKind.BAN, ModerationPermission.BAN);
        fineDoor();
        boolean rules = !services().ruleBreaches().rules().isEmpty();
        band(MenuLayout.RULES, 7, may(ModerationPermission.WARN) && rules,
                Icons.of(Material.IRON_BARS, "<yellow>Broke a rule", "<gray>Pick the rule; its next",
                        "<gray>punishment is handed out.", "<dark_gray>The same everybody reads in /rules."),
                rules ? "For whoever may hand out punishments" : "This server has no rules written down",
                click -> new RuleBreachMenu(services(), viewer, this, subject, subjectName).open());

        // ── practical ───────────────────────────────────────────────────────────────────────────
        band(MenuLayout.LAND, 1, may(ModerationPermission.INVSEE),
                Icons.of(Material.CHEST, "<yellow>Inventory & ender chest",
                        may(ModerationPermission.INVSEE_EDIT) ? "<gray>You may change what is in it."
                                : "<gray>Looking only.", "<dark_gray>Offline too."),
                "For whoever may look in inventories",
                click -> lookInside());
        boolean here = seen.where() != null;
        band(MenuLayout.LAND, 2, here && viewer.hasPermission(TELEPORT),
                Icons.of(Material.ENDER_PEARL, "<yellow>Go to them",
                        here && access.location() ? "<gray>" + seen.where().world() + " " + seen.where().x() + " " + seen.where().y()
                                + " " + seen.where().z() : here ? "<gray>To where they stand." : "<gray>Only while they are online."),
                here ? "For whoever may teleport" : "They are not online",
                click -> goTo());
        band(MenuLayout.LAND, 3, may(ModerationPermission.HISTORY),
                Icons.of(Material.GOLD_NUGGET, "<yellow>Fines", access.record() ? "<gray>Owes <white>"
                                + de.raindancer.core.social.economy.Fees.format(services().fines().owed(subject))
                                + "</white>." : "<gray>What they were fined.", "<dark_gray>Revoke one, or forgive the debt."),
                "For whoever may read a record",
                click -> new FinesMenu(services(), viewer, this, subject, subjectName).open());
        // Flight, invulnerability, one-hit-kill: none is a punishment, so they live apart from them.
        band(MenuLayout.LAND, 4, may(ModerationPermission.FLY) || may(ModerationPermission.GOD)
                        || may(ModerationPermission.INSTAKILL),
                Icons.of(Material.FEATHER, "<yellow>Tools", "<gray>Flight, invulnerability,",
                        "<gray>heal, feed, one-hit-kill."),
                "For whoever holds one of those",
                click -> new ToolsMenu(services(), viewer, this, subject, subjectName).open());
        // Drawn for everybody, greyed for anybody but the owner: ranks exist, and are not theirs to give.
        band(MenuLayout.LAND, 5, viewer.isOp()
                        || viewer.hasPermission(de.raindancer.modules.moderation.command.PromoteCommand.USE),
                Icons.of(Material.IRON_HELMET, "<yellow>Staff rank", "<gray>" + services().roster().rankOf(subject)
                        .map(rank -> rank.title()).orElse("Not staff") + ".", "<dark_gray>Promote, demote, permissions."),
                "Only the server owner may hand out ranks",
                click -> new RankMenu(services(), viewer, this, subject, subjectName).open());
        band(MenuLayout.LAND, 6, Icons.filler(Material.BLACK_STAINED_GLASS_PANE));
        band(MenuLayout.LAND, 7, Icons.of(Material.CLOCK, "<yellow>Refresh", "<gray>Read everything again."),
                click -> open());

        permanentBan();
    }

    @Override
    protected List<String> helpLines() {
        return List.of("<gray>Top: online (green), away (orange) or offline (red),",
                "<gray>what is in force, who they are, their record, x-ray.",
                "<gray>Rows: look into · punish · practical — always the same slots.");
    }

    /** Vanilla's own node: going to somebody is the teleport command's power, not a moderation one. */
    static final String TELEPORT = "minecraft.command.teleport";

    /** A coloured pane at the start of a row, naming the group. */
    private void section(int band, Material pane, String title) {
        set(band * 9, Icons.of(pane, title));
    }

    /** To where they stand, read on their thread and gone to on ours. */
    private void goTo() {
        Player target = services().server().getPlayer(subject);
        if (target == null) {
            tell("moderation.not-here", "player", subjectName);
            return;
        }
        if (!viewer.hasPermission(TELEPORT)) {
            tell("moderation.no-permission");
            return;
        }
        viewer.closeInventory();
        Scheduling.entity(services().plugin(), target, () -> {
            org.bukkit.Location at = target.getLocation();
            Scheduling.entity(services().plugin(), viewer, () -> viewer.teleportAsync(at));
        });
    }

    /**
     * The one irreversible thing on this page: gone for good.
     *
     * <p>Here rather than only behind the Bans door because it is the decision somebody reaches for when
     * they already know what they want — the griefer whose build log they have just read. Two clicks
     * through a category page to reach it is two clicks of a decision already made.
     *
     * <h2>Why the position is safe enough to allow that</h2>
     * The danger slot is flanked by navigation, so a misclick costs a page rather than a player, and it
     * only ever opens a confirmation. Bedrock, because it should not look like the buttons above it.
     *
     * <h2>Greyed for a mod rather than absent</h2>
     * A permanent ban needs {@link ModerationPermission#BAN}, which is an admin's — a mod holds
     * {@code TEMPBAN} and is capped. Drawing it locked keeps the page the same shape for everybody, and
     * tells a mod both that the power exists and that it is not theirs. Which is the whole grammar: a
     * button that is simply missing has no explanation on screen.
     */
    private void permanentBan() {
        boolean allowed = may(ModerationPermission.BAN);
        var button = Icons.of(Material.BEDROCK, "<red>Ban for ever",
                "<gray>Gone for good, straight away.",
                "<gray>No reason from the list, no length to pick.",
                "",
                allowed
                        ? "<dark_gray>Asks before it happens."
                        : "<dark_gray>An admin's decision — you can ban for up to "
                        + services().banLimitRule().capDescribed() + ".");

        danger(allowed ? button : Icons.locked(button, "A permanent ban is an admin's decision"),
                click -> {
                    if (!allowed) {
                        tell("moderation.ban.not-for-ever-for-you",
                                "detail", services().banLimitRule().capDescribed());
                        return;
                    }
                    new ConfirmScreen(services(), viewer, this,
                            "<red>Ban " + subjectName + " for ever?",
                            List.of("<gray>They are thrown off now and cannot come back.",
                                    "<gray>It goes on their record, and any admin can lift it.",
                                    "<dark_gray>For anything less than for ever, use Bans above."),
                            this::banForEver).open();
                });
    }

    /** Hands out the permanent ban, having asked twice. */
    private void banForEver() {
        // Asked again at the moment it happens: the page was drawn at least two clicks ago, and this is
        // the one action on it that cannot be undone by pressing the button a second time.
        if (refusedFor(ModerationPermission.BAN)) {
            return;
        }
        var withinTheirLimit = services().banLimitRule()
                .mayBanFor(viewer.getUniqueId(), Sentence.forEver());
        if (withinTheirLimit.isRefused()) {
            withinTheirLimit.refusal().ifPresent(key -> tell(key, "detail",
                    withinTheirLimit.detail() == null ? "" : withinTheirLimit.detail()));
            return;
        }
        services().punishmentService().punish(viewer.getUniqueId(), viewer.getName(), subject,
                subjectName, PunishmentKind.BAN, Sentence.forEver(), "banned permanently");
        tell("moderation.punished", "player", subjectName, "what", PunishmentKind.BAN.past(),
                "length", "for ever");
        viewer.closeInventory();
    }

    /**
     * One door per kind of punishment.
     *
     * <p>The lore carries what is in force for that kind, so the page answers "is this person already
     * muted?" without anybody opening the mute door to find out.
     */
    private void category(int column, PunishmentKind kind, ModerationPermission permission) {
        List<String> lore = new ArrayList<>();
        services().punishments().active(subject, kind).ifPresentOrElse(
                one -> {
                    lore.add("<red>Currently " + kind.past() + ".");
                    lore.add("<gray>" + one.reason());
                    lore.add("<gray>" + remaining(one));
                },
                () -> lore.add("<gray>Not " + kind.past() + "."));
        lore.add("");
        lore.add(kind.isLasting()
                ? "<dark_gray>Hand one out, or lift the one in force."
                : "<dark_gray>Hand one out.");

        band(MenuLayout.RULES, column, may(permission),
                Icons.of(CategoryMenu.icon(kind), "<yellow>" + CategoryMenu.title(kind), lore),
                "You may not " + CategoryMenu.verb(kind).toLowerCase(java.util.Locale.ROOT),
                click -> new CategoryMenu(services(), viewer, this, subject, subjectName, kind).open());
    }

    /** Fining them: you type the amount and why. Greyed with the reason when there is nothing to charge or no right to. */
    private void fineDoor() {
        boolean economy = services().fines().hasEconomy();
        List<String> lore = new ArrayList<>();
        lore.add("<gray>Takes money from their balance, offline or not.");
        services().fines().limitFor(viewer.getUniqueId()).ifPresent(most -> lore.add("<gray>You may fine up to <white>"
                + de.raindancer.core.social.economy.Fees.format(most) + "</white>."));
        lore.add("<dark_gray>You type the amount, then why. Asks first.");
        band(MenuLayout.RULES, 6, may(ModerationPermission.FINE) && economy,
                Icons.of(Material.GOLD_INGOT, "<yellow>Fine", lore),
                economy ? "You may not fine" : "This server has no economy",
                click -> FinesMenu.askForAFine(viewer, this, services(), subject, subjectName));
    }

    /**
     * Opens Core's inventory window.
     *
     * <p>Editing armour needs the wider grant, because taking somebody's helmet off by clicking one
     * slot too far is the mistake {@code Access} was split up to prevent. The answer comes back through
     * a callback and always on this thread — reading a logged-out player means reading a file, which is
     * not something to do on the thread also running the world.
     */
    private void lookInside() {
        Access wanted = may(ModerationPermission.INVSEE_EDIT) ? Access.EDIT : Access.READ_ONLY;
        services().inventories().open(viewer, subject, subjectName, wanted,
                outcome -> {
                    if (!outcome.opened()) {
                        tell("moderation.invsee.refused", "reason", outcome.saying());
                    }
                });
    }

    /**
     * Checks again at the moment of the click, and says so if the answer changed.
     *
     * <p>The render happened at least one click ago, and a permission can be taken away in between —
     * which is the whole reason a screen may not treat its own greyed-out state as the check.
     */
    private boolean refusedFor(ModerationPermission what) {
        var verdict = canAct(subject, what);
        if (verdict.isRefused()) {
            tellRefusal(verdict);
            return true;
        }
        return false;
    }

    private static String remaining(Punishment one) {
        return one.remainingAt(Instant.now())
                .map(left -> Times.brief(left) + " left")
                .orElse(one.isPermanent() ? "for ever" : "over");
    }

    @Override
    public String describe() {
        return "one player: what is in force, what is on the record, and what may be done";
    }
}
