# Design Doc: CYD Detections List

**Feature ID:** forge-fixes-002  
**Author:** Bob (Architect)  
**Date:** 2026-06-29  
**Status:** Ready for Forge  

## Problem

When the CYD hardware detects an ALPR system, `FlockFreeLayer` drops a cyan diamond marker on the map at the detection's GPS coordinates. While driving, Eric cannot safely tap that marker — it may be off-screen, at a bad zoom level, or simply too small to hit. There is no way to review past detections without remembering where each diamond was and manually panning/zooming to find it.

## Solution

Add a "Recent Detections" list accessible from the FlockFree settings screen. The list shows all recent CYD detections (up to 20, already maintained by `CydHardwareManager.recentDetections`) with type, source, time-ago, GPS status, and actions to show-on-map or report.

## UX Approach: Settings Preference → AlertDialog List

**Recommendation:** Add a "Recent Detections" `Preference` item in the CYD Hardware category of `flockfree_preferences.xml`. Tapping it opens an `AlertDialog` containing a scrollable list of detections rendered via a custom `ArrayAdapter<CydDetectionCandidate>`.

### Why this approach over a Bottom Sheet

| Factor | Settings Preference + AlertDialog | Bottom Sheet from map UI |
|--------|-----------------------------------|--------------------------|
| **Driving safety** | Settings is reachable via menu → already a "parked" context. AlertDialog is modal, clear, dismissable. | Bottom sheet from a FAB or map button requires reaching for a small target while driving. |
| **Code simplicity** | 1 new preference item + 1 new dialog class + 1 adapter. No new layout XML needed — use `android.R.layout.select_dialog_item` or a simple custom row. | Requires new bottom sheet fragment, layout XML, fragment lifecycle management, and integration with map UI elements. |
| **Consistency** | Matches existing FlockFree settings pattern — all CYD actions (connect, simulate, clear) are already preferences that trigger dialogs/actions. | Would need a new map UI entry point — a button or FAB — which adds visual clutter. |
| **Persistence** | No new persistence needed — reads from `getRecentDetections()` which already loads from `CydDetectionStore` on startup. | Same. |
| **Reachability** | Two taps from map: menu → Settings → FlockFree → "Recent Detections". Not instant, but safe and predictable. | One tap from map, but the tap target is small and requires visual focus. |

**Decision:** Settings preference + AlertDialog. The use case is review-after-the-fact, not real-time-while-driving. Eric pulls over or has a passenger open settings. This matches the existing pattern perfectly.

### Alternative considered: Bottom Sheet from the existing navigation FAB

The `NavigationReportButton` / `ReportBottomSheet` pattern is nice, but adding a "Detections" item to that sheet would conflate reporting (active action) with detection review (passive review). The settings screen is the right home for a review list.

## Files to Create

### 1. `CydDetectionListAdapter.java`
**Path:** `OsmAnd/src/net/osmand/plus/plugins/flockfree/cyd/CydDetectionListAdapter.java`

Custom `ArrayAdapter<CydDetectionCandidate>` that renders each detection as a row in the dialog list.

