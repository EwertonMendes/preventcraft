# PreventCraft

**PreventCraft** is a modern Hytale server-side mod for controlling item crafting, bench crafting and bench access with a clean Custom UI, a simple JSON file and optional LuckPerms integration.

It was designed as a direct improvement over older permission-only crafting restriction workflows. Admins can manage restrictions visually, or edit the JSON manually when they prefer file-based administration.

## What it solves

PreventCraft separates three different problems that are often mixed together:

1. **Craft an item** — block or allow the final output item of a recipe.
2. **Craft a bench** — block or allow crafting the bench item itself.
3. **Access an existing bench** — block or allow opening/using a bench that already exists in the world.

That distinction matters because a server may want players to find a bench in the world but not craft it, or allow crafting a bench but restrict who can use advanced stations.

## Main features

- Custom UI admin panel: `/preventcraft` or `/pcraft`.
- Searchable item picker by translated name or item id.
- Searchable bench picker by bench id, block id, craft item id or translated name when available.
- Supports vanilla items, vanilla benches, modded items and modded benches through runtime registries.
- Simple JSON configuration in `mods/Tblack_PreventCraft/preventcraft.json`.
- Safe reload with `/preventcraft reload`.
- Manual backups with `/preventcraft backup`.
- Dry-run and apply import from CraftRestrict LuckPerms nodes.
- Optional LuckPerms support for group rules and migration.
- Bypass permissions for admins or trusted roles.
- Server-side enforcement for actual crafting and bench usage.
- Optional recipe hiding from crafting windows when the server can safely filter recipe packets.
- Language files for the currently visible Hytale language list: English, Portuguese (Brazil), Russian, Ukrainian and Simplified Chinese.

## Commands

```text
/preventcraft
/pcraft
/preventcraft ui
/preventcraft reload
/preventcraft status
/preventcraft validate
/preventcraft backup
/preventcraft import craftrestrict
/preventcraft import craftrestrict-apply
/preventcraft import craftrestrict-allow
/preventcraft import craftrestrict-allow-apply
/preventcraft import craftrestrict-deny
/preventcraft import craftrestrict-deny-apply
```

Import commands without `-apply` run as dry-run and only generate a report. Commands with `-apply` write new rules to JSON after creating a backup.

## Permissions

```text
preventcraft.use
preventcraft.ui
preventcraft.admin
preventcraft.reload
preventcraft.status
preventcraft.backup
preventcraft.import
preventcraft.bypass
preventcraft.bypass.craft
preventcraft.bypass.bench
```

`preventcraft.admin` is accepted anywhere a specific management permission is required.

## JSON configuration

The generated config is intentionally small and readable:

```json
{
  "SchemaVersion": 1,
  "Enabled": true,
  "Mode": "BLACKLIST",
  "HideBlockedRecipes": true,
  "Debug": false,
  "Commands": {
    "Primary": "preventcraft",
    "Aliases": ["pcraft"],
    "UsePermission": "preventcraft.use",
    "AdminPermission": "preventcraft.admin"
  },
  "Feedback": {
    "SendCraftDeniedMessage": true,
    "SendBenchDeniedMessage": true,
    "SendDeniedSound": true,
    "CraftDeniedMessage": "You cannot craft this item.",
    "BenchCraftDeniedMessage": "You cannot craft this bench.",
    "BenchAccessDeniedMessage": "You cannot use this bench.",
    "DeniedSound": "SFX_Antelope_Alerted"
  },
  "Migration": {
    "CraftRestrictMode": "DENY",
    "IncludeUsers": false
  },
  "Rules": []
}
```

## Rule format

```json
{
  "Id": "block-crude-arrow-default",
  "Enabled": true,
  "Type": "CRAFT_ITEM",
  "Target": "weapon_arrow_crude",
  "Action": "DENY",
  "Scope": "GROUP",
  "Group": "default",
  "Player": "",
  "Note": "Optional admin note"
}
```

### Type

- `CRAFT_ITEM`: controls crafting the output item of a recipe.
- `CRAFT_BENCH`: controls crafting a bench item.
- `ACCESS_BENCH`: controls opening/using an existing bench in the world.

### Action

- `DENY`: blocks the action.
- `ALLOW`: allows the action.

### Scope

- `EVERYONE`: applies to all players.
- `GROUP`: applies to a LuckPerms group.
- `PLAYER`: applies to a specific username or UUID.

## Modes

### BLACKLIST

Everything is allowed by default. Add `DENY` rules to block specific crafts or benches.

This is the safest default for most servers.

### WHITELIST

Everything is blocked by default. Add `ALLOW` rules to unlock specific crafts or benches.

This is useful for RPG progression, quests or ranked unlocks.

## Rule priority

PreventCraft keeps priority predictable:

1. Bypass permissions win first.
2. Player-specific rules beat group rules.
3. Group rules beat global rules.
4. `DENY` wins over `ALLOW` when rules have the same specificity.
5. If no explicit rule matches, the current `Mode` decides.

## CraftRestrict migration

PreventCraft can read old CraftRestrict LuckPerms nodes:

```text
craftrestrict.recipe.<item_id>
craftrestrict.bench.<bench_id>
```

Migration behavior:

- `craftrestrict.recipe.*` nodes become `CRAFT_ITEM` rules.
- `craftrestrict.bench.*` nodes become `ACCESS_BENCH` rules.
- Duplicates are skipped.
- Wildcards are reported but not expanded into thousands of rules.
- A JSON report is written under `mods/Tblack_PreventCraft/reports/`.
- A backup is created before apply imports.

LuckPerms is optional for normal global rules, but required for migration and group-based rules.

## Folder layout

```text
mods/
  Tblack_PreventCraft/
    preventcraft.json
    backups/
    reports/
```

## Development notes

This project was generated from the Tblack Hytale mod template and keeps the same publishing conventions:

- Gradle Wrapper included.
- `manifest.json` values expanded from `gradle.properties`.
- Shadow JAR output.
- PolyForm Noncommercial license.
- Optional dependency on LuckPerms.
- Custom UI layouts under `Common/UI/Custom/PreventCraft/`.
- Server language files under `Server/Languages/<locale>/server.lang`.

## Build

```bash
./gradlew clean build
```

Windows:

```bat
gradlew.bat clean build
```

For local server testing:

```bash
./gradlew runServer
```

If your Hytale install is in a custom location, configure `hytale_home`, `server_jar` or `assets_zip` in `gradle.properties`.

## Release checklist

Before publishing a real release:

1. Replace `icon-256.png` with final branding.
2. Review `gradle.properties` version.
3. Build with `./gradlew clean build --no-build-cache --rerun-tasks --warning-mode all`.
4. Test `/preventcraft`, rule creation, reload, backup and CraftRestrict dry-run import on a local server.
5. Test at least one vanilla item, one vanilla bench and one modded bench if available.
6. Confirm that rule denial messages and recipe hiding behave correctly on the target Hytale version.
