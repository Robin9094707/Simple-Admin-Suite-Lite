# Simple Admin Suite Lite

An independent, English-only Paper security plugin with native Java inventory menus and optional native Bedrock forms. Version **1.0.0**.

## Scope

- `/sal` opens the security dashboard. Full level-4 operators have access automatically. Other accounts need explicit permissions; none are granted to ordinary players by default.
- Local UUID-based player profiles, online/offline player browsing, client insight, bounded IP history and reported Bedrock device history.
- Real account bans, ultra bans, exact IP bans, individual device blocks, timed bans and immediate kicks.
- Alternative-account evidence, strong account groups, explicit confirmed/excluded links and optional inherited sanctions.
- Local admin roles and individual permission grants/denials with expiry; denial wins over another local grant. LuckPerms remains installed and usable.
- Restriction templates for chat, commands, building, interactions, inventory access and combat.
- Optional, explicitly selected imports from Minecraft ban lists and closed local LiteBans H2/SQLite database snapshots, with preview, exclusions, reasons and expiry preservation.
- Export known active bans and known linked accounts to Minecraft's official ban lists before removing Lite.
- Hourly official Geyser/Floodgate checks, verified compatible update staging and English admin join notifications. No automatic server restart.
- Optional Discord webhook for blocked logins / possible ban evasion. No Discord bot or mobile app integration.
- Editable prefix, admin roles, restriction templates, client access rules, bridge settings and configuration reload.

There is no iOS app, panel API, bot, automatic data migration, observation tab, AFK system, maintenance mode, server control, world controls, reports, anti-spam, AI moderation, team messages or Quick Delivery module.

## Install

1. Put `SimpleAdminSuiteLite-1.0.0.jar` into Paper's `plugins/` folder and start the server.
2. Use `/sal` as an operator, or assign `simpleadminlite.admin` deliberately through LuckPerms, for example `/lp user YourName permission set simpleadminlite.admin true`.
3. For Bedrock metadata, install the optional **SimpleSentinelBridge** Geyser extension and copy the generated pairing file as described in [INSTALLATION.md](INSTALLATION.md).

The suite targets Paper **1.21.11 and 26.3**, using public Bukkit/Paper APIs and Java 17-compatible plugin bytecode. The server still needs its own required Java runtime: Java 21 for 1.21.11, Java 25 for 26.x. Future versions require validation; they are not guaranteed automatically. Folia is not supported.

## Build and test

```sh
python3 tools/build.py all --test --compatibility
```

Use Java 25 for the full build/test matrix. All compile dependencies are downloaded from official project/Maven endpoints and checked against `tools/dependencies.json`. No stub server APIs are used or packaged. The plugin includes SQLite and H2 for its own durable database and optional local import. It does not require an external database or a first-start driver download.

Outputs are two JARs, two separate source ZIPs and checksums in `dist/`. Each source archive includes the build script and dependency lock; it can build its module with `python3 tools/build.py suite` or `python3 tools/build.py sentinel-bridge`.

See [INSTALLATION.md](INSTALLATION.md), [SECURITY-MODEL.md](SECURITY-MODEL.md) and [VALIDATION.md](VALIDATION.md).

The existing `Robin9094707/PANELAPios` repository was used only as a read-only reference. This repository is independent and has its own package names, plugin names, data folder, bridge ID, secret and port.
