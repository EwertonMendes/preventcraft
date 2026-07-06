package tblack.preventcraft.ui;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.DropdownEntryInfo;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.ui.LocalizableString;
import tblack.preventcraft.PreventCraftPlugin;
import tblack.preventcraft.config.ConfigOperationResult;
import tblack.preventcraft.config.PreventCraftConfig;
import tblack.preventcraft.i18n.I18n;
import tblack.preventcraft.rule.PreventRule;
import tblack.preventcraft.rule.RuleAction;
import tblack.preventcraft.rule.RuleScope;
import tblack.preventcraft.rule.RuleType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class RuleEditorPage extends InteractiveCustomUIPage<RuleEditorPage.EditorEventData> {
    private static final String LAYOUT = "PreventCraft/RuleEditor.ui";

    private final PreventCraftPlugin plugin;
    private final PlayerRef viewerRef;
    private final String locale;
    private final int ruleIndex;
    private final int returnPage;
    private final String returnSearch;
    private RuleEditorDraft draft;
    private String status;
    private boolean confirmDelete;

    public RuleEditorPage(PlayerRef playerRef, PreventCraftPlugin plugin, int ruleIndex, int returnPage, String returnSearch, RuleType defaultType, RuleEditorDraft incomingDraft) {
        super(playerRef, CustomPageLifetime.CanDismiss, EditorEventData.CODEC);
        this.plugin = plugin;
        this.viewerRef = playerRef;
        this.locale = I18n.localeFromPlayerRef(playerRef);
        this.ruleIndex = ruleIndex;
        this.returnPage = Math.max(0, returnPage);
        this.returnSearch = returnSearch == null ? "" : returnSearch;
        this.draft = incomingDraft == null ? buildInitialDraft(defaultType) : incomingDraft.copy();
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder commands, UIEventBuilder events, Store<EntityStore> store) {
        commands.append(LAYOUT);
        bindEvents(events);
        render(commands);
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, EditorEventData data) {
        super.handleDataEvent(ref, store, data);
        if (data == null || data.action == null || data.action.isBlank()) {
            refresh();
            return;
        }
        switch (data.action) {
            case "close" -> close();
            case "back" -> openAdmin(ref, store, "");
            case "pick-target" -> {
                applyData(data);
                openPicker(ref, store);
            }
            case "save" -> {
                applyData(data);
                save(ref, store);
            }
            case "delete-request" -> {
                applyData(data);
                confirmDelete = true;
                refresh();
            }
            case "delete-cancel" -> {
                confirmDelete = false;
                refresh();
            }
            case "delete-confirm" -> delete(ref, store);
            default -> refresh();
        }
    }

    private void bindEvents(UIEventBuilder events) {
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ClosePageButton", event("close"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BackButton", event("back"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#PickTargetButton", formEvent("pick-target"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#SaveButton", formEvent("save"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#DeleteButton", formEvent("delete-request"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CancelDeleteButton", event("delete-cancel"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ConfirmDeleteButton", event("delete-confirm"), false);
    }

    private RuleEditorDraft buildInitialDraft(RuleType defaultType) {
        PreventCraftConfig config = plugin.getPreventCraftConfig();
        if (ruleIndex >= 0 && ruleIndex < config.Rules.size()) {
            return RuleEditorDraft.fromRule(config.Rules.get(ruleIndex));
        }
        RuleEditorDraft value = new RuleEditorDraft();
        value.id = nextRuleId(config, defaultType == null ? RuleType.CRAFT_ITEM : defaultType);
        value.type = defaultType == null ? RuleType.CRAFT_ITEM : defaultType;
        value.action = RuleAction.DENY;
        value.scope = RuleScope.EVERYONE;
        value.enabled = true;
        return value;
    }

    private void render(UICommandBuilder commands) {
        commands.set("#EditorTitle.TextSpans", Message.raw(I18n.translate(locale, ruleIndex >= 0 ? "ui.editor.title_edit" : "ui.editor.title_add")));
        setLabel(commands, "#RuleIdLabel", "ui.editor.id");
        setLabel(commands, "#TypeLabel", "ui.editor.type");
        setLabel(commands, "#ActionLabel", "ui.editor.action");
        setLabel(commands, "#ScopeLabel", "ui.editor.scope");
        setLabel(commands, "#TargetLabel", "ui.editor.target");
        setLabel(commands, "#GroupLabel", "ui.editor.group");
        setLabel(commands, "#PlayerLabel", "ui.editor.player");
        setLabel(commands, "#NoteLabel", "ui.editor.note");
        setText(commands, "#PickTargetButton", I18n.translate(locale, "ui.editor.pick_target"));
        setText(commands, "#EnabledLabel", I18n.translate(locale, "ui.editor.enabled"));
        setText(commands, "#BackButton", I18n.translate(locale, "ui.common.back"));
        setText(commands, "#SaveButton", I18n.translate(locale, "ui.editor.save"));
        setText(commands, "#DeleteButton", I18n.translate(locale, "ui.common.delete"));
        setText(commands, "#ClosePageButton", I18n.translate(locale, "ui.common.close"));
        setText(commands, "#CancelDeleteButton", I18n.translate(locale, "ui.common.cancel"));
        setText(commands, "#ConfirmDeleteButton", I18n.translate(locale, "ui.common.confirm_delete"));
        commands.set("#StatusLabel.TextSpans", Message.raw(status == null ? "" : status));
        commands.set("#DeleteButton.Visible", ruleIndex >= 0);
        commands.set("#DeleteConfirmation.Visible", confirmDelete && ruleIndex >= 0);
        commands.set("#DeleteConfirmationLabel.TextSpans", Message.raw(I18n.translate(locale, "ui.editor.delete_confirmation", draft.id)));

        commands.set("#RuleIdField.Value", draft.id == null ? "" : draft.id);
        commands.set("#TargetField.Value", draft.target == null ? "" : draft.target);
        commands.set("#GroupField.Value", draft.group == null ? "" : draft.group);
        commands.set("#PlayerField.Value", draft.player == null ? "" : draft.player);
        commands.set("#NoteField.Value", draft.note == null ? "" : draft.note);
        commands.set("#EnabledCheck.Value", draft.enabled);
        commands.set("#TypeDropdown.Entries", typeEntries());
        commands.set("#ActionDropdown.Entries", actionEntries());
        commands.set("#ScopeDropdown.Entries", scopeEntries());
        commands.set("#TypeDropdown.Value", safeName(draft.type, RuleType.CRAFT_ITEM));
        commands.set("#ActionDropdown.Value", safeName(draft.action, RuleAction.DENY));
        commands.set("#ScopeDropdown.Value", safeName(draft.scope, RuleScope.EVERYONE));
    }

    private void applyData(EditorEventData data) {
        draft.id = clean(data.ruleId, 96);
        draft.type = parseEnum(RuleType.class, data.type, RuleType.CRAFT_ITEM);
        draft.action = parseEnum(RuleAction.class, data.actionName, RuleAction.DENY);
        draft.scope = parseEnum(RuleScope.class, data.scope, RuleScope.EVERYONE);
        draft.target = clean(data.target, 256);
        draft.group = clean(data.group, 96);
        draft.player = clean(data.player, 96);
        draft.note = clean(data.note, 256);
        draft.enabled = data.enabled;
    }

    private void save(Ref<EntityStore> ref, Store<EntityStore> store) {
        String validation = validateDraft();
        if (validation != null) {
            status = validation;
            refresh();
            return;
        }
        PreventCraftConfig config = plugin.getPreventCraftConfig();
        PreventCraftConfig copy = plugin.getConfigManager().copy(config);
        PreventRule rule = draft.toRule();
        if (ruleIndex >= 0 && ruleIndex < copy.Rules.size()) copy.Rules.set(ruleIndex, rule);
        else copy.Rules.add(rule);
        ConfigOperationResult result = plugin.saveConfig(copy);
        if (!result.success()) {
            status = I18n.translate(locale, "messages.save_failed", result.message());
            refresh();
            return;
        }
        openAdmin(ref, store, I18n.translate(locale, "messages.rule_saved", rule.Id));
    }

    private void delete(Ref<EntityStore> ref, Store<EntityStore> store) {
        PreventCraftConfig config = plugin.getPreventCraftConfig();
        if (ruleIndex < 0 || ruleIndex >= config.Rules.size()) {
            openAdmin(ref, store, "");
            return;
        }
        PreventCraftConfig copy = plugin.getConfigManager().copy(config);
        String deletedId = copy.Rules.get(ruleIndex).Id;
        copy.Rules.remove(ruleIndex);
        ConfigOperationResult result = plugin.saveConfig(copy);
        openAdmin(ref, store, result.success()
                ? I18n.translate(locale, "messages.rule_deleted", deletedId)
                : I18n.translate(locale, "messages.save_failed", result.message()));
    }

    private String validateDraft() {
        if (draft.id == null || draft.id.isBlank()) return I18n.translate(locale, "ui.editor.validation_id");
        if (draft.target == null || draft.target.isBlank()) return I18n.translate(locale, "ui.editor.validation_target");
        if (draft.scope == RuleScope.GROUP && (draft.group == null || draft.group.isBlank())) return I18n.translate(locale, "ui.editor.validation_group");
        if (draft.scope == RuleScope.PLAYER && (draft.player == null || draft.player.isBlank())) return I18n.translate(locale, "ui.editor.validation_player");
        return null;
    }

    private void openAdmin(Ref<EntityStore> ref, Store<EntityStore> store, String message) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player != null) player.getPageManager().openCustomPage(ref, store, new PreventCraftAdminPage(viewerRef, plugin, returnPage, returnSearch, message));
    }

    private void openPicker(Ref<EntityStore> ref, Store<EntityStore> store) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player != null) player.getPageManager().openCustomPage(ref, store, new TargetPickerPage(viewerRef, plugin, ruleIndex, returnPage, returnSearch, draft, 0, ""));
    }

    private void refresh() {
        UICommandBuilder commands = new UICommandBuilder();
        render(commands);
        sendUpdate(commands, false);
    }

    private EventData event(String action) {
        return EventData.of("Action", action);
    }

    private EventData formEvent(String action) {
        return EventData.of("Action", action)
                .append("@RuleId", "#RuleIdField.Value")
                .append("@Type", "#TypeDropdown.Value")
                .append("@ActionName", "#ActionDropdown.Value")
                .append("@Scope", "#ScopeDropdown.Value")
                .append("@Target", "#TargetField.Value")
                .append("@Group", "#GroupField.Value")
                .append("@Player", "#PlayerField.Value")
                .append("@Note", "#NoteField.Value")
                .append("@Enabled", "#EnabledCheck.Value");
    }

    private List<DropdownEntryInfo> typeEntries() {
        List<DropdownEntryInfo> entries = new ArrayList<>();
        for (RuleType value : RuleType.values()) entries.add(entry(I18n.translate(locale, "ui.type." + value.name().toLowerCase(Locale.ROOT)), value.name()));
        return entries;
    }

    private List<DropdownEntryInfo> actionEntries() {
        List<DropdownEntryInfo> entries = new ArrayList<>();
        for (RuleAction value : RuleAction.values()) entries.add(entry(I18n.translate(locale, "ui.action." + value.name().toLowerCase(Locale.ROOT)), value.name()));
        return entries;
    }

    private List<DropdownEntryInfo> scopeEntries() {
        List<DropdownEntryInfo> entries = new ArrayList<>();
        for (RuleScope value : RuleScope.values()) entries.add(entry(I18n.translate(locale, "ui.scope." + value.name().toLowerCase(Locale.ROOT)), value.name()));
        return entries;
    }

    private DropdownEntryInfo entry(String label, String value) {
        return new DropdownEntryInfo(LocalizableString.fromString(label), value);
    }

    private String nextRuleId(PreventCraftConfig config, RuleType type) {
        String prefix = switch (type == null ? RuleType.CRAFT_ITEM : type) {
            case CRAFT_ITEM -> "craft-item";
            case CRAFT_BENCH -> "craft-bench";
            case ACCESS_BENCH -> "access-bench";
        };
        int index = 1;
        while (containsRuleId(config, prefix + "-" + index)) index++;
        return prefix + "-" + index;
    }

    private boolean containsRuleId(PreventCraftConfig config, String id) {
        if (config == null || config.Rules == null) return false;
        for (PreventRule rule : config.Rules) if (rule != null && id.equalsIgnoreCase(rule.Id)) return true;
        return false;
    }

    private String clean(String value, int maxLength) {
        if (value == null) return "";
        String clean = value.trim();
        if (clean.length() <= maxLength) return clean;
        return clean.substring(0, maxLength);
    }

    private <T extends Enum<T>> T parseEnum(Class<T> type, String value, T fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return fallback;
        }
    }

    private <T extends Enum<T>> String safeName(T value, T fallback) {
        return value == null ? fallback.name() : value.name();
    }

    private void setLabel(UICommandBuilder commands, String selector, String key) {
        commands.set(selector + ".TextSpans", Message.raw(I18n.translate(locale, key)));
    }

    private void setText(UICommandBuilder commands, String selector, String value) {
        commands.set(selector + ".TextSpans", Message.raw(value));
    }

    public static final class EditorEventData {
        public static final BuilderCodec<EditorEventData> CODEC = BuilderCodec.builder(EditorEventData.class, EditorEventData::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (data, value) -> data.action = value, data -> data.action).add()
                .append(new KeyedCodec<>("@RuleId", Codec.STRING), (data, value) -> data.ruleId = value, data -> data.ruleId).add()
                .append(new KeyedCodec<>("@Type", Codec.STRING), (data, value) -> data.type = value, data -> data.type).add()
                .append(new KeyedCodec<>("@ActionName", Codec.STRING), (data, value) -> data.actionName = value, data -> data.actionName).add()
                .append(new KeyedCodec<>("@Scope", Codec.STRING), (data, value) -> data.scope = value, data -> data.scope).add()
                .append(new KeyedCodec<>("@Target", Codec.STRING), (data, value) -> data.target = value, data -> data.target).add()
                .append(new KeyedCodec<>("@Group", Codec.STRING), (data, value) -> data.group = value, data -> data.group).add()
                .append(new KeyedCodec<>("@Player", Codec.STRING), (data, value) -> data.player = value, data -> data.player).add()
                .append(new KeyedCodec<>("@Note", Codec.STRING), (data, value) -> data.note = value, data -> data.note).add()
                .append(new KeyedCodec<>("@Enabled", Codec.BOOLEAN), (data, value) -> data.enabled = value, data -> data.enabled).add()
                .build();

        public String action = "";
        public String ruleId = "";
        public String type = RuleType.CRAFT_ITEM.name();
        public String actionName = RuleAction.DENY.name();
        public String scope = RuleScope.EVERYONE.name();
        public String target = "";
        public String group = "";
        public String player = "";
        public String note = "";
        public boolean enabled = true;

        public EditorEventData() {
        }

        public EditorEventData(String action, String ruleId, String type, String actionName, String scope, String target, String group, String player, String note, boolean enabled) {
            this.action = action;
            this.ruleId = ruleId;
            this.type = type;
            this.actionName = actionName;
            this.scope = scope;
            this.target = target;
            this.group = group;
            this.player = player;
            this.note = note;
            this.enabled = enabled;
        }
    }
}
