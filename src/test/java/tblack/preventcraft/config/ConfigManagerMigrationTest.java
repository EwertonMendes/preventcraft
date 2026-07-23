package tblack.preventcraft.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigManagerMigrationTest {
    @Test
    void oldSchemaIsMigratedToCatalogBasedRecipeVisibility(@TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("preventcraft.json"), """
                {
                  "SchemaVersion": 2,
                  "Enabled": true,
                  "Mode": "BLACKLIST",
                  "HideBlockedRecipes": false,
                  "Rules": []
                }
                """, StandardCharsets.UTF_8);

        ConfigManager manager = new ConfigManager(directory);
        ConfigOperationResult result = manager.loadInitial();

        assertTrue(result.success());
        assertEquals(PreventCraftConfig.CURRENT_SCHEMA_VERSION, result.config().SchemaVersion);
        assertTrue(result.config().HideBlockedRecipes);
    }
}
