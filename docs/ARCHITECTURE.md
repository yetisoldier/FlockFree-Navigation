# FlockFree Navigation — Architecture Document

**Fork of OsmAnd with built-in ALPR/surveillance camera avoidance plugin.**

---

## 1. Plugin Module Structure

### 1.1 Approach: In-tree plugin (not separate Gradle module)

FlockFree is built as an in-tree plugin inside the main `OsmAnd` module, following the same pattern as `OsmEditingPlugin`, `ParkingPositionPlugin`, etc. This avoids the complexity of a separate Gradle module (like `plugins/Osmand-Nautical`) while giving full access to OsmAnd internals.

### 1.2 Package structure

```
OsmAnd/src/net/osmand/plus/plugins/flockfree/
├── FlockFreePlugin.java                 # Main plugin class (extends OsmandPlugin)
├── data/
│   ├── CameraData.java                   # Camera entity model
│   ├── CameraDatabase.java               # SQLite database for cached cameras
│   ├── CameraDbHelper.java               # SQLiteOpenHelper implementation
│   └── CameraSyncManager.java            # Download, parse, cache orchestration
├── layer/
│   ├── FlockFreeLayer.java               # Map layer (extends OsmandMapLayer)
│   └── FlockFreeTileProvider.java         # Optional tile-based overlay for dense areas
├── routing/
│   ├── CameraAvoidanceHelper.java        # Feeds camera positions as blocked roads to routing
│   └── CameraRoutingParameter.java       # Custom routing parameter for camera avoidance
├── reporting/
│   ├── SurveillancePoiPreset.java         # Pre-filled tag set for OSM editing
│   ├── ReportCameraFragment.java          # UI fragment for reporting a new camera
│   └── ReportCameraAction.java            # QuickAction for one-tap reporting
├── ble/
│   ├── BleFlockManager.java               # BLE connection manager for CYD-Flock-You hardware
│   ├── BleFlockService.java               # Background service for BLE scanning/detection
│   └── BleProtocolParser.java             # Parses FYHELLO/FYGPS/FYSIM/detection JSON frames
├── widgets/
│   ├── CameraCountWidget.java             # Map widget showing nearby camera count
│   └── CameraAlertWidget.java            # Widget showing closest camera distance/bearing
├── fragments/
│   ├── FlockFreeSettingsFragment.java      # Plugin settings screen
│   └── CameraDetailsFragment.java         # Context menu details for a camera
└── quickactions/
    ├── AddCameraAction.java               # QuickAction: add camera at location
    └── ToggleCameraLayerAction.java       # QuickAction: show/hide camera layer
```

### 1.3 Resource files

```
OsmAnd/res/
├── drawable/
│   ├── ic_flockfree_logo.xml              # Plugin logo (vector)
│   ├── ic_action_camera_marker.xml       # Camera map marker icon
│   ├── ic_action_camera_alert.xml         # Alert/warning variant
│   ├── ic_flockfree_widget.xml            # Widget icon
│   └── ic_flockfree_report.xml            # Report action icon
├── layout/
│   ├── flockfree_settings.xml             # Settings layout
│   ├── flockfree_camera_details.xml       # Camera context menu layout
│   └── flockfree_widget.xml               # Widget layout
├── values/
│   └── flockfree_strings.xml               # All plugin string resources
├── values-night/
│   └── flockfree_strings.xml              # Night mode variants if needed
```

---

## 2. Plugin Registration & Lifecycle

### 2.1 Plugin ID constant

In `OsmAndCustomizationConstants.java` (OsmAnd-api module), add:

```java
String PLUGIN_FLOCKFREE = "flockfree.navigation";
```

### 2.2 Register in PluginsHelper

In `PluginsHelper.initPlugins()`, add after existing plugins (around line 126):

```java
allPlugins.add(new FlockFreePlugin(app));
```

File: `OsmAnd/src/net/osmand/plus/plugins/PluginsHelper.java`
Location: `initPlugins()` method, after `allPlugins.add(new OsmandDevelopmentPlugin(app));`

### 2.3 Plugin class: FlockFreePlugin

```java
package net.osmand.plus.plugins.flockfree;

public class FlockFreePlugin extends OsmandPlugin {

    // Preferences
    public final OsmandPreference<Boolean> SHOW_CAMERAS;
    public final OsmandPreference<Boolean> CAMERA_AVOIDANCE_ENABLED;
    public final CommonPreference<Integer> CAMERA_AVOIDANCE_RADIUS;
    public final OsmandPreference<Boolean> BLE_DETECTION_ENABLED;
    public final CommonPreference<Integer> CAMERA_DATA_SYNC_INTERVAL;

    private FlockFreeLayer flockFreeLayer;
    private CameraSyncManager syncManager;
    private CameraAvoidanceHelper avoidanceHelper;
    private BleFlockManager bleManager;

    public FlockFreePlugin(OsmandApplication app) {
        super(app);
        SHOW_CAMERAS = registerBooleanPreference("show_flockfree_cameras", true)
            .makeProfile().cache();
        CAMERA_AVOIDANCE_ENABLED = registerBooleanPreference("camera_avoidance", true)
            .makeProfile().cache();
        CAMERA_AVOIDANCE_RADIUS = registerIntPreference("camera_avoidance_radius_m", 200)
            .makeProfile().cache();
        BLE_DETECTION_ENABLED = registerBooleanPreference("ble_detection_enabled", false)
            .makeProfile().cache();
        CAMERA_DATA_SYNC_INTERVAL = registerIntPreference("camera_sync_interval_hrs", 24)
            .makeProfile().cache();
    }

    @Override
    public String getId() { return PLUGIN_FLOCKFREE; }

    @Override
    public void updateLayers(@NonNull Context context, @Nullable MapActivity mapActivity) {
        OsmandMapTileView mapView = app.getOsmandMap().getMapView();
        if (isActive()) {
            if (flockFreeLayer == null) {
                registerLayers(context, mapActivity);
            }
            if (mapView.getLayers().contains(flockFreeLayer) != SHOW_CAMERAS.get()) {
                if (SHOW_CAMERAS.get()) {
                    mapView.addLayer(flockFreeLayer, 3.0f);
                } else {
                    mapView.removeLayer(flockFreeLayer);
                }
            }
        } else {
            if (flockFreeLayer != null) {
                mapView.removeLayer(flockFreeLayer);
            }
        }
    }

    @Override
    public void registerLayers(@NonNull Context context, @Nullable MapActivity mapActivity) {
        if (flockFreeLayer != null) {
            app.getOsmandMap().getMapView().removeLayer(flockFreeLayer);
        }
        flockFreeLayer = new FlockFreeLayer(context, this);
    }

    // ... additional overrides (see sections below)
}
```

