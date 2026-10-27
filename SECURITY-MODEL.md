# Security and data model

The core stores immutable profile snapshots and sanction rules in its own SQLite database, using WAL, full synchronization, parameterized statements and transactional administrative writes. Normal profile/audit/import storage runs on a bounded worker queue. No database password or remote service is needed for normal operation.

## Account evidence

Only shared reported Bedrock device IDs, authenticated Xbox account identities and explicit staff-confirmed links form automatic strong account groups. Explicit exclusions are considered across an entire component, so indirect links cannot silently re-add an excluded pair. The grouping is deterministic and cached until identity/link data changes.

IPs and self-signed client IDs are unconfirmed hints. Java brands, locale, protocol, input settings, registered plugin channels, inferred mod names and a device model are never account identity signals. The server cannot reliably discover a Java player's physical device or full installed-mod list. Bedrock device IDs are client reported, resettable and potentially spoofable; a strong account group is an operational correlation, not proof that two accounts belong to one human.

Raw Bedrock device/XUID/self-signed identifiers are not persisted. Values are HMAC-SHA256 hashed with a separate per-installation identity secret and domain prefix. IP history is stored as exact addresses for the explicitly requested administrative history/IP-ban functions. Access to history requires explicit admin permissions. No private messages or ordinary chat content are stored by Lite.

## Bridge trust boundary

The optional extension forwards bounded Java Properties payloads through a loopback-only HTTP receiver. Both request and response bodies use HMAC-SHA256. Requests require a recent timestamp and a unique nonce from a bounded replay cache. Endpoint, method, source, size and field counts are validated. The backend associates a snapshot using the Floodgate/Geyser authenticated XUID and rejects a conflicting Java UUID. A valid transport signature authenticates the bridge, not the client's hardware claims.

The receiver has no generic command execution, file management, app API, bot integration or remote administration route. A remote proxy must use a local tunnel. No legacy settings or player data are automatically migrated.

## Administrative boundaries

Only full operators or explicitly assigned permission nodes can use the suite. Every menu action rechecks its permission. Native form callbacks use per-session generations; stale/duplicate callbacks are rejected. Java menu items cannot be moved through click, shift-click, number-key swap, drag or inventory transfer. Ordinary players receive no permission by default.

Local admin roles never inherit to alt accounts. Individual rights can be configured to inherit. Arbitrary permission/role/template/settings management is intentionally highly privileged because those operations can grant further administrative power.

## Bans and transfer

Only active rules enforce; permanent uses an explicit zero expiry. An IP-derived device block retains its parent rule and expiry. Revoking the parent removes the derived effects. An inherited rule remains traceable to its original target and can be revoked at that source.

Imports never modify their source database. Local SQL imports use read-only URLs, fixed SELECT statements and validated table identifiers. Unsupported records remain visible as exclusions. The selected rows and import markers commit together; source keys prevent duplicates. Import previews do not authorize an unselected row.

Vanilla exports retain expiries/reasons and preserve stronger existing bans. They cover known UUIDs and exact IPs only. Future unknown account correlation/device bans cannot continue after the suite is removed.

The optional Discord webhook only sends a player name/UUID, rule reference and rule type for a blocked login/possible evasion. It has no bot token, disables mentions, rate limits repeated events and does not send raw IPs or device IDs. A rejected login can also be an ordinary retry by an already banned account; the notification is labelled as a possible evasion, not a confirmed finding.
