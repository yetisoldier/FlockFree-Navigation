package net.osmand.plus.plugins.flockfree.widgets;

import android.os.Bundle;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.fragment.app.FragmentManager;

import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.base.MenuBottomSheetDialogFragment;
import net.osmand.plus.base.bottomsheetmenu.BaseBottomSheetItem;
import net.osmand.plus.base.bottomsheetmenu.SimpleBottomSheetItem;
import net.osmand.plus.base.bottomsheetmenu.simpleitems.TitleItem;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.plugins.flockfree.FlockFreePlugin;
import net.osmand.plus.plugins.osmedit.OsmEditingPlugin;
import net.osmand.plus.utils.AndroidUtils;

import java.util.Locale;

/**
 * Quick report sheet shown from the navigation FAB.
 */
public class ReportBottomSheet extends MenuBottomSheetDialogFragment {

	public static final String TAG = ReportBottomSheet.class.getSimpleName();

	@Override
	public void createMenuItems(Bundle savedInstanceState) {
		items.add(new TitleItem(getString(R.string.flockfree_report_sheet_title)));
		items.add(createReportItem(R.drawable.ic_action_device_camera,
				R.string.flockfree_report_camera, this::reportCamera));
		items.add(createReportItem(R.drawable.ic_action_placard_hazard,
				R.string.flockfree_report_hazard, this::reportHazard));
		items.add(createReportItem(R.drawable.ic_action_car,
				R.string.flockfree_report_traffic, this::reportTraffic));
	}

	@NonNull
	private BaseBottomSheetItem createReportItem(@DrawableRes int iconRes,
	                                             @StringRes int titleRes,
	                                             @NonNull Runnable action) {
		return new SimpleBottomSheetItem.Builder()
				.setIcon(getContentIcon(iconRes))
				.setTitle(getString(titleRes))
				.setLayoutId(R.layout.bottom_sheet_item_simple)
				.setOnClickListener(v -> {
					dismiss();
					action.run();
				})
				.create();
	}

	private void reportCamera() {
		MapActivity mapActivity = getMapActivity();
		FlockFreePlugin plugin = PluginsHelper.getEnabledPlugin(FlockFreePlugin.class);
		if (mapActivity != null && plugin != null) {
			plugin.getCameraReporter().showAddCameraDialogAtMapCenter(mapActivity);
		}
	}

	private void reportHazard() {
		openOsmNote("FlockFree navigation report: road hazard\n"
				+ "hazard=road\n"
				+ "traffic:incident=hazard\n"
				+ "source=FlockFree Navigation");
	}

	private void reportTraffic() {
		openOsmNote("FlockFree navigation report: traffic incident\n"
				+ "traffic=congestion\n"
				+ "traffic:incident=yes\n"
				+ "source=FlockFree Navigation");
	}

	private void openOsmNote(@NonNull String message) {
		MapActivity mapActivity = getMapActivity();
		if (mapActivity == null || mapActivity.getMapView() == null) {
			app.showShortToastMessage(R.string.flockfree_report_last_draft_map_unavailable);
			return;
		}
		double lat = mapActivity.getMapView().getLatitude();
		double lon = mapActivity.getMapView().getLongitude();
		if (!isValidCoordinate(lat, lon)) {
			app.showShortToastMessage(R.string.flockfree_report_last_draft_map_unavailable);
			return;
		}
		OsmEditingPlugin editingPlugin = PluginsHelper.getPlugin(OsmEditingPlugin.class);
		if (editingPlugin == null) {
			app.showShortToastMessage(R.string.flockfree_osm_editor_unavailable_title);
			return;
		}
		String note = String.format(Locale.US, "%s\nlat=%.5f\nlon=%.5f", message, lat, lon);
		editingPlugin.openOsmNote(mapActivity, lat, lon, note, false);
	}

	private boolean isValidCoordinate(double lat, double lon) {
		return !Double.isNaN(lat) && !Double.isInfinite(lat)
				&& !Double.isNaN(lon) && !Double.isInfinite(lon)
				&& lat >= -90d && lat <= 90d
				&& lon >= -180d && lon <= 180d;
	}

	public static void showInstance(@NonNull MapActivity activity) {
		FragmentManager fragmentManager = activity.getSupportFragmentManager();
		if (AndroidUtils.isFragmentCanBeAdded(fragmentManager, TAG)) {
			ReportBottomSheet fragment = new ReportBottomSheet();
			fragment.setUsedOnMap(true);
			fragment.show(fragmentManager, TAG);
		}
	}
}
