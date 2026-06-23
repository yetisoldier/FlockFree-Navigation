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
import net.osmand.plus.utils.OsmAndFormatter;
import net.osmand.plus.views.layers.base.OsmandMapLayer.DrawSettings;
import net.osmand.plus.views.mapwidgets.WidgetsPanel;
import net.osmand.plus.views.mapwidgets.widgets.SimpleWidget;
import net.osmand.util.MapUtils;

import java.util.List;

public class CameraProximityWidget extends SimpleWidget {

	private static final int SEARCH_RADIUS_METERS = 1000;

	private int cachedCameraCount = -1;
	private double cachedNearestDistance = -1;
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
			setText(null, null);
			return;
		}
		CameraData cameraData = plugin.getCameraData();
		if (!cameraData.isDataLoaded()) {
			setText(null, null);
			return;
		}
		Location location = locationProvider.getLastKnownLocation();
		if (location == null) {
			setText(null, null);
			return;
		}
		double lat = location.getLatitude();
		double lon = location.getLongitude();
		if (isSameLocation(lat, lon) && cachedCameraCount >= 0) {
			return; // no movement, keep cached values
		}
		cachedLat = lat;
		cachedLon = lon;

		int searchRadiusMeters = Math.max(SEARCH_RADIUS_METERS, plugin.CAMERA_ALERT_DISTANCE.get());
		List<CameraData.CameraPoint> cameras = cameraData.getCamerasNear(lat, lon, searchRadiusMeters);
		if (cameras.isEmpty()) {
			cachedCameraCount = 0;
			cachedNearestDistance = -1;
			setText("0", app.getString(R.string.flockfree_widget_no_cameras));
		} else {
			cachedCameraCount = cameras.size();
			double nearest = Double.MAX_VALUE;
			for (CameraData.CameraPoint cam : cameras) {
				double dist = MapUtils.getDistance(cam.lat, cam.lon, lat, lon);
				if (dist < nearest) {
					nearest = dist;
				}
			}
			cachedNearestDistance = nearest;
			String countText = String.valueOf(cameras.size());
			String distanceText = OsmAndFormatter.getFormattedDistance((float) nearest, app);
			setText(countText, app.getString(R.string.flockfree_widget_nearest, distanceText));
		}
	}

	private boolean isSameLocation(double lat, double lon) {
		return Double.compare(cachedLat, lat) == 0 && Double.compare(cachedLon, lon) == 0;
	}
}
