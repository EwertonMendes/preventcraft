package tblack.preventcraft.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import tblack.preventcraft.ModConstants;
import tblack.preventcraft.importer.CraftRestrictMode;
import tblack.preventcraft.rule.PreventRule;
import tblack.preventcraft.rule.RuleAction;
import tblack.preventcraft.rule.RuleScope;
import tblack.preventcraft.rule.RuleType;
import tblack.preventcraft.rule.RestrictionMode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

public final class ConfigManager {
    private static final DateTimeFormatter BACKUP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final Path modDirectory;
    private final Path configFile;
    private final Path backupsDirectory;
    private final Path reportsDirectory;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private PreventCraftConfig current;

    public ConfigManager(Path modDirectory) {
        this.modDirectory = modDirectory.toAbsolutePath().normalize();
        this.configFile = this.modDirectory.resolve("preventcraft.json");
        this.backupsDirectory = this.modDirectory.resolve("backups");
        this.reportsDirectory = this.modDirectory.resolve("reports");
    }

    public synchronized ConfigOperationResult loadInitial() {
        try {
            Files.createDirectories(modDirectory);
            Files.createDirectories(backupsDirectory);
            Files.createDirectories(reportsDirectory);
            if (!Files.exists(configFile)) {
                PreventCraftConfig created = normalize(new PreventCraftConfig());
                write(created);
                current = created;
                return ConfigOperationResult.success(copy(created), "Configuration created");
            }
            return readAndAccept();
        } catch (Exception exception) {
            PreventCraftConfig fallback = normalize(new PreventCraftConfig());
            current = fallback;
            return ConfigOperationResult.failure(copy(fallback), message(exception));
        }
    }

    public synchronized ConfigOperationResult reload() {
        if (!Files.exists(configFile)) return loadInitial();
        return readAndAccept();
    }

    public synchronized ConfigOperationResult save(PreventCraftConfig candidate) {
        PreventCraftConfig normalized = normalize(copy(candidate));
        try {
            write(normalized);
            current = normalized;
            return ConfigOperationResult.success(copy(normalized), "Configuration saved");
        } catch (IOException exception) {
            return ConfigOperationResult.failure(get(), message(exception));
        }
    }

    public synchronized ConfigOperationResult saveWithBackup(PreventCraftConfig candidate, String reason) {
        ConfigOperationResult backup = backup(reason);
        ConfigOperationResult save = save(candidate);
        if (!save.success()) return save;
        return backup.success() ? save : ConfigOperationResult.success(save.config(), save.message() + "; backup skipped: " + backup.message());
    }

