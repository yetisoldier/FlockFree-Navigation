package net.osmand.plus.plugins.flockfree;

import static net.osmand.aidlapi.OsmAndCustomizationConstants.QUICK_ACTION_HUD_ID;
import static net.osmand.shared.grid.ButtonPositionSize.POS_BOTTOM;
import static net.osmand.shared.grid.ButtonPositionSize.POS_RIGHT;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.settings.backend.preferences.CommonPreference;
import net.osmand.plus.views.mapwidgets.configure.buttons.MapButtonState;
import net.osmand.shared.grid.ButtonPositionSize;

public class FlockFreeReportButtonState extends MapButtonState {

	private final CommonPreference<Boolean> visibilityPref;

	public FlockFreeReportButtonState(@NonNull OsmandApplication app) {
		super(app, "flockfree_report_button");
		this.visibilityPref = addPreference(settings.registerBooleanPreference(id + "_state", true)).makeProfile();
	}

	@NonNull
	@Override
	public String getName() {
		return app.getString(R.string.flockfree_report_sheet_title);
	}

	@NonNull
	@Override
	public String getDescription() {
		return app.getString(R.string.flockfree_report_sheet_title);
	}

	@Override
	public boolean isEnabled() {
		return visibilityPref.get();
	}

	@NonNull
	@Override
	public CommonPreference<Boolean> getVisibilityPref() {
		return visibilityPref;
	}

	@Override
	public int getDefaultLayoutId() {
		return R.layout.flockfree_report_map_button;
	}

	@NonNull
	@Override
	public String getDefaultIconName(@Nullable Boolean nightMode) {
		return "ic_action_device_camera";
	}

	@Override
	protected void updatePosition(@NonNull ButtonPositionSize position) {
		super.updatePosition(position);
		if (!portrait) {
			position.setMoveHorizontal();
		}
	}

	@NonNull
	@Override
	protected ButtonPositionSize setupButtonPosition(@NonNull ButtonPositionSize position) {
		return setupButtonPosition(position, POS_RIGHT, POS_BOTTOM, false, true);
	}
}