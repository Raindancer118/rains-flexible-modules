package de.raindancer.modules.essentials.service;

import de.raindancer.core.platform.rule.Verdict;
import de.raindancer.core.ui.chat.ChatButtons;
import de.raindancer.core.ui.messages.Messages;
import de.raindancer.modules.essentials.EssentialsSettings;
import de.raindancer.modules.essentials.model.HouseRule;
import de.raindancer.modules.essentials.model.RulePreset;
import de.raindancer.modules.essentials.rules.HouseRuleTextRule;
import de.raindancer.modules.essentials.store.RuleBook;
import de.raindancer.modules.essentials.util.PermissionNodes;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;

/**
 * The server's rules: reading them in chat, and every change to them or their presets, each checked and
 * answered — so {@code /rules} typed and the editor clicked say the same things.
 */
public final class RulesService implements IEssentialsService {

    private final RuleBook book;
    private final Messages messages;
    private final ChatButtons buttons;
    private final HouseRuleTextRule textRule = new HouseRuleTextRule();
    private volatile EssentialsSettings settings;

    public RulesService(RuleBook book, Messages messages, ChatButtons buttons, EssentialsSettings settings) {
        this.book = book;
        this.messages = messages;
        this.buttons = buttons;
        this.settings = settings;
    }

    @Override
    public void settings(EssentialsSettings settings) {
        this.settings = settings;
    }

    public RuleBook book() {
        return book;
    }

    // ------------------------------------------------------------------------------------- reading

    /**
     * Every rule in chat. Whoever may edit them sees the switched-off ones too, numbered among the rest,
     * so the numbers they see are the ones {@code /rules remove} takes.
     */
    public void show(CommandSender to) {
        boolean manager = to.hasPermission(PermissionNodes.RULES_MANAGE);
        List<HouseRule> listed = manager ? book.rules() : book.shown();
        messages.send(to, "essentials.rules.header");
        if (listed.isEmpty()) {
            messages.sendPlain(to, "essentials.rules.none");
        }
        for (int index = 0; index < listed.size(); index++) {
            line(to, index + 1, listed.get(index));
        }
        if (manager && to instanceof Player) {
            to.sendMessage(buttons.row(
                    buttons.label(messages.raw("essentials.rules.edit-button"))
                            .tooltip(messages.raw("essentials.rules.edit-tooltip"))
                            .runs("/rules edit")));
        }
    }

    /** @param number as {@link #show} numbered it for this reader */
    public void showOne(CommandSender to, int number) {
        List<HouseRule> listed = to.hasPermission(PermissionNodes.RULES_MANAGE) ? book.rules() : book.shown();
        if (number < 1 || number > listed.size()) {
            messages.send(to, "essentials.rules.no-such", "number", number);
            return;
        }
        line(to, number, listed.get(number - 1));
    }

    private void line(CommandSender to, int number, HouseRule rule) {
        messages.sendPlain(to, rule.enabled() ? "essentials.rules.line" : "essentials.rules.line-off",
                "number", number, "title", rule.title(), "text", rule.text());
    }

    /** Somebody's very first join, if the owner wants the rules shown then. */
    public void firstJoin(Player player) {
        if (settings.rulesOnFirstJoin() && !book.shown().isEmpty()) {
            show(player);
        }
    }

    // ------------------------------------------------------------------------------------ changing

    /** @return the refusal already said to {@code by}, or allowed */
    public Verdict check(CommandSender by, HouseRuleTextRule.Part part, String value) {
        Verdict verdict = textRule.judge(new HouseRuleTextRule.Ask(part, value));
        if (verdict.isRefused()) {
            messages.send(by, verdict.reason(), "limit", verdict.detail(), "name", verdict.detail());
        }
        return verdict;
    }

    public Optional<HouseRule> add(CommandSender by, String title, String text) {
        if (check(by, HouseRuleTextRule.Part.TITLE, title).isRefused()
                || check(by, HouseRuleTextRule.Part.TEXT, text).isRefused()) {
            return Optional.empty();
        }
        HouseRule added = book.add(title, text);
        messages.send(by, "essentials.rules.added", "number", book.rules().size(), "title", added.title());
        return Optional.of(added);
    }

    public boolean retitle(CommandSender by, String id, String title) {
        if (check(by, HouseRuleTextRule.Part.TITLE, title).isRefused()) {
            return false;
        }
        return saved(by, book.update(id, rule -> rule.withTitle(title)));
    }

    public boolean retext(CommandSender by, String id, String text) {
        if (check(by, HouseRuleTextRule.Part.TEXT, text).isRefused()) {
            return false;
        }
        return saved(by, book.update(id, rule -> rule.withText(text)));
    }

    private boolean saved(CommandSender by, boolean found) {
        messages.send(by, found ? "essentials.rules.saved" : "essentials.rules.gone");
        return found;
    }

    public boolean remove(CommandSender by, String id) {
        Optional<HouseRule> removed = book.remove(id);
        removed.ifPresentOrElse(rule -> messages.send(by, "essentials.rules.removed", "title", rule.title()),
                () -> messages.send(by, "essentials.rules.gone"));
        return removed.isPresent();
    }

    // ------------------------------------------------------------------------------------- presets

    public void listPresets(CommandSender to) {
        List<RulePreset> presets = book.presets();
        messages.send(to, "essentials.rules.preset.header");
        if (presets.isEmpty()) {
            messages.sendPlain(to, "essentials.rules.preset.none");
        }
        for (RulePreset preset : presets) {
            messages.sendPlain(to, "essentials.rules.preset.line", "name", preset.name(),
                    "description", preset.description(), "count", preset.rules().size());
        }
    }

    public boolean applyPreset(CommandSender by, String name) {
        Optional<RulePreset> preset = book.preset(HouseRuleTextRule.presetName(name));
        if (preset.isEmpty() || !book.applyPreset(preset.get().name())) {
            messages.send(by, "essentials.rules.preset.no-such", "name", name);
            return false;
        }
        messages.send(by, "essentials.rules.preset.applied", "name", preset.get().name(),
                "count", preset.get().rules().size());
        return true;
    }

    public boolean savePreset(CommandSender by, String typedName, String description) {
        String name = HouseRuleTextRule.presetName(typedName);
        if (check(by, HouseRuleTextRule.Part.PRESET, name).isRefused()) {
            return false;
        }
        boolean replaced = book.preset(name).isPresent();
        if (!book.savePreset(name, description)) {
            messages.send(by, "essentials.rules.preset.could-not-save");
            return false;
        }
        messages.send(by, replaced ? "essentials.rules.preset.replaced" : "essentials.rules.preset.saved",
                "name", name, "count", book.rules().size());
        return true;
    }

    public boolean deletePreset(CommandSender by, String typedName) {
        String name = HouseRuleTextRule.presetName(typedName);
        if (book.preset(name).isEmpty()) {
            messages.send(by, "essentials.rules.preset.no-such", "name", typedName);
            return false;
        }
        if (!book.deletePreset(name)) {
            messages.send(by, "essentials.rules.preset.could-not-save");
            return false;
        }
        messages.send(by, "essentials.rules.preset.deleted", "name", name);
        return true;
    }
}
