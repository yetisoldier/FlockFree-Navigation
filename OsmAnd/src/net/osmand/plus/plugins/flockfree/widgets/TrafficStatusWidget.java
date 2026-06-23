package net.osmand.plus.plugins.flockfree.widgets;

import static net.osmand.plus.views.mapwidgets.WidgetType.TRAFFIC_STATUS;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.plugins.flockfree.FlockFreePlugin;
import net.osmand.plus.plugins.flockfree.TrafficRoutingHelper;
import net.osmand.plus.views.layers.base.OsmandMapLayer.DrawSettings;
import net.osmand.plus.views.mapwidgets.WidgetsPanel;
import net.osmand.plus.views.mapwidgets.widgets.SimpleWidget;

import java.util.Date;

public class TrafficStatusWidget extends SimpleWidget {

	public TrafficStatusWidget(@NonNull MapActivity mapActivity, @Nullable String customId,
	                           @Nullable WidgetsPanel widgetsPanel) {
		super(mapActivity, TRAFFIC_STATUS, customId, widgetsPanel);
	}

	@Override
	protected void setupView(@NonNull View view) {
		super.setupView(view);
		setIcons(TRAFFIC_STATUS);
		setText(null, null);
	}

	@Override
	protected void updateSimpleWidgetInfo(@Nullable DrawSettings drawSettings) {
		OsmandApplication app = getMyApplication();
		FlockFreePlugin plugin = PluginsHelper.getEnabledPlugin(FlockFreePlugin.class);
		if (plugin == null) {
			setText(null, null);
			return;
		}
		TrafficRoutingHelper helper = plugin.getTrafficRoutingHelper();
		long refreshMs = helper.getLastTrafficColorRefreshMs();
		String primary = app.getString(R.string.flockfree_widget_traffic_status);
		if (app.getRoutingHelper().isRouteBeingCalculated()) {
			setText(primary, app.getString(R.string.flockfree_traffic_widget_routing));
			return;
		}
		String summary = helper.getTrafficColorLegendSummary();
		if (refreshMs > 0 && !helper.isTrafficColorRefreshRunning()) {
			String time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(new Date(refreshMs));
			setText(primary, time);
		} else {
			setText(primary, summary);
		}
	}
}
