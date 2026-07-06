package tblack.preventcraft.packets;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.inventory.MaterialQuantity;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import tblack.preventcraft.PreventCraftPlugin;

final class RecipePackets {
    private RecipePackets() {
    }

    static String outputItemId(String recipeId) {
        if (recipeId == null || recipeId.isBlank()) return null;
        try {
            CraftingRecipe recipe = (CraftingRecipe) CraftingRecipe.getAssetMap().getAsset(recipeId);
            if (recipe == null) return null;
            MaterialQuantity output = recipe.getPrimaryOutput();
            if (output == null) return null;
            return output.getItemId();
        } catch (Throwable ignored) {
            return null;
        }
    }

    static boolean shouldHideRecipe(PreventCraftPlugin plugin, PlayerRef playerRef, String recipeId) {
        String outputItemId = outputItemId(recipeId);
        return outputItemId != null && plugin.getRuleService().shouldHideRecipe(playerRef, outputItemId);
    }

    static JsonArray filteredRecipes(PreventCraftPlugin plugin, PlayerRef playerRef, JsonArray source) {
        JsonArray filtered = new JsonArray();
        if (source == null) return filtered;
        for (JsonElement element : source) {
            String recipeId;
            try {
                recipeId = element.getAsString();
            } catch (Throwable ignored) {
                filtered.add(element);
                continue;
            }
            if (recipeId.endsWith("_Restricted")) continue;
            if (!shouldHideRecipe(plugin, playerRef, recipeId)) filtered.add(recipeId);
        }
        return filtered;
    }

    static boolean hasArray(JsonObject object, String name) {
        return object != null && object.has(name) && object.get(name).isJsonArray();
    }
}
