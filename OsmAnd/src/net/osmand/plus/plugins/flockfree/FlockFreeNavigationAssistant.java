package net.osmand.plus.plugins.flockfree;

import android.app.AlertDialog;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.data.LatLon;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.plugins.srtm.SRTMPlugin;
import net.osmand.plus.routing.RouteCalculationResult;
import net.osmand.plus.search.ShowQuickSearchMode;
import net.osmand.plus.settings.backend.OsmandSettings;
import net.osmand.plus.utils.OsmAndFormatter;
import net.osmand.util.Algorithms;

import java.util.List;

public final class FlockFreeNavigationAssistant {

	private FlockFreeNavigationAssistant() {
	}

	public static void showRoutePreview(@NonNull MapActivity mapActivity) {
		OsmandApplication app = getApp(mapActivity);
		RouteCalculationResult route = app.getRoutingHelper().getRoute();
		if (route == null || !route.isCalculated()) {
			app.showShortToastMessage(R.string.flockfree_route_preview_no_route);
			return;
		}

		FlockFreePlugin plugin = PluginsHelper.getEnabledPlugin(FlockFreePlugin.class);
		String eta = formatRouteEta(app, route);
		String distance = OsmAndFormatter.getFormattedDistance(route.getWholeDistance(), app);
		String baseSummary = app.getString(R.string.flockfree_route_preview_current_route,
				eta, distance, app.getString(R.string.flockfree_route_preview_recommended));

		String cameraSummary = app.getString(R.string.flockfree_route_preview_no_camera_data);
		String trafficSummary = app.getString(R.string.flockfree_traffic_last_check_none);
		if (plugin != null) {
			cameraSummary = formatCameraSummary(app, plugin, route);
			trafficSummary = plugin.getLastTrafficRouteCheckSummary();
		}

		CharSequence[] items = new CharSequence[] {
				app.getString(R.string.flockfree_route_preview_fastest) + "\n" + baseSummary,
				app.getString(R.string.flockfree_route_preview_flock_safe) + "\n" + cameraSummary,
				app.getString(R.string.flockfree_route_preview_traffic_aware) + "\n"
						+ app.getString(R.string.flockfree_route_preview_traffic_summary, trafficSummary)
		};

		new AlertDialog.Builder(mapActivity)
				.setTitle(R.string.flockfree_route_preview_title)
				.setItems(items, null)
				.setPositiveButton(R.string.shared_string_close, null)
				.show();
	}

	public static void openAddStopSearch(@NonNull MapActivity mapActivity, @NonNull String query) {
		mapActivity.getFragmentsHelper().showQuickSearch(ShowQuickSearchMode.INTERMEDIATE_SELECTION,
				false, query, getSearchLocation(mapActivity));
	}

	public static void showLayersSheet(@NonNull MapActivity mapActivity) {
		OsmandApplication app = getApp(mapActivity);
		FlockFreePlugin plugin = PluginsHelper.getEnabledPlugin(FlockFreePlugin.class);
		SRTMPlugin srtmPlugin = PluginsHelper.getEnabledPlugin(SRTMPlugin.class);
		OsmandSettings settings = app.getSettings();

		String[] labels = new String[] {
				app.getString(R.string.flockfree_layer_traffic),
				app.getString(R.string.flockfree_layer_cameras),
				app.getString(R.string.flockfree_layer_incidents),
				app.getString(R.string.flockfree_layer_3d_buildings),
				app.getString(R.string.flockfree_layer_terrain),
				app.getString(R.string.flockfree_layer_poi_icons),
				app.getString(R.string.flockfree_layer_poi_labels)
		};
		boolean[] checked = new boolean[] {
				plugin != null && plugin.TRAFFIC_ROUTING_ENABLED.get(),
				plugin != null && plugin.CAMERA_SHOW_LAYER.get(),
				plugin != null && plugin.INCIDENTS_SHOW_LAYER.get(),
				srtmPlugin != null && srtmPlugin.ENABLE_3D_MAP_OBJECTS.get(),
				srtmPlugin != null && srtmPlugin.TERRAIN.get(),
				!settings.getCustomRenderBooleanProperty("hideIcons").get(),
				!settings.getCustomRenderBooleanProperty("hidePOILabels").get()
		};

		new AlertDialog.Builder(mapActivity)
				.setTitle(R.string.flockfree_layers_title)
				.setMultiChoiceItems(labels, checked, (dialog, which, isChecked) -> {
					if (toggleLayer(mapActivity, app, plugin, srtmPlugin, which, isChecked)) {
						mapActivity.updateLayers();
						mapActivity.refreshMap();
					}
				})
				.setPositiveButton(R.string.shared_string_close, null)
				.show();
	}

