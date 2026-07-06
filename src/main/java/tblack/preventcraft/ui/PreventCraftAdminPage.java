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
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import tblack.preventcraft.PreventCraftPlugin;
import tblack.preventcraft.catalog.BenchCatalogEntry;
import tblack.preventcraft.catalog.ItemCatalogEntry;
import tblack.preventcraft.config.ConfigOperationResult;
import tblack.preventcraft.config.PreventCraftConfig;
import tblack.preventcraft.importer.CraftRestrictImportResult;
import tblack.preventcraft.i18n.I18n;
import tblack.preventcraft.rule.PreventRule;
import tblack.preventcraft.rule.RuleType;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PreventCraftAdminPage extends InteractiveCustomUIPage<PreventCraftAdminPage.AdminEventData> {
    private static final String LAYOUT = "PreventCraft/Admin.ui";
    private static final int PAGE_SIZE = 8;

    private final PreventCraftPlugin plugin;
    private final PlayerRef viewerRef;
    private final String locale;
    private int page;
    private String searchQuery;
    private String status;
    private Integer pendingDeleteIndex;

    public PreventCraftAdminPage(PlayerRef playerRef, PreventCraftPlugin plugin, int page, String searchQuery, String status) {
        super(playerRef, CustomPageLifetime.CanDismiss, AdminEventData.CODEC);
        this.plugin = plugin;
        this.viewerRef = playerRef;
        this.locale = I18n.localeFromPlayerRef(playerRef);
        this.page = Math.max(0, page);
        this.searchQuery = searchQuery == null ? "" : searchQuery.trim();
        this.status = status == null ? "" : status;
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder commands, UIEventBuilder events, Store<EntityStore> store) {
        commands.append(LAYOUT);
        bindEvents(events);
        render(commands);
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, AdminEventData data) {
        super.handleDataEvent(ref, store, data);
        if (data == null || data.action == null || data.action.isBlank()) {
            refresh();
            return;
        }
        switch (data.action) {
            case "close" -> close();
            case "previous" -> previous();
            case "next" -> next();
            case "search" -> search(data.searchQuery);
            case "clear-search" -> search("");
            case "add-craft-item" -> openEditor(ref, store, -1, RuleType.CRAFT_ITEM, null);
            case "add-craft-bench" -> openEditor(ref, store, -1, RuleType.CRAFT_BENCH, null);
            case "add-access-bench" -> openEditor(ref, store, -1, RuleType.ACCESS_BENCH, null);
            case "reload" -> reload();
            case "backup" -> backup();
            case "import-dry-run" -> importCraftRestrict(true);
            case "import-apply" -> importCraftRestrict(false);
            case "delete-cancel" -> cancelDelete();
            default -> handleSlotAction(ref, store, data.action);
        }
    }

    private void bindEvents(UIEventBuilder events) {
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ClosePageButton", event("close"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#PreviousButton", event("previous"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#NextButton", event("next"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#SearchButton", searchEvent(), false);
        events.addEventBinding(CustomUIEventBindingType.Validating, "#SearchField", searchEvent(), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ClearSearchButton", event("clear-search"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#AddItemRuleButton", event("add-craft-item"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#AddBenchCraftRuleButton", event("add-craft-bench"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#AddBenchAccessRuleButton", event("add-access-bench"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ReloadButton", event("reload"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BackupButton", event("backup"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ImportDryRunButton", event("import-dry-run"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ImportApplyButton", event("import-apply"), false);
        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            events.addEventBinding(CustomUIEventBindingType.Activating, "#EditButton" + slot, event("edit:" + slot), false);
            events.addEventBinding(CustomUIEventBindingType.Activating, "#DeleteButton" + slot, event("delete-request:" + slot), false);
            events.addEventBinding(CustomUIEventBindingType.Activating, "#ConfirmDeleteButton" + slot, event("delete-confirm:" + slot), false);
            events.addEventBinding(CustomUIEventBindingType.Activating, "#CancelDeleteButton" + slot, event("delete-cancel"), false);
        }
    }

    private void render(UICommandBuilder commands) {
        PreventCraftConfig config = plugin.getPreventCraftConfig();
        List<Integer> filtered = filteredIndexes(config);
        clampPage(filtered.size());
        commands.set("#AdminTitle.TextSpans", Message.raw(I18n.translate(locale, "ui.admin.title")));
        commands.set("#StatusGroup.Visible", status != null && !status.isBlank());
        commands.set("#StatusLabel.TextSpans", Message.raw(status == null ? "" : status));
        commands.set("#ModeLabel.TextSpans", Message.raw(I18n.translate(locale, "ui.admin.mode", config.Mode.name(), config.HideBlockedRecipes)));
        commands.set("#RulesCountLabel.TextSpans", Message.raw(I18n.translate(locale, "ui.admin.rules_count", plugin.getRuleService().activeRuleCount(), config.Rules.size())));
        commands.set("#LuckPermsLabel.TextSpans", Message.raw(I18n.translate(locale, "ui.admin.luckperms", plugin.getPermissionService().isLuckPermsAvailable())));
        commands.set("#CatalogLabel.TextSpans", Message.raw(I18n.translate(locale, "ui.admin.catalog", safeSizeItems(), safeSizeBenches())));
        commands.set("#SearchField.Value", searchQuery);
        setText(commands, "#SearchLabel", I18n.translate(locale, "ui.admin.search"));
        setText(commands, "#SearchButton", I18n.translate(locale, "ui.admin.search"));
        setText(commands, "#ClearSearchButton", I18n.translate(locale, "ui.admin.clear"));
        setText(commands, "#AddItemRuleButton", I18n.translate(locale, "ui.admin.add_item"));
        setText(commands, "#AddBenchCraftRuleButton", I18n.translate(locale, "ui.admin.add_bench_craft"));
        setText(commands, "#AddBenchAccessRuleButton", I18n.translate(locale, "ui.admin.add_bench_access"));
        setText(commands, "#ReloadButton", I18n.translate(locale, "ui.admin.reload"));
        setText(commands, "#BackupButton", I18n.translate(locale, "ui.admin.backup"));
        setText(commands, "#ImportDryRunButton", I18n.translate(locale, "ui.admin.import_dry_run"));
        setText(commands, "#ImportApplyButton", I18n.translate(locale, "ui.admin.import_apply"));
        setText(commands, "#ImportHintLabel", I18n.translate(locale, "ui.admin.import_hint"));
        setText(commands, "#RulesLabel", I18n.translate(locale, "ui.admin.rules"));
        setText(commands, "#PreviousButton", I18n.translate(locale, "ui.common.previous"));
        setText(commands, "#NextButton", I18n.translate(locale, "ui.common.next"));
        setText(commands, "#ClosePageButton", I18n.translate(locale, "ui.common.close"));

        for (int slot = 0; slot < PAGE_SIZE; slot++) renderRuleRow(commands, config, filtered, slot);
        int totalPages = Math.max(1, (int) Math.ceil(filtered.size() / (double) PAGE_SIZE));
        commands.set("#PageLabel.TextSpans", Message.raw(I18n.translate(locale, "ui.admin.page", page + 1, totalPages, filtered.size(), config.Rules.size())));
        commands.set("#PreviousButton.Visible", page > 0);
        commands.set("#NextButton.Visible", page + 1 < totalPages);
    }

    private void renderRuleRow(UICommandBuilder commands, PreventCraftConfig config, List<Integer> filtered, int slot) {
        int position = page * PAGE_SIZE + slot;
        boolean visible = position >= 0 && position < filtered.size();
        commands.set("#RuleRow" + slot + ".Visible", visible);
        if (!visible) return;
        int ruleIndex = filtered.get(position);
        PreventRule rule = config.Rules.get(ruleIndex);
        boolean confirm = Integer.valueOf(ruleIndex).equals(pendingDeleteIndex);
        commands.set("#RuleActions" + slot + ".Visible", !confirm);
        commands.set("#DeleteConfirmation" + slot + ".Visible", confirm);
        commands.set("#DeleteConfirmationLabel" + slot + ".TextSpans", Message.raw(I18n.translate(locale, "ui.admin.delete_confirmation", rule.Id)));
        setText(commands, "#EditButton" + slot, I18n.translate(locale, "ui.common.edit"));
        setText(commands, "#DeleteButton" + slot, I18n.translate(locale, "ui.common.delete"));
        setText(commands, "#CancelDeleteButton" + slot, I18n.translate(locale, "ui.common.cancel"));
        setText(commands, "#ConfirmDeleteButton" + slot, I18n.translate(locale, "ui.common.confirm_delete"));

        commands.set("#RuleName" + slot + ".TextSpans", Message.raw(rule.Id + (rule.Enabled ? "" : " (disabled)")));
        commands.set("#RuleDescription" + slot + ".TextSpans", Message.raw(describeRule(rule)));
        renderIcon(commands, rule, slot);
    }

    private void renderIcon(UICommandBuilder commands, PreventRule rule, int slot) {
        String iconId = null;
        String fallback = rule.Type == RuleType.CRAFT_ITEM ? "ITEM" : "BENCH";
        if (rule.Type == RuleType.CRAFT_ITEM) {
            if (plugin.getItemCatalog().isValidItemId(rule.Target)) iconId = rule.Target;
        } else {
            BenchCatalogEntry bench = plugin.getBenchCatalog().describe(rule.Target, locale);
            if (bench != null && bench.hasIcon()) iconId = bench.iconItemId();
        }
        commands.set("#RuleIcon" + slot + ".Visible", iconId != null);
        if (iconId != null) commands.set("#RuleIcon" + slot + ".ItemId", iconId);
        commands.set("#RuleKindIcon" + slot + ".Visible", iconId == null);
        commands.set("#RuleKindIcon" + slot + ".TextSpans", Message.raw(fallback));
    }

    private String describeRule(PreventRule rule) {
        String targetName = rule.Target;
        if (rule.Type == RuleType.CRAFT_ITEM) {
            ItemCatalogEntry entry = plugin.getItemCatalog().describe(rule.Target, locale);
            if (entry != null && entry.hasDisplayName()) targetName = entry.displayName() + " (" + rule.Target + ")";
        } else {
            BenchCatalogEntry entry = plugin.getBenchCatalog().describe(rule.Target, locale);
            if (entry != null && entry.hasDisplayName()) targetName = entry.displayName() + " (" + rule.Target + ")";
        }
        String scope = switch (rule.Scope) {
            case EVERYONE -> I18n.translate(locale, "ui.scope.everyone");
            case GROUP -> I18n.translate(locale, "ui.scope.group") + ": " + rule.Group;
            case PLAYER -> I18n.translate(locale, "ui.scope.player") + ": " + rule.Player;
        };
        return rule.Type + " • " + rule.Action + " • " + scope + "\n" + targetName;
    }

    private void handleSlotAction(Ref<EntityStore> ref, Store<EntityStore> store, String action) {
        if (action.startsWith("edit:")) {
            int index = indexForSlot(parseSlot(action, "edit:"));
            if (index >= 0) openEditor(ref, store, index, null, null);
            else refresh();
            return;
        }
        if (action.startsWith("delete-request:")) {
            int index = indexForSlot(parseSlot(action, "delete-request:"));
            pendingDeleteIndex = index >= 0 ? index : null;
            refresh();
            return;
        }
        if (action.startsWith("delete-confirm:")) {
            int index = indexForSlot(parseSlot(action, "delete-confirm:"));
            confirmDelete(index);
            return;
        }
        refresh();
    }

    private void openEditor(Ref<EntityStore> ref, Store<EntityStore> store, int ruleIndex, RuleType newType, RuleEditorDraft draft) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            status = I18n.translate(locale, "messages.player_not_found");
            refresh();
            return;
        }
        player.getPageManager().openCustomPage(ref, store, new RuleEditorPage(viewerRef, plugin, ruleIndex, page, searchQuery, newType, draft));
    }

    private void previous() {
        page = Math.max(0, page - 1);
        pendingDeleteIndex = null;
        refresh();
    }

    private void next() {
        page++;
        pendingDeleteIndex = null;
        refresh();
    }

    private void search(String query) {
        searchQuery = query == null ? "" : query.trim();
        page = 0;
        pendingDeleteIndex = null;
        refresh();
    }

    private void reload() {
        ConfigOperationResult result = plugin.reloadConfig();
        status = result.success() ? I18n.translate(locale, "messages.reload_success", plugin.getRuleService().activeRuleCount()) : I18n.translate(locale, "messages.reload_failed", result.message());
        refresh();
    }

    private void backup() {
        ConfigOperationResult result = plugin.createBackup("manual-ui");
        status = result.success() ? I18n.translate(locale, "messages.backup_success", result.message()) : I18n.translate(locale, "messages.backup_failed", result.message());
        refresh();
    }

    private void importCraftRestrict(boolean dryRun) {
        PreventCraftConfig config = plugin.getPreventCraftConfig();
        CraftRestrictImportResult result = plugin.importCraftRestrict(config.Migration.CraftRestrictModeValue, dryRun, config.Migration.IncludeUsers);
        status = result.success()
                ? I18n.translate(locale, "messages.import_result", result.scannedHolders(), result.scannedNodes(), result.importedRules(), result.skippedDuplicates(), result.skippedWildcards(), result.skippedUnknown(), result.dryRun(), result.reportFile() == null ? "-" : result.reportFile().getFileName())
                : I18n.translate(locale, "messages.import_failed", result.message());
        refresh();
    }

    private void confirmDelete(int index) {
        PreventCraftConfig config = plugin.getPreventCraftConfig();
        if (index < 0 || index >= config.Rules.size() || !Integer.valueOf(index).equals(pendingDeleteIndex)) {
            pendingDeleteIndex = null;
            refresh();
            return;
        }
        String id = config.Rules.get(index).Id;
        config.Rules.remove(index);
        ConfigOperationResult result = plugin.saveConfig(config);
        status = result.success() ? I18n.translate(locale, "messages.rule_deleted", id) : I18n.translate(locale, "messages.save_failed", result.message());
        pendingDeleteIndex = null;
        refresh();
    }

    private void cancelDelete() {
        pendingDeleteIndex = null;
        refresh();
    }

    private List<Integer> filteredIndexes(PreventCraftConfig config) {
        String query = searchQuery == null ? "" : searchQuery.toLowerCase(Locale.ROOT);
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < config.Rules.size(); i++) {
            PreventRule rule = config.Rules.get(i);
            if (rule == null) continue;
            String text = (rule.Id + " " + rule.Type + " " + rule.Target + " " + rule.Action + " " + rule.Scope + " " + rule.Group + " " + rule.Player + " " + rule.Note).toLowerCase(Locale.ROOT);
            if (query.isBlank() || text.contains(query)) indexes.add(i);
        }
        return indexes;
    }

    private int indexForSlot(int slot) {
        if (slot < 0 || slot >= PAGE_SIZE) return -1;
        List<Integer> filtered = filteredIndexes(plugin.getPreventCraftConfig());
        int position = page * PAGE_SIZE + slot;
        return position >= 0 && position < filtered.size() ? filtered.get(position) : -1;
    }

    private int parseSlot(String action, String prefix) {
        try {
            return Integer.parseInt(action.substring(prefix.length()));
        } catch (Exception exception) {
            return -1;
        }
    }

    private void clampPage(int size) {
        int max = Math.max(0, (int) Math.ceil(size / (double) PAGE_SIZE) - 1);
        if (page > max) page = max;
    }

    private int safeSizeItems() {
        try {
            return plugin.getItemCatalog().search("", locale).size();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private int safeSizeBenches() {
        try {
            return plugin.getBenchCatalog().search("", locale).size();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private void refresh() {
        UICommandBuilder commands = new UICommandBuilder();
        render(commands);
        sendUpdate(commands, false);
    }

    private EventData event(String action) {
        return EventData.of("Action", action);
    }

    private EventData searchEvent() {
        return EventData.of("Action", "search").append("@SearchQuery", "#SearchField.Value");
    }

    private void setText(UICommandBuilder commands, String selector, String value) {
        commands.set(selector + ".TextSpans", Message.raw(value));
    }

    public static final class AdminEventData {
        public static final BuilderCodec<AdminEventData> CODEC = BuilderCodec.builder(AdminEventData.class, AdminEventData::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (data, value) -> data.action = value, data -> data.action).add()
                .append(new KeyedCodec<>("@SearchQuery", Codec.STRING), (data, value) -> data.searchQuery = value, data -> data.searchQuery).add()
                .build();
        public String action = "";
        public String searchQuery = "";
    }
}