```
package net.osmand.plus.plugins.flockfree.cyd;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.ImageButton;

import net.osmand.plus.R;
import net.osmand.plus.plugins.flockfree.cyd.CydDetectionCandidate;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class CydDetectionListAdapter extends ArrayAdapter<CydDetectionCandidate> {

    public interface DetectionActionListener {
        void onShowOnMap(CydDetectionCandidate detection);
        void onReport(CydDetectionCandidate detection);
    }

    private final DetectionActionListener listener;
    private final long nowMs;

    public CydDetectionListAdapter(Context context, List<CydDetectionCandidate> detections,
                                   DetectionActionListener listener) {
        super(context, 0, detections);
        this.listener = listener;
        this.nowMs = System.currentTimeMillis();
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(getContext())
                    .inflate(R.layout.flockfree_cyd_detection_list_item, parent, false);
        }
        CydDetectionCandidate detection = getItem(position);

        // Type label
        TextView typeLabel = convertView.findViewById(R.id.detection_type);
        typeLabel.setText(detection.getDetectionTypeLabel());

        // Source label
        TextView sourceLabel = convertView.findViewById(R.id.detection_source);
        sourceLabel.setText(detection.getSourceLabel());

        // Time ago
        TextView timeLabel = convertView.findViewById(R.id.detection_time);
        timeLabel.setText(formatTimeAgo(detection.getReceivedAgeMs(nowMs)));

        // GPS status
        TextView gpsLabel = convertView.findViewById(R.id.detection_gps);
        gpsLabel.setText(detection.hasGpsFix()
                ? String.format(Locale.US, "%.6f, %.6f", detection.getLatitude(), detection.getLongitude())
                : "GPS unavailable");

        // Show on Map button
        ImageButton showOnMapBtn = convertView.findViewById(R.id.btn_show_on_map);
        showOnMapBtn.setOnClickListener(v -> listener.onShowOnMap(detection));

        // Report button
        ImageButton reportBtn = convertView.findViewById(R.id.btn_report);
        reportBtn.setOnClickListener(v -> listener.onReport(detection));

        // Disable report button if no GPS fix
        reportBtn.setEnabled(detection.hasGpsFix());
        if (!detection.hasGpsFix()) {
            reportBtn.setAlpha(0.4f);
        }

        return convertView;
    }

    private String formatTimeAgo(long ageMs) {
        long seconds = ageMs / 1000L;
        if (seconds < 60) return seconds + " sec ago";
        long minutes = seconds / 60L;
        if (minutes < 60) return minutes + " min ago";
        long hours = minutes / 60L;
        if (hours < 24) return hours + " hr ago";
        long days = hours / 24L;
        return days + " day(s) ago";
    }
}
```

### 2. `flockfree_cyd_detection_list_item.xml`
**Path:** `OsmAnd/res/layout/flockfree_cyd_detection_list_item.xml`

Layout for each row in the detection list.

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="horizontal"
    android:padding="12dp"
    android:gravity="center_vertical">

    <LinearLayout
        android:layout_width="0dp"
        android:layout_height="wrap_content"
        android:layout_weight="1"
        android:orientation="vertical">

        <TextView
            android:id="@+id/detection_type"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textStyle="bold"
            android:textSize="14sp"
            android:textColor="?android:textColorPrimary" />

        <TextView
            android:id="@+id/detection_source"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textSize="13sp"
            android:textColor="?android:textColorSecondary" />

        <TextView
            android:id="@+id/detection_time"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textSize="12sp"
            android:textColor="?android:textColorTertiary" />

        <TextView
            android:id="@+id/detection_gps"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:textSize="12sp"
            android:textColor="?android:textColorTertiary"
            android:maxLines="1"
            android:ellipsize="middle" />

    </LinearLayout>

    <ImageButton
        android:id="@+id/btn_show_on_map"
        android:layout_width="40dp"
        android:layout_height="40dp"
        android:src="@drawable/ic_action_globe_dark"
        android:background="?android:attr/selectableItemBackgroundBorderless"
        android:contentDescription="@string/flockfree_cyd_detection_list_show_on_map" />

    <ImageButton
        android:id="@+id/btn_report"
        android:layout_width="40dp"
        android:layout_height="40dp"
        android:src="@drawable/ic_action_plus_dark"
        android:background="?android:attr/selectableItemBackgroundBorderless"
        android:contentDescription="@string/flockfree_cyd_detection_list_report" />

</LinearLayout>
```

### 3. `CydDetectionListDialog.java`
**Path:** `OsmAnd/src/net/osmand/plus/plugins/flockfree/cyd/CydDetectionListDialog.java`

Static utility class that builds and shows the AlertDialog. Follows the same `AlertDialog.Builder` pattern used throughout the FlockFree plugin (e.g., `showCydDetectionDetails`, `showCameraDetails`, `CameraReporter.showAddCameraDialog`).

```
package net.osmand.plus.plugins.flockfree.cyd;

import android.app.AlertDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.plugins.flockfree.FlockFreePlugin;

import java.util.List;

public final class CydDetectionListDialog {

    private CydDetectionListDialog() {}

    public static void show(@NonNull MapActivity mapActivity) {
        FlockFreePlugin plugin = PluginsHelper.getEnabledPlugin(FlockFreePlugin.class);
        if (plugin == null) return;

        OsmandApplication app = (OsmandApplication) mapActivity.getApplication();
        List<CydDetectionCandidate> detections = plugin.getCydHardwareManager().getRecentDetections();

        AlertDialog.Builder builder = new AlertDialog.Builder(mapActivity);
        builder.setTitle(R.string.flockfree_cyd_detection_list_title);

        if (detections.isEmpty()) {
            // Empty state
            View emptyView = LayoutInflater.from(mapActivity)
                    .inflate(R.layout.flockfree_cyd_detection_list_empty, null);
            builder.setView(emptyView);
            builder.setPositiveButton(R.string.shared_string_close, null);
        } else {
            // List with adapter
            View listViewContainer = LayoutInflater.from(mapActivity)
                    .inflate(R.layout.flockfree_cyd_detection_list, null);
            ListView listView = listViewContainer.findViewById(R.id.detection_list_view);

            CydDetectionListAdapter adapter = new CydDetectionListAdapter(
                    mapActivity, detections, new CydDetectionListAdapter.DetectionActionListener() {
                @Override
                public void onShowOnMap(CydDetectionCandidate detection) {
                    showOnMap(mapActivity, detection);
                }

                @Override
                public void onReport(CydDetectionCandidate detection) {
                    plugin.showCydDetectionReportFromList(mapActivity, detection);
                }
            });
            listView.setAdapter(adapter);

            builder.setView(listViewContainer);
            builder.setPositiveButton(R.string.shared_string_close, null);
            builder.setNegativeButton(R.string.flockfree_cyd_clear_detections,
                    (dialog, which) -> {
                        plugin.getCydHardwareManager().clearDetections();
                    });
        }

        AlertDialog dialog = builder.create();
        dialog.show();
    }

    private static void showOnMap(@NonNull MapActivity mapActivity,
                                  @NonNull CydDetectionCandidate detection) {
        if (!detection.hasGpsFix()) {
            ((OsmandApplication) mapActivity.getApplication())
                    .showShortToastMessage(R.string.flockfree_cyd_detection_no_gps);
            return;
        }
        // Close the dialog (and settings) then animate map to detection
        // The dialog is dismissed by finishing the activity or dismissing the dialog.
        // Since we're in a settings fragment context, we dismiss the dialog and
        // navigate back to the map, then animate.
        // Actually: the dialog's host is MapActivity (since FlockFreeSettingsFragment
        // is hosted by MapActivity). So we can just dismiss the dialog and animate.
        double lat = detection.getLatitude();
        double lon = detection.getLongitude();
        int targetZoom = 16;

        // Dismiss all dialogs
        if (mapActivity.hasWindowFocus()) {
            // Dialog is showing over MapActivity — dismiss it by sending a dismiss
            // We need the dialog reference. Better approach: dismiss in the listener
            // before calling showOnMap. But simplest: just animate, the dialog
            // will be dismissed by the caller.
        }

        mapActivity.getMapView().getAnimatedDraggingThread()
                .startMoving(lat, lon, targetZoom);
        mapActivity.getMapView().setIntZoom(targetZoom);
        mapActivity.refreshMap();
    }
}
```

**Note for Forge:** The `showOnMap` implementation needs careful dialog dismissal handling. The cleanest approach: in the adapter's `onShowOnMap` callback, call `dialog.dismiss()` before animating. To do this, `show()` should pass the `AlertDialog` reference to the listener, or the listener should dismiss via a callback. See "Implementation Detail: Dialog Dismissal" below.

### 4. `flockfree_cyd_detection_list.xml`
**Path:** `OsmAnd/res/layout/flockfree_cyd_detection_list.xml`

Container layout for the ListView inside the dialog.

```xml
<?xml version="1.0" encoding="utf-8"?>
<ListView xmlns:android="http://schemas.android.com/apk/res/android"
    android:id="@+id/detection_list_view"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:divider="?android:attr/listDivider"
    android:dividerHeight="1dp" />
```

### 5. `flockfree_cyd_detection_list_empty.xml`
**Path:** `OsmAnd/res/layout/flockfree_cyd_detection_list_empty.xml`

Empty state layout shown when no detections exist.

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:orientation="vertical"
    android:padding="24dp"
    android:gravity="center">

    <TextView
        android:layout_width="wrap_content"
        android:layout_height="wrap_content"
        android:text="@string/flockfree_cyd_detection_list_empty"
        android:textSize="14sp"
        android:textColor="?android:textColorSecondary"
        android:gravity="center" />

</LinearLayout>
```

## Files to Modify

### 1. `flockfree_preferences.xml`
**Path:** `OsmAnd/res/xml/flockfree_preferences.xml`

**Location:** After the `flockfree_cyd_status` preference and before `flockfree_cyd_connect`, insert a new preference item.

**Add:**
```xml
<Preference
    android:key="flockfree_cyd_recent_detections"
    android:layout="@layout/preference_with_descr"
    android:persistent="false"
    android:title="@string/flockfree_cyd_detection_list_title" />
```

Insert this between the `flockfree_cyd_status` block and the `flockfree_cyd_connect` block (approximately line 255 area). This places it right below the CYD status, which is the most logical position — the user sees status, then can tap to review detections.

### 2. `strings.xml`
**Path:** `OsmAnd/res/values/strings.xml`

**Location:** After the existing `flockfree_cyd_*` strings (after line 270, `flockfree_cyd_service_notification_text`).

**Add:**
```xml
<string name="flockfree_cyd_detection_list_title">Recent Detections</string>
<string name="flockfree_cyd_detection_list_empty">No CYD detections recorded yet. Detections with GPS coordinates will appear here.</string>
<string name="flockfree_cyd_detection_list_show_on_map">Show on map</string>
<string name="flockfree_cyd_detection_list_report">Report as ALPR camera</string>
```

### 3. `FlockFreeSettingsFragment.java`
**Path:** `OsmAnd/src/net/osmand/plus/plugins/flockfree/FlockFreeSettingsFragment.java`

#### 3a. Add key constant
**Location:** Near other `CYD_*` key constants (around line 39).

**Add:**
```java
private static final String CYD_RECENT_DETECTIONS_KEY = "flockfree_cyd_recent_detections";
```

#### 3b. Add setup method
**Location:** In `setupPreferences()`, after `setupCydStatusPreference()` call (around line 90).

**Add call:**
```java
setupCydRecentDetectionsPreference();
```

**Add method** (near `setupCydStatusPreference()`, around line 195):
```java
private void setupCydRecentDetectionsPreference() {
    Preference preference = findPreference(CYD_RECENT_DETECTIONS_KEY);
    if (preference != null) {
        int count = plugin.getCydHardwareManager().getRecentDetectionCount();
        preference.setSummary(count > 0
                ? getString(R.string.flockfree_cyd_detection_list_count, count)
                : getString(R.string.flockfree_cyd_detection_list_empty));
    }
}
```

**Note:** This requires one additional string:
```xml
<string name="flockfree_cyd_detection_list_count">%1$d detection(s) recorded</string>
```

Add this to the strings.xml block above (total 5 new strings).

#### 3c. Handle preference click
**Location:** In `onPreferenceClick(Preference preference)`, in the chain of `if/else if` statements (around line 260, after the `CYD_CLEAR_DETECTIONS_KEY` block).

**Add:**
```java
} else if (CYD_RECENT_DETECTIONS_KEY.equals(key)) {
    MapActivity mapActivity = getMapActivity();
    if (mapActivity != null) {
        CydDetectionListDialog.show(mapActivity);
    }
    return true;
```

#### 3d. Refresh in dynamic status refresh
**Location:** In `refreshDynamicStatusPreferences()`, after `setupCydStatusPreference()` call (around line 310).

**Add:**
```java
setupCydRecentDetectionsPreference();
```

### 4. `FlockFreePlugin.java`
**Path:** `OsmAnd/src/net/osmand/plus/plugins/flockfree/FlockFreePlugin.java`

#### 4a. Make `showCydDetectionReport` accessible from list dialog
**Location:** The existing `showCydDetectionReport` method (line ~1149) is `private`. Change it to package-private or add a wrapper.

**Change:**
```java
// FROM:
private void showCydDetectionReport(@NonNull MapActivity mapActivity,
                                    @NonNull CydDetectionCandidate detection) {

// TO:
void showCydDetectionReportFromList(@NonNull MapActivity mapActivity,
                                    @NonNull CydDetectionCandidate detection) {
    showCydDetectionReport(mapActivity, detection);
}
```

Or simply change the existing method from `private` to package-private:
```java
void showCydDetectionReport(@NonNull MapActivity mapActivity,
                            @NonNull CydDetectionCandidate detection) {
```

**Recommended:** Change visibility from `private` to package-private (remove `private`). The `CydDetectionListDialog` is in the same package (`net.osmand.plus.plugins.flockfree.cyd`), so it would need to be `public` or the dialog should call through the plugin. Since `CydDetectionListDialog` calls `plugin.showCydDetectionReport(...)`, the method needs to be at least package-visible. Since `CydDetectionListDialog` is in a sub-package (`flockfree.cyd`), the method should be `public`.

**Final decision:** Change `showCydDetectionReport` from `private` to `public`:
```java
public void showCydDetectionReport(@NonNull MapActivity mapActivity,
                                    @NonNull CydDetectionCandidate detection) {
```

Also add a convenience method for the list dialog to call:
```java
public void showCydDetectionDetailsFromList(@NonNull MapActivity mapActivity,
                                             @NonNull CydDetectionCandidate detection) {
    showCydDetectionDetails(mapActivity, detection);
}
```

This is optional — the list dialog can call `showCydDetectionReport` directly. The "details" view is already accessible from the map marker tap. The list focuses on "Show on Map" and "Report" actions.

## Implementation Detail: Dialog Dismissal for "Show on Map"

The `CydDetectionListDialog.show()` method creates an `AlertDialog`. When the user taps "Show on Map", we need to:
1. Dismiss the AlertDialog
2. Close the settings screen (navigate back to map)
3. Animate the map to the detection's coordinates

**Approach:** The `CydDetectionListAdapter.DetectionActionListener` callbacks should receive a reference to the `AlertDialog` so they can dismiss it. Modified pattern:

```java
// In CydDetectionListDialog.show():
final AlertDialog[] dialogHolder = new AlertDialog[1];

CydDetectionListAdapter adapter = new CydDetectionListAdapter(
        mapActivity, detections, new CydDetectionListAdapter.DetectionActionListener() {
    @Override
    public void onShowOnMap(CydDetectionCandidate detection) {
        if (dialogHolder[0] != null) dialogHolder[0].dismiss();
        showOnMap(mapActivity, detection);
    }

    @Override
    public void onReport(CydDetectionCandidate detection) {
        if (dialogHolder[0] != null) dialogHolder[0].dismiss();
        plugin.showCydDetectionReport(mapActivity, detection);
    }
});

// ... build and show ...
dialogHolder[0] = builder.create();
dialogHolder[0].show();
```

For "Show on Map", after dismissing the dialog, we also want to navigate back to the map (close settings). Since `FlockFreeSettingsFragment` is hosted inside `MapActivity`'s settings UI, dismissing the settings fragment will reveal the map underneath. The simplest approach:

```java
private static void showOnMap(@NonNull MapActivity mapActivity,
                              @NonNull CydDetectionCandidate detection) {
    if (!detection.hasGpsFix()) {
        ((OsmandApplication) mapActivity.getApplication())
                .showShortToastMessage(R.string.flockfree_cyd_detection_no_gps);
        return;
    }
    double lat = detection.getLatitude();
    double lon = detection.getLongitude();
    int targetZoom = 16;

    // Navigate back to map (close settings if open)
    mapActivity.onBackPressed();

    // Animate map to detection
    mapActivity.getMapView().getAnimatedDraggingThread()
            .startMoving(lat, lon, targetZoom);
    mapActivity.refreshMap();
}
```

**Note:** `mapActivity.onBackPressed()` may need to be called on the UI thread. Since the dialog callback is already on the UI thread, this should work. However, if the settings fragment doesn't close cleanly, an alternative is:

```java
mapActivity.getSupportFragmentManager().popBackStack();
```

Forge should test this interaction and use whichever works. The map animation call is safe regardless.

## Exact Change Summary

| File | Action | Description |
|------|--------|-------------|
| `OsmAnd/res/xml/flockfree_preferences.xml` | Modify | Add `flockfree_cyd_recent_detections` Preference item in CYD Hardware category |
| `OsmAnd/res/values/strings.xml` | Modify | Add 5 new strings for detection list |
| `OsmAnd/res/layout/flockfree_cyd_detection_list_item.xml` | Create | Row layout for each detection |
| `OsmAnd/res/layout/flockfree_cyd_detection_list.xml` | Create | ListView container for dialog |
| `OsmAnd/res/layout/flockfree_cyd_detection_list_empty.xml` | Create | Empty state layout |
| `OsmAnd/src/.../flockfree/cyd/CydDetectionListAdapter.java` | Create | ArrayAdapter for detection rows |
| `OsmAnd/src/.../flockfree/cyd/CydDetectionListDialog.java` | Create | Static dialog builder class |
| `OsmAnd/src/.../flockfree/FlockFreeSettingsFragment.java` | Modify | Add key constant, setup method, click handler, refresh call |
| `OsmAnd/src/.../flockfree/FlockFreePlugin.java` | Modify | Change `showCydDetectionReport` from `private` to `public` |

## New String Resources (complete list)

```xml
<string name="flockfree_cyd_detection_list_title">Recent Detections</string>
<string name="flockfree_cyd_detection_list_count">%1$d detection(s) recorded</string>
<string name="flockfree_cyd_detection_list_empty">No CYD detections recorded yet. Detections with GPS coordinates will appear here.</string>
<string name="flockfree_cyd_detection_list_show_on_map">Show on map</string>
<string name="flockfree_cyd_detection_list_report">Report as ALPR camera</string>
```

## Implementation Order

1. **Add strings** to `strings.xml` — all 5 new strings after line 270.
2. **Create layout XMLs** — 3 new files (`flockfree_cyd_detection_list.xml`, `flockfree_cyd_detection_list_empty.xml`, `flockfree_cyd_detection_list_item.xml`).
3. **Create `CydDetectionListAdapter.java`** — the ArrayAdapter with row binding and action callbacks.
4. **Create `CydDetectionListDialog.java`** — the static dialog show method with empty/list handling, dialog dismissal, and map animation.
5. **Modify `FlockFreePlugin.java`** — change `showCydDetectionReport` visibility from `private` to `public`.
6. **Modify `flockfree_preferences.xml`** — add the new Preference item in the CYD Hardware section.
7. **Modify `FlockFreeSettingsFragment.java`** — add key constant, setup method, click handler, and refresh call.
8. **Build and test** — verify compilation, then test on device.

## Validation Checklist (for Karen)

### Build
- [ ] Project compiles without errors
- [ ] No new lint warnings introduced
- [ ] All 5 new strings are defined in `strings.xml`
- [ ] All 3 new layout XMLs are valid (no missing IDs, correct namespaces)

### Functional — Settings Entry Point
- [ ] "Recent Detections" preference appears in FlockFree settings under CYD Hardware category
- [ ] Preference summary shows "X detection(s) recorded" when detections exist
- [ ] Preference summary shows empty state message when no detections exist
- [ ] Tapping the preference opens the detection list dialog

### Functional — Empty State
- [ ] When no detections exist, dialog shows the empty state message
- [ ] Empty state dialog has only a "Close" button
- [ ] Empty state layout is centered and readable

### Functional — List Display
- [ ] Each row shows detection type label (e.g., "Wi-Fi probe", "Simulated CYD")
- [ ] Each row shows source label (device name / SSID / MAC)
- [ ] Each row shows time ago (e.g., "2 min ago", "1 hr ago")
- [ ] Each row shows GPS coordinates or "GPS unavailable"
- [ ] Rows are ordered newest-first (matching `getRecentDetections()` order)
- [ ] List is scrollable when more items than fit on screen
- [ ] List shows up to 20 items (the max from `CydHardwareManager`)