	@NonNull
	public static String getArrivalSummary(@NonNull OsmandApplication app, @Nullable String destinationName) {
		if (!Algorithms.isEmpty(destinationName)) {
			return app.getString(R.string.flockfree_arrival_summary, destinationName);
		}
		return app.getString(R.string.flockfree_arrival_summary_generic);
	}

	private static boolean toggleLayer(@NonNull MapActivity mapActivity, @NonNull OsmandApplication app,
	                                   @Nullable FlockFreePlugin plugin, @Nullable SRTMPlugin srtmPlugin,
	                                   int which, boolean isChecked) {
		switch (which) {
			case 0:
				if (plugin != null) {
					plugin.TRAFFIC_ROUTING_ENABLED.set(isChecked);
					return true;
				}
				break;
			case 1:
				if (plugin != null) {
					plugin.CAMERA_SHOW_LAYER.set(isChecked);
					return true;
				}
				break;
			case 2:
				if (plugin != null) {
					plugin.INCIDENTS_SHOW_LAYER.set(isChecked);
					return true;
				}
				break;
			case 3:
				if (srtmPlugin != null) {
					srtmPlugin.ENABLE_3D_MAP_OBJECTS.set(isChecked);
					return true;
				}
				break;
			case 4:
				if (srtmPlugin != null) {
					srtmPlugin.setTerrainLayerEnabled(isChecked);
					return true;
				}
				break;
			case 5:
				app.getSettings().getCustomRenderBooleanProperty("hideIcons").set(!isChecked);
				return true;
			case 6:
				app.getSettings().getCustomRenderBooleanProperty("hidePOILabels").set(!isChecked);
				return true;
			default:
				break;
		}
		app.showShortToastMessage(R.string.flockfree_layer_unavailable);
		return false;
	}

	@NonNull
	private static String formatRouteEta(@NonNull OsmandApplication app, @NonNull RouteCalculationResult route) {
		Location location = app.getLocationProvider().getLastKnownLocation();
		int seconds = location != null ? route.getLeftTime(location) : 0;
		if (seconds <= 0) {
			seconds = Math.max(0, (int) route.getRoutingTime());
		}
		return OsmAndFormatter.getFormattedDuration(seconds, app);
	}

	@NonNull
	private static String formatCameraSummary(@NonNull OsmandApplication app, @NonNull FlockFreePlugin plugin,
	                                          @NonNull RouteCalculationResult route) {
		List<Location> locations = route.getImmutableAllLocations();
		if (!plugin.getCameraData().isDataLoaded() || locations == null || locations.isEmpty()) {
			return app.getString(R.string.flockfree_route_preview_no_camera_data);
		}
		int radius = plugin.CAMERA_AVOIDANCE_RADIUS.get();
		int cameraCount = plugin.getAvoidanceHelper()
				.findCamerasNearRouteLocations(locations, radius).size();
		return app.getString(R.string.flockfree_route_preview_camera_summary, cameraCount);
	}

	@Nullable
	private static LatLon getSearchLocation(@NonNull MapActivity mapActivity) {
		Location location = getApp(mapActivity).getLocationProvider().getLastKnownLocation();
		if (location != null) {
			return new LatLon(location.getLatitude(), location.getLongitude());
		}
		return new LatLon(mapActivity.getMapView().getLatitude(), mapActivity.getMapView().getLongitude());
	}

	@NonNull
	private static OsmandApplication getApp(@NonNull MapActivity mapActivity) {
		return (OsmandApplication) mapActivity.getApplication();
	}
}