Key `OsmandPlugin` methods to override (signatures from `OsmandPlugin.java`):

| Method | Purpose |
|--------|---------|
| `getId()` | Return `"flockfree.navigation"` |
| `getName()` | Return `"FlockFree"` |
| `getLogoResourceId()` | Return `R.drawable.ic_flockfree_logo` |
| `getDescription(boolean)` | Return plugin description string |
| `registerLayers(Context, MapActivity)` | Create `FlockFreeLayer` |
| `updateLayers(Context, MapActivity)` | Add/remove layer based on `SHOW_CAMERAS` |
| `registerMapContextMenuActions(...)` | Add "Report Camera" and "Camera Details" menu items |
| `registerConfigureMapCategoryActions(...)` | Add toggle for camera layer in Configure Map |
| `getQuickActionTypes()` | Return `AddCameraAction.TYPE`, `ToggleCameraLayerAction.TYPE` |
| `getSettingsScreenType()` | Return custom `SettingsScreenType` or null |
| `getPrefsDescription()` | Return settings description |
| `newRouteIsCalculated(boolean)` | Hook to inject camera avoidance on route recalculation |

---

## 3. Camera Data Layer: Fetch, Cache, Render

### 3.1 Data source

- **URL:** `https://data.dontgetflocked.com/cameras.geojson.gz`
- **Size:** ~28 MB compressed, GeoJSON FeatureCollection
- **Features:** ~104,902 camera points
- **Properties per feature:**
  ```json
  {
    "osmId": 13938820801,
    "osmType": "node",
    "brand": "Flock Safety",          // optional
    "direction": 158,                 // bearing in degrees
    "directions": [286, 150],          // optional, multi-direction
    "surveillanceZone": "traffic",
    "mountType": "pole",              // optional
    "operator": "Nassau County PD",   // optional
    "osmTimestamp": "2026-06-14T17:28:35Z",
    "osmVersion": 1
  }
  ```
- **Geometry:** `Point` with `[longitude, latitude]` in WGS84

### 3.2 CameraData model

```java
package net.osmand.plus.plugins.flockfree.data;

public class CameraData {
    private final long osmId;
    private final double latitude;
    private final double longitude;
    private final String brand;          // e.g., "Flock Safety", "Motorola Solutions", "Leonardo"
    private final int direction;          // primary bearing degrees, 0 = north
    private final int[] directions;      // optional multi-direction
    private final String surveillanceZone;  // "traffic", "parking", "entrance", "street", "gate"
    private final String mountType;      // "pole", "street_lamp", "traffic_signals", "wall", "ceiling"
    private final String operator;       // optional operator name
    private final long osmTimestamp;     // epoch millis
    private final int osmVersion;

    // Constructor from GeoJSON Feature
    // Getters
    // toContentValues() for SQLite insert
    // getMarkerColor() — brand-based color mapping
}
```

### 3.3 CameraDatabase / CameraDbHelper

SQLite database for local caching. Schema:

```sql
CREATE TABLE cameras (
    osm_id INTEGER PRIMARY KEY,
    latitude REAL NOT NULL,
    longitude REAL NOT NULL,
    brand TEXT,
    direction INTEGER DEFAULT 0,
    directions TEXT,           -- JSON array
    surveillance_zone TEXT,
    mount_type TEXT,
    operator TEXT,
    osm_timestamp INTEGER,
    osm_version INTEGER,
    updated_at INTEGER NOT NULL DEFAULT (strftime('%s','now'))
);
CREATE INDEX idx_cameras_latlon ON cameras(latitude, longitude);
```

`CameraDbHelper extends SQLiteOpenHelper`:
- `onCreate()` — create table + index
- `onUpgrade()` — migration-friendly
- `bulkInsert(List<CameraData>)` — batch insert with transaction
- `getCamerasInBounds(double lat1, double lon1, double lat2, double lon2)` — bounding box query
- `getCameraCount()` — for widget display
- `getNearestCameras(LatLon, int maxCount, double maxDistanceM)` — nearest-neighbor using haversine
- `clear()` — full wipe before re-sync

### 3.4 CameraSyncManager

Orchestrates download, decompress, parse, and DB insert.

```java
public class CameraSyncManager {
    private static final String CAMERA_DATA_URL = "https://data.dontgetflocked.com/cameras.geojson.gz";
    private final OsmandApplication app;
    private final CameraDbHelper dbHelper;

    // Downloads .gz, decompresses on-the-fly, streams GeoJSON parsing
    public void syncCameras(@Nullable SyncCallback callback) {
        // 1. Download to temp file using AndroidNetworkUtils or OkHttp
        // 2. GZIPInputStream wrap FileInputStream
        // 3. Stream-parse with org.json or a streaming JSON parser
        //    (104k features — org.json is fine if done in background thread)
        // 4. Batch insert into SQLite (1000 per transaction)
        // 5. Call callback.onComplete(count)
    }

    // Schedule periodic sync via AlarmManager or WorkManager
    public void schedulePeriodicSync(int intervalHours) { ... }

    // One-shot: load from local file (for first-run bundled data)
    public void loadFromBundledFile() { ... }
}
```

**First-run strategy:** Bundle a snapshot of `cameras.geojson.gz` in `OsmAnd/assets/flockfree/cameras.geojson.gz`. On first plugin activation, `loadFromBundledFile()` populates the DB. Subsequent syncs update from the URL.

### 3.5 FlockFreeLayer (map rendering)

Extends `OsmandMapLayer` — follows the pattern of `ImpassableRoadsLayer` and `OsmEditsLayer`.

```java
public class FlockFreeLayer extends OsmandMapLayer
        implements ContextMenuLayer.IContextMenuProvider {

    private static final int START_ZOOM = 11;

    private final FlockFreePlugin plugin;
    private final OsmandApplication app;
    private CameraDbHelper dbHelper;
    private Bitmap cameraIcon;
    private Bitmap cameraIconAlert;
    private MapMarkersCollection markersCollection;

    @Override
    public void initLayer(@NonNull OsmandMapTileView view) {
        super.initLayer(view);
        dbHelper = app.getSettings().getFlockFreeDbHelper();
        createResources();
    }

    @Override
    public void onPrepareBufferImage(Canvas canvas, RotatedTileBox tileBox, DrawSettings settings) {
        if (tileBox.getZoom() >= START_ZOOM) {
            // Get visible bounding box
            QuadRect bbox = tileBox.getLatLonBounds();
            // Query DB for cameras in bounds
            List<CameraData> cameras = dbHelper.getCamerasInBounds(
                bbox.top, bbox.left, bbox.bottom, bbox.right);
            // Render using either OpenGL (MapRendererView) or legacy canvas
            MapRendererView mapRenderer = getMapRenderer();
            if (mapRenderer != null) {
                renderOpenglMarkers(cameras);
            } else {
                renderCanvasMarkers(canvas, tileBox, cameras);
            }
        }
    }

    // OpenGL path: use MapMarkersCollection like ImpassableRoadsLayer
    private void renderOpenglMarkers(List<CameraData> cameras) {
        clearMapMarkersCollections();
        markersCollection = new MapMarkersCollection();
        for (CameraData cam : cameras) {
            int x = MapUtils.get31TileNumberX(cam.getLongitude());
            int y = MapUtils.get31TileNumberY(cam.getLatitude());
            MapMarkerBuilder builder = new MapMarkerBuilder();
            builder.setPosition(new PointI(x, y))
                   .setBaseOrder(getPointsOrder())
                   .setIsAccuracyCircleSupported(false)
                   .setPinIcon(NativeUtilities.createSkImageFromBitmap(
                       getCameraIcon(cam)))
                   .buildAndAddToCollection(markersCollection);
        }
        getMapRenderer().addSymbolsProvider(markersCollection);
    }

    // Legacy canvas path
    private void renderCanvasMarkers(Canvas canvas, RotatedTileBox tileBox,
                                      List<CameraData> cameras) {
        for (CameraData cam : cameras) {
            float x = tileBox.getPixXFromLatLon(cam.getLatitude(), cam.getLongitude());
            float y = tileBox.getPixYFromLatLon(cam.getLatitude(), cam.getLongitude());
            // Draw camera icon centered on (x, y)
            canvas.drawBitmap(cameraIcon, x - cameraIcon.getWidth()/2f,
                             y - cameraIcon.getHeight()/2f, paint);
        }
    }

    @Override
    public void collectObjectsFromPoint(@NonNull MapSelectionResult result,
                                        @NonNull MapSelectionRules rules) {
        // Hit-test: check if tapped point is near a camera marker
    }

    @Override
    public LatLon getObjectLocation(Object o) {
        if (o instanceof CameraData) {
            return new LatLon(((CameraData) o).getLatitude(), ((CameraData) o).getLongitude());
        }
        return null;
    }

    @Override
    public PointDescription getObjectName(Object o) {
        if (o instanceof CameraData cam) {
            String name = cam.getBrand() != null ? cam.getBrand() : "Surveillance Camera";
            return new PointDescription(POINT_TYPE_POI, name);
        }
        return null;
    }
}
```

**Performance optimization for 104k markers:**
- Only query cameras within the visible bounding box
- At low zoom (< START_ZOOM), render nothing or density circles
- Use spatial index in SQLite (the `idx_cameras_latlon` index)
- Cache the last query result and only re-query when the map moves significantly
- Cap rendered markers at ~500 per frame for performance

---

## 4. Camera-Avoidance Routing

### 4.1 Internal routing API (preferred approach)

OsmAnd's `RoutingConfiguration.Builder` supports impassable road locations via `addImpassableRoad(long roadId)`. The `AvoidRoadsHelper` class in `OsmAnd/src/net/osmand/plus/avoidroads/AvoidRoadsHelper.java` manages this.

**Strategy:** For each camera near the calculated route, find the nearest road segment and add it as an impassable road, then trigger a route recalculation.

```java
public class CameraAvoidanceHelper {

    private final OsmandApplication app;
    private final CameraDbHelper dbHelper;
    private final AvoidRoadsHelper avoidRoadsHelper;

    /**
     * Called when a new route is calculated. Scans the route path for nearby cameras
     * and blocks road segments within the avoidance radius.
     */
    public void onRouteCalculated(List<RouteSegmentResult> routeSegments) {
        if (!app.getSettings().CAMERA_AVOIDANCE_ENABLED.get()) return;

        int radiusM = app.getSettings().CAMERA_AVOIDANCE_RADIUS.get(); // default 200m
        Set<Long> blockedRoadIds = new HashSet<>();

        for (RouteSegmentResult segment : routeSegments) {
            // Get segment geometry points
            // For each point, query cameras within radius
            // For each nearby camera, find the nearest road segment
            // Add that road's ID to blockedRoadIds
        }

        // Add to routing configs
        for (RoutingConfiguration.Builder builder : app.getAllRoutingConfigs()) {
            for (long roadId : blockedRoadIds) {
                if (!builder.getImpassableRoadLocations().contains(roadId)) {
                    builder.addImpassableRoad(roadId);
                }
            }
        }

        // Trigger recalculation if we blocked new roads
        if (!blockedRoadIds.isEmpty()) {
            app.getRoutingHelper().onSettingsChanged(true);
        }
    }

    /**
     * Find nearest road segment to a camera location.
     * Uses app.getLocationProvider().getRouteSegment() — same as AvoidRoadsHelper.
     */
    private long findNearestRoadId(LatLon cameraLoc, ApplicationMode mode) {
        // Use RouteSegmentSearchResult.searchRouteSegment() on the route
        // or getLocationProvider().getRouteSegment() for ad-hoc lookup
        // Returns road ID from RouteDataObject.getId()
    }
}
```

