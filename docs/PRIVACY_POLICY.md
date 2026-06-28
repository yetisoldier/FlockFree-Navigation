# FlockFree Navigation — Privacy Policy

**Effective date:** June 28, 2026

FlockFree Navigation ("the App") is developed by Yeti Wurks LLC. This privacy policy describes how the App handles data on your device.

## Data Collected and Stored Locally

### Location
- **What:** Precise GPS location (latitude, longitude, bearing, speed)
- **Why:** Turn-by-turn navigation, camera proximity alerts, route calculation
- **Where:** On your device only. Location data is not transmitted off the device unless you explicitly use a third-party API (see "Third-Party APIs" below).

### Bluetooth LE
- **What:** BLE scan results for CYD-Flock-You companion hardware
- **Why:** Connects to optional CYD hardware for RF-based camera detection
- **Where:** On your device only. BLE scan results are not transmitted off the device.

### Wi-Fi Scan Results
- **What:** Passive Wi-Fi beacon scanning for Flock Safety camera WiFi signatures
- **Why:** Detects nearby Flock Safety cameras for situational awareness alerts
- **Where:** On your device only. Wi-Fi scan data is not transmitted off the device.

### Flock Camera Database
- **What:** A bundled database of approximately 89,942 Flock-labeled camera records derived from publicly contributed OpenStreetMap data and the public DontGetFlocked camera feed
- **Why:** Map display, proximity alerts, nearest-camera checks, route comparison, and route avoidance
- **Where:** Bundled in the App and stored in app-private SQLite after first load. Not collected from users. Updated via App updates and optional in-app refreshes.

### User-Generated Reports
- **What:** ALPR camera reports you create using the built-in OSM POI editor
- **Why:** Submits camera location data to OpenStreetMap via OsmAnd's editor
- **Where:** Reports are only submitted when you manually create and submit them. Draft reports persist locally across app restarts until submitted or discarded.

### App Preferences
- **What:** Feature toggles (camera layer, alerts, avoidance, CYD BLE, TomTom API key, etc.)
- **Why:** Remembers your settings across sessions
- **Where:** On your device only (Android SharedPreferences).

## Third-Party APIs (User-Initiated Only)

### TomTom Traffic API
- **What:** Live traffic data for route optimization
- **When:** Only if you enter a TomTom API key in Settings
- **Data sent:** Route coordinates to TomTom's API using your own API key
- **TomTom's privacy policy:** https://www.tomtom.com/legal/privacy-notice/

### OpenStreetMap
- **What:** Map data downloads and POI submissions via OsmAnd's built-in editor
- **When:** When you download maps or manually submit a POI report
- **OSM privacy policy:** https://wiki.openstreetmap.org/wiki/Privacy_Policy

## Data NOT Collected

The App does **not**:
- Collect personal data (name, email, phone number, etc.)
- Sell or share data with third parties
- Send analytics or telemetry to the developer
- Profile users or build behavioral profiles
- Use advertising SDKs
- Track users across apps or websites

## Data Shared

The App does **not** share data with third parties except:
- TomTom (only if you enter your own API key)
- OpenStreetMap (only when you manually submit a POI report)

Both are user-initiated actions using the user's own credentials.

## Data Encryption
- All network communication uses HTTPS/TLS
- Local data is stored in standard Android app-private storage

## Children's Privacy
The App is not directed at children and does not knowingly collect data from children.

## Changes to This Policy
If the App's data practices change, this policy will be updated and the effective date revised.

## Contact
Questions about this privacy policy can be directed to: yetiwurks@gmail.com

## Open Source
FlockFree Navigation is open source. The source code is available at:
https://github.com/yetisoldier/FlockFree-Navigation
