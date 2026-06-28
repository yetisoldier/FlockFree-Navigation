package net.osmand.plus.routepreparationmenu.cards;

import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.content.ContextCompat;

import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.utils.ColorUtilities;

public class FlockFreeRouteStatusCard extends MapBaseCard {

	private final String routeStatusSummary;

	public FlockFreeRouteStatusCard(@NonNull MapActivity mapActivity, @NonNull String routeStatusSummary) {
		super(mapActivity);
		this.routeStatusSummary = routeStatusSummary;
	}

	@Override
	public int getCardLayoutId() {
		return R.layout.flockfree_route_status_card;
	}

	@Override
	protected void updateContent() {
		AppCompatImageView icon = view.findViewById(R.id.flockfree_route_status_icon);
		icon.setImageResource(R.drawable.ic_action_privacy_and_security);
		icon.setColorFilter(ContextCompat.getColor(app, ColorUtilities.getActiveColorId(nightMode)));

		TextView title = view.findViewById(R.id.flockfree_route_status_title);
		title.setText(R.string.flockfree_route_status_card_title);
		title.setTextColor(ColorUtilities.getPrimaryTextColor(app, nightMode));

		TextView summary = view.findViewById(R.id.flockfree_route_status_summary);
		summary.setText(routeStatusSummary);
		summary.setTextColor(ColorUtilities.getSecondaryTextColor(app, nightMode));
	}
}