### 4.2 Hook into route calculation

In `FlockFreePlugin`:

```java
@Override
public void newRouteIsCalculated(boolean newRoute) {
    if (newRoute && CAMERA_AVOIDANCE_ENABLED.get()) {
        List<RouteSegmentResult> route = app.getRoutingHelper().getRoute().getOriginalRoute();
        if (route != null) {
            avoidanceHelper.onRouteCalculated(route);
        }
    }
}
```

### 4.3 AIDL API alternative (for external integration)

If the internal API proves fragile across OsmAnd updates, the AIDL path is available:

```java
// ABlockedRoad constructor:
new ABlockedRoad(roadId, latitude, longitude, direction, name, appModeKey)
// Then: aidlInterface.addRoadBlock(new AddBlockedRoadParams(blockedRoad));
```

Available via `IOsmAndAidlInterface.aidl`:
- `boolean addRoadBlock(in AddBlockedRoadParams params);`
- `boolean removeRoadBlock(in RemoveBlockedRoadParams params);`
- `boolean getBlockedRoads(out List<ABlockedRoad> blockedRoads);`

This is simpler but requires AIDL binding setup. The internal approach is preferred for a fork.

### 4.4 Routing parameter

Add a custom routing parameter that appears in Route Settings:

```java
public class CameraRoutingParameter {
    // Add to routing.xml or programmatically to RoutingConfiguration
    // String key = "avoid_cameras"
    // Boolean default = true
    // Appears in: Route Parameters → Avoid → "Surveillance Cameras"
}
```

This requires modifying `OsmAnd-java/src/net/osmand/router/routing.xml` or adding the parameter programmatically via `RoutingConfiguration.Builder.addBooleanParam(key, defaultValue)`.

---

## 5. Reporting Flow: OSM Editor Integration

### 5.1 Surveillance tag preset

OSM tag template for ALPR cameras:
```
man_made=surveillance
surveillance:type=ALPR
surveillance=public
camera:type=fixed
surveillance:zone=traffic
```

Additional optional tags based on data:
```
brand=<brand>           # e.g., "Flock Safety", "Motorola Solutions"
direction=<direction>    # bearing in degrees
operator=<operator>     # if known
camera:mount=<mountType> # pole, street_lamp, traffic_signals, wall, ceiling
```

### 5.2 SurveillancePoiPreset

Integrates with OsmAnd's existing `EditPoiData` / `EditPoiDialogFragment` system.

```java
public class SurveillancePoiPreset {

    public static LinkedHashMap<String, String> getDefaultTags() {
        LinkedHashMap<String, String> tags = new LinkedHashMap<>();
        tags.put("man_made", "surveillance");
        tags.put("surveillance:type", "ALPR");
        tags.put("surveillance", "public");
        tags.put("camera:type", "fixed");
        tags.put("surveillance:zone", "traffic");
        return tags;
    }

    public static LinkedHashMap<String, String> getTagsFromCameraData(CameraData cam) {
        LinkedHashMap<String, String> tags = getDefaultTags();
        if (cam.getBrand() != null) tags.put("brand", cam.getBrand());
        if (cam.getDirection() != 0) tags.put("direction", String.valueOf(cam.getDirection()));
        if (cam.getOperator() != null) tags.put("operator", cam.getOperator());
        if (cam.getMountType() != null) tags.put("camera:mount", cam.getMountType());
        return tags;
    }

    /**
     * Pre-fill the EditPoiDialogFragment with surveillance tags.
     * Called from the map context menu "Report Camera" action.
     */
    public static void showReportCameraDialog(MapActivity mapActivity,
                                               double lat, double lon,
                                               @Nullable CameraData existingCam) {
        Entity entity = new Node(lat, lon, -1);
        LinkedHashMap<String, String> tags = existingCam != null
            ? getTagsFromCameraData(existingCam)
            : getDefaultTags();
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            entity.putTag(entry.getKey(), entry.getValue());
        }
        EditPoiDialogFragment.showAddPoiInstance(mapActivity, lat, lon);
        // The dialog uses EditPoiData internally; we need to pre-fill tags
        // Alternative: create EditPoiData with entity, then show dialog
    }
}
```

### 5.3 Context menu integration

In `FlockFreePlugin.registerMapContextMenuActions()`:

```java
@Override
public void registerMapContextMenuActions(@NonNull MapActivity mapActivity,
        double latitude, double longitude,
        @NonNull ContextMenuAdapter adapter,
        Object selectedObj, boolean configureMenu) {

    ItemClickListener listener = (uiAdapter, view, item, isChecked) -> {
        if (item.getTitleId() == R.string.flockfree_report_camera) {
            SurveillancePoiPreset.showReportCameraDialog(mapActivity, latitude, longitude, null);
        } else if (item.getTitleId() == R.string.flockfree_camera_details && selectedObj instanceof CameraData) {
            CameraDetailsFragment.show(mapActivity, (CameraData) selectedObj);
        }
        return true;
    };

    // Add "Report Camera" — always available when plugin active
    adapter.addItem(new ContextMenuItem(MAP_CONTEXT_MENU_ACTIONS + "report_camera")
            .setTitleId(R.string.flockfree_report_camera, mapActivity)
            .setIcon(R.drawable.ic_flockfree_report)
            .setOrder(7400)
            .setListener(listener));

    // Add "Camera Details" — only when a camera is selected
    if (selectedObj instanceof CameraData) {
        adapter.addItem(new ContextMenuItem(MAP_CONTEXT_MENU_ACTIONS + "camera_details")
                .setTitleId(R.string.flockfree_camera_details, mapActivity)
                .setIcon(R.drawable.ic_action_camera_marker)
                .setOrder(7350)
                .setListener(listener));
    }
}
```

