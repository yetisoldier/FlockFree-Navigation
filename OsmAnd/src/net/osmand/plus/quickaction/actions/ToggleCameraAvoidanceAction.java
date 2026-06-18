package net.osmand.plus.quickaction.actions;

import static net.osmand.plus.quickaction.QuickActionIds.TOGGLE_CAMERA_AVOIDANCE_ACTION_ID;

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

public class ToggleCameraAvoidanceAction extends QuickAction {

    public static final QuickActionType TYPE = new QuickActionType(TOGGLE_CAMERA_AVOIDANCE_ACTION_ID,
            "flockfree.avoidance.toggle", ToggleCameraAvoidanceAction.class)
            .nameActionRes(R.string.quick_action_verb_show_hide)
            .nameRes(R.string.flockfree_avoidance_enabled)
            .iconRes(R.drawable.ic_action_info_dark).nonEditable()
            .category(QuickActionType.NAVIGATION);

    public ToggleCameraAvoidanceAction() {
        super(TYPE);
    }

    public ToggleCameraAvoidanceAction(QuickAction quickAction) {
        super(quickAction);
    }

    @Override
    public void execute(@NonNull MapActivity mapActivity, @Nullable Bundle params) {
        FlockFreePlugin plugin = PluginsHelper.getPlugin(FlockFreePlugin.class);
        if (plugin != null) {
            plugin.CAMERA_AVOIDANCE_ENABLED.set(!plugin.CAMERA_AVOIDANCE_ENABLED.get());
        }
    }

    @Override
    public void drawUI(@NonNull ViewGroup parent, @NonNull MapActivity mapActivity, boolean nightMode) {
        View view = UiUtilities.inflate(parent.getContext(), nightMode, R.layout.quick_action_with_text, parent, false);
        ((TextView) view.findViewById(R.id.text)).setText(
                R.string.flockfree_quick_action_toggle_avoidance_desc);
        parent.addView(view);
    }

    @Override
    public String getActionText(@NonNull OsmandApplication app) {
        String nameRes = app.getString(getNameRes());
        String actionName = isActionWithSlash(app) ? app.getString(R.string.shared_string_disable) : app.getString(R.string.shared_string_enable);
        return app.getString(R.string.ltr_or_rtl_combine_via_dash, actionName, nameRes);
    }

    @Override
    public boolean isActionWithSlash(@NonNull OsmandApplication app) {
        FlockFreePlugin plugin = PluginsHelper.getPlugin(FlockFreePlugin.class);
        return plugin != null && plugin.CAMERA_AVOIDANCE_ENABLED.get();
    }
}