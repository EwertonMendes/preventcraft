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
import tblack.preventcraft.i18n.I18n;
import tblack.preventcraft.rule.RuleType;

import java.util.ArrayList;
import java.util.List;

public final class TargetPickerPage extends InteractiveCustomUIPage<TargetPickerPage.PickerEventData> {
    private static final String LAYOUT = "PreventCraft/TargetPicker.ui";
    private static final int PAGE_SIZE = 8;

    private final PreventCraftPlugin plugin;
    private final PlayerRef viewerRef;
    private final String locale;
    private final int ruleIndex;
    private final int returnPage;
    private final String returnSearch;
    private final RuleEditorDraft draft;
    private int page;
    private String query;
    private List<TargetEntry> lastResults = List.of();

    public TargetPickerPage(PlayerRef playerRef, PreventCraftPlugin plugin, int ruleIndex, int returnPage, String returnSearch, RuleEditorDraft draft, int page, String query) {
        super(playerRef, CustomPageLifetime.CanDismiss, PickerEventData.CODEC);
        this.plugin = plugin;
        this.viewerRef = playerRef;
        this.locale = I18n.localeFromPlayerRef(playerRef);
        this.ruleIndex = ruleIndex;
        this.returnPage = Math.max(0, returnPage);
        this.returnSearch = returnSearch == null ? "" : returnSearch;
        this.draft = draft == null ? new RuleEditorDraft() : draft.copy();
        this.page = Math.max(0, page);
        this.query = query == null ? "" : query.trim();
    }

    @Override
    public void build(Ref<EntityStore> ref, UICommandBuilder commands, UIEventBuilder events, Store<EntityStore> store) {
        commands.append(LAYOUT);
        bindEvents(events);
        render(commands);
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> ref, Store<EntityStore> store, PickerEventData data) {
        super.handleDataEvent(ref, store, data);
        if (data == null || data.action == null || data.action.isBlank()) {
            refresh();
            return;
        }
        switch (data.action) {
            case "close" -> close();
            case "back" -> openEditor(ref, store);
            case "search" -> search(data.searchQuery);
            case "clear-search" -> search("");
            case "previous" -> previous();
            case "next" -> next();
            default -> handleSelect(ref, store, data.action);
        }
    }

    private void bindEvents(UIEventBuilder events) {
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ClosePageButton", event("close"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BackButton", event("back"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#SearchButton", searchEvent(), false);
        events.addEventBinding(CustomUIEventBindingType.Validating, "#SearchField", searchEvent(), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ClearSearchButton", event("clear-search"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#PreviousButton", event("previous"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#NextButton", event("next"), false);
        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            events.addEventBinding(CustomUIEventBindingType.Activating, "#SelectButton" + slot, event("select:" + slot), false);
        }
    }

    private void render(UICommandBuilder commands) {
        lastResults = searchResults();
        clampPage(lastResults.size());
        commands.set("#PickerTitle.TextSpans", Message.raw(I18n.translate(locale, "ui.target_picker.title")));
        commands.set("#SubtitleLabel.TextSpans", Message.raw(I18n.translate(locale, draft.type == RuleType.CRAFT_ITEM ? "ui.target_picker.subtitle_item" : "ui.target_picker.subtitle_bench")));
        commands.set("#SearchField.Value", query);
        setText(commands, "#SearchButton", I18n.translate(locale, "ui.admin.search"));
        setText(commands, "#ClearSearchButton", I18n.translate(locale, "ui.admin.clear"));
        setText(commands, "#ResultsLabel", I18n.translate(locale, "ui.target_picker.results"));
        setText(commands, "#PreviousButton", I18n.translate(locale, "ui.common.previous"));
        setText(commands, "#NextButton", I18n.translate(locale, "ui.common.next"));
        setText(commands, "#BackButton", I18n.translate(locale, "ui.common.back"));
        setText(commands, "#ClosePageButton", I18n.translate(locale, "ui.common.close"));
        for (int slot = 0; slot < PAGE_SIZE; slot++) renderResult(commands, slot);
        int totalPages = Math.max(1, (int) Math.ceil(lastResults.size() / (double) PAGE_SIZE));
        commands.set("#PageLabel.TextSpans", Message.raw(I18n.translate(locale, "ui.admin.page", page + 1, totalPages, lastResults.size(), lastResults.size())));
        commands.set("#PreviousButton.Visible", page > 0);
        commands.set("#NextButton.Visible", page + 1 < totalPages);
    }

    private void renderResult(UICommandBuilder commands, int slot) {
        int index = page * PAGE_SIZE + slot;
        boolean visible = index >= 0 && index < lastResults.size();
        commands.set("#ResultRow" + slot + ".Visible", visible);
        if (!visible) return;
        TargetEntry entry = lastResults.get(index);
        boolean hasIcon = entry.iconItemId() != null && !entry.iconItemId().isBlank();
        commands.set("#ResultIcon" + slot + ".Visible", hasIcon);
        commands.set("#ResultKindIcon" + slot + ".Visible", !hasIcon);
        if (hasIcon) commands.set("#ResultIcon" + slot + ".ItemId", entry.iconItemId());
        commands.set("#ResultKindIcon" + slot + ".TextSpans", Message.raw(entry.kind()));
        commands.set("#ResultName" + slot + ".TextSpans", Message.raw(entry.displayName() == null || entry.displayName().isBlank() ? entry.id() : entry.displayName()));
        commands.set("#ResultId" + slot + ".TextSpans", Message.raw(entry.id()));
        setText(commands, "#SelectButton" + slot, I18n.translate(locale, "ui.target_picker.select"));
    }

    private void handleSelect(Ref<EntityStore> ref, Store<EntityStore> store, String action) {
        if (!action.startsWith("select:")) {
            refresh();
            return;
        }
        int slot;
        try {
            slot = Integer.parseInt(action.substring("select:".length()));
        } catch (NumberFormatException exception) {
            refresh();
            return;
        }
        List<TargetEntry> results = searchResults();
        int index = page * PAGE_SIZE + slot;
        if (index < 0 || index >= results.size()) {
            refresh();
            return;
        }
        RuleEditorDraft updated = draft.copy();
        updated.target = results.get(index).id();
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player != null) {
            player.getPageManager().openCustomPage(ref, store, new RuleEditorPage(viewerRef, plugin, ruleIndex, returnPage, returnSearch, updated.type, updated));
        }
    }

    private List<TargetEntry> searchResults() {
        if (draft.type == RuleType.CRAFT_ITEM) {
            List<ItemCatalogEntry> items = plugin.getItemCatalog().search(query, locale);
            List<TargetEntry> entries = new ArrayList<>(items.size());
            for (ItemCatalogEntry item : items) entries.add(new TargetEntry(item.itemId(), item.displayName(), item.itemId(), "ITEM"));
            return entries;
        }
        List<BenchCatalogEntry> benches = plugin.getBenchCatalog().search(query, locale);
        List<TargetEntry> entries = new ArrayList<>(benches.size());
        for (BenchCatalogEntry bench : benches) entries.add(new TargetEntry(bench.benchId(), bench.displayName(), bench.iconItemId(), "BENCH"));
        return entries;
    }

    private void search(String value) {
        query = value == null ? "" : value.trim();
        page = 0;
        refresh();
    }

    private void previous() {
        page = Math.max(0, page - 1);
        refresh();
    }

    private void next() {
        page++;
        refresh();
    }

    private void clampPage(int totalItems) {
        int totalPages = Math.max(1, (int) Math.ceil(totalItems / (double) PAGE_SIZE));
        if (page >= totalPages) page = totalPages - 1;
        if (page < 0) page = 0;
    }

    private void openEditor(Ref<EntityStore> ref, Store<EntityStore> store) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player != null) player.getPageManager().openCustomPage(ref, store, new RuleEditorPage(viewerRef, plugin, ruleIndex, returnPage, returnSearch, draft.type, draft));
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

    private record TargetEntry(String id, String displayName, String iconItemId, String kind) {
    }

    public static final class PickerEventData {
        public static final BuilderCodec<PickerEventData> CODEC = BuilderCodec.builder(PickerEventData.class, PickerEventData::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (data, value) -> data.action = value, data -> data.action).add()
                .append(new KeyedCodec<>("@SearchQuery", Codec.STRING), (data, value) -> data.searchQuery = value, data -> data.searchQuery).add()
                .build();

        public String action = "";
        public String searchQuery = "";

        public PickerEventData() {
        }

        public PickerEventData(String action, String searchQuery) {
            this.action = action;
            this.searchQuery = searchQuery;
        }
    }
}
