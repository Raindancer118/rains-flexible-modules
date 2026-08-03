package de.raindancer.modules.moderation;

import de.raindancer.core.moderation.audit.Audit;
import de.raindancer.core.moderation.invsee.Inventories;
import de.raindancer.core.moderation.players.PlayerAdmin;
import de.raindancer.core.moderation.players.PlayerPowers;
import de.raindancer.core.moderation.punishment.PunishmentGuard;
import de.raindancer.core.moderation.punishment.Punishments;
import de.raindancer.core.moderation.vanish.Vanish;
import de.raindancer.core.platform.log.LogChannel;
import de.raindancer.core.ui.chat.Brand;
import de.raindancer.core.ui.chat.Chat;
import de.raindancer.core.ui.choose.PlayerDirectory;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.core.ui.prompt.ChatPrompts;
import de.raindancer.modules.moderation.rules.AnnouncementRule;
import de.raindancer.modules.moderation.rules.BanLimitRule;
import de.raindancer.modules.moderation.rules.PromotionRule;
import de.raindancer.modules.moderation.rules.EscalationRule;
import de.raindancer.modules.moderation.rules.ReportRule;
import de.raindancer.modules.moderation.rules.StaffRule;
import de.raindancer.modules.moderation.rules.StandingRule;
import de.raindancer.modules.moderation.service.NoteService;
import de.raindancer.modules.moderation.service.PunishmentService;
import de.raindancer.modules.moderation.service.ReportService;
import de.raindancer.modules.moderation.service.StaffChatService;
import de.raindancer.modules.moderation.store.NoteRegistry;
import de.raindancer.modules.moderation.store.Reasons;
import de.raindancer.core.platform.permission.Grants;
import de.raindancer.modules.moderation.service.StaffService;
import de.raindancer.modules.moderation.store.ReportRegistry;
import de.raindancer.modules.moderation.store.StaffRoster;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;

import java.util.function.Supplier;

/**
 * Everything the moderation module has built, in one place, so a listener, a screen or a command can be
 * handed what it needs.
 *
 * <h2>Why this is not the god object it replaces</h2>
 * The thing before it was the {@code JavaPlugin} subclass, reached through statics from every command:
 * every command therefore depended on all of it, and none of them could be built without a server.
 *
 * <p>The difference is that this is <em>data</em>. A record of collaborators, constructed once by the
 * module and handed over; a test builds one with fakes in the fields it cares about. Nothing here
 * reaches back into Bukkit, nothing here is static, and anything needing two of these still says so in
 * its own constructor — this is for the handful that genuinely coordinate half the module.
 *
 * <h2>How much of it is Core's</h2>
 * Most of it, and deliberately. {@link Punishments}, {@link PunishmentGuard}, {@link Vanish},
 * {@link PlayerAdmin}, {@link Inventories} and {@link Audit} are all RainsCore's — this module is the
 * commands, the screens and the product decisions on top. A server that removes it keeps every ban it
 * has already handed out, which is the whole reason the split falls where it does.
 *
 * @param settings read through a supplier, not captured: a reload has to change what happens next, not
 *                 what happens after the next restart
 * @param screens  opening a screen, as an interface — so nothing here depends on the menus
 */
public record ModerationServices(
        Plugin plugin,
        Server server,
        LogChannel log,
        Messages messages,
        Chat chat,
        Brand brand,
        ChatPrompts prompts,
        de.raindancer.core.data.settings.SettingsNavigation settingsNavigation,

        // ── RainsCore's, all of it ────────────────────────────────────────────────────────────
        Punishments punishments,
        PunishmentGuard guard,
        Vanish vanish,
        PlayerAdmin players,
        PlayerPowers powers,
        Inventories inventories,
        Audit audit,
        Grants grants,
        Supplier<PlayerDirectory> directory,

        // ── the module's own ──────────────────────────────────────────────────────────────────
        Reasons reasons,
        ReportRegistry reports,
        NoteRegistry notes,
        StaffRule staffRule,
        EscalationRule escalation,
        AnnouncementRule announcements,
        Supplier<StandingRule> standing,
        Supplier<BanLimitRule> banLimit,
        Supplier<PromotionRule> promotion,
        Supplier<ReportRule> reportRule,
        PunishmentService punishmentService,
        ReportService reportService,
        NoteService noteService,
        StaffChatService staffChat,
        StaffRoster roster,
        StaffService staff,
        Supplier<de.raindancer.modules.moderation.listener.StaffChatListener> staffChatSpeaker,

        Supplier<ModerationSettings> settings,
        ModerationScreensOpener screens) {

    /**
     * Where a record leaves somebody.
     *
     * <p>Behind a supplier because its window is a setting: one built at startup would keep
     * yesterday's window until the next restart.
     */
    public StandingRule standingRule() {
        return standing.get();
    }

    /**
     * How long a ban somebody may hand out.
     *
     * <p>Behind a supplier because the cap is a setting: one built at startup would keep yesterday's
     * ceiling until the next restart.
     */
    public BanLimitRule banLimitRule() {
        return banLimit.get();
    }

    /**
     * Who may hand out which rank.
     *
     * <p>Behind a supplier because both directions are settings a server may switch off.
     */
    public PromotionRule promotionRule() {
        return promotion.get();
    }

    /** The settings as they are right now. */
    public ModerationSettings config() {
        return settings.get();
    }

    /**
     * Everybody who could be picked, rebuilt each time it is asked for.
     *
     * <p>Behind a supplier because building it reads the player data directory, which is not something
     * to hold on to: a directory captured at startup is one that does not contain the player who
     * joined this evening.
     */
    public PlayerDirectory everybody() {
        return directory.get();
    }

    /**
     * The rule that decides whether a report may be filed, with the limits as they are now.
     *
     * <p>Behind a supplier because the limits are settings: a rule built once at startup would keep
     * yesterday's cooldown until the next restart, which is the exact thing every service here takes
     * {@code settings(...)} to avoid.
     */
    public ReportRule filingRule() {
        return reportRule.get();
    }

    /**
     * The listener that puts a line in front of the staff.
     *
     * <p>Reached through a supplier because it is built <em>after</em> this record — it takes the record
     * as its own argument. A field would have to be null for a moment, and a command run in that moment
     * would find it.
     */
    public de.raindancer.modules.moderation.listener.StaffChatListener staffChatListener() {
        return staffChatSpeaker.get();
    }
}