### 5.4 QuickAction for one-tap reporting

```java
public class AddCameraAction extends QuickAction {
    public static final QuickActionType TYPE = new QuickActionType(...);

    @Override
    public void execute(MapActivity mapActivity) {
        LatLon tapLocation = ...; // get from quick action params
        SurveillancePoiPreset.showReportCameraDialog(mapActivity,
            tapLocation.getLatitude(), tapLocation.getLongitude(), null);
    }
}
```

---

## 6. BLE Integration: CYD-Flock-You Hardware

### 6.1 Protocol

The CYD-Flock-You device communicates over BLE with these message types:

| Message | Direction | Format | Description |
|---------|-----------|--------|-------------|
| `FYHELLO` | Device→App | JSON | Handshake, device info, firmware version |
| `FYGPS` | Device→App | JSON | GPS coordinates from device |
| `FYSIM` | Device→App | JSON | SIM/cellular info |
| Detection JSON | Device→App | JSON | Camera detection event (camera ID, signal strength, type) |

Example detection JSON:
```json
{
  "type": "detection",
  "camera_id": "FL-2024-00471",
  "timestamp": 1718700000,
  "signal_strength": -67,
  "camera_type": "ALPR",
  "brand": "Flock Safety",
  "latitude": 38.9072,
  "longitude": -77.0369,
  "direction": 158
}
```

### 6.2 BleFlockManager

```java
public class BleFlockManager {
    private static final String SERVICE_UUID = "0000flock-0000-1000-8000-00805f9b34fb"; // replace with actual
    private static final String CHARACTERISTIC_NOTIFY_UUID = "0000fynot-0000-1000-8000-00805f9b34fb";

    private final BluetoothAdapter bluetoothAdapter;
    private final BleProtocolParser parser;
    private BluetoothGatt connectedGatt;
    private final List<BleDetectionListener> listeners = new ArrayList<>();

    public interface BleDetectionListener {
        void onHandshake(String firmwareVersion);
        void onGpsFix(LatLon location);
        void onDetection(CameraDetection detection);
        void onConnectionState(int state);
    }

    public void startScanning() { ... }
    public void stopScanning() { ... }
    public void connect(BluetoothDevice device) { ... }
    public void disconnect() { ... }

    // BluetoothGattCallback
    // onCharacteristicChanged → parse → dispatch to listeners
}
```

### 6.3 BleProtocolParser

```java
public class BleProtocolParser {
    /**
     * Parses incoming BLE characteristic data as JSON.
     * @param data raw bytes from BLE characteristic
     * @return parsed message object or null if invalid
     */
    public Object parse(byte[] data) {
        String json = new String(data, StandardCharsets.UTF_8);
        JSONObject obj = new JSONObject(json);
        String type = obj.optString("type", "");
        switch (type) {
            case "FYHELLO": return parseHandshake(obj);
            case "FYGPS": return parseGps(obj);
            case "FYSIM": return parseSim(obj);
            case "detection": return parseDetection(obj);
            default: return null;
        }
    }
}
```

### 6.4 BleFlockService (background)

```java
public class BleFlockService extends Service {
    // Foreground service that maintains BLE connection
    // Notifies user on new camera detections
    // Feeds detections to FlockFreeLayer for immediate display
    // Optionally triggers audio alert via TTS
}
```

### 6.5 Integration in FlockFreePlugin

```java
// In FlockFreePlugin constructor or onPluginEnabled():
bleManager = new BleFlockManager(app);
bleManager.addListener(new BleDetectionListener() {
    @Override
    public void onDetection(CameraDetection detection) {
        // Add to local DB as a user-reported camera
        // Show toast
        // Optionally trigger vibration
    }
});

// Preference toggle:
// BLE_DETECTION_ENABLED → start/stop BleFlockService
```

### 6.6 WiFi Flock Detection

The plugin includes a `WifiScannerManager` that uses Android's `WifiManager` to passively scan for Flock Safety camera WiFi beacons:

- Matches scan results against a known Flock OUI list
- Requires `ACCESS_FINE_LOCATION` and device Location enabled at runtime
- On detection: triggers toast + vibration alert and auto-pauses CYD companion WiFi scanning
- Settings toggle: `WIFI_SCAN_ENABLED` preference

### 6.7 Camera Alerts (Toast + Vibration)

Camera alerts use toast + vibration only — no persistent notification:

- `showCameraAlert()` fires a toast with brand and distance info
- `vibrateForCameraAlert()` triggers a buzz-pause-buzz pattern via `Vibrator` service
- Cooldown logic: 60s between different cameras, 30s for the same camera
- Debug trigger via ADB broadcast: `adb shell am broadcast -a net.osmand.flockfree.TEST_ALERT`

### 6.8 Permissions

Add to `AndroidManifest.xml`:
```xml
<uses-permission android:name="android.permission.BLUETOOTH" android:maxSdkVersion="30"/>
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"/>
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT"/>
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
<uses-feature android:name="android.hardware.bluetooth_le" android:required="false"/>
```

---

## 7. UI Customization & Rebranding

### 7.1 App name and identity

In `OsmAnd/build.gradle`, modify the `productFlavors` or add a new flavor:

```groovy
// Option A: Modify existing flavors
androidFull {
    dimension "version"
    applicationId "com.flockfree.navigation"
    resValue "string", "app_name", "FlockFree Navigation"
    resValue "string", "app_edition", ""
}
```

Or create a dedicated flavor:
```groovy
flockfree {
    dimension "version"
    applicationId "com.flockfree.navigation"
    resValue "string", "app_name", "FlockFree Navigation"
    resValue "string", "app_edition", ""
}
```

Add to `flavorDimensions` if needed and update `settings.gradle`.

### 7.2 Launcher icon

Replace:
- `OsmAnd/res/mipmap-*/ic_launcher.png` — FlockFree icon
- `OsmAnd/res/drawable/ic_launcher_foreground.xml` — vector foreground
- `OsmAnd/res/mipmap-anydpi-v26/ic_launcher.xml` — adaptive icon config

### 7.3 Splash screen / navigation drawer header

- `OsmAnd/res/drawable/nav_drawer_header_default.png` — FlockFree branding
- `OsmAnd/res/values/strings.xml` — update `app_name` and related strings

### 7.4 Theme colors (optional)

Modify `OsmAnd/res/values/colors.xml`:
```xml
<color name="flockfree_primary">#FF063A5A</color>  <!-- dark logo blue -->
<color name="flockfree_accent">#FF00BCD4</color>   <!-- CYD/camera-cone cyan -->
<color name="flockfree_camera_marker">#FFE53935</color> <!-- red camera markers -->
<color name="flockfree_camera_marker_alert">#FFFFC107</color> <!-- amber for detected -->
```

### 7.5 String resources

Create `OsmAnd/res/values/flockfree_strings.xml`:
```xml
<resources>
    <string name="flockfree_plugin_name">FlockFree</string>
    <string name="flockfree_plugin_description">Surveillance camera avoidance and ALPR detection</string>
    <string name="flockfree_show_cameras">Show surveillance cameras</string>
    <string name="flockfree_camera_avoidance">Avoid cameras in routing</string>
    <string name="flockfree_camera_avoidance_radius">Camera avoidance radius (m)</string>
    <string name="flockfree_ble_detection">BLE camera detection</string>
    <string name="flockfree_report_camera">Report Camera</string>
    <string name="flockfree_camera_details">Camera Details</string>
    <string name="flockfree_camera_count">Cameras nearby: %1$d</string>
    <string name="flockfree_nearest_camera">Nearest camera: %1$.0f m</string>
    <string name="flockfree_sync_data">Sync camera data</string>
    <string name="flockfree_sync_progress">Syncing camera data… %1$d/%2$d</string>
    <string name="flockfree_sync_complete">Camera data synced: %1$d cameras</string>
    <string name="flockfree_settings">FlockFree Settings</string>
    <string name="flockfree_settings_descr">Configure surveillance camera display, routing avoidance, and BLE detection.</string>
    <string name="flockfree_ble_scanning">Scanning for CYD-Flock-You device…</string>
    <string name="flockfree_ble_connected">Connected to CYD-Flock-You</string>
    <string name="flockfree_ble_disconnected">CYD-Flock-You disconnected</string>
    <string name="flockfree_detection_alert">Camera detected: %1$s</string>
</resources>
```

---

## 8. Build Configuration Changes

### 8.1 settings.gradle

No changes needed for in-tree plugin. Plugin code lives inside the existing `OsmAnd` module.

### 8.2 OsmAnd/build.gradle

```groovy
defaultConfig {
    // Change version code/name for FlockFree builds
    versionCode 1  // FlockFree-specific
    versionName "1.0.0"
}

// In productFlavors, either modify existing or add new:
flockfreeFull {
    dimension "version"
    applicationId "com.flockfree.navigation"
    resValue "string", "app_name", "FlockFree Navigation"
    resValue "string", "app_edition", ""
    // Keep same signing config
}
```

### 8.3 AndroidManifest.xml

Add to `OsmAnd/AndroidManifest.xml`:
```xml
<!-- BLE permissions for CYD-Flock-You -->
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"/>
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT"/>
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION"/>
<uses-feature android:name="android.hardware.bluetooth_le" android:required="false"/>

<!-- Foreground service for BLE detection -->
<service android:name="net.osmand.plus.plugins.flockfree.ble.BleFlockService"
         android:foregroundServiceType="connectedDevice"
         android:exported="false"/>
```

### 8.4 Dependencies

No additional Gradle dependencies needed. OsmAnd already includes:
- `org.json` (platform, for GeoJSON parsing)
- `androidx.sqlite` (for SQLite database)
- BLE APIs (platform, `android.bluetooth.*`)
- `OkHttp` / `AndroidNetworkUtils` (for HTTP download)

### 8.5 First-run bundled data

Place the camera database snapshot in:
```
OsmAnd/assets/flockfree/cameras.geojson.gz
```

This adds ~28 MB to the APK. Alternative: download on first run from the URL.

---

## 9. File-by-File Implementation Checklist (Prioritized)

### Phase 1: Core Plugin & Camera Display (MVP)

| # | Priority | File | Action | Description |
|---|----------|------|--------|-------------|
| 1 | P0 | `OsmAnd-api/.../OsmAndCustomizationConstants.java` | Edit | Add `PLUGIN_FLOCKFREE = "flockfree.navigation"` constant |
| 2 | P0 | `OsmAnd/.../plugins/PluginsHelper.java` | Edit | Add `allPlugins.add(new FlockFreePlugin(app))` in `initPlugins()` |
| 3 | P0 | `OsmAnd/.../plugins/flockfree/FlockFreePlugin.java` | Create | Main plugin class extending `OsmandPlugin` |
| 4 | P0 | `OsmAnd/.../plugins/flockfree/data/CameraData.java` | Create | Camera entity model |
| 5 | P0 | `OsmAnd/.../plugins/flockfree/data/CameraDbHelper.java` | Create | SQLite database helper |
| 6 | P0 | `OsmAnd/.../plugins/flockfree/data/CameraSyncManager.java` | Create | Download/cache orchestration |
| 7 | P0 | `OsmAnd/.../plugins/flockfree/layer/FlockFreeLayer.java` | Create | Map layer for rendering cameras |
| 8 | P0 | `OsmAnd/res/values/flockfree_strings.xml` | Create | String resources |
| 9 | P0 | `OsmAnd/res/drawable/ic_action_camera_marker.xml` | Create | Camera marker icon |
| 10 | P0 | `OsmAnd/res/drawable/ic_flockfree_logo.xml` | Create | Plugin logo |
| 11 | P0 | `OsmAnd/assets/flockfree/cameras.geojson.gz` | Copy | Bundled first-run data (from /tmp) |

