# Installation and administration

## Paper suite

Place `SimpleAdminSuiteLite-1.0.0.jar` in `plugins/`. Start Paper and open `/sal` as an operator. The independent data folder is `plugins/SimpleAdminSuiteLite/`; it contains `config.yml`, the SQLite database and its journal files, and the generated `bridge-link.properties` pairing file. Back up this folder with the server stopped, or use a consistent SQLite backup. Keep `identity-secret` stable: changing it changes newly calculated identifier hashes and separates them from the existing history.

No Simplex files are read or migrated automatically. Imports are optional administrative operations.

## Permissions and LuckPerms

Full level-4 operators have access independently of permission defaults; limited level-1/2/3 operators require explicit permission nodes. Non-operators require `simpleadminlite.admin` for full access, or `simpleadminlite.access` plus selected action permissions:

| Permission suffix | Access |
| --- | --- |
| `view` | Player database, profiles and account evidence |
| `devices` | Client metadata, saved IPs and device history |
| `ban` | Real account/ultra/IP/device bans and unbans |
| `kick` | Immediate session kicks |
| `links` | Staff-confirmed/excluded account links |
| `permissions` | Arbitrary local permission grants/denials; highly privileged |
| `roles` | Local role assignments; highly privileged |
| `restrictions` | Timed restriction templates |
| `transfer` | Import previews, selected import and vanilla export |
| `updates` | Geyser/Floodgate update checks and staging |
| `settings` | Prefix, role/template definitions, integration settings and reload; highly privileged |
| `audit` | Local administrative/security audit |

All nodes have `default: false`. Prefix each suffix with `simpleadminlite.`. Lite applies its own reversible Bukkit permission attachments; it does not overwrite LuckPerms' persisted groups or nodes. Expired/revoked Lite grants remove only Lite's attachment entries, leaving the underlying permissions intact. A local explicit denial wins over another local grant. Wildcards and permissions implemented internally by another plugin can have plugin-specific semantics; use that plugin's actual permission nodes. A Bukkit operator flag cannot be removed by denying an unrelated permission node.

The role definitions in `config.yml` are editable from Settings. Role assignments do not inherit to alt accounts. Individually configured rights and restriction templates can inherit; inspect the confirmation before applying a grant.

## Optional Bedrock support

Geyser and Floodgate are optional. Java-only servers can use the suite without either dependency. Native Bedrock forms use the installed Floodgate API, with a Geyser API fallback, and fall back to a protected Java inventory if a forms API is unavailable.

The current Geyser release emulates Java 26.3. On a Paper 1.21.11 server, install ViaVersion as required by Geyser's official setup instructions: https://geysermc.org/wiki/geyser/supported-versions/ . Lite itself does not require ViaVersion for Java-only use. Its updater skips new Geyser staging on servers below 26.3 when ViaVersion is absent.

For extended Bedrock device observations:

1. Install Geyser and Floodgate normally.
2. Place `SimpleSentinelBridge-1.0.0.jar` in **Geyser's `extensions/` folder**, not Paper's `plugins/` folder.
3. Start Paper once. Copy `plugins/SimpleAdminSuiteLite/bridge-link.properties` to the extension's data folder as `bridge.properties`, replacing its example file. Its ID is `simplesentinelbridge`; on Geyser-Spigot the folder is normally `plugins/Geyser-Spigot/extensions/simplesentinelbridge/`.
4. Restart Geyser/the server. The Suite dashboard should show `Sentinel: Connected` after the heartbeat.

The transport binds to loopback only, port `39093` by default. A remote Geyser proxy requires a loopback-bound authenticated tunnel; the bridge receiver is not a public API. Keep the generated pairing secret private. Copy an updated pairing file and restart Geyser after changing the receiver port/secret. No existing Simplex bridge settings are reused.

Floodgate supplies the authenticated XUID used to associate signed bridge observations with the backend account. Device/model/input/version fields are only recorded when the installed Geyser exposes them. An unavailable identifier stays unavailable. Lite does not invent a Java hardware identity or use a device model as a unique identifier.

## Bans and restrictions

The profile menu offers kick, account/ultra ban, individual rights, roles and restriction templates. Each security change has an inspection/confirmation screen. Supported durations include `30m`, `1h`, `7d`, `4w` and `permanent`.

- Account bans apply to the target UUID and optionally its strongly linked group.
- Ultra bans additionally reject sessions reporting devices previously observed on the banned profile, including not-yet-registered accounts. The target UUID remains banned even if its device changes.
- Device blocks check the current reported device identifier. Historical device use alone is not treated as the current device during normal login enforcement.
- Exact IP bans reject every account using that address. If a signed, associated Bedrock session is seen on that actively banned IP, its reported device becomes a derived block with the same expiry. Revoking the original IP rule removes the derived blocks.
- Shared IP *hints* never silently become account bans or device bans.
- Restriction templates apply their stored action flags until expiry. Editing a template affects new rules; existing rules retain the flags reviewed at creation.
- `/salrestrictions` lets a player see currently effective local permission/action restrictions.

Console/in-game command alternatives:

```text
/sal
/sal player <registered-name|uuid>
/sal ban <registered-name|uuid> <account|ultra> <duration> <true|false> <reason>
/sal unban <full-rule-id>
/sal kick <online-player> <reason>
/sal reload
/sal updates
```

For Java text entry, responses are captured privately, one field at a time. Type `.` to keep a default or `cancel` to stop. Input expires after two minutes. Bedrock uses native input forms.

## Optional imports

Open **Import / export bans** and choose a source. No row is selected initially. Inspect individual rows and exclusions, select specific rows or all supported rows, then confirm. Previews expire after ten minutes. Imports are transactional and idempotent: the same external record is not imported twice, even if a previously imported rule was later revoked. Expired, inactive, malformed and unsupported wildcard IP rows are skipped. Reasons and millisecond expiries are retained. An account-only LiteBans row's stored IP is never converted into an IP ban.

For LiteBans, configure a local **closed, consistent** H2 `.mv.db` or SQLite snapshot path and table prefix. The file is opened read-only. Live locked H2 files, encrypted files, remote MySQL/MariaDB, and incompatible older H2 formats are not silently converted or modified. Produce a compatible local snapshot/export for these cases. The default H2 connection uses username `sa` and an empty password; `imports.h2-username` and `imports.h2-password` can be configured for an authenticated snapshot. Source plugin bans remain controlled by the source plugin until you remove or disable that plugin separately.

## Export before removing Lite

Open **Preview Minecraft export** and inspect the targets. Confirm to write account/IP entries using Bukkit's official ban list API. Longer/permanent existing bans are preserved. Known strong alt accounts and known users of a blocked device can be exported as account bans. An export preview must be regenerated if account/rule identities change.

Vanilla cannot represent device identifiers, timed permission restrictions, account-link policies or future unknown alt accounts. Keep Lite installed if you need those protections. Verify the export completion before removing the JAR. The suite does not delete its database or remove existing vanilla/source bans automatically.

## Updates and optional client rules

Hourly checks cover locally installed Geyser-Spigot and Floodgate JARs only. Downloads use the official Geyser API, pinned build metadata for that check and its official SHA-256. Lite validates the plugin identity, main class, Java bytecode requirement and Minecraft `api-version`; incompatible builds are not staged. Valid updates go into Paper's configured update folder under the original installed filename. Admins joining while an update is staged are informed in English. Lite does not restart the server or overwrite a running JAR.

The optional Java client rules compare configured exact brands/channels. They default to disabled and log-only. They are not a complete anti-cheat and do not ban or correlate accounts based on those unverified signals. No external CheckHacks engine or app is required.
