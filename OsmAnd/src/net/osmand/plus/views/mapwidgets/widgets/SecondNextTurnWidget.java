package net.osmand.plus.views.mapwidgets.widgets;

import static net.osmand.plus.views.mapwidgets.WidgetType.SECOND_NEXT_TURN;

import android.graphics.Typeface;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.auto.TripUtils;
import net.osmand.plus.helpers.AndroidUiHelper;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.plugins.flockfree.FlockFreePlugin;
import net.osmand.plus.routing.CurrentStreetName;
import net.osmand.plus.routing.NextDirectionInfo;
import net.osmand.plus.routing.RoutingHelperUtils;
import net.osmand.plus.views.layers.base.OsmandMapLayer.DrawSettings;
import net.osmand.plus.views.layers.MapInfoLayer.TextState;
import net.osmand.plus.views.mapwidgets.OutlinedTextContainer;
import net.osmand.plus.views.mapwidgets.WidgetsPanel;
import net.osmand.router.TurnType;
import net.osmand.util.Algorithms;

public class SecondNextTurnWidget extends NextTurnBaseWidget {

	private final NextDirectionInfo nextDirectionInfo = new NextDirectionInfo();

	public SecondNextTurnWidget(@NonNull MapActivity mapActivity, @Nullable String customId, @Nullable WidgetsPanel panel) {
		super(mapActivity, customId, SECOND_NEXT_TURN, panel, true);
	}

	private boolean isFlockFreeActive() {
		return PluginsHelper.getEnabledPlugin(FlockFreePlugin.class) != null;
	}

	private boolean isFlockFreeLandscape() {
		return isFlockFreeActive() && !AndroidUiHelper.isOrientationPortrait(mapActivity);
	}

	@Override
	@LayoutRes
	protected int getContentLayoutId() {
		if (verticalWidget && isFlockFreeActive()) {
			return R.layout.flockfree_second_next_turn_chip;
		}
		return super.getContentLayoutId();
	}

	@Override
	protected boolean shouldHide() {
		// When FlockFreePlugin is active, we handle visibility ourselves based on
		// whether there is actually a second-next turn. The base class visibility
		// logic in updateInfo() already hides us when there is no turn data.
		if (isFlockFreeActive()) {
			return false;
		}
		return super.shouldHide();
	}

	@Override
	protected void updateVerticalWidgetColors(@NonNull TextState textState) {
		super.updateVerticalWidgetColors(textState);
		if (isFlockFreeActive()) {
			boolean landscape = isFlockFreeLandscape();
			LinearLayout bg = getView().findViewById(R.id.widget_bg);
			if (bg != null) {
				bg.setBackgroundResource(isNightMode()
					? R.drawable.bg_flockfree_second_next_turn_chip_night
					: R.drawable.bg_flockfree_second_next_turn_chip);
			}

			int primaryTextColor = ContextCompat.getColor(app, isNightMode()
					? R.color.google_maps_nav_bar_text_night
					: R.color.google_maps_text_primary);
			int secondaryTextColor = ContextCompat.getColor(app, isNightMode()
					? R.color.google_maps_nav_bar_text_secondary_night
					: R.color.google_maps_text_secondary);

			OutlinedTextContainer distanceView = getView().findViewById(R.id.distance_text);
			if (distanceView != null) {
				distanceView.setTextColor(primaryTextColor);
				distanceView.setTypeface(Typeface.DEFAULT_BOLD);
			}
			OutlinedTextContainer distanceSubView = getView().findViewById(R.id.distance_sub_text);
			if (distanceSubView != null) {
				distanceSubView.setTextColor(secondaryTextColor);
			}
			OutlinedTextContainer streetView = getView().findViewById(R.id.street_text);
			if (streetView != null) {
				streetView.setTextColor(secondaryTextColor);
			}
			TextView exitView = getView().findViewById(R.id.map_exit_ref);
			if (exitView != null) {
				exitView.setTextColor(ContextCompat.getColor(app, android.R.color.white));
			}
			ImageView arrowView = getView().findViewById(R.id.arrow_icon);
			if (arrowView != null) {
				arrowView.setColorFilter(primaryTextColor);
			}
		}
	}

	/**
	 * Do not delete to have pressed state. Uncomment to test rendering
	 */
	@NonNull
	protected OnClickListener getOnClickListener() {
		return new View.OnClickListener() {
//			int i = 0;
			@Override
			public void onClick(View v) {
//				final int l = TurnType.predefinedTypes.length;
//				final int exits = 5;
//				i++;
//				if (i % (l + exits) >= l ) {
//					nextTurnInfo.turnType = TurnType.valueOf("EXIT" + (i % (l + exits) - l + 1), true);
//					nextTurnInfo.exitOut = (i % (l + exits) - l + 1)+"";
//					float a = 180 - (i % (l + exits) - l + 1) * 50;
//					nextTurnInfo.turnType.setTurnAngle(a < 0 ? a + 360 : a);
//				} else {
//					nextTurnInfo.turnType = TurnType.valueOf(TurnType.predefinedTypes[i % (TurnType.predefinedTypes.length + exits)], true);
//					nextTurnInfo.exitOut = "";
//				}
//				nextTurnInfo.turnImminent = (nextTurnInfo.turnImminent + 1) % 3;
//				nextTurnInfo.nextTurnDirection = 580;
//				TurnPathHelper.calcTurnPath(nextTurnInfo.pathForTurn, nexsweepAngletTurnInfo.turnType,nextTurnInfo.pathTransform);
//				showMiniMap = true;
			}
		};
	}

	@Override
	public void updateNavigationInfo(@Nullable DrawSettings drawSettings) {
		boolean followingMode = routingHelper.isFollowingMode() || locationProvider.getLocationSimulation().isRouteAnimating();
		StreetNameWidget.StreetNameWidgetParams params = new StreetNameWidget.StreetNameWidgetParams(mapActivity, true);
		CurrentStreetName streetName = params.streetName;
		TurnType turnType = null;
		boolean deviatedFromRoute = false;
		int turnImminent = 0;
		int nextTurnDistance = 0;
		if (routingHelper.isRouteCalculated() && followingMode) {
			deviatedFromRoute = routingHelper.isDeviatedFromRoute();
			NextDirectionInfo info = routingHelper.getNextRouteDirectionInfo(nextDirectionInfo, true);
			if (!deviatedFromRoute) {
				if (info != null) {
					info = routingHelper.getNextRouteDirectionInfoAfter(info, nextDirectionInfo, true);
				}
			}
			if (info != null && info.distanceTo > 0 && info.directionInfo != null) {
				streetName = TripUtils.getStreetName(info);
				if (verticalWidget && Algorithms.isEmpty(streetName.text)) {
					streetName.text = info.directionInfo.getDescriptionRoutePart(app, true);
				}
				if (!Algorithms.isEmpty(streetName.text)) {
					streetName.text = RoutingHelperUtils.abbreviateStreetName(streetName.text);
				}
				turnType = info.directionInfo.getTurnType();
				turnImminent = info.imminent;
				nextTurnDistance = info.distanceTo;
			}
		}
		setStreetName(streetName);
		setTurnType(turnType);
		setTurnImminent(turnImminent, deviatedFromRoute);
		setTurnDistance(nextTurnDistance);
	}

}
