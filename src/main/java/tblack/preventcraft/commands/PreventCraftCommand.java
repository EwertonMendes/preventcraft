package tblack.preventcraft.commands;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractCommandCollection;
import com.hypixel.hytale.server.core.command.system.basecommands.AbstractPlayerCommand;
import com.hypixel.hytale.server.core.command.system.basecommands.CommandBase;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import tblack.preventcraft.ModConstants;
import tblack.preventcraft.PreventCraftPlugin;
import tblack.preventcraft.config.ConfigOperationResult;
import tblack.preventcraft.config.PreventCraftConfig;
import tblack.preventcraft.importer.CraftRestrictImportResult;
import tblack.preventcraft.importer.CraftRestrictMode;
import tblack.preventcraft.i18n.I18n;
import tblack.preventcraft.ui.PreventCraftAdminPage;
import tblack.preventcraft.util.Chat;

import java.util.UUID;

public final class PreventCraftCommand extends AbstractPlayerCommand {
    private final PreventCraftPlugin plugin;

    public PreventCraftCommand(PreventCraftPlugin plugin) {
        super(plugin.getPreventCraftConfig().Commands.Primary, I18n.commandKey("commands.root.description"));
        this.plugin = plugin;
        PreventCraftConfig config = plugin.getPreventCraftConfig();
        if (config.Commands.Aliases != null && !config.Commands.Aliases.isEmpty()) {
            addAliases(config.Commands.Aliases.toArray(String[]::new));
        }
        addSubCommand(new UiCommand(plugin));
        addSubCommand(new ReloadCommand(plugin));
        addSubCommand(new StatusCommand(plugin));
        addSubCommand(new BackupCommand(plugin));
        addSubCommand(new ValidateCommand(plugin));
        addSubCommand(new ImportCommand(plugin));
    }

    @Override
    protected boolean canGeneratePermission() {
        return false;
    }

    @Override
    protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef playerRef, World world) {
        openPanel(plugin, context, store, ref, playerRef);
    }

    private static void openPanel(PreventCraftPlugin plugin, CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef playerRef) {
        if (!canUse(plugin, context)) {
            Chat.send(context, Chat.error(context, "messages.no_permission"));
            return;
        }
        if (!plugin.getPreventCraftConfig().Enabled) {
            Chat.send(context, Chat.error(context, "messages.disabled"));
            return;
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null) {
            Chat.send(context, Chat.error(context, "messages.player_not_found"));
            return;
        }
        player.getPageManager().openCustomPage(ref, store, new PreventCraftAdminPage(playerRef, plugin, 0, "", ""));
    }

    private static boolean canUse(PreventCraftPlugin plugin, CommandContext context) {
        return hasPermission(plugin, context, plugin.getPreventCraftConfig().Commands.UsePermission)
                || hasPermission(plugin, context, ModConstants.UI_PERMISSION)
                || canAdmin(plugin, context);
    }

    private static boolean canAdmin(PreventCraftPlugin plugin, CommandContext context) {
        return hasPermission(plugin, context, plugin.getPreventCraftConfig().Commands.AdminPermission)
                || hasPermission(plugin, context, ModConstants.ADMIN_PERMISSION);
    }

    private static boolean canReload(PreventCraftPlugin plugin, CommandContext context) {
        return canAdmin(plugin, context) || hasPermission(plugin, context, ModConstants.RELOAD_PERMISSION);
    }

    private static boolean canImport(PreventCraftPlugin plugin, CommandContext context) {
        return canAdmin(plugin, context) || hasPermission(plugin, context, ModConstants.IMPORT_PERMISSION);
    }

    private static boolean canBackup(PreventCraftPlugin plugin, CommandContext context) {
        return canAdmin(plugin, context) || hasPermission(plugin, context, ModConstants.BACKUP_PERMISSION);
    }

    private static boolean canStatus(PreventCraftPlugin plugin, CommandContext context) {
        return canAdmin(plugin, context) || hasPermission(plugin, context, ModConstants.STATUS_PERMISSION);
    }

    private static boolean hasPermission(PreventCraftPlugin plugin, CommandContext context, String permission) {
        if (permission == null || permission.isBlank()) return false;
        try {
            if (context.sender().hasPermission(permission)
                    || context.sender().hasPermission(ModConstants.ADMIN_PERMISSION)
                    || context.sender().hasPermission("*")) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            UUID uuid = context.sender().getUuid();
            return plugin.getPermissionService().hasPermission(uuid, permission);
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static final class UiCommand extends AbstractPlayerCommand {
        private final PreventCraftPlugin plugin;

        private UiCommand(PreventCraftPlugin plugin) {
            super("ui", I18n.commandKey("commands.ui.description"));
            this.plugin = plugin;
        }

        @Override
        protected boolean canGeneratePermission() {
            return false;
        }

        @Override
        protected void execute(CommandContext context, Store<EntityStore> store, Ref<EntityStore> ref, PlayerRef playerRef, World world) {
            openPanel(plugin, context, store, ref, playerRef);
        }
    }

    private static final class ReloadCommand extends CommandBase {
        private final PreventCraftPlugin plugin;

        private ReloadCommand(PreventCraftPlugin plugin) {
            super("reload", I18n.commandKey("commands.reload.description"));
            this.plugin = plugin;
        }

        @Override
        protected boolean canGeneratePermission() {
            return false;
        }

        @Override
        protected void executeSync(CommandContext context) {
            if (!canReload(plugin, context)) {
                Chat.send(context, Chat.error(context, "messages.no_permission"));
                return;
            }
            ConfigOperationResult result = plugin.reloadConfig();
            Chat.send(context, result.success()
                    ? Chat.success(context, "messages.reload_success", plugin.getRuleService().activeRuleCount())
                    : Chat.error(context, "messages.reload_failed", result.message()));
        }
    }

    private static final class StatusCommand extends CommandBase {
        private final PreventCraftPlugin plugin;

        private StatusCommand(PreventCraftPlugin plugin) {
            super("status", I18n.commandKey("commands.status.description"));
            this.plugin = plugin;
        }

        @Override
        protected boolean canGeneratePermission() {
            return false;
        }

        @Override
        protected void executeSync(CommandContext context) {
            if (!canStatus(plugin, context)) {
                Chat.send(context, Chat.error(context, "messages.no_permission"));
                return;
            }
            PreventCraftConfig config = plugin.getPreventCraftConfig();
            Chat.send(context, Chat.info(context, "messages.status", config.Enabled, config.Mode.name(), config.HideBlockedRecipes, plugin.getRuleService().activeRuleCount(), config.Rules.size(), plugin.getPermissionService().isLuckPermsAvailable(), plugin.getConfigManager().configFile()));
        }
    }

    private static final class BackupCommand extends CommandBase {
        private final PreventCraftPlugin plugin;

        private BackupCommand(PreventCraftPlugin plugin) {
            super("backup", I18n.commandKey("commands.backup.description"));
            this.plugin = plugin;
        }

        @Override
        protected boolean canGeneratePermission() {
            return false;
        }

        @Override
        protected void executeSync(CommandContext context) {
            if (!canBackup(plugin, context)) {
                Chat.send(context, Chat.error(context, "messages.no_permission"));
                return;
            }
            ConfigOperationResult result = plugin.createBackup("manual-command");
            Chat.send(context, result.success()
                    ? Chat.success(context, "messages.backup_success", plugin.getConfigManager().backupsDirectory())
                    : Chat.error(context, "messages.backup_failed", result.message()));
        }
    }

    private static final class ValidateCommand extends CommandBase {
        private final PreventCraftPlugin plugin;

        private ValidateCommand(PreventCraftPlugin plugin) {
            super("validate", I18n.commandKey("commands.validate.description"));
            this.plugin = plugin;
        }

        @Override
        protected boolean canGeneratePermission() {
            return false;
        }

        @Override
        protected void executeSync(CommandContext context) {
            if (!canStatus(plugin, context)) {
                Chat.send(context, Chat.error(context, "messages.no_permission"));
                return;
            }
            ConfigOperationResult result = plugin.reloadConfig();
            Chat.send(context, result.success()
                    ? Chat.success(context, "messages.validation_success", plugin.getPreventCraftConfig().Rules.size())
                    : Chat.error(context, "messages.validation_failed", result.message()));
        }
    }

    private static final class ImportCommand extends AbstractCommandCollection {
        private ImportCommand(PreventCraftPlugin plugin) {
            super("import", I18n.commandKey("commands.import.description"));
            addSubCommand(new ImportCraftRestrictCommand(plugin, "craftrestrict", null, true));
            addSubCommand(new ImportCraftRestrictCommand(plugin, "craftrestrict-apply", null, false));
            addSubCommand(new ImportCraftRestrictCommand(plugin, "craftrestrict-allow", CraftRestrictMode.ALLOW, true));
            addSubCommand(new ImportCraftRestrictCommand(plugin, "craftrestrict-allow-apply", CraftRestrictMode.ALLOW, false));
            addSubCommand(new ImportCraftRestrictCommand(plugin, "craftrestrict-deny", CraftRestrictMode.DENY, true));
            addSubCommand(new ImportCraftRestrictCommand(plugin, "craftrestrict-deny-apply", CraftRestrictMode.DENY, false));
        }

        @Override
        protected boolean canGeneratePermission() {
            return false;
        }
    }

    private static final class ImportCraftRestrictCommand extends CommandBase {
        private final PreventCraftPlugin plugin;
        private final CraftRestrictMode explicitMode;
        private final boolean dryRun;

        private ImportCraftRestrictCommand(PreventCraftPlugin plugin, String command, CraftRestrictMode explicitMode, boolean dryRun) {
            super(command, I18n.commandKey("commands.import.craftrestrict.description"));
            this.plugin = plugin;
            this.explicitMode = explicitMode;
            this.dryRun = dryRun;
        }

        @Override
        protected boolean canGeneratePermission() {
            return false;
        }

        @Override
        protected void executeSync(CommandContext context) {
            if (!canImport(plugin, context)) {
                Chat.send(context, Chat.error(context, "messages.no_permission"));
                return;
            }
            CraftRestrictMode mode = explicitMode == null ? plugin.getPreventCraftConfig().Migration.CraftRestrictModeValue : explicitMode;
            boolean includeUsers = plugin.getPreventCraftConfig().Migration.IncludeUsers;
            CraftRestrictImportResult result = plugin.importCraftRestrict(mode, dryRun, includeUsers);
            Chat.send(context, result.success()
                    ? Chat.success(context, "messages.import_result", result.scannedHolders(), result.scannedNodes(), result.importedRules(), result.skippedDuplicates(), result.skippedWildcards(), result.skippedUnknown(), result.dryRun(), result.reportFile())
                    : Chat.error(context, "messages.import_failed", result.message()));
        }
    }
}