### Phase 2: Camera-Avoidance Routing

| # | Priority | File | Action | Description |
|---|----------|------|--------|-------------|
| 12 | P1 | `OsmAnd/.../flockfree/routing/CameraAvoidanceHelper.java` | Create | Camera-to-road-segment mapping + routing block |
| 13 | P1 | `OsmAnd/.../flockfree/FlockFreePlugin.java` | Edit | Add `newRouteIsCalculated()` override calling `CameraAvoidanceHelper` |
| 14 | P1 | `OsmAnd/res/values/flockfree_strings.xml` | Edit | Add routing-related strings |

### Phase 3: Reporting (OSM Edit Integration)

| # | Priority | File | Action | Description |
|---|----------|------|--------|-------------|
| 15 | P1 | `OsmAnd/.../flockfree/reporting/SurveillancePoiPreset.java` | Create | Tag preset + dialog launcher |
| 16 | P1 | `OsmAnd/.../flockfree/FlockFreePlugin.java` | Edit | Add `registerMapContextMenuActions()` with "Report Camera" |
| 17 | P2 | `OsmAnd/.../flockfree/quickactions/AddCameraAction.java` | Create | QuickAction for one-tap reporting |
| 18 | P2 | `OsmAnd/.../flockfree/quickactions/ToggleCameraLayerAction.java` | Create | QuickAction to toggle layer |
| 19 | P2 | `OsmAnd/.../flockfree/FlockFreePlugin.java` | Edit | Add `getQuickActionTypes()` |

### Phase 4: Configure Map UI & Widgets

| # | Priority | File | Action | Description |
|---|----------|------|--------|-------------|
| 20 | P1 | `OsmAnd/.../flockfree/FlockFreePlugin.java` | Edit | Add `registerConfigureMapCategoryActions()` for layer toggle |
| 21 | P2 | `OsmAnd/.../flockfree/widgets/CameraCountWidget.java` | Create | Widget showing nearby camera count |
| 22 | P2 | `OsmAnd/.../flockfree/widgets/CameraAlertWidget.java` | Create | Widget showing nearest camera distance |
| 23 | P2 | `OsmAnd/.../flockfree/FlockFreePlugin.java` | Edit | Register widgets via `createWidgets()` |
| 24 | P2 | `OsmAnd/res/layout/flockfree_widget.xml` | Create | Widget layout |

### Phase 5: Settings UI

| # | Priority | File | Action | Description |
|---|----------|------|--------|-------------|
| 25 | P2 | `OsmAnd/.../flockfree/fragments/FlockFreeSettingsFragment.java` | Create | Settings screen |
| 26 | P2 | `OsmAnd/res/layout/flockfree_settings.xml` | Create | Settings layout |
| 27 | P2 | `OsmAnd/.../flockfree/FlockFreePlugin.java` | Edit | Add `getSettingsScreenType()` / `getPrefsDescription()` |

### Phase 6: BLE Integration

| # | Priority | File | Action | Description |
|---|----------|------|--------|-------------|
| 28 | P3 | `OsmAnd/.../flockfree/ble/BleFlockManager.java` | Create | BLE connection manager |
| 29 | P3 | `OsmAnd/.../flockfree/ble/BleProtocolParser.java` | Create | FYHELLO/FYGPS/FYSIM/detection parser |
| 30 | P3 | `OsmAnd/.../flockfree/ble/BleFlockService.java` | Create | Foreground service |
| 31 | P3 | `OsmAnd/AndroidManifest.xml` | Edit | Add BLE permissions + service declaration |
| 32 | P3 | `OsmAnd/.../flockfree/FlockFreePlugin.java` | Edit | Wire BLE_DETECTION_ENABLED preference to start/stop service |

### Phase 7: Rebranding

| # | Priority | File | Action | Description |
|---|----------|------|--------|-------------|
| 33 | P3 | `OsmAnd/build.gradle` | Edit | Add FlockFree flavor or modify existing flavor |
| 34 | P3 | `OsmAnd/res/mipmap-*/ic_launcher.png` | Replace | Launcher icon |
| 35 | P3 | `OsmAnd/res/drawable/ic_launcher_foreground.xml` | Replace | Vector launcher foreground |
| 36 | P3 | `OsmAnd/res/values/strings.xml` | Edit | Update `app_name` |
| 37 | P3 | `OsmAnd/res/drawable/nav_drawer_header_default.png` | Replace | Drawer header image |
| 38 | P3 | `OsmAnd/res/values/colors.xml` | Edit | Add FlockFree brand colors |

### Phase 8: Camera Details & Polish

| # | Priority | File | Action | Description |
|---|----------|------|--------|-------------|
| 39 | P2 | `OsmAnd/.../flockfree/fragments/CameraDetailsFragment.java` | Create | Context menu details for camera tap |
| 40 | P2 | `OsmAnd/res/layout/flockfree_camera_details.xml` | Create | Details layout |
| 41 | P3 | `OsmAnd/.../flockfree/FlockFreePlugin.java` | Edit | Add `buildContextMenuRows()` for camera OSM link |
| 42 | P3 | `OsmAnd/.../flockfree/data/CameraDatabase.java` | Create | Database wrapper for queries |

---

## 10. Key OsmAnd Classes Reference

