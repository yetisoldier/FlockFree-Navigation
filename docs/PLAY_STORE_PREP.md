# FlockFree Navigation — Google Play Store Preparation

## Status Tracker

| Step | Status | Notes |
|------|--------|-------|
| 1. Release keystore | ✅ Done | `keystores/flockfree-release.keystore`, alias `flockfree`, valid until 2053 |
| 2. Signing config updated | ✅ Done | `build.gradle` publishing block now references FlockFree keystore |
| 3. Privacy policy | ✅ Done | `docs/PRIVACY_POLICY.md` — needs hosting URL before Play submission |
| 4. Permissions audit | ✅ Done | See below |
| 5. Billing/IAP cleanup | ⚠️ Needed | `src-google/` contains BillingManager + InAppPurchasesImpl referencing OsmAnd products that won't exist in Play Console |
| 6. Google Play Developer account | ⚠️ Needed | $25 one-time, requires identity verification |
| 7. Data Safety form | ⚠️ Needed | Fill out in Play Console (see Data Safety section below) |
| 8. Content rating | ⚠️ Needed | IARC questionnaire in Play Console |
| 9. Store listing assets | ⚠️ Needed | Screenshots, feature graphic, descriptions |
| 10. AAB build | ⚠️ Needed | `bundleGplayFreeRelease` after billing cleanup |
| 11. Internal testing | ⚠️ Needed | Upload AAB to Play Console internal testing track |

---

## Permissions Audit

### Main manifest permissions (AndroidManifest.xml)

| Permission | Used by | FlockFree needs it? | Action |
|------------|---------|---------------------|--------|
| `ACCESS_FINE_LOCATION` | Navigation, camera alerts | ✅ YES — core function | Keep |
| `ACCESS_COARSE_LOCATION` | Navigation fallback | ✅ YES — core function | Keep |
| `ACCESS_LOCATION_EXTRA_COMMANDS` | GPS commands | ✅ YES — navigation | Keep |
| `INTERNET` | Map downloads, TomTom, OSM | ✅ YES | Keep |
| `WRITE_EXTERNAL_STORAGE` | Legacy file storage (8 refs in OsmAnd src) | ⚠️ MAYBE — targetSdk 35 uses scoped storage. `requestLegacyExternalStorage=true` is set. Play may flag. | Remove `requestLegacyExternalStorage` and this permission for Play build, or verify it doesn't cause issues |
| `STORAGE` | Legacy storage permission | ⚠️ MAYBE — same as above | Evaluate removal for Play flavor |
| `ACCESS_NETWORK_STATE` | Connectivity checks | ✅ YES | Keep |
| `ACCESS_WIFI_STATE` | WiFi Flock detection | ✅ YES — Flock detection feature | Keep |
| `CHANGE_WIFI_STATE` | WiFi scanning control | ✅ YES — Flock detection needs to trigger scans | Keep |
| `WAKE_LOCK` | Navigation service CPU wake | ✅ YES — navigation | Keep |
| `CAMERA` | OsmAnd AudioVideoNotesPlugin (photo/video notes) | ❌ NO — FlockFree does not use camera | **Remove for Play build** |
| `VIBRATE` | Camera proximity alerts | ✅ YES — alert vibration | Keep |
| `RECORD_AUDIO` | OsmAnd AudioVideoNotesPlugin (audio notes), voice navigation | ⚠️ MAYBE — OsmAnd TTS may use it for voice prompts. FlockFree doesn't record audio. | Remove if TTS doesn't need it; OsmAnd uses TTS service, not RECORD_AUDIO for playback. **Safe to remove** unless voice recording feature is used |
| `FOREGROUND_SERVICE` | Navigation + BLE + Download services | ✅ YES | Keep |
| `FOREGROUND_SERVICE_LOCATION` | NavigationService | ✅ YES | Keep |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` | CydBleService | ✅ YES — BLE companion | Keep |
| `FOREGROUND_SERVICE_DATA_SYNC` | DownloadService | ✅ YES — map downloads | Keep |
| `POST_NOTIFICATIONS` | Android 13+ notification permission | ✅ YES — alerts, navigation notifications | Keep |
| `MAP_TEMPLATES` (androidx.car.app) | Android Auto | ✅ YES | Keep |
| `MODIFY_AUDIO_SETTINGS` | Audio routing | ⚠️ MAYBE — used for voice navigation audio routing | Keep (low risk) |
| `NAVIGATION_TEMPLATES` (androidx.car.app) | Android Auto | ✅ YES | Keep |
| `ACCESS_SURFACE` (androidx.car.app) | Android Auto surface | ✅ YES | Keep |
| `BLUETOOTH_SCAN` | CYD BLE scanning | ✅ YES — CYD companion | Keep |
| `BLUETOOTH_CONNECT` | CYD BLE communication | ✅ YES — CYD companion | Keep |
| `BLUETOOTH` (maxSdk 30) | Legacy BLE | ✅ YES — backward compat | Keep |
| `BLUETOOTH_ADMIN` (maxSdk 30) | Legacy BLE | ✅ YES — backward compat | Keep |

### Debug manifest additions (AndroidManifest-debug.xml)

| Permission | Action |
|------------|--------|
| `REQUEST_INSTALL_PACKAGES` | Debug only — not in release manifest. Safe. |

### Permissions to remove for Play Store build

1. **`CAMERA`** — Only used by OsmAnd's AudioVideoNotesPlugin for taking photos/videos in-app. FlockFree does not use this feature. Removing it eliminates an unnecessary permission flag during Play review.

2. **`RECORD_AUDIO`** — Only used by OsmAnd's AudioVideoNotesPlugin for audio notes and ExternalApiHelper for audio recording API. FlockFree uses TTS (text-to-speech) for voice prompts which does not need RECORD_AUDIO. Safe to remove.

3. **`uses-feature: android.hardware.camera`** — Remove with CAMERA permission
4. **`uses-feature: android.hardware.microphone`** — Remove with RECORD_AUDIO permission

5. **`WRITE_EXTERNAL_STORAGE`** — Deprecated for targetSdk 35. OsmAnd uses scoped storage internally. The `requestLegacyExternalStorage` flag should be removed for the Play flavor. Test thoroughly — some legacy file access code may need updating.

6. **`STORAGE`** — Same as WRITE_EXTERNAL_STORAGE, legacy permission.

### Approach

Create a `AndroidManifest-gplayFree.xml` overlay that removes these permissions using `tools:node="remove"`:

```xml
<manifest xmlns:android="..." xmlns:tools="...">
    <uses-permission android:name="android.permission.CAMERA" tools:node="remove"/>
    <uses-permission android:name="android.permission.RECORD_AUDIO" tools:node="remove"/>
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" tools:node="remove"/>
    <uses-permission android:name="android.permission.STORAGE" tools:node="remove"/>
    <uses-feature android:name="android.hardware.camera" tools:node="remove"/>
    <uses-feature android:name="android.hardware.microphone" tools:node="remove"/>