    public synchronized ConfigOperationResult backup(String reason) {
        try {
            Files.createDirectories(backupsDirectory);
            if (!Files.exists(configFile)) return ConfigOperationResult.success(get(), "No config file to back up");
            String safeReason = safeFileName(reason == null ? "manual" : reason, "manual");
            Path backupFile = backupsDirectory.resolve("preventcraft-" + BACKUP_FORMAT.format(Instant.now()) + "-" + safeReason + ".json");
            Files.copy(configFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
            return ConfigOperationResult.success(get(), "Backup created: " + backupFile.getFileName());
        } catch (IOException exception) {
            return ConfigOperationResult.failure(get(), message(exception));
        }
    }

    public synchronized PreventCraftConfig get() {
        if (current == null) loadInitial();
        return copy(current);
    }

    public Path directory() {
        return modDirectory;
    }

    public Path configFile() {
        return configFile;
    }

    public Path backupsDirectory() {
        return backupsDirectory;
    }

    public Path reportsDirectory() {
        return reportsDirectory;
    }

    public Gson gson() {
        return gson;
    }

    private ConfigOperationResult readAndAccept() {
        PreventCraftConfig previous = current == null ? normalize(new PreventCraftConfig()) : current;
        String json = null;
        try {
            json = Files.readString(configFile, StandardCharsets.UTF_8);
            PreventCraftConfig parsed = gson.fromJson(json, PreventCraftConfig.class);
            if (parsed == null) throw new JsonParseException("Configuration is empty");
            PreventCraftConfig normalized = normalize(parsed);
            write(normalized);
            current = normalized;
            return ConfigOperationResult.success(copy(normalized), "Configuration loaded");
        } catch (Exception exception) {
            if (json != null) backupInvalid(json);
            current = previous;
            return ConfigOperationResult.failure(copy(previous), message(exception));
        }
    }

    public PreventCraftConfig normalize(PreventCraftConfig config) {
        if (config == null) config = new PreventCraftConfig();
        if (config.SchemaVersion <= 0) config.SchemaVersion = 1;
        if (config.SchemaVersion > PreventCraftConfig.CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported SchemaVersion " + config.SchemaVersion + ". Expected " + PreventCraftConfig.CURRENT_SCHEMA_VERSION + ".");
        }
        int sourceSchemaVersion = config.SchemaVersion;
        if (config.SchemaVersion < PreventCraftConfig.CURRENT_SCHEMA_VERSION) {
            config.SchemaVersion = PreventCraftConfig.CURRENT_SCHEMA_VERSION;
        }
        if (config.Mode == null) config.Mode = RestrictionMode.BLACKLIST;
        // Schema 2 forced this compatibility field off while recipe-window filtering
        // was retired. Schema 3 uses client recipe-catalog deltas instead, so old
        // schema 2 files are upgraded to the new non-blocking visibility behavior.
        if (sourceSchemaVersion < 3) config.HideBlockedRecipes = true;
        if (config.Commands == null) config.Commands = new PreventCraftConfig.Commands();
        if (config.Feedback == null) config.Feedback = new PreventCraftConfig.Feedback();
        if (config.Migration == null) config.Migration = new PreventCraftConfig.Migration();
        if (config.Migration.CraftRestrictModeValue == null) config.Migration.CraftRestrictModeValue = CraftRestrictMode.DENY;
        if (config.Rules == null) config.Rules = new ArrayList<>();

        config.Commands.Primary = normalizeCommand(config.Commands.Primary, "preventcraft");
        if (config.Commands.Aliases == null) config.Commands.Aliases = new ArrayList<>();
        LinkedHashSet<String> aliases = new LinkedHashSet<>();
        aliases.add("pcraft");
        for (String alias : config.Commands.Aliases) {
            String normalized = normalizeCommand(alias, "");
            if (!normalized.isBlank() && !normalized.equals(config.Commands.Primary)) aliases.add(normalized);
        }
        config.Commands.Aliases = new ArrayList<>(aliases);

        config.Commands.UsePermission = normalizePermission(config.Commands.UsePermission, ModConstants.USE_PERMISSION);
        config.Commands.AdminPermission = normalizePermission(config.Commands.AdminPermission, ModConstants.ADMIN_PERMISSION);

        config.Feedback.CraftDeniedMessage = fallback(config.Feedback.CraftDeniedMessage, "You cannot craft this item.");
        config.Feedback.BenchCraftDeniedMessage = fallback(config.Feedback.BenchCraftDeniedMessage, "You cannot craft this bench.");
        config.Feedback.BenchAccessDeniedMessage = fallback(config.Feedback.BenchAccessDeniedMessage, "You cannot use this bench.");
        config.Feedback.DeniedSound = fallback(config.Feedback.DeniedSound, "SFX_Antelope_Alerted");

        if (config.Rules.size() > PreventCraftConfig.MAX_RULES) {
            config.Rules = new ArrayList<>(config.Rules.subList(0, PreventCraftConfig.MAX_RULES));
        }
        normalizeRules(config.Rules);
        return config;
    }

    private void normalizeRules(ArrayList<PreventRule> ignored) {
        normalizeRules((java.util.List<PreventRule>) ignored);
    }

    private void normalizeRules(java.util.List<PreventRule> rules) {
        Set<String> ids = new LinkedHashSet<>();
        int number = 1;
        for (PreventRule rule : rules) {
            if (rule == null) continue;
            if (rule.Type == null) rule.Type = RuleType.CRAFT_ITEM;
            if (rule.Action == null) rule.Action = RuleAction.DENY;
            if (rule.Scope == null) rule.Scope = RuleScope.EVERYONE;
            rule.Id = normalizeRuleId(rule.Id, "rule-" + number);
            while (!ids.add(rule.Id.toLowerCase(Locale.ROOT))) {
                rule.Id = normalizeRuleId(rule.Id + "-" + number, "rule-" + number);
            }
            rule.Target = normalizeTarget(rule.Target);
            rule.Group = rule.Group == null ? "" : rule.Group.trim().toLowerCase(Locale.ROOT);
            rule.Player = rule.Player == null ? "" : rule.Player.trim();
            rule.Note = rule.Note == null ? "" : rule.Note.trim();
            if (rule.Scope == RuleScope.EVERYONE) {
                rule.Group = "";
                rule.Player = "";
            } else if (rule.Scope == RuleScope.GROUP) {
                rule.Player = "";
            } else if (rule.Scope == RuleScope.PLAYER) {
                rule.Group = "";
            }
            number++;
        }
    }

    private String normalizeCommand(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
    }

    private String normalizePermission(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeRuleId(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "-");
        while (normalized.contains("--")) normalized = normalized.replace("--", "-");
        return normalized.isBlank() ? fallback : normalized;
    }

    private String normalizeTarget(String value) {
        if (value == null) return "";
        return value.replaceAll("\\p{Cntrl}", "").trim();
    }

    private String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private String safeFileName(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String sanitized = value.replace('\\', '/').trim();
        while (sanitized.startsWith("/")) sanitized = sanitized.substring(1);
        return sanitized.replaceAll("\\.\\./", "").replaceAll("[\\p{Cntrl}]", "").replaceAll("[^a-zA-Z0-9._-]", "-");
    }

    public PreventCraftConfig copy(PreventCraftConfig source) {
        if (source == null) return normalize(new PreventCraftConfig());
        return gson.fromJson(gson.toJson(source), PreventCraftConfig.class);
    }

    private void write(PreventCraftConfig config) throws IOException {
        Files.createDirectories(modDirectory);
        Path temporary = configFile.resolveSibling("preventcraft.json.tmp");
        Files.writeString(temporary, gson.toJson(config), StandardCharsets.UTF_8);
        try {
            Files.move(temporary, configFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, configFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void backupInvalid(String content) {
        try {
            Files.createDirectories(backupsDirectory);
            Files.writeString(backupsDirectory.resolve("preventcraft.invalid." + Instant.now().toEpochMilli() + ".json"), content, StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    private String message(Exception exception) {
        String value = exception.getMessage();
        return value == null || value.isBlank() ? exception.getClass().getSimpleName() : value;
    }
}
