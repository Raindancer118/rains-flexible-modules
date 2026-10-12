package de.raindancer.modules.moderation.model;

import org.bukkit.Material;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * What a player can report somebody for. Each carries how bad it usually is, and whether the chat around
 * it is worth reading — for a slur, the lines before and after are the evidence.
 *
 * <p>These are categories, not verdicts: what happens to the reported player is a moderator's decision.
 */
public enum ReportPreset {

    MOVEMENT_HACKS(ReportGroup.CHEATING, "Flying or speed hacks", Material.FEATHER, 3, false),
    XRAY(ReportGroup.CHEATING, "X-ray", Material.DIAMOND_ORE, 3, false),
    COMBAT_HACKS(ReportGroup.CHEATING, "Kill aura, reach or auto clicker", Material.IRON_SWORD, 3, false),
    DUPING(ReportGroup.CHEATING, "Duplicating items", Material.SHULKER_BOX, 4, false),
    EXPLOITING(ReportGroup.CHEATING, "Abusing a bug", Material.SPIDER_EYE, 3, false),
    LAG_MACHINE(ReportGroup.CHEATING, "Lag machine", Material.REPEATER, 3, false),
    ALT_ACCOUNT(ReportGroup.CHEATING, "Ban evasion or alt account", Material.PLAYER_HEAD, 4, false),
    CHEATING(ReportGroup.CHEATING, "Cheating", Material.COMMAND_BLOCK, 3, false, "cheating", "hacking"),

    GRIEFING(ReportGroup.GRIEFING, "Griefing", Material.TNT, 3, false, "griefing"),
    STEALING(ReportGroup.GRIEFING, "Stealing", Material.CHEST, 3, false, "stealing"),
    CLAIM_BREAK_IN(ReportGroup.GRIEFING, "Getting into a claim", Material.IRON_DOOR, 3, false),
    KILLING(ReportGroup.GRIEFING, "Killing without agreement", Material.SKELETON_SKULL, 2, false),
    TRAPPING(ReportGroup.GRIEFING, "Trapping or spawn killing", Material.COBWEB, 2, false),
    SCAMMING(ReportGroup.GRIEFING, "Scamming in a trade", Material.EMERALD, 3, true),
    ANIMALS(ReportGroup.GRIEFING, "Killing others' animals", Material.LEAD, 2, false),

    HARASSMENT(ReportGroup.CHAT, "Harassment or bullying", Material.WITHER_ROSE, 3, true, "harassment"),
    HATE_SPEECH(ReportGroup.CHAT, "Hate speech or slurs", Material.BARRIER, 4, true),
    THREATS(ReportGroup.CHAT, "Threats", Material.CROSSBOW, 4, true),
    PERSONAL_INFO(ReportGroup.CHAT, "Sharing personal information", Material.NAME_TAG, 4, true),
    SEXUAL(ReportGroup.CHAT, "Sexual content", Material.RED_DYE, 4, true),
    BAD_LANGUAGE(ReportGroup.CHAT, "Bad language", Material.BOOK, 1, true),
    SPAM(ReportGroup.CHAT, "Spam", Material.PAPER, 1, true, "spam"),
    ADVERTISING(ReportGroup.CHAT, "Advertising other servers", Material.OAK_SIGN, 2, true, "advertising"),
    IMPERSONATION(ReportGroup.CHAT, "Pretending to be staff", Material.GOLDEN_HELMET, 2, true),

    INAPPROPRIATE_BUILD(ReportGroup.BUILDS, "Inappropriate build", Material.PAINTING, 2, false, "inappropriate build"),
    INAPPROPRIATE_NAME(ReportGroup.BUILDS, "Inappropriate name or skin", Material.LEATHER_CHESTPLATE, 2, false,
            "inappropriate name or skin"),

    SOMETHING_ELSE(ReportGroup.OTHER, "Something else", Material.PAPER, 2, true, "something else");

    private final ReportGroup group;
    private final String title;
    private final Material icon;
    private final int severity;
    private final boolean chat;
    /** Categories of the old nine-item menu, as written in front of a report's text. */
    private final List<String> formerly;

    ReportPreset(ReportGroup group, String title, Material icon, int severity, boolean chat, String... formerly) {
        this.group = group;
        this.title = title;
        this.icon = icon;
        this.severity = severity;
        this.chat = chat;
        this.formerly = List.of(formerly);
    }

    public ReportGroup group() {
        return group;
    }

    public String title() {
        return title;
    }

    public Material icon() {
        return icon;
    }

    /** 1 (low) to 4 (critical). */
    public int severity() {
        return severity;
    }

    /** Whether what was said around the report is part of the evidence. */
    public boolean chat() {
        return chat;
    }

    public static List<ReportPreset> in(ReportGroup group) {
        return Arrays.stream(values()).filter(preset -> preset.group == group).toList();
    }

    /**
     * What a report is about: its stored preset, or — for one filed before presets, by command, or by the
     * server itself — read from what is written in front of its text.
     */
    public static ReportPreset of(Report report) {
        if (report.preset() != null) {
            for (ReportPreset preset : values()) {
                if (preset.name().equalsIgnoreCase(report.preset())) {
                    return preset;
                }
            }
        }
        String text = report.text().toLowerCase(Locale.ROOT);
        if (text.startsWith("x-ray")) {
            return XRAY;
        }
        if (text.startsWith("movement")) {
            return MOVEMENT_HACKS;
        }
        for (ReportPreset preset : values()) {
            if (text.startsWith(preset.title.toLowerCase(Locale.ROOT))) {
                return preset;
            }
            for (String old : preset.formerly) {
                if (text.startsWith(old)) {
                    return preset;
                }
            }
        }
        return SOMETHING_ELSE;
    }
}
