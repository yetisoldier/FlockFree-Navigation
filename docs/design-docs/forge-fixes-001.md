# Forge Design Doc: Three Widget Fixes for FlockFree Navigation

**Author:** Bob (Architect)
**Date:** 2025-07-17
**Target:** Forge implementation agent

---

## Issue 1: Long Rectangle Appears Before Road Name in Next-Turn Widget

### Problem

Eric reports that a long rectangle sometimes appears before the road name in the next-turn widget. This is intermittent.

### Root Cause

The "long rectangle" is a **road shield image that failed to render its text/icon content** but still occupies space in the `shieldImagesContainer` LinearLayout. When `setShieldImage()` in `StreetNameWidget.java` partially succeeds — i.e., the shield drawable resource is found and an ImageView is added to the container, but the shield's text rendering or the shield icon drawable itself fails — the result is an ImageView displaying a blank/rectangle-shaped bitmap.

Specifically, in `StreetNameWidget.setShieldImage()` (line 262–348), the method:

1. Searches for a shield drawable resource via `app.getResources().getIdentifier("h_" + text.getShieldResIcon(), "drawable", app.getPackageName())` (line 297)
2. If `shieldRes == 0`, returns `false` (no shield added) — this is fine
3. If `shieldDrawable != null`, creates a bitmap, draws the shield icon and text onto it, and adds an ImageView to `shieldImagesContainer`

The rectangle appears when:
- The shield drawable resource exists but is a simple rectangular shape (some render styles provide generic rectangle shields)
- OR the shield text fails to render (font issues, empty shield value) leaving just the shield background shape
- OR the `textRenderer.drawShieldIcon()` call produces an empty/opaque bitmap

Additionally, in `NextTurnBaseWidget.setStreetName()` (line 200–245), the shield container visibility is managed based on whether `setRoadShield()` returns `isShieldSet`. If shields are set but contain empty/rectangle bitmaps, the container shows but displays rectangles.

There is also a related issue in `NextTurnBaseWidget.setStreetName()` at line 209: `removeSymbol()` only strips `"» "` from the beginning of streetName.text. If the street name contains the `»` character mid-string (used as a separator towards destination), it can render as a rectangle/missing glyph on some devices/fonts.

### Files to Modify

1. **`OsmAnd/src/net/osmand/plus/views/mapwidgets/widgets/StreetNameWidget.java`** (lines 262–348)
2. **`OsmAnd/src/net/osmand/plus/views/mapwidgets/widgets/NextTurnBaseWidget.java`** (lines 200–245, 283–290)

### Fix

#### Fix 1a: Validate shield bitmap content before adding to container

In `StreetNameWidget.setShieldImage()`, after creating the bitmap (around line 330), add a check to verify the bitmap is not blank/all-transparent or a solid rectangle:

```java
// After line: imageView.setImageBitmap(bitmap);
// Add validation:
if (bitmap == null || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
    return false;
}
// Check if bitmap is entirely transparent or entirely one solid color (rectangle)
java.nio.IntBuffer buffer = java.nio.IntBuffer.allocate(bitmap.getWidth() * bitmap.getHeight());
bitmap.copyPixelsToBuffer(buffer);
int[] pixels = buffer.array();
boolean hasContent = false;
int firstPixel = pixels[0];
boolean allSameColor = true;
for (int pixel : pixels) {
    if ((pixel & 0xFF000000) != 0) { // has non-transparent pixel
        hasContent = true;
    }
    if (pixel != firstPixel) {
        allSameColor = false;
    }
}
if (!hasContent || allSameColor) {
    return false; // Don't add blank/solid-rectangle shields
}
```

**Note:** The pixel-scanning approach may be too expensive for every shield. A lighter approach: just check the four corners and center pixel for transparency/uniformity:

```java
// Lightweight check: sample a few pixels
boolean hasContent = false;
int w = bitmap.getWidth(), h = bitmap.getHeight();
int[] samples = {
    bitmap.getPixel(w / 2, h / 2),
    bitmap.getPixel(w / 4, h / 4),
    bitmap.getPixel(3 * w / 4, 3 * h / 4),
    bitmap.getPixel(w / 2, h / 2)  // center again for safety
};
for (int px : samples) {
    if ((px & 0xFF000000) != 0 && px != Color.TRANSPARENT) {
        hasContent = true;
        break;
    }
}
if (!hasContent) {
    return false;
}
```

#### Fix 1b: Guard against `»` character rendering as rectangle

In `NextTurnBaseWidget.setStreetName()` (line 201), the `removeSymbol` method only strips `"» "` from the start. If the text starts with just `"»"` (no space), it won't be stripped. Update `removeSymbol`:

**File:** `OsmAnd/src/net/osmand/plus/views/mapwidgets/widgets/NextTurnBaseWidget.java`
**Line:** ~307

```java
// OLD:
public static String removeSymbol(String input) {
    if (input.startsWith("» ")) {
        return input.replace("» ", "");
    }
    return input;
}

// NEW:
public static String removeSymbol(String input) {
    if (input == null) return input;
    // Strip leading » characters with or without trailing space
    while (input.startsWith("» ") || input.startsWith("»")) {
        input = input.substring(input.startsWith("» ") ? 2 : 1).trim();
    }
    return input;
}
```

#### Fix 1c: Hide shield container when shields fail to render

In `NextTurnBaseWidget.setRoadShield()` (around line 210–222), add a fallback to hide the container if `setShieldImage` returned `false` for all shields:

The existing code already handles this via `isShieldSet`:
```java
isShieldSet |= setShieldImage(shield, addedShields, mapActivity, shieldImagesContainer, isNightMode());
```
and then:
```java
AndroidUiHelper.updateVisibility(shieldImagesContainer, isShieldSet);
```

This is correct IF `setShieldImage` returns `false` when the shield is invalid. Fix 1a ensures this by adding the validation. No further change needed here beyond Fix 1a.

### Summary for Forge (Issue 1)

1. In `StreetNameWidget.setShieldImage()` (around line 330, after `imageView.setImageBitmap(bitmap)`), add a lightweight pixel-sampling check to detect blank/solid-rectangle bitmaps. Return `false` if detected.
2. In `NextTurnBaseWidget.removeSymbol()` (line ~307), update to handle `»` without trailing space and multiple leading `»` characters.

### Risk
- **Low.** Shield validation only prevents adding invalid ImageViews. The `»` fix is a string manipulation that won't affect valid street names.
- Side effect: some legitimate shields that are very simple (single-color roundals with text) won't be affected because `allSameColor` would be false (text is a different color than background).

---

## Issue 2: Railroad Crossing and Crosswalk Indicators Show Behind Speed Indicator

### Problem

Alarm widgets (railroad crossings, crosswalks, etc.) sometimes appear behind the speedometer widget instead of above it. The z-order is inconsistent.

### Root Cause

In `OsmAnd/res/layout/map_hud_bottom.xml`, both `map_alarm_warning` and `speedometer_widget` are included in a `FrameLayout`:

```xml
<FrameLayout
    android:id="@+id/bottom_controls_container"
    android:layout_width="match_parent"
    android:layout_height="wrap_content">

    <include layout="@layout/map_alarm_warning" />     <!-- Added first -->
    <include layout="@layout/speedometer_widget" />    <!-- Added second -->
    ...
</FrameLayout>
```

In a `FrameLayout`, children are drawn in XML declaration order — later children draw on top of earlier children. So `speedometer_widget` (declared second) draws **on top of** `map_alarm_warning` (declared first). This is the **opposite** of what Eric wants.

However, the speedometer widget's child containers have explicit `android:elevation="2dp"` set in `speedometer_widget.xml` (lines 20 and 66), and the speed_limit_container also has `android:translationZ="2dp"` (line 72). The alarm widget has **no elevation or translationZ set** in `map_alarm_warning.xml`.

On Android, `elevation` overrides the standard draw order — views with elevation draw above views without elevation, regardless of XML order. So:
- When speedometer has elevation → it draws above alarm (always)
- The alarm can never draw above the speedometer because it has no elevation

This is exactly Eric's complaint: "Sometimes stuff like railroad crossing or crosswalk indicators show up behind the current speed indicator."

The "sometimes above" behavior Eric sees is likely due to the speedometer widget being hidden (visibility GONE) in certain situations, which allows the alarm to show through.

### Files to Modify

1. **`OsmAnd/res/layout/map_alarm_warning.xml`** — Add elevation
2. **`OsmAnd/res/layout/map_hud_bottom.xml`** — Ensure alarm is declared after speedometer (or handle via elevation)

### Fix

#### Fix 2a: Add elevation to map_alarm_warning.xml

**File:** `OsmAnd/res/layout/map_alarm_warning.xml`
**Line:** 4 (root element `net.osmand.plus.widgets.LinearLayoutEx`)

```xml
<!-- OLD: -->
<net.osmand.plus.widgets.LinearLayoutEx xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:id="@+id/map_alarm_warning"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:paddingHorizontal="@dimen/content_padding_minimal">

<!-- NEW: -->
<net.osmand.plus.widgets.LinearLayoutEx xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:id="@+id/map_alarm_warning"
    android:layout_width="wrap_content"
    android:layout_height="wrap_content"
    android:elevation="4dp"
    android:paddingHorizontal="@dimen/content_padding_minimal">
```

By setting `android:elevation="4dp"` on the alarm widget root, which is higher than the speedometer's `2dp` elevation, the alarm widget will **always draw above** the speedometer.

#### Fix 2b: Reorder includes in map_hud_bottom.xml (defense in depth)

**File:** `OsmAnd/res/layout/map_hud_bottom.xml`
**Lines:** 15–17

```xml
<!-- OLD: -->
<include layout="@layout/map_alarm_warning" />
<include layout="@layout/speedometer_widget" />

<!-- NEW: -->
<include layout="@layout/speedometer_widget" />
<include layout="@layout/map_alarm_warning" />
```

This ensures that even if elevation is stripped or not supported (older Android), the alarm draws after (on top of) the speedometer. Combined with Fix 2a's higher elevation, this guarantees correct z-order in all scenarios.

### Summary for Forge (Issue 2)

1. Add `android:elevation="4dp"` to the root element of `map_alarm_warning.xml`
2. Swap the order of the two `<include>` tags in `map_hud_bottom.xml` so `speedometer_widget` is first and `map_alarm_warning` is second

### Risk
- **Very low.** Elevation is purely visual z-ordering. The only possible side effect is a subtle shadow under the alarm widget (from the elevation), which is actually a desirable visual effect consistent with the speedometer widget already having elevation.

---

## Issue 3: Speed Indicator Color Thresholds (Yellow 1–10 mph over, Red 10+ mph over)

### Problem

Currently the speedometer changes to red (EXCEED) at 5+ km/h over the limit. Eric wants:
- 0 mph over = normal color (SAFE/white)
- 1–10 mph over = yellow (WARNING)
- 10+ mph over = red (EXCEED)

### Root Cause Analysis

The speed state is controlled by two booleans in `SpeedometerWidget.java`:

**Line 407–412:**
```java
float delta = app.getSettings().SPEED_LIMIT_EXCEED_KMH.get();
speedExceed = formattedSpeed.valueSrc > 0 && cachedSpeedLimit > 0 &&
        formattedSpeed.valueSrc > cachedSpeedLimit + delta;

speedExceedWarning = formattedSpeed.valueSrc > 0 && cachedWarningSpeedLimit > 0 &&
        formattedSpeed.valueSrc > cachedWarningSpeedLimit && !speedExceed;
```

- `delta` = `SPEED_LIMIT_EXCEED_KMH` setting (default **5.0**, in km/h) — this is the threshold for EXCEED (red)
- `speedExceedWarning` triggers when speed > limit AND speed ≤ limit + delta — this is the WARNING (yellow) range
- `SpeedState` enum maps these to colors:
  - `SAFE` → white/primary text color
  - `WARNING` → yellow background (`#FFBF00`), black text
  - `EXCEED` → red background (`#E71D34`), white text

**Current behavior with default 5 km/h delta:**
- 0–5 km/h over → WARNING (yellow)
- 5+ km/h over → EXCEED (red)

**Eric wants (in mph):**
- 0 mph over → SAFE
- 1–10 mph over → WARNING (yellow)
- 10+ mph over → EXCEED (red)

There are two problems:
1. The `SPEED_LIMIT_EXCEED_KMH` default of 5 km/h ≈ 3.1 mph. This means red triggers at 3.1 mph over, not 10 mph.
2. The WARNING range starts at 0 km/h over (any speed > limit triggers warning). Eric wants it to start at 1 mph over (not 0).

### The Unit Problem

`SPEED_LIMIT_EXCEED_KMH` is in **km/h** but `formattedSpeed.valueSrc` is in the user's **selected speed unit** (km/h or mph). When the user has mph selected, the comparison `formattedSpeed.valueSrc > cachedSpeedLimit + delta` compares mph against km/h — this is a **pre-existing bug**. The `delta` needs to be converted to the user's speed unit, or the comparison needs to be done in a consistent unit.

However, looking more carefully: `cachedSpeedLimit` comes from `alarm.getIntValue()`, which is set in `createSpeedAlarm()`:
```java
if (constants.getImperial()) {
    speed = Math.round(mxspeed * 3.6f / 1.6f);  // converts to mph
} else {
    speed = Math.round(mxspeed * 3.6f);  // converts to km/h
}
```

So `cachedSpeedLimit` IS in the user's unit. But `SPEED_LIMIT_EXCEED_KMH` is always in km/h. So the `delta` comparison is indeed unit-buggy.

### Files to Modify

1. **`OsmAnd/src/net/osmand/plus/views/mapwidgets/widgets/SpeedometerWidget.java`** (lines 407–412)
2. **`OsmAnd/src/net/osmand/plus/settings/backend/OsmandSettings.java`** (line 1293) — change default

### Fix

#### Fix 3a: Change SPEED_LIMIT_EXCEED_KMH default to 10

**File:** `OsmAnd/src/net/osmand/plus/settings/backend/OsmandSettings.java`
**Line:** 1293

```java
// OLD:
public final OsmandPreference<Float> SPEED_LIMIT_EXCEED_KMH =
        new FloatPreference(this, "speed_limit_exceed", 5f).makeProfile();

// NEW:
public final OsmandPreference<Float> SPEED_LIMIT_EXCEED_KMH =
        new FloatPreference(this, "speed_limit_exceed", 10f).makeProfile();
```

Wait — this setting is in km/h, and Eric's threshold is in mph. 10 mph ≈ 16.09 km/h. But since `formattedSpeed.valueSrc` is in the user's selected unit, and `delta` is in km/h, the comparison is broken when using mph.

The correct fix is to convert `delta` to the user's speed unit before comparison. But actually, the cleaner approach is to hardcode the thresholds directly in `SpeedometerWidget.java` and not depend on `SPEED_LIMIT_EXCEED_KMH` at all for the color state transitions. The `SPEED_LIMIT_EXCEED_KMH` setting controls when the speed limit *sign* appears (via `WaypointHelper`), not the color state. The color thresholds should be independent.

#### Fix 3a (Revised): Add explicit color thresholds in SpeedometerWidget.java

**File:** `OsmAnd/src/net/osmand/plus/views/mapwidgets/widgets/SpeedometerWidget.java`
**Lines:** 407–412

Replace the current speed exceed/warning logic:

```java
// OLD (lines 407-412):
float delta = app.getSettings().SPEED_LIMIT_EXCEED_KMH.get();
speedExceed = formattedSpeed.valueSrc > 0 && cachedSpeedLimit > 0 &&
        formattedSpeed.valueSrc > cachedSpeedLimit + delta;

speedExceedWarning = formattedSpeed.valueSrc > 0 && cachedWarningSpeedLimit > 0 &&
        formattedSpeed.valueSrc > cachedWarningSpeedLimit && !speedExceed;

// NEW:
// Color state thresholds in the user's selected speed unit
// WARNING (yellow): 1+ over the limit
// EXCEED (red): 10+ over the limit
float colorExceedThreshold = 10f; // 10 in user's speed unit (mph or km/h)
float colorWarningThreshold = 1f;  // 1 in user's speed unit

speedExceed = formattedSpeed.valueSrc > 0 && cachedSpeedLimit > 0 &&
        formattedSpeed.valueSrc > cachedSpeedLimit + colorExceedThreshold;

speedExceedWarning = formattedSpeed.valueSrc > 0 && cachedWarningSpeedLimit > 0 &&
        formattedSpeed.valueSrc > cachedWarningSpeedLimit + colorWarningThreshold - 1f
        && !speedExceed;
```

Wait, let me re-examine. The `cachedWarningSpeedLimit` comes from `getSpeedLimitInfo(false)`, which calls `getSpeedLimitInfo(false)`. When `whenExceeded=false`, `delta = maxSpeed * -1` (negative), so `createSpeedAlarm` always returns the alarm (showAlways = delta < 0). So `cachedWarningSpeedLimit` is just the speed limit value itself (same as `cachedSpeedLimit` in most cases).

So the warning comparison is:
```java
speedExceedWarning = formattedSpeed.valueSrc > cachedWarningSpeedLimit && !speedExceed
```

This means: speed > limit AND not exceeding → any speed above the limit that isn't "exceeding" is "warning". The boundary between warning and exceed is `delta` (SPEED_LIMIT_EXCEED_KMH).

To implement Eric's desired behavior:
- WARNING (yellow): speed > limit + 0 (i.e., 1+ over, since speed display is rounded to whole numbers) AND speed ≤ limit + 10
- EXCEED (red): speed > limit + 10

The cleanest fix:

```java
// NEW (lines 407-412):
// FlockFree color thresholds (in user's displayed speed unit)
// WARNING (yellow): 1-10 over the limit
// EXCEED (red): 10+ over the limit
float colorExceedThreshold = 10f;
speedExceed = formattedSpeed.valueSrc > 0 && cachedSpeedLimit > 0 &&
        formattedSpeed.valueSrc > cachedSpeedLimit + colorExceedThreshold;

speedExceedWarning = formattedSpeed.valueSrc > 0 && cachedWarningSpeedLimit > 0 &&
        formattedSpeed.valueSrc > cachedWarningSpeedLimit && !speedExceed;
```

This changes only the EXCEED threshold. The WARNING already triggers at any speed above the limit (which, with integer display, means 1+ over). The key change is making the EXCEED threshold 10 (in the user's unit) instead of `SPEED_LIMIT_EXCEED_KMH` (which is 5 km/h).

**Important note about units:** `formattedSpeed.valueSrc` and `cachedSpeedLimit` are both in the user's selected speed unit (mph or km/h), so `colorExceedThreshold = 10f` is 10 mph when using mph, and 10 km/h when using km/h. Eric specified the thresholds in mph. If he uses mph as his speed setting (which he does, being in the US), this works correctly. If a user switches to km/h, 10 km/h over would be the red threshold, which is reasonable.

If Eric wants the thresholds to always be in mph regardless of the display unit, we'd need unit conversion, but that seems unlikely — he's in the US and uses mph.

#### Fix 3b: Keep SPEED_LIMIT_EXCEED_KMH for the speed limit sign display

The `SPEED_LIMIT_EXCEED_KMH` setting in `OsmandSettings.java` (line 1293) controls when the speed limit **sign** appears (via `WaypointHelper.createSpeedAlarm()`), not the color state. We should NOT change this setting's default, as it affects when the speed limit sign is displayed on screen.

The current default of 5 km/h for sign display is fine — it means the speed limit sign appears when you're 5 km/h (~3 mph) over the limit, giving early warning.

**No change needed to `OsmandSettings.java`.**

### Summary for Forge (Issue 3)

**Single file change:** `OsmAnd/src/net/osmand/plus/views/mapwidgets/widgets/SpeedometerWidget.java`

At lines 407–412, replace:

```java
float delta = app.getSettings().SPEED_LIMIT_EXCEED_KMH.get();
speedExceed = formattedSpeed.valueSrc > 0 && cachedSpeedLimit > 0 &&
        formattedSpeed.valueSrc > cachedSpeedLimit + delta;
```

with:

```java
// FlockFree: EXCEED (red) at 10+ over the limit, WARNING (yellow) at 1-10 over
float colorExceedThreshold = 10f;
speedExceed = formattedSpeed.valueSrc > 0 && cachedSpeedLimit > 0 &&
        formattedSpeed.valueSrc > cachedSpeedLimit + colorExceedThreshold;
```

The `speedExceedWarning` line (lines 411–412) stays **unchanged** — it already triggers at any speed above the limit that isn't EXCEED, which gives us the 1–10 range.

### Resulting Behavior

| Speed Over Limit | State | Color |
|---|---|---|
| 0 | SAFE | White/normal |
| 1–10 | WARNING | Yellow (#FFBF00) |
| 10+ | EXCEED | Red (#E71D34) |

### Risk
- **Low–Medium.** This decouples the color threshold from the `SPEED_LIMIT_EXCEED_KMH` setting. If Eric later changes that setting in the UI, the color threshold won't change (it's hardcoded to 10). If Eric wants the threshold to be configurable, we'd need to add a separate setting. For now, hardcoding is correct per his request.
- The `SPEED_LIMIT_EXCEED_KMH` setting still controls when the speed limit **sign** appears, which is a separate concern.
- The `formattedSpeed.valueSrc` is in the user's display unit, so 10 means 10 mph for Eric (US setting). This is the desired behavior.

---

## Implementation Checklist for Forge

### Issue 1: Rectangle before road name
- [ ] In `StreetNameWidget.java`, `setShieldImage()` method (~line 330): After `imageView.setImageBitmap(bitmap)`, add a lightweight pixel check (sample center + 2 corner pixels) to detect blank/transparent bitmaps. Return `false` if all sampled pixels are transparent or all identical.
- [ ] In `NextTurnBaseWidget.java`, `removeSymbol()` method (~line 307): Update to handle leading `»` without trailing space, and strip multiple leading `»` characters.

### Issue 2: Alarm z-order
- [ ] In `map_alarm_warning.xml` (line 4, root element): Add `android:elevation="4dp"`
- [ ] In `map_hud_bottom.xml` (lines 15–17): Swap order so `speedometer_widget` include comes first, `map_alarm_warning` comes second

### Issue 3: Speed color thresholds
- [ ] In `SpeedometerWidget.java` (line 407): Replace `float delta = app.getSettings().SPEED_LIMIT_EXCEED_KMH.get();` with `float colorExceedThreshold = 10f;`
- [ ] In `SpeedometerWidget.java` (line 409): Replace `formattedSpeed.valueSrc > cachedSpeedLimit + delta` with `formattedSpeed.valueSrc > cachedSpeedLimit + colorExceedThreshold`
- [ ] Lines 411–412 remain unchanged

---

## File Summary

| File | Issue(s) | Changes |
|---|---|---|
| `OsmAnd/src/net/osmand/plus/views/mapwidgets/widgets/StreetNameWidget.java` | 1 | Add bitmap content validation in `setShieldImage()` |
| `OsmAnd/src/net/osmand/plus/views/mapwidgets/widgets/NextTurnBaseWidget.java` | 1 | Fix `removeSymbol()` to handle `»` without space |
| `OsmAnd/res/layout/map_alarm_warning.xml` | 2 | Add `android:elevation="4dp"` to root |
| `OsmAnd/res/layout/map_hud_bottom.xml` | 2 | Reorder includes (speedometer first, alarm second) |
| `OsmAnd/src/net/osmand/plus/views/mapwidgets/widgets/SpeedometerWidget.java` | 3 | Change exceed threshold from `SPEED_LIMIT_EXCEED_KMH` to hardcoded `10f` |