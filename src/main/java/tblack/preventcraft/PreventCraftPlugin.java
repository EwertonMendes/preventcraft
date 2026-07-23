package tblack.preventcraft;

import com.hypixel.hytale.assetstore.event.LoadedAssetsEvent;
import com.hypixel.hytale.assetstore.event.RemovedAssetsEvent;
import com.hypixel.hytale.assetstore.map.DefaultAssetMap;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.asset.type.item.config.Item;
import com.hypixel.hytale.server.core.asset.type.item.config.CraftingRecipe;
import com.hypixel.hytale.server.core.event.events.permissions.GroupPermissionChangeEvent;
import com.hypixel.hytale.server.core.event.events.permissions.PlayerPermissionChangeEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
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
import tblack.preventcraft.feedback.NotificationService;
import tblack.preventcraft.permissions.PermissionService;
import tblack.preventcraft.rule.RuleService;
import tblack.preventcraft.systems.BenchAccessSystem;
import tblack.preventcraft.systems.CraftRestrictionSystem;
import tblack.preventcraft.visibility.RecipeVisibilityService;

import java.nio.file.Paths;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class PreventCraftPlugin extends JavaPlugin {
    public static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static PreventCraftPlugin instance;

    private final ConfigManager configManager;
    private final PermissionService permissionService = new PermissionService();
    private final HytaleItemCatalog itemCatalog = new HytaleItemCatalog();
    private final HytaleBenchCatalog benchCatalog = new HytaleBenchCatalog(itemCatalog);
    private final RuleService ruleService = new RuleService(permissionService, benchCatalog);
    private final RecipeVisibilityService recipeVisibilityService = new RecipeVisibilityService(ruleService, permissionService);
    private final CraftRestrictImporter craftRestrictImporter;
    private final ScheduledExecutorService assetRefreshExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "PreventCraft-AssetRefresh");
        thread.setDaemon(true);
        return thread;
    });
    private volatile ScheduledFuture<?> pendingAssetRefresh;
    private PreventCraftConfig config;

    public PreventCraftPlugin(JavaPluginInit init) {
        super(init);
        configManager = new ConfigManager(Paths.get("mods", ModConstants.MOD_FOLDER));
        craftRestrictImporter = new CraftRestrictImporter(configManager, permissionService, itemCatalog);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void setup() {
        super.setup();
        instance = this;

        ConfigOperationResult load = configManager.loadInitial();
        config = load.config();
        refreshRuntime(config);
        refreshPermissions();
        permissionService.setAuthorizationListener(recipeVisibilityService::synchronize);
        recipeVisibilityService.requestCatalogRebuild();

        getCommandRegistry().registerCommand(new PreventCraftCommand(this));
        registerSystems();
        registerPlayerAuthorizationEvents();
        permissionService.registerLuckPermsListener(this);

        getEventRegistry().register(LoadedAssetsEvent.class, Item.class, this::onItemAssetsLoaded);
        getEventRegistry().register(LoadedAssetsEvent.class, BlockType.class, this::onBlockAssetsLoaded);
        getEventRegistry().register(LoadedAssetsEvent.class, CraftingRecipe.class, this::onRecipeAssetsLoaded);
        getEventRegistry().register(RemovedAssetsEvent.class, Item.class, this::onItemAssetsRemoved);
        getEventRegistry().register(RemovedAssetsEvent.class, BlockType.class, this::onBlockAssetsRemoved);
        getEventRegistry().register(RemovedAssetsEvent.class, CraftingRecipe.class, this::onRecipeAssetsRemoved);

        if (!load.success()) {
            LOGGER.atWarning().log("Configuration load failed: %s", load.message());
        }
        LOGGER.atInfo().log("Loaded %s %s with %s active rules", ModConstants.MOD_NAME, getManifest().getVersion(), ruleService.activeRuleCount());
    }

    @Override
    protected void shutdown() {
        ScheduledFuture<?> refresh = pendingAssetRefresh;
        if (refresh != null) refresh.cancel(false);
        assetRefreshExecutor.shutdownNow();
        recipeVisibilityService.shutdown();
        permissionService.shutdown();
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
        if (config == null) config = configManager.get();
        return config;
    }

    public synchronized ConfigOperationResult reloadConfig() {
        ConfigOperationResult result = configManager.reload();
        if (result.success()) {
            config = result.config();
            refreshRuntime(config);
            refreshPermissions();
            LOGGER.atInfo().log("Configuration reloaded from %s", configManager.configFile());
        } else {
            LOGGER.atWarning().log("Configuration reload failed: %s", result.message());
        }
        return result;
    }

    public synchronized ConfigOperationResult saveConfig(PreventCraftConfig candidate) {
        ConfigOperationResult result = configManager.save(candidate);
        if (result.success()) {
            config = result.config();
            refreshRuntime(config);
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
            config = configManager.get();
            refreshRuntime(config);
            refreshPermissions();
        }
        return result;
    }

    private void refreshRuntime(PreventCraftConfig config) {
        ruleService.rebuild(config);
        recipeVisibilityService.configure(config != null && config.HideBlockedRecipes);
        recipeVisibilityService.synchronizeAll();
    }

    private void refreshPermissions() {
        permissionService.clearCache();
        PreventCraftConfig activeConfig = getPreventCraftConfig();
        permissionService.register(activeConfig.Commands.AdminPermission);
        permissionService.register(activeConfig.Commands.UsePermission);
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
            getEntityStoreRegistry().registerSystem(new CraftRestrictionSystem(this));
        } catch (Throwable throwable) {
            LOGGER.atWarning().withCause(throwable).log("Gameplay restriction systems could not be registered.");
        }
    }

    private void registerPlayerAuthorizationEvents() {
        getEventRegistry().registerGlobal(PlayerReadyEvent.class, event -> permissionService.playerReady(
                event.getPlayerRef().getStore().getComponent(event.getPlayerRef(), com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType())));
        getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, event -> {
            permissionService.playerDisconnected(event.getPlayerRef().getUuid());
            recipeVisibilityService.playerDisconnected(event.getPlayerRef().getUuid());
            NotificationService.clear(event.getPlayerRef().getUuid());
        });
        getEventRegistry().registerGlobal(PlayerPermissionChangeEvent.GroupAdded.class, event -> permissionService.refreshAuthorization(event.getPlayerUuid()));
        getEventRegistry().registerGlobal(PlayerPermissionChangeEvent.GroupRemoved.class, event -> permissionService.refreshAuthorization(event.getPlayerUuid()));
        getEventRegistry().registerGlobal(PlayerPermissionChangeEvent.PermissionsAdded.class, event -> permissionService.refreshAuthorization(event.getPlayerUuid()));
        getEventRegistry().registerGlobal(PlayerPermissionChangeEvent.PermissionsRemoved.class, event -> permissionService.refreshAuthorization(event.getPlayerUuid()));
        getEventRegistry().registerGlobal(GroupPermissionChangeEvent.Added.class, event -> permissionService.refreshAllAuthorizations());
        getEventRegistry().registerGlobal(GroupPermissionChangeEvent.Removed.class, event -> permissionService.refreshAllAuthorizations());
    }

    private void onItemAssetsLoaded(LoadedAssetsEvent<String, Item, DefaultAssetMap<String, Item>> event) {
        itemCatalog.invalidate();
        benchCatalog.invalidate();
        scheduleAssetRefresh();
    }

    private void onBlockAssetsLoaded(LoadedAssetsEvent<String, BlockType, DefaultAssetMap<String, BlockType>> event) {
        benchCatalog.invalidate();
        scheduleAssetRefresh();
    }

    private void onRecipeAssetsLoaded(LoadedAssetsEvent<String, CraftingRecipe, DefaultAssetMap<String, CraftingRecipe>> event) {
        scheduleAssetRefresh();
    }

    private void onItemAssetsRemoved(RemovedAssetsEvent<String, Item, DefaultAssetMap<String, Item>> event) {
        itemCatalog.invalidate();
        benchCatalog.invalidate();
        scheduleAssetRefresh();
    }

    private void onBlockAssetsRemoved(RemovedAssetsEvent<String, BlockType, DefaultAssetMap<String, BlockType>> event) {
        benchCatalog.invalidate();
        scheduleAssetRefresh();
    }

    private void onRecipeAssetsRemoved(RemovedAssetsEvent<String, CraftingRecipe, DefaultAssetMap<String, CraftingRecipe>> event) {
        scheduleAssetRefresh();
    }

    private synchronized void scheduleAssetRefresh() {
        ScheduledFuture<?> previous = pendingAssetRefresh;
        if (previous != null) previous.cancel(false);
        pendingAssetRefresh = assetRefreshExecutor.schedule(() -> {
            try {
                itemCatalog.size();
                benchCatalog.size();
                refreshRuntime(getPreventCraftConfig());
                recipeVisibilityService.requestCatalogRebuild();
            } catch (Throwable throwable) {
                LOGGER.atFine().log("Catalog refresh deferred: %s", throwable.getMessage());
            }
        }, 250L, TimeUnit.MILLISECONDS);
    }
}