| Class | Path | Role |
|-------|------|------|
| `OsmandPlugin` | `OsmAnd/src/.../plugins/OsmandPlugin.java` | Abstract base for all plugins |
| `PluginsHelper` | `OsmAnd/src/.../plugins/PluginsHelper.java` | Plugin registry; `initPlugins()` is where plugins are registered |
| `OsmandMapLayer` | `OsmAnd/src/.../views/layers/base/OsmandMapLayer.java` | Abstract base for map layers |
| `ImpassableRoadsLayer` | `OsmAnd/src/.../views/layers/ImpassableRoadsLayer.java` | Reference for custom marker layer with OpenGL |
| `OsmEditsLayer` | `OsmAnd/src/.../plugins/osmedit/OsmEditsLayer.java` | Reference for plugin-owned layer |
| `OsmEditingPlugin` | `OsmAnd/src/.../plugins/osmedit/OsmEditingPlugin.java` | Reference for full plugin pattern |
| `AvoidRoadsHelper` | `OsmAnd/src/.../avoidroads/AvoidRoadsHelper.java` | Road blocking for routing avoidance |
| `AvoidRoadInfo` | `OsmAnd/src/.../avoidroads/AvoidRoadInfo.java` | Data class for blocked roads |
| `RoutingHelper` | `OsmAnd/src/.../routing/RoutingHelper.java` | Route calculation; `onSettingsChanged()` triggers recalc |
| `MapLayers` | `OsmAnd/src/.../views/MapLayers.java` | Layer registry for the main map |
| `EditPoiData` | `OsmAnd/src/.../plugins/osmedit/data/EditPoiData.java` | POI tag editing model |
| `EditPoiDialogFragment` | `OsmAnd/src/.../plugins/osmedit/dialogs/EditPoiDialogFragment.java` | POI edit dialog |
| `OsmandMapTileView` | `OsmAnd/src/.../views/OsmandMapTileView.java` | Map view; `addLayer()` / `removeLayer()` |
| `OsmAndCustomizationConstants` | `OsmAnd-api/src/.../OsmAndCustomizationConstants.java` | Plugin/menu ID constants |
| `IOsmAndAidlInterface` | `OsmAnd-api/src/.../IOsmAndAidlInterface.aidl` | AIDL API for external integration |
| `ABlockedRoad` | `OsmAnd-api/src/.../navigation/ABlockedRoad.java` | AIDL blocked road model |
| `NavigateParams` | `OsmAnd-api/src/.../navigation/NavigateParams.java` | AIDL navigation start params |
| `AMapLayer` | `OsmAnd-api/src/.../maplayer/AMapLayer.java` | AIDL map layer model |
| `AMapPoint` | `OsmAnd-api/src/.../maplayer/point/AMapPoint.java` | AIDL map point model |

---

## 11. GeoJSON Data Structure

From the actual data file (`/tmp/deflock-cameras.geojson.gz`):

```json
{
  "type": "FeatureCollection",
  "features": [
    {
      "type": "Feature",
      "geometry": {
        "type": "Point",
        "coordinates": [-94.2539975, 39.013246]
      },
      "properties": {
        "osmId": 13938820801,
        "osmType": "node",
        "brand": "Flock Safety",
        "direction": 158,
        "surveillanceZone": "traffic",
        "osmTimestamp": "2026-06-14T17:28:35Z",
        "osmVersion": 1
      }
    },
    {
      "type": "Feature",
      "geometry": {
        "type": "Point",
        "coordinates": [-88.4883761, 45.1937525]
      },
      "properties": {
        "osmId": 13938888601,
        "osmType": "node",
        "brand": "Flock Safety",
        "direction": 286,
        "directions": [286, 150],
        "surveillanceZone": "traffic",
        "osmTimestamp": "2026-06-14T18:13:48Z",
        "osmVersion": 1
      }
    }
  ]
}
```

**Property field summary:**

| Field | Type | Required | Notes |
|-------|------|----------|-------|
| `osmId` | long | yes | OSM node ID |
| `osmType` | string | yes | Always `"node"` |
| `brand` | string | no | `"Flock Safety"`, `"Motorola Solutions"`, `"Leonardo"`, `"Genetec"`, `"Neology, Inc."`, `"PlateSmart/CyclopsTchnlgs"` |
| `direction` | int | yes | Bearing 0-359 degrees |
| `directions` | int[] | no | Multi-direction cameras |
| `surveillanceZone` | string | no | `"traffic"`, `"parking"`, `"entrance"`, `"street"`, `"gate"` |
| `mountType` | string | no | `"pole"`, `"street_lamp"`, `"traffic_signals"`, `"wall"`, `"ceiling"`, `"new pole mount"` |
| `operator` | string | no | Police dept, company, etc. |
| `osmTimestamp` | string | yes | ISO 8601 |
| `osmVersion` | int | yes | OSM version number |

---

## 12. Risks & Mitigations

| Risk | Impact | Mitigation |
|------|--------|------------|
| 104k markers cause map lag | High | Bounding-box query + render cap (500 max) + spatial index in SQLite |
| Camera avoidance blocks too many roads | Medium | Limit to cameras within `CAMERA_AVOIDANCE_RADIUS` (default 200m) of route segments |
| OsmAnd API changes break plugin | Medium | Pin to specific OsmAnd commit; use AIDL API as fallback for routing |
| 28 MB bundled data inflates APK | Low | Option to download on first run instead of bundling |
| BLE protocol undocumented/unstable | Medium | Parse defensively; log unparsed messages; make BLE feature opt-in |
| GeoJSON parsing on main thread | High | All parsing in background `AsyncTask` / `ExecutorService` |
| Memory pressure from 104k CameraData objects | Medium | Only load cameras for visible bbox; use Cursor-based iteration for DB queries |

---

## 13. Development Order Summary

1. **Phase 1 (P0):** Plugin skeleton + data model + DB + sync + map layer → cameras on map
2. **Phase 2 (P1):** Camera avoidance routing
3. **Phase 3 (P1):** Reporting via OSM edit integration
4. **Phase 4 (P1-P2):** Configure Map toggle + widgets
5. **Phase 5 (P2):** Settings screen
6. **Phase 6 (P3):** BLE hardware integration
7. **Phase 7 (P3):** Full rebranding
8. **Phase 8 (P2-P3):** Camera details + polish

**Estimated effort:** 2-3 weeks for Phase 1-3 (MVP with cameras on map + avoidance + reporting). 4-6 weeks for full feature set including BLE.
