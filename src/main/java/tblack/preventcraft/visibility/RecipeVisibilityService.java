package tblack.preventcraft.visibility;

import com.hypixel.hytale.protocol.CraftingRecipe;
import com.hypixel.hytale.protocol.UpdateType;
import com.hypixel.hytale.protocol.packets.assets.UpdateRecipes;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import tblack.preventcraft.PreventCraftPlugin;
import tblack.preventcraft.permissions.PermissionService;
import tblack.preventcraft.permissions.PlayerAuthorizationSnapshot;
import tblack.preventcraft.rule.RuleService;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Synchronizes player-specific recipe visibility through the official recipe asset
 * delta packet. No work in this class runs while a crafting window is opening.
 */
public final class RecipeVisibilityService {
    private final RuleService ruleService;
    private final PermissionService permissionService;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(new VisibilityThreadFactory());
    private final AtomicReference<RecipeCatalogSnapshot> catalog = new AtomicReference<>(RecipeCatalogSnapshot.empty());
    private final AtomicLong catalogGeneration = new AtomicLong();
    private final Map<UUID, PlayerVisibilityState> playerStates = new LinkedHashMap<>();
    private volatile boolean enabled = true;

    public RecipeVisibilityService(RuleService ruleService, PermissionService permissionService) {
        this.ruleService = ruleService;
        this.permissionService = permissionService;
    }

    public void configure(boolean hideBlockedRecipes) {
        enabled = hideBlockedRecipes;
    }

    public void requestCatalogRebuild() {
        executor.execute(this::rebuildCatalogNow);
    }

    public void synchronize(PermissionService.AuthorizationUpdate update) {
        if (update == null) return;
        synchronize(update.playerRef(), update.snapshot());
    }

    public void synchronize(PlayerRef playerRef, PlayerAuthorizationSnapshot authorization) {
        if (playerRef == null || authorization == null) return;
        executor.execute(() -> synchronizeNow(playerRef, authorization));
    }

    public void synchronizeAll() {
        List<PermissionService.AuthorizationUpdate> players = permissionService.onlineAuthorizations();
        executor.execute(() -> {
            for (PermissionService.AuthorizationUpdate player : players) {
                synchronizeNow(player.playerRef(), player.snapshot());
            }
        });
    }

    public void playerDisconnected(UUID uuid) {
        if (uuid == null) return;
        executor.execute(() -> playerStates.remove(uuid));
    }

    public void shutdown() {
        executor.shutdownNow();
        playerStates.clear();
    }

    private void rebuildCatalogNow() {
        try {
            Map<String, com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe> assets =
                    com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe.getAssetMap().getAssetMap();
            if (assets == null || assets.isEmpty()) return;

            Map<String, RecipeEntry> recipes = new LinkedHashMap<>(assets.size());
            for (Map.Entry<String, com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe> asset : assets.entrySet()) {
                String recipeId = clean(asset.getKey());
                com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe recipe = asset.getValue();
                if (recipeId == null || recipe == null) continue;
                MaterialQuantity primaryOutput = recipe.getPrimaryOutput();
                String outputItemId = primaryOutput == null ? null : clean(primaryOutput.getItemId());
                if (outputItemId == null) continue;
                recipes.put(recipeId, new RecipeEntry(outputItemId, recipe));
            }
            if (recipes.isEmpty()) return;

            long generation = catalogGeneration.incrementAndGet();
            catalog.set(new RecipeCatalogSnapshot(Collections.unmodifiableMap(recipes), generation));
            PreventCraftPlugin.LOGGER.atFine().log("Recipe visibility catalog rebuilt with %s recipes", recipes.size());

            for (PermissionService.AuthorizationUpdate player : permissionService.onlineAuthorizations()) {
                synchronizeNow(player.playerRef(), player.snapshot());
            }
        } catch (Throwable throwable) {
            PreventCraftPlugin.LOGGER.atFine().log("Recipe visibility catalog refresh deferred: %s", throwable.getMessage());
        }
    }

    private void synchronizeNow(PlayerRef playerRef, PlayerAuthorizationSnapshot authorization) {
        UUID uuid;
        try {
            if (!playerRef.isValid()) return;
            uuid = playerRef.getUuid();
        } catch (Throwable ignored) {
            return;
        }
        if (uuid == null) return;

        RecipeCatalogSnapshot currentCatalog = catalog.get();
        Set<String> hidden = enabled
                ? computeHiddenRecipes(currentCatalog, authorization)
                : Set.of();
        PlayerVisibilityState previous = playerStates.get(uuid);

        boolean catalogChanged = previous == null || previous.catalogGeneration() != currentCatalog.generation();
        Set<String> previousHidden = previous == null ? Set.of() : previous.hiddenRecipeIds();
        Set<String> restore = difference(previousHidden, hidden);
        Set<String> remove = catalogChanged ? hidden : difference(hidden, previousHidden);

        try {
            if (!restore.isEmpty()) sendRestoredRecipes(playerRef, currentCatalog, restore);
            if (!remove.isEmpty()) sendRemovedRecipes(playerRef, remove);
            playerStates.put(uuid, new PlayerVisibilityState(hidden, currentCatalog.generation()));
        } catch (Throwable throwable) {
            PreventCraftPlugin.LOGGER.atFine().log("Recipe visibility sync failed for %s: %s", uuid, throwable.getMessage());
        }
    }

    private Set<String> computeHiddenRecipes(
            RecipeCatalogSnapshot currentCatalog,
            PlayerAuthorizationSnapshot authorization
    ) {
        if (currentCatalog.recipes().isEmpty()) return Set.of();
        Set<String> hidden = new LinkedHashSet<>();
        for (Map.Entry<String, RecipeEntry> recipe : currentCatalog.recipes().entrySet()) {
            if (!ruleService.decideCraft(authorization, recipe.getValue().outputItemId()).allowed()) {
                hidden.add(recipe.getKey());
            }
        }
        return hidden.isEmpty() ? Set.of() : Collections.unmodifiableSet(hidden);
    }

    private void sendRestoredRecipes(PlayerRef playerRef, RecipeCatalogSnapshot currentCatalog, Set<String> restore) {
        Map<String, CraftingRecipe> restored = new LinkedHashMap<>(restore.size());
        for (String recipeId : restore) {
            RecipeEntry entry = currentCatalog.recipes().get(recipeId);
            if (entry != null) restored.put(recipeId, entry.recipe().toPacket(recipeId));
        }
        if (restored.isEmpty()) return;
        playerRef.getPacketHandler().writeNoCache(new UpdateRecipes(UpdateType.AddOrUpdate, restored, null));
    }

    private void sendRemovedRecipes(PlayerRef playerRef, Set<String> remove) {
        String[] recipeIds = remove.toArray(String[]::new);
        playerRef.getPacketHandler().writeNoCache(new UpdateRecipes(UpdateType.Remove, null, recipeIds));
    }

    private Set<String> difference(Set<String> left, Set<String> right) {
        if (left.isEmpty()) return Set.of();
        List<String> values = new ArrayList<>();
        for (String value : left) if (!right.contains(value)) values.add(value);
        return values.isEmpty() ? Set.of() : Set.copyOf(values);
    }

    private String clean(String value) {
        if (value == null) return null;
        String cleaned = value.replaceAll("\\p{Cntrl}", "").trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private record RecipeEntry(
            String outputItemId,
            com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe recipe
    ) {
    }

    private record RecipeCatalogSnapshot(Map<String, RecipeEntry> recipes, long generation) {
        private static RecipeCatalogSnapshot empty() {
            return new RecipeCatalogSnapshot(Map.of(), 0L);
        }
    }

    private record PlayerVisibilityState(Set<String> hiddenRecipeIds, long catalogGeneration) {
    }

    private static final class VisibilityThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "PreventCraft-RecipeVisibility");
            thread.setDaemon(true);
            return thread;
        }
    }
}
