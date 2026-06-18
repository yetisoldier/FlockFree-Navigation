package net.osmand.plus.quickaction.actions;

import static net.osmand.plus.quickaction.QuickActionIds.ADD_CAMERA_ACTION_ID;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.plugins.flockfree.FlockFreePlugin;
import net.osmand.plus.quickaction.QuickAction;
import net.osmand.plus.quickaction.QuickActionType;
import net.osmand.plus.utils.UiUtilities;

public class AddCameraAction extends QuickAction {

    public static final QuickActionType TYPE = new QuickActionType(ADD_CAMERA_ACTION_ID,
            "flockfree.add_camera", AddCameraAction.class)
            .nameRes(R.string.flockfree_add_camera)
            .iconRes(R.drawable.ic_action_plus_dark).nonEditable()
            .category(QuickActionType.MAP_INTERACTIONS);

    public AddCameraAction() {
        super(TYPE);
    }

    public AddCameraAction(QuickAction quickAction) {
        super(quickAction);
    }

    @Override
    public void execute(@NonNull MapActivity mapActivity, @Nullable Bundle params) {
        FlockFreePlugin plugin = PluginsHelper.getPlugin(FlockFreePlugin.class);
        if (plugin != null && mapActivity.getMapView() != null) {
            double lat = mapActivity.getMapView().getLatitude();
            double lon = mapActivity.getMapView().getLongitude();
            plugin.getCameraReporter().showAddCameraDialog(mapActivity, lat, lon);
        }
    }

    @Override
    public void drawUI(@NonNull ViewGroup parent, @NonNull MapActivity mapActivity, boolean nightMode) {
        View view = UiUtilities.inflate(parent.getContext(), nightMode, R.layout.quick_action_with_text, parent, false);
        ((TextView) view.findViewById(R.id.text)).setText(
                R.string.flockfree_quick_action_add_camera_desc);
        parent.addView(view);
    }
}