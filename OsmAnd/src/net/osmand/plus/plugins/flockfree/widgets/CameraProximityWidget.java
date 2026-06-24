package net.osmand.plus.plugins.flockfree.widgets;

import static net.osmand.plus.views.mapwidgets.WidgetType.CAMERA_PROXIMITY;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.plugins.flockfree.CameraData;
import net.osmand.plus.plugins.flockfree.FlockFreePlugin;
import net.osmand.plus.routing.RouteCalculationResult;
import net.osmand.plus.views.layers.base.OsmandMapLayer.DrawSettings;
import net.osmand.plus.views.mapwidgets.WidgetsPanel;
import net.osmand.plus.views.mapwidgets.widgets.SimpleWidget;
import net.osmand.util.MapUtils;

import java.util.List;

public class CameraProximityWidget extends SimpleWidget {

	private static final int LOCATION_RECALC_THRESHOLD_METERS = 50;

	private RouteCalculationResult cachedRoute;
	private int cachedRouteSize = -1;
	private int cachedRadius = -1;
	private int cachedStartIndex = -1;
	private int cachedCameraCount = -1;
	private double cachedLat = Double.NaN;
	private double cachedLon = Double.NaN;

	public CameraProximityWidget(@NonNull MapActivity mapActivity, @Nullable String customId,
	                              @Nullable WidgetsPanel widgetsPanel) {
		super(mapActivity, CAMERA_PROXIMITY, customId, widgetsPanel);
	}

	@Override
	protected void setupView(@NonNull View view) {
		super.setupView(view);
		setIcons(CAMERA_PROXIMITY);
		setText(null, null);
	}

	@Override
	protected void updateSimpleWidgetInfo(@Nullable DrawSettings drawSettings) {
		OsmandApplication app = getMyApplication();
		FlockFreePlugin plugin = PluginsHelper.getPlugin(FlockFreePlugin.class);
		if (plugin == null || !plugin.CAMERA_SHOW_LAYER.get()) {
			hideWidget();
			return;
		}
		CameraData cameraData = plugin.getCameraData();
		if (!cameraData.isDataLoaded()) {
			hideWidget();
			return;
		}
		if (!app.getRoutingHelper().isFollowingMode() || app.getRoutingHelper().isRouteBeingCalculated()) {
			hideWidget();
			return;
		}
		RouteCalculationResult route = app.getRoutingHelper().getRoute();
		if (route == null || !route.isCalculated()) {
			hideWidget();
			return;
		}
		List<Location> routeLocations = route.getImmutableAllLocations();
		if (routeLocations == null || routeLocations.isEmpty()) {
			hideWidget();
			return;
		}
		Location location = locationProvider.getLastKnownLocation();
		if (location == null) {
			hideWidget();
			return;
		}
		double lat = location.getLatitude();
		double lon = location.getLongitude();

		int startIndex = findNearestRouteIndex(location, routeLocations);
		int radiusMeters = plugin.CAMERA_AVOIDANCE_RADIUS.get();
		if (canUseCachedValue(route, routeLocations.size(), radiusMeters, startIndex, lat, lon)) {
			showRouteCameraCount(app, cachedCameraCount);
			return;
		}

		cachedLat = lat;
		cachedLon = lon;
		cachedRoute = route;
		cachedRouteSize = routeLocations.size();
		cachedRadius = radiusMeters;
		cachedStartIndex = startIndex;

		List<Location> remainingRoute = routeLocations.subList(startIndex, routeLocations.size());
		List<CameraData.CameraPoint> cameras =
				plugin.getAvoidanceHelper().findCamerasNearRouteLocations(remainingRoute, radiusMeters);
		cachedCameraCount = cameras.size();
		showRouteCameraCount(app, cachedCameraCount);
	}

	private boolean canUseCachedValue(@NonNull RouteCalculationResult route, int routeSize, int radiusMeters,
	                                  int startIndex, double lat, double lon) {
		return cachedCameraCount >= 0
				&& cachedRoute == route
				&& cachedRouteSize == routeSize
				&& cachedRadius == radiusMeters
				&& cachedStartIndex == startIndex
				&& isSameLocation(lat, lon);
	}

	private int findNearestRouteIndex(@NonNull Location location, @NonNull List<Location> routeLocations) {
		int nearestIndex = 0;
		double nearestDistance = Double.MAX_VALUE;
		double lat = location.getLatitude();
		double lon = location.getLongitude();
		for (int i = 0; i < routeLocations.size(); i++) {
			Location routeLocation = routeLocations.get(i);
			double distance = MapUtils.getDistance(lat, lon,
					routeLocation.getLatitude(), routeLocation.getLongitude());
			if (distance < nearestDistance) {
				nearestDistance = distance;
				nearestIndex = i;
			}
		}
		return nearestIndex;
	}

	private void showRouteCameraCount(@NonNull OsmandApplication app, int cameraCount) {
		setText(String.valueOf(cameraCount), app.getString(cameraCount == 0
				? R.string.flockfree_widget_no_route_cameras_left
				: R.string.flockfree_widget_route_cameras_left));
		updateVisibility(true);
	}

	private void hideWidget() {
		cachedCameraCount = -1;
		setText(null, null);
		updateVisibility(false);
	}

	private boolean isSameLocation(double lat, double lon) {
		return !Double.isNaN(cachedLat)
				&& !Double.isNaN(cachedLon)
				&& MapUtils.getDistance(cachedLat, cachedLon, lat, lon) <= LOCATION_RECALC_THRESHOLD_METERS;
	}
}