</manifest>
```

This is cleaner than editing the base manifest — keeps sideload builds unchanged while the Play flavor strips the extra permissions.

---

## Billing / In-App Purchase Cleanup

The `src-google/` directory contains:
- `BillingManager.java` — Full Google Play billing library integration
- `InAppPurchasesImpl.java` — Defines OsmAnd products: full version, depth contours, contour lines, live updates subscriptions, OsmAnd Pro subscriptions, Maps+ annual
- `InAppPurchaseHelperImpl.java` — Purchase flow
- `ExtendedMapActivity.java` — Google Maps extension

These reference OsmAnd-specific SKUs that won't exist in FlockFree's Play Console. Options:

### Option A: No-op the billing (recommended for first release)
Replace InAppPurchasesImpl with a stub that reports all features as already purchased (full version = true, no subscriptions needed). This disables all purchase prompts without ripping out the infrastructure.

### Option B: Remove billing entirely
Delete src-google/ billing classes and remove all references. More invasive — many OsmAnd UI components reference InAppPurchaseHelper.

### Option C: Leave as-is
The billing code will initialize but find no products. Purchase attempts will fail silently. Risky — Play reviewers may test purchase flows and see crashes.

**Recommendation:** Option A. Stub InAppPurchasesImpl to return `isFullVersion = true` and `isPurchased() = true` for all features. This is the lowest-risk change.

---

## Data Safety Form (Play Console)

Fill out as follows:

### Data collected
| Data type | Collected | Shared | Purpose | Encrypted | Required |
|-----------|-----------|--------|---------|-----------|----------|
| Location | ✅ | ❌ | App functionality (navigation, alerts) | In transit (TLS) | Required |
| Photos/Videos | ❌ | — | — | — | — |
| Audio | ❌ | — | — | — | — |
| Wi-Fi scan results | ✅ | ❌ | App functionality (Flock detection) | Not transmitted | Required |
| Bluetooth scan results | ✅ | ❌ | App functionality (CYD companion) | Not transmitted | Required |
| App activity | ❌ | — | — | — | — |
| App interactions/logs | ❌ | — | — | — | — |
| Device/other IDs | ❌ | — | — | — | — |

### Data shared
- **None** (unless user manually submits POI to OSM or enters TomTom API key — both user-initiated with user's own credentials)

### Security practices
- Data encrypted in transit: YES (HTTPS/TLS)
- Data deletion: YES (uninstall removes all app data)

### Permissions declared in manifest
- Location, Bluetooth, Wi-Fi access — all declared and justified

---

## Content Rating Assessment

IARC questionnaire expected answers:

- **Violence:** No
- **Sexual content:** No
- **Language:** No strong language
- **Controlled substances:** No
- **User-generated content:** Yes (OSM POI reports) — but moderated by OSM community
- **In-app purchases:** No (after billing stub)
- **Social features:** No
- **Location sharing:** No (location stays on device)
- **Surveillance/privacy context:** The app provides awareness of publicly known camera locations. This is personal safety/situational awareness, not surveillance facilitation.

**Expected rating:** Everyone (or Teen if reviewer flags the camera-avoidance theme)

---

## Store Listing Assets

### Needed
- [ ] Feature graphic (1024×500 PNG) — can adapt FlockFree splash branding
- [ ] 2-8 screenshots (min 320px, max 3840px) — existing screenshots in `screenshots/` can be used
- [ ] App icon: Already have FlockFree branding in all densities
- [ ] Short description (80 char max): "Navigation with ALPR camera awareness and route avoidance"
- [ ] Full description (4000 char max): Adapt from README, remove build instructions and sideload references

### Screenshots available
From `screenshots/` directory:
- `flockfree_static_day.png` — Day mode map
- `flockfree_static_night.png` — Night mode map
- `flockfree_navigation.png` — Navigation active
- `flockfree_navigation_started.png` — Navigation started
- `flockfree_layers_dialog.png` — Layers dialog
- `flockfree_map_minneapolis.png` — Map overview

---

## Privacy Policy Hosting

Play Store requires a hosted URL. Options:
1. GitHub Pages: Create a `docs/` folder GitHub Pages site at `https://yetisoldier.github.io/FlockFree-Navigation/privacy-policy`
2. Host on yetiwurks.com or antonson.co
3. Use a privacy policy hosting service

**Recommended:** GitHub Pages — it's free, version-controlled, and already has the policy in the repo.

---

## Build & Upload Steps

Once all above is complete:

```bash
# 1. Build the AAB
cd /home/yetisoldier/projects/FlockFree-Navigation
./gradlew bundleGplayFreeRelease

# 2. The AAB will be at:
# OsmAnd/build/outputs/bundle/gplayFreeRelease/OsmAnd-gplayFree-release.aab

# 3. Upload to Play Console → Internal testing → Create new release
# 4. Add testers by email
# 5. Verify install via Play Store internal testing
# 6. Promote to production when ready
```

---

## Risk Areas for Play Review

1. **Wi-Fi scanning** — Most likely to trigger manual review. Justification: "App passively scans for specific Wi-Fi beacons (Flock Safety camera devices) to alert users of nearby surveillance cameras for personal safety and situational awareness. No network data is collected or transmitted."

2. **Camera database** — 104K camera locations is surveillance infrastructure data. Defense: Publicly available OpenStreetMap data, bundled in APK (not collected from users), used for safety awareness.

3. **App framing** — Store description should emphasize "situational awareness" and "informed routing decisions." Avoid language suggesting evasion of law enforcement.

4. **Foreground services** — 3 FGS types declared (location, connectedDevice, dataSync). All properly justified by core features. Should pass review.

---

## Keystore Details

- **File:** `keystores/flockfree-release.keystore`
- **Alias:** `flockfree`
- **Store/Key password:** Set via `FLOCKFREE_KEYSTORE_PASSWORD` env var (default: temporary password — **change before production upload**)
- **Validity:** 10,000 days (until November 2053)
- **SHA-1:** `AF:F7:7C:3A:97:38:26:2A:10:5F:74:BC:A6:B5:FA:67:81:F6:AB:B3`
- **SHA-256:** `72:37:49:E1:31:2E:9F:74:38:D2:4B:E2:92:77:A8:83:6B:F0:5E:75:A7:A4:A1:4C:EC:A3:C9:A3:A3:40:1A:A7`

⚠️ **CRITICAL:** Back up this keystore. If lost, you cannot update the app on Google Play. Store copies in at least 2 secure locations.