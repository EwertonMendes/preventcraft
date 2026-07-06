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
    private static final int PAGE_SIZE = 6;

    private final PreventCraftPlugin plugin;
    private final PlayerRef viewerRef;
    private final String locale;
    private final int ruleIndex;
    private final int returnPage;
    private final String returnSearch;
    private final RuleEditorDraft draft;
    private int page;
    private String query;
    private String searchDraft;
    private String status = "";
    private List<TargetEntry> matches;

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
        this.searchDraft = this.query;
        this.matches = searchTargets(this.query);
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
            refreshPicker();
            return;
        }
        switch (data.action) {
            case "close" -> close();
            case "back" -> openEditor(ref, store, draft);
            case "search-value-changed" -> searchDraft = data.searchValue == null ? "" : data.searchValue;
            case "search" -> applySearch(data.searchValue == null ? searchDraft : data.searchValue);
            case "clear-search" -> applySearch("");
            case "previous" -> {
                page = Math.max(0, page - 1);
                refreshPicker();
            }
            case "next" -> {
                page = Math.min(lastPage(), page + 1);
                refreshPicker();
            }
            default -> {
                if (data.action.startsWith("select:")) {
                    select(ref, store, data.action);
                } else {
                    refreshPicker();
                }
            }
        }
    }

    private void bindEvents(UIEventBuilder events) {
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", event("close"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#BackButton", event("back"), false);
        events.addEventBinding(
                CustomUIEventBindingType.ValueChanged,
                "#SearchField",
                EventData.of("@SearchField", "#SearchField.Value").append("Action", "search-value-changed"),
                false
        );
        events.addEventBinding(CustomUIEventBindingType.Validating, "#SearchField", searchEvent(), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#SearchButton", searchEvent(), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#ClearSearchButton", event("clear-search"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#PreviousButton", event("previous"), false);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#NextButton", event("next"), false);
        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            events.addEventBinding(CustomUIEventBindingType.Activating, "#SelectButton" + slot, event("select:" + slot), false);
        }
    }

    private void applySearch(String rawQuery) {
        query = rawQuery == null ? "" : rawQuery.trim();
        searchDraft = query;
        matches = searchTargets(query);
        page = 0;
        refreshPicker();
    }

    private List<TargetEntry> searchTargets(String value) {
        try {
            status = "";
            if (draft.type == RuleType.CRAFT_ITEM) {
                List<ItemCatalogEntry> items = plugin.getItemCatalog().search(value, locale);
                List<TargetEntry> entries = new ArrayList<>(items.size());
                for (ItemCatalogEntry item : items) {
                    entries.add(new TargetEntry(
                            item.itemId(),
                            item.hasDisplayName() ? item.displayName() : item.itemId(),
                            item.itemId(),
                            I18n.translate(locale, "ui.target_picker.kind_item")
                    ));
                }
                return List.copyOf(entries);
            }
            List<BenchCatalogEntry> benches = plugin.getBenchCatalog().search(value, locale);
            List<TargetEntry> entries = new ArrayList<>(benches.size());
            for (BenchCatalogEntry bench : benches) {
                entries.add(new TargetEntry(
                        bench.benchId(),
                        bench.hasDisplayName() ? bench.displayName() : bench.benchId(),
                        bench.iconItemId(),
                        I18n.translate(locale, "ui.target_picker.kind_bench")
                ));
            }
            return List.copyOf(entries);
        } catch (RuntimeException exception) {
            status = I18n.translate(locale, "ui.target_picker.error");
            return List.of();
        }
    }

    private void select(Ref<EntityStore> ref, Store<EntityStore> store, String action) {
        int slot;
        try {
            slot = Integer.parseInt(action.substring("select:".length()));
        } catch (NumberFormatException | IndexOutOfBoundsException exception) {
            refreshPicker();
            return;
        }
        if (slot < 0 || slot >= PAGE_SIZE) {
            refreshPicker();
            return;
        }
        int index = page * PAGE_SIZE + slot;
        if (index < 0 || index >= matches.size()) {
            refreshPicker();
            return;
        }
        RuleEditorDraft updated = draft.copy();
        updated.target = matches.get(index).id();
        openEditor(ref, store, updated);
    }

    private void openEditor(Ref<EntityStore> ref, Store<EntityStore> store, RuleEditorDraft updatedDraft) {
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            status = I18n.translate(locale, "messages.player_not_found");
            refreshPicker();
            return;
        }
        player.getPageManager().openCustomPage(
                ref,
                store,
                new RuleEditorPage(viewerRef, plugin, ruleIndex, returnPage, returnSearch, updatedDraft.type, updatedDraft)
        );
    }

    private void render(UICommandBuilder commands) {
        if (page < 0) page = 0;
        if (page > lastPage()) page = lastPage();

        setText(commands, "#PickerTitle", I18n.translate(locale, "ui.target_picker.title"));
        setText(commands, "#Subtitle", I18n.translate(locale, draft.type == RuleType.CRAFT_ITEM ? "ui.target_picker.subtitle_item" : "ui.target_picker.subtitle_bench"));
        commands.set("#SearchField.Value", searchDraft);
        commands.set("#SearchField.PlaceholderText", I18n.translate(locale, "ui.target_picker.search_placeholder"));
        setText(commands, "#SearchButton", I18n.translate(locale, "ui.admin.search"));
        setText(commands, "#ClearSearchButton", I18n.translate(locale, "ui.admin.clear"));
        setText(commands, "#ResultsLabel", I18n.translate(locale, "ui.target_picker.results"));
        setText(commands, "#BackButton", I18n.translate(locale, "ui.common.back"));
        setText(commands, "#CloseButton", I18n.translate(locale, "ui.common.close"));
        setText(commands, "#PreviousButton", I18n.translate(locale, "ui.common.previous"));
        setText(commands, "#NextButton", I18n.translate(locale, "ui.common.next"));
        commands.set("#StatusLabel.TextSpans", Message.raw(status == null || status.isBlank()
                ? I18n.translate(locale, "ui.target_picker.results_count", matches.size())
                : status));
        commands.set("#PageLabel.TextSpans", Message.raw(I18n.translate(locale, "ui.target_picker.page", page + 1, lastPage() + 1, matches.size())));
        commands.set("#PreviousButton.Visible", page > 0);
        commands.set("#NextButton.Visible", page < lastPage());

        boolean empty = matches.isEmpty();
        commands.set("#NoResultsLabel.Visible", empty);
        if (empty) {
            String key = query.isBlank() ? "ui.target_picker.unavailable" : "ui.target_picker.no_results";
            commands.set("#NoResultsLabel.TextSpans", Message.raw(I18n.translate(locale, key)));
        }

        int start = page * PAGE_SIZE;
        for (int slot = 0; slot < PAGE_SIZE; slot++) {
            int index = start + slot;
            boolean visible = index >= 0 && index < matches.size();
            commands.set("#ResultRow" + slot + ".Visible", visible);
            if (!visible) {
                continue;
            }
            TargetEntry entry = matches.get(index);
            boolean hasIcon = entry.iconItemId() != null && !entry.iconItemId().isBlank();
            commands.set("#ResultIcon" + slot + ".Visible", hasIcon);
            commands.set("#ResultKindIcon" + slot + ".Visible", !hasIcon);
            if (hasIcon) {
                commands.set("#ResultIcon" + slot + ".ItemId", entry.iconItemId());
            }
            commands.set("#ResultKindIcon" + slot + ".TextSpans", Message.raw(entry.kind()));
            commands.set("#ResultName" + slot + ".TextSpans", Message.raw(entry.displayName()));
            commands.set("#ResultId" + slot + ".TextSpans", Message.raw(entry.id()));
            setText(commands, "#SelectButton" + slot, I18n.translate(locale, "ui.target_picker.select"));
        }
    }

    private int lastPage() {
        return matches.isEmpty() ? 0 : Math.max(0, (matches.size() - 1) / PAGE_SIZE);
    }

    private void refreshPicker() {
        UICommandBuilder commands = new UICommandBuilder();
        render(commands);
        sendUpdate(commands, false);
    }

    private EventData event(String action) {
        return EventData.of("Action", action);
    }

    private EventData searchEvent() {
        return EventData.of("Action", "search").append("@SearchField", "#SearchField.Value");
    }

    private void setText(UICommandBuilder commands, String selector, String value) {
        commands.set(selector + ".TextSpans", Message.raw(value));
    }

    private record TargetEntry(String id, String displayName, String iconItemId, String kind) {
    }

    public static final class PickerEventData {
        public static final BuilderCodec<PickerEventData> CODEC = BuilderCodec.builder(PickerEventData.class, PickerEventData::new)
                .append(new KeyedCodec<>("Action", Codec.STRING), (data, value) -> data.action = value, data -> data.action).add()
                .append(new KeyedCodec<>("@SearchField", Codec.STRING), (data, value) -> data.searchValue = value, data -> data.searchValue).add()
                .build();

        public String action = "";
        public String searchValue = "";

        public PickerEventData() {
        }
    }
}
