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
import tblack.preventcraft.i18n.I18n;
import tblack.preventcraft.importer.CraftRestrictImportResult;
import tblack.preventcraft.rule.PreventRule;
import tblack.preventcraft.rule.RestrictionMode;
import tblack.preventcraft.rule.RuleAction;
import tblack.preventcraft.rule.RuleScope;
import tblack.preventcraft.rule.RuleType;

import java.text.Normalizer;
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
    private List<Integer> cachedFilteredIndexes = List.of();
    private String cachedFilterSignature = null;
    private int cachedItemCount = -1;
    private int cachedBenchCount = -1;

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
            case "clear-search" -> clearSearch();
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
        commands.set("#StatusLabel.TextSpans", Message.raw(status == null ? "" : status));

        setText(commands, "#ModeCardTitle", I18n.translate(locale, "ui.admin.metric_mode"));
        setText(commands, "#ModeCardValue", modeLabel(config.Mode));
        setText(commands, "#ModeCardSub", I18n.translate(locale, "ui.admin.metric_mode_sub", yesNo(config.HideBlockedRecipes)));

        setText(commands, "#RulesCardTitle", I18n.translate(locale, "ui.admin.metric_rules"));
        setText(commands, "#RulesCardValue", plugin.getRuleService().activeRuleCount() + " / " + config.Rules.size());
        setText(commands, "#RulesCardSub", I18n.translate(locale, "ui.admin.metric_rules_sub"));

        setText(commands, "#LuckPermsCardTitle", I18n.translate(locale, "ui.admin.metric_luckperms"));
        setText(commands, "#LuckPermsCardValue", plugin.getPermissionService().isLuckPermsAvailable() ? I18n.translate(locale, "ui.common.available") : I18n.translate(locale, "ui.common.unavailable"));
        setText(commands, "#LuckPermsCardSub", I18n.translate(locale, "ui.admin.metric_luckperms_sub"));

        setText(commands, "#CatalogCardTitle", I18n.translate(locale, "ui.admin.metric_catalog"));
        setText(commands, "#CatalogCardValue", I18n.translate(locale, "ui.admin.metric_catalog_value"));
        setText(commands, "#CatalogCardSub", I18n.translate(locale, "ui.admin.metric_catalog_sub"));

        commands.set("#SearchField.Value", searchQuery);
        commands.set("#SearchField.PlaceholderText", I18n.translate(locale, "ui.admin.search_placeholder"));
        setText(commands, "#SearchButton", I18n.translate(locale, "ui.admin.search"));
        setText(commands, "#ClearSearchButton", I18n.translate(locale, "ui.admin.clear"));
        setText(commands, "#AddItemRuleButton", I18n.translate(locale, "ui.admin.add_item"));
        setText(commands, "#AddBenchCraftRuleButton", I18n.translate(locale, "ui.admin.add_bench_craft"));
        setText(commands, "#AddBenchAccessRuleButton", I18n.translate(locale, "ui.admin.add_bench_access"));
        setText(commands, "#ReloadButton", I18n.translate(locale, "ui.admin.reload"));
        setText(commands, "#BackupButton", I18n.translate(locale, "ui.admin.backup"));
        setText(commands, "#ImportDryRunButton", I18n.translate(locale, "ui.admin.import_dry_run"));
        setText(commands, "#ImportApplyButton", I18n.translate(locale, "ui.admin.import_apply"));
        setText(commands, "#RulesLabel", I18n.translate(locale, "ui.admin.rules"));
        setText(commands, "#PreviousButton", I18n.translate(locale, "ui.common.previous"));
        setText(commands, "#NextButton", I18n.translate(locale, "ui.common.next"));
        setText(commands, "#ClosePageButton", I18n.translate(locale, "ui.common.close"));

        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            renderRuleRow(commands, config, filtered, slot);
        }

        int totalPages = Math.max(1, (int) Math.ceil(filtered.size() / (double) PAGE_SIZE));
        commands.set("#PageLabel.TextSpans", Message.raw(I18n.translate(locale, "ui.admin.page", page + 1, totalPages, filtered.size(), config.Rules.size())));
        commands.set("#PreviousButton.Visible", page > 0);
        commands.set("#NextButton.Visible", page + 1 < totalPages);
    }

    private void renderRuleRow(UICommandBuilder commands, PreventCraftConfig config, List<Integer> filtered, int slot) {
        int position = page * PAGE_SIZE + slot;
        boolean visible = position >= 0 && position < filtered.size();
        commands.set("#RuleRow" + slot + ".Visible", visible);
        if (!visible) {
            return;
        }

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

        String ruleName = rule.Id + (rule.Enabled ? "" : " (" + I18n.translate(locale, "ui.admin.inactive") + ")");
        commands.set("#RuleName" + slot + ".TextSpans", Message.raw(ruleName));
        commands.set("#RuleDescription" + slot + ".TextSpans", Message.raw(describeRule(rule)));
        renderIcon(commands, rule, slot);
    }

    private void renderIcon(UICommandBuilder commands, PreventRule rule, int slot) {
        String iconId = null;
        String fallback = rule.Type == RuleType.CRAFT_ITEM
                ? I18n.translate(locale, "ui.target_picker.kind_item")
                : I18n.translate(locale, "ui.target_picker.kind_bench");
        if (rule.Type == RuleType.CRAFT_ITEM) {
            iconId = safe(rule.Target);
        } else {
            iconId = vanillaBenchIcon(rule.Target);
        }
        boolean hasIcon = iconId != null && !iconId.isBlank();
        commands.set("#RuleIcon" + slot + ".Visible", hasIcon);
        if (hasIcon) {
            commands.set("#RuleIcon" + slot + ".ItemId", iconId);
        }
        commands.set("#RuleKindIcon" + slot + ".Visible", !hasIcon);
        commands.set("#RuleKindIcon" + slot + ".TextSpans", Message.raw(fallback));
    }

    private String describeRule(PreventRule rule) {
        String type = I18n.translate(locale, "ui.type." + rule.Type.name().toLowerCase(Locale.ROOT));
        String action = I18n.translate(locale, "ui.action." + rule.Action.name().toLowerCase(Locale.ROOT));
        String scope = switch (rule.Scope) {
            case EVERYONE -> I18n.translate(locale, "ui.scope.everyone");
            case GROUP -> I18n.translate(locale, "ui.scope.group") + ": " + safe(rule.Group);
            case PLAYER -> I18n.translate(locale, "ui.scope.player") + ": " + safe(rule.Player);
        };
        return type + " • " + action + " • " + scope + "\n" + ruleTargetLabel(rule);
    }

    private String ruleTargetLabel(PreventRule rule) {
        return safe(rule.Target);
    }

    private String translatedTargetForSearch(PreventRule rule) {
        try {
            if (rule.Type == RuleType.CRAFT_ITEM) {
                ItemCatalogEntry entry = plugin.getItemCatalog().describe(rule.Target, locale);
                return entry != null && entry.hasDisplayName() ? entry.displayName() : "";
            }
            BenchCatalogEntry entry = plugin.getBenchCatalog().describe(rule.Target, locale);
            return entry != null && entry.hasDisplayName() ? entry.displayName() : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private String vanillaBenchIcon(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (normalized.startsWith("bench_")) normalized = normalized.substring("bench_".length());
        return switch (normalized) {
            case "workbench", "work_bench" -> "Bench_WorkBench";
            case "builders", "builder", "builders_workbench", "builder_workbench", "builder's_workbench" -> "Bench_Builders";
            case "campfire" -> "Bench_Campfire";
            case "furnace" -> "Bench_Furnace";
            case "anvil" -> "Bench_Anvil";
            case "forge" -> "Bench_Forge";
            case "loom" -> "Bench_Loom";
            default -> "";
        };
    }

    private void handleSlotAction(Ref<EntityStore> ref, Store<EntityStore> store, String action) {
        if (action.startsWith("edit:")) {
            int index = indexForSlot(parseSlot(action, "edit:"));
            if (index >= 0) {
                openEditor(ref, store, index, null, null);
            } else {
                refresh();
            }
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
        cachedFilterSignature = null;
        refresh();
    }

    private void clearSearch() {
        searchQuery = "";
        page = 0;
        pendingDeleteIndex = null;
        cachedFilterSignature = null;
        refresh();
    }

    private void reload() {
        ConfigOperationResult result = plugin.reloadConfig();
        status = result.success()
                ? I18n.translate(locale, "messages.reload_success", plugin.getRuleService().activeRuleCount())
                : I18n.translate(locale, "messages.reload_failed", result.message());
        invalidateCaches();
        refresh();
    }

    private void backup() {
        ConfigOperationResult result = plugin.createBackup("manual-ui");
        status = result.success()
                ? I18n.translate(locale, "messages.backup_success", result.message())
                : I18n.translate(locale, "messages.backup_failed", result.message());
        refresh();
    }

    private void importCraftRestrict(boolean dryRun) {
        PreventCraftConfig config = plugin.getPreventCraftConfig();
        CraftRestrictImportResult result = plugin.importCraftRestrict(config.Migration.CraftRestrictModeValue, dryRun, config.Migration.IncludeUsers);
        status = result.success()
                ? I18n.translate(locale, "messages.import_result", result.scannedHolders(), result.scannedNodes(), result.importedRules(), result.skippedDuplicates(), result.skippedWildcards(), result.skippedUnknown(), result.dryRun(), result.reportFile() == null ? "-" : result.reportFile().getFileName())
                : I18n.translate(locale, "messages.import_failed", result.message());
        invalidateCaches();
        refresh();
    }

    private void confirmDelete(int index) {
        PreventCraftConfig currentConfig = plugin.getPreventCraftConfig();
        if (index < 0 || index >= currentConfig.Rules.size() || !Integer.valueOf(index).equals(pendingDeleteIndex)) {
            pendingDeleteIndex = null;
            refresh();
            return;
        }
        PreventCraftConfig copy = plugin.getConfigManager().copy(currentConfig);
        String id = copy.Rules.get(index).Id;
        copy.Rules.remove(index);
        ConfigOperationResult result = plugin.saveConfig(copy);
        status = result.success() ? I18n.translate(locale, "messages.rule_deleted", id) : I18n.translate(locale, "messages.save_failed", result.message());
        pendingDeleteIndex = null;
        invalidateCaches();
        refresh();
    }

    private void cancelDelete() {
        pendingDeleteIndex = null;
        refresh();
    }

    private List<Integer> filteredIndexes(PreventCraftConfig config) {
        String signature = buildFilterSignature(config);
        if (signature.equals(cachedFilterSignature)) {
            return cachedFilteredIndexes;
        }
        String query = normalizeSearch(searchQuery);
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < config.Rules.size(); i++) {
            PreventRule rule = config.Rules.get(i);
            if (rule == null) {
                continue;
            }
            if (query.isBlank()) {
                indexes.add(i);
                continue;
            }
            String text = buildRuleSearchText(rule);
            if (text.contains(query)) {
                indexes.add(i);
            }
        }
        cachedFilteredIndexes = List.copyOf(indexes);
        cachedFilterSignature = signature;
        return cachedFilteredIndexes;
    }

    private String buildFilterSignature(PreventCraftConfig config) {
        return normalizeSearch(searchQuery) + "|" + config.Rules.size() + "|" + plugin.getRuleService().activeRuleCount();
    }

    private String buildRuleSearchText(PreventRule rule) {
        StringBuilder builder = new StringBuilder();
        appendSearch(builder, rule.Id);
        appendSearch(builder, rule.Type == null ? null : I18n.translate(locale, "ui.type." + rule.Type.name().toLowerCase(Locale.ROOT)));
        appendSearch(builder, rule.Action == null ? null : I18n.translate(locale, "ui.action." + rule.Action.name().toLowerCase(Locale.ROOT)));
        appendSearch(builder, rule.Scope == null ? null : I18n.translate(locale, "ui.scope." + rule.Scope.name().toLowerCase(Locale.ROOT)));
        appendSearch(builder, rule.Target);
        appendSearch(builder, rule.Group);
        appendSearch(builder, rule.Player);
        appendSearch(builder, rule.Note);
        appendSearch(builder, ruleTargetLabel(rule));
        appendSearch(builder, translatedTargetForSearch(rule));
        return builder.toString();
    }

    private void appendSearch(StringBuilder builder, String value) {
        String normalized = normalizeSearch(value);
        if (!normalized.isBlank()) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(normalized);
        }
    }

    private String normalizeSearch(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.replaceAll("\\p{Cntrl}", " ").trim();
        String decomposed = Normalizer.normalize(cleaned, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private int indexForSlot(int slot) {
        if (slot < 0 || slot >= PAGE_SIZE) {
            return -1;
        }
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
        if (page > max) {
            page = max;
        }
    }

    private int catalogItemCount() {
        if (cachedItemCount >= 0) {
            return cachedItemCount;
        }
        try {
            cachedItemCount = plugin.getItemCatalog().size();
        } catch (Throwable ignored) {
            cachedItemCount = 0;
        }
        return cachedItemCount;
    }

    private int catalogBenchCount() {
        if (cachedBenchCount >= 0) {
            return cachedBenchCount;
        }
        try {
            cachedBenchCount = plugin.getBenchCatalog().size();
        } catch (Throwable ignored) {
            cachedBenchCount = 0;
        }
        return cachedBenchCount;
    }

    private void invalidateCaches() {
        cachedFilterSignature = null;
        cachedItemCount = -1;
        cachedBenchCount = -1;
    }

    private String formatCatalogCount(int count) {
        return String.format(Locale.US, "%,d", count);
    }

    private String modeLabel(RestrictionMode mode) {
        RestrictionMode current = mode == null ? RestrictionMode.BLACKLIST : mode;
        return I18n.translate(locale, "ui.mode." + current.name().toLowerCase(Locale.ROOT));
    }

    private String yesNo(boolean value) {
        return value ? I18n.translate(locale, "ui.common.yes") : I18n.translate(locale, "ui.common.no");
    }

    private String safe(String value) {
        return value == null ? "" : value;
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
