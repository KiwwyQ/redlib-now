# Persistent debug signing keystore

`debug.keystore` is committed so every CI and local `assembleDebug` APK is
signed with the same certificate. That lets users install successive builds
as updates instead of uninstalling (which would wipe saved posts / settings).

| Field        | Value        |
|--------------|--------------|
| store pass   | `android`    |
| key pass     | `android`    |
| alias        | `redlibnow`  |
| validity     | 10000 days   |

Configured in `app/build.gradle.kts` under `signingConfigs.debug`.

This is intentional for an open-source sideload app. Do not reuse this
keystore for Play Store / production release builds.