### Functional — Show on Map
- [ ] Tapping the globe icon dismisses the dialog
- [ ] Tapping the globe icon closes the settings screen (returns to map)
- [ ] Map animates to the detection's coordinates
- [ ] Map zoom level is set to ~16
- [ ] The cyan diamond marker for that detection is visible after animation
- [ ] If detection has no GPS fix, tapping globe shows "CYD detection has no GPS fix" toast and does not animate

### Functional — Report
- [ ] Tapping the + icon dismisses the dialog
- [ ] Tapping the + icon opens the camera reporter dialog (`showAddCameraDialog`) with detection's GPS pre-filled
- [ ] Report button is disabled (dimmed) when detection has no GPS fix
- [ ] Tapping disabled report button does nothing

### Functional — Clear All
- [ ] "Clear" button (negative button) in the list dialog calls `clearDetections()`
- [ ] After clearing, the list dialog closes or updates to empty state
- [ ] After clearing, the settings preference summary updates to empty state
- [ ] After clearing, map markers disappear

### Functional — Persistence
- [ ] Detections loaded from `CydDetectionStore` on app start appear in the list
- [ ] New detections received while CYD is connected appear in the list after reopening
- [ ] Simulated detections appear in the list
- [ ] Clearing detections also clears the persisted JSON file

### Regression
- [ ] Existing CYD status preference still works
- [ ] Existing "Scan and connect CYD" still works
- [ ] Existing "Simulate CYD detection" still works
- [ ] Existing "Clear CYD detections" still works
- [ ] Existing map marker tap → detection details dialog still works
- [ ] Existing context menu "Review Detection" and "Detection Details" still work
- [ ] No crashes when opening settings with 0 detections
- [ ] No crashes when opening settings with 20 detections

## Key Method References (for Forge)

| Method | Class | Location |
|--------|-------|----------|
| `getRecentDetections()` | `CydHardwareManager` | Returns `List<CydDetectionCandidate>` copy, thread-safe |
| `getRecentDetectionCount()` | `CydHardwareManager` | Returns int count |
| `clearDetections()` | `CydHardwareManager` | Clears in-memory + persisted, calls `refreshMap()` |
| `hasGpsFix()` | `CydDetectionCandidate` | Returns true if lat+lon valid |
| `getLatitude()` / `getLongitude()` | `CydDetectionCandidate` | Returns `Double` (nullable) |
| `getDetectionTypeLabel()` | `CydDetectionCandidate` | Returns String |
| `getSourceLabel()` | `CydDetectionCandidate` | Returns String |
| `getReceivedAgeMs(long)` | `CydDetectionCandidate` | Returns age in ms |
| `getGpsStatus()` | `CydDetectionCandidate` | Returns formatted GPS status string |
| `showCydDetectionReport(MapActivity, CydDetectionCandidate)` | `FlockFreePlugin` | Opens camera reporter with detection GPS (change to public) |
| `showCydDetectionDetails(MapActivity, CydDetectionCandidate)` | `FlockFreePlugin` | Shows details AlertDialog (already public) |
| `getCameraReporter().showAddCameraDialog(MapActivity, lat, lon)` | `CameraReporter` | Opens POI editor with pre-filled tags |
| `getAnimatedDraggingThread().startMoving(lat, lon, zoom)` | `OsmandMapTileView` | Animates map to position |
| `setIntZoom(int)` | `OsmandMapTileView` | Sets integer zoom level |
| `refreshMap()` | `MapActivity` / `OsmandMap` | Refreshes map rendering |

## Icons

Use existing OsmAnd drawable resources:
- **Show on Map:** `@drawable/ic_action_globe_dark` (or `ic_map`)
- **Report:** `@drawable/ic_action_plus_dark` (already used for "Review as ALPR camera" context menu item)

Forge should verify these drawable names exist in the project. If `ic_action_globe_dark` doesn't exist, search for an alternative map/location icon in `res/drawable/`.