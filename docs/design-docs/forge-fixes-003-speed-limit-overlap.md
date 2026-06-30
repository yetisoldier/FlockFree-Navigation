# Forge Fix 003: Speed Limit Widget Overlapping Turn Instructions in Landscape

## Problem

During navigation in landscape mode, the `MaxSpeedWidget` (showing "55 mph") in the right side widget panel overlaps the turn-by-turn navigation instructions in the top widget panel. The speed limit sign should appear **below** the FlockFree camera enroute chip at the top-right, not at the same vertical level as the turn instructions.

## Root Cause

Two issues contribute:

1. **Right panel starts at the very top** — `map_right_widgets_panel` in `map_hud_top.xml` has `layout_marginTop="@dimen/content_padding_small_half"` (6dp). The `MaxSpeedWidget` is the first widget in the right panel, so it renders at ~6dp from the top of the screen — the same vertical level as the top widgets panel containing turn instructions.

2. **Top panel right margin race condition** — `MapHudLayout.updateHorizontalMargins()` calculates the top panel's `rightMargin` using `rightWidgetsPanel.getWidth()`. If the right panel hasn't been measured yet (`getWidth() == 0`), the top panel gets full width minus `targetWidth`, which may not leave enough space for the right panel.

## Fix

### Change 1: Push right widget panel below the camera chip in landscape (FlockFree active)

**File:** `OsmAnd/src/net/osmand/plus/views/controls/MapHudLayout.java`

Add a method to calculate the right panel top margin when FlockFree is active in landscape, and apply it during `updateVerticalPanels()` / `updateHorizontalMargins()`.

The FlockFree camera chip (`flockfree_camera_widget`) is at 88dp from top with ~40dp height, so the right panel should start at ~96dp from top when FlockFree is active in landscape.

**In `updateHorizontalMargins()`, after setting left/right margins for the top panel, also set the top margin of `rightWidgetsPanel`:**

```java
// After the existing if/else if block that sets panel leftMargin/rightMargin:
if (shouldUseFlockFreeLandscapeTopPanel(panel)) {
    int rightPanelTopMargin = (int) (dpToPx * FLOCKFREE_RIGHT_PANEL_TOP_MARGIN_DP);
    if (rightWidgetsPanel.getLayoutParams() instanceof MarginLayoutParams rightParams) {
        if (rightParams.topMargin != rightPanelTopMargin) {
            rightParams.topMargin = rightPanelTopMargin;
            rightWidgetsPanel.setLayoutParams(rightParams);
        }
    }
}
```

**Add constant near the existing FlockFree constants:**
```java
private static final int FLOCKFREE_RIGHT_PANEL_TOP_MARGIN_DP = 96;
```

This pushes the right side panel (containing `MaxSpeedWidget`, `CurrentSpeedWidget`, `CameraProximityWidget`) down to 96dp from top, clearing the camera enroute chip (at 88dp + ~40dp height) and the FlockFree search bar (48dp at top-left).

### Change 2: Guard against zero-width right panel in margin calculation

**File:** `OsmAnd/src/net/osmand/plus/views/controls/MapHudLayout.java`  
**Method:** `updateHorizontalMargins()`

In the FlockFree landscape branch, when `rightWidth == 0` but the panel is `VISIBLE`, fall back to a minimum width estimate so the top panel doesn't overlap the right panel during measurement races:

```java
int rightWidth = rightWidgetsPanel.getVisibility() == VISIBLE ? rightWidgetsPanel.getWidth() : 0;
// Guard against unmeasured panel during layout passes
if (rightWidth == 0 && rightWidgetsPanel.getVisibility() == VISIBLE) {
    rightWidth = (int) (dpToPx * 72); // estimate: typical widget width
}
```

Apply the same guard in the `shouldCenterVerticalPanels()` branch.

## Files to Modify

1. **`OsmAnd/src/net/osmand/plus/views/controls/MapHudLayout.java`**
   - Add `FLOCKFREE_RIGHT_PANEL_TOP_MARGIN_DP` constant (line ~59)
   - Add right panel top margin logic in `updateHorizontalMargins()` (after line ~655)
   - Add zero-width guard for `rightWidth` (line ~650 and ~659)

## Validation Checklist

### Build
- [ ] `./gradlew assembleFullFossFatDebug` compiles cleanly
- [ ] No new lint errors

### Functional (Landscape, FlockFree active)
- [ ] Speed limit widget ("55 mph") appears below the camera enroute chip at top-right
- [ ] Speed limit widget does NOT overlap turn instructions in the top panel
- [ ] Current speed widget also below camera chip, stacked below speed limit
- [ ] Camera proximity widget below current speed widget
- [ ] Turn instructions (next turn, distance) fully visible in top panel
- [ ] Lanes widget (center-top) not overlapping right panel widgets

### Functional (Portrait, FlockFree active)
- [ ] No regression — right panel still at top in portrait
- [ ] Camera chip at 88dp top-right unchanged
- [ ] All widgets visible and correctly positioned

### Functional (FlockFree disabled)
- [ ] No regression — right panel at 6dp from top (stock behavior)
- [ ] Top panel margins revert to stock OsmAnd behavior

### Functional (Landscape, FlockFree disabled)
- [ ] No regression — stock OsmAnd landscape layout
- [ ] Right panel at 6dp from top