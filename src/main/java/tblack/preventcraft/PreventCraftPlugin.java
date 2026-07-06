package tblack.preventcraft;

import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import tblack.preventcraft.catalog.HytaleBenchCatalog;
import tblack.preventcraft.catalog.HytaleItemCatalog;
import tblack.preventcraft.commands.PreventCraftCommand;
import tblack.preventcraft.config.ConfigManager;
import tblack.preventcraft.config.ConfigOperationResult;
import tblack.preventcraft.config.PreventCraftConfig;
import tblack.preventcraft.importer.CraftRestrictImportResult;
import tblack.preventcraft.importer.CraftRestrictImporter;
import tblack.preventcraft.importer.CraftRestrictMode;
import tblack.preventcraft.packets.PacketManager;
import tblack.preventcraft.permissions.PermissionService;
import tblack.preventcraft.rule.RuleService;
import tblack.preventcraft.systems.BenchAccessSystem;

import java.nio.file.Paths;

public final class PreventCraftPlugin extends JavaPlugin {
    public static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static PreventCraftPlugin instance;

    private final ConfigManager configManager;
    private final PermissionService permissionService = new PermissionService();
    private final HytaleItemCatalog itemCatalog = new HytaleItemCatalog();
    private final HytaleBenchCatalog benchCatalog = new HytaleBenchCatalog(itemCatalog);
    private final RuleService ruleService = new RuleService(permissionService, benchCatalog);
    private final PacketManager packetManager = new PacketManager(this);
    private final CraftRestrictImporter craftRestrictImporter;

    public PreventCraftPlugin(JavaPluginInit init) {
        super(init);
        configManager = new ConfigManager(Paths.get("mods", ModConstants.MOD_FOLDER));
        craftRestrictImporter = new CraftRestrictImporter(configManager, permissionService);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void setup() {
        super.setup();
        instance = this;

        ConfigOperationResult load = configManager.loadInitial();
        refreshRuntime(load.config());
        refreshPermissions();

        getCommandRegistry().registerCommand(new PreventCraftCommand(this));
        registerSystems();
        packetManager.register();

        getEventRegistry().register(LoadedAssetsEvent.class, Item.class, this::onItemAssetsLoaded);
        getEventRegistry().register(LoadedAssetsEvent.class, BlockType.class, this::onBlockAssetsLoaded);

        if (!load.success()) {
            LOGGER.atWarning().log("Configuration load failed: %s", load.message());
        }
        LOGGER.atInfo().log("Loaded %s %s with %s active rules", ModConstants.MOD_NAME, getManifest().getVersion(), ruleService.activeRuleCount());
    }

    @Override
    protected void shutdown() {
        packetManager.unregister();
        super.shutdown();
    }

    public static PreventCraftPlugin getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public PermissionService getPermissionService() {
        return permissionService;
    }

    public HytaleItemCatalog getItemCatalog() {
        return itemCatalog;
    }

    public HytaleBenchCatalog getBenchCatalog() {
        return benchCatalog;
    }

    public RuleService getRuleService() {
        return ruleService;
    }

    public synchronized PreventCraftConfig getPreventCraftConfig() {
        return configManager.get();
    }

    public synchronized ConfigOperationResult reloadConfig() {
        ConfigOperationResult result = configManager.reload();
        if (result.success()) {
            refreshRuntime(result.config());
            refreshPermissions();
            LOGGER.atInfo().log("Configuration reloaded from %s", configManager.configFile());
        } else {
            LOGGER.atWarning().log("Configuration reload failed: %s", result.message());
        }
        return result;
    }

    public synchronized ConfigOperationResult saveConfig(PreventCraftConfig candidate) {
        ConfigOperationResult result = configManager.saveWithBackup(candidate, "before-ui-save");
        if (result.success()) {
            refreshRuntime(result.config());
            refreshPermissions();
            LOGGER.atInfo().log("Configuration saved to %s", configManager.configFile());
        } else {
            LOGGER.atWarning().log("Configuration save failed: %s", result.message());
        }
        return result;
    }

    public synchronized ConfigOperationResult createBackup(String reason) {
        return configManager.backup(reason == null || reason.isBlank() ? "manual" : reason);
    }

    public synchronized CraftRestrictImportResult importCraftRestrict(CraftRestrictMode mode, boolean dryRun, boolean includeUsers) {
        CraftRestrictImportResult result = craftRestrictImporter.importRules(mode, dryRun, includeUsers);
        if (result.success() && !dryRun) {
            refreshRuntime(configManager.get());
            refreshPermissions();
        }
        return result;
    }

    private void refreshRuntime(PreventCraftConfig config) {
        ruleService.rebuild(config);
    }

    private void refreshPermissions() {
        permissionService.clearCache();
        PreventCraftConfig config = configManager.get();
        permissionService.register(config.Commands.AdminPermission);
        permissionService.register(config.Commands.UsePermission);
        permissionService.register(ModConstants.ADMIN_PERMISSION);
        permissionService.register(ModConstants.UI_PERMISSION);
        permissionService.register(ModConstants.RELOAD_PERMISSION);
        permissionService.register(ModConstants.IMPORT_PERMISSION);
        permissionService.register(ModConstants.BACKUP_PERMISSION);
        permissionService.register(ModConstants.STATUS_PERMISSION);
        permissionService.register(ModConstants.BYPASS_PERMISSION);
        permissionService.register(ModConstants.BYPASS_CRAFT_PERMISSION);
        permissionService.register(ModConstants.BYPASS_BENCH_PERMISSION);
    }

    private void registerSystems() {
        try {
            getEntityStoreRegistry().registerSystem(new BenchAccessSystem(this));
        } catch (Throwable throwable) {
            LOGGER.atWarning().withCause(throwable).log("Bench access system could not be registered.");
        }
    }

    private void onItemAssetsLoaded(LoadedAssetsEvent<String, Item, DefaultAssetMap<String, Item>> event) {
        itemCatalog.invalidate();
        LOGGER.atInfo().log("Item catalog invalidated after assets loaded.");
    }

    private void onBlockAssetsLoaded(LoadedAssetsEvent<String, BlockType, DefaultAssetMap<String, BlockType>> event) {
        benchCatalog.invalidate();
        LOGGER.atInfo().log("Bench catalog invalidated after block assets loaded.");
    }
}
