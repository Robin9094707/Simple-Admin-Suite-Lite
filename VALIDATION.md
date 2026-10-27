# Validation

The release build uses the real pinned Paper 1.21.11 API and the official Geyser 2.12.1 build 1273 JAR for the extension API. It also compiles the suite against Paper API 26.3.218-stable. No compile-time stubs are packaged or used.

Run `python3 tools/build.py all --test --compatibility` on Java 25. The automated tests cover account correlation/exclusions, inheritance, real account/device/IP bans, expiry, derived-device revocation, permission expiry, profile/rule/link persistence, bounded histories, selected/idempotent transactional imports, read-only SQLite/H2 previews, vanilla date/type handling, HMAC/replay validation, download bounds and update API/Java checks.

Run `python3 tools/smoke.py` for pinned real Paper startup checks. The harness starts an isolated test server, exercises configuration reload and console ban/unban commands, checks persistence, and stops it cleanly. It may download Mojang/Paper runtime components on first use. These tests are not a substitute for live Java and Bedrock client testing on the deployment's own Geyser/proxy/permission setup.

Detailed results and any runtime limitations are recorded in `dist/VALIDATION-RESULTS.txt` by the release process. Compatibility with untested future Minecraft/Geyser versions is not guaranteed.
