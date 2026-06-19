package net.osmand;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.RelativeLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import net.osmand.plus.R;
import net.osmand.plus.base.BaseFullScreenFragment;
import net.osmand.plus.utils.AndroidUtils;

public class SecondSplashScreenFragment extends BaseFullScreenFragment {

	private static final int LOGO_ID = 1001;

	public static final String TAG = "SecondSplashScreenFragment";
	public static boolean SHOW = true;
	public static boolean VISIBLE;

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
		FragmentActivity activity = requireActivity();
		nightMode = settings.isSupportSystemTheme() && !settings.isLightSystemTheme();

		RelativeLayout view = new RelativeLayout(activity);
		view.setId(R.id.bottom_buttons_container);
		view.setClickable(true);
		view.setFocusable(true);
		view.setOnClickListener(null);

		view.setBackgroundColor(getColor(R.color.splash_screen_background_color));

		ImageView splash = new ImageView(activity);
		splash.setId(LOGO_ID);
		splash.setImageDrawable(AppCompatResources.getDrawable(activity, R.drawable.flockfree_startup_splash));
		splash.setScaleType(ImageView.ScaleType.CENTER_CROP);
		splash.setAdjustViewBounds(false);
		RelativeLayout.LayoutParams splashLayoutParams = new RelativeLayout.LayoutParams(
				RelativeLayout.LayoutParams.MATCH_PARENT,
				RelativeLayout.LayoutParams.MATCH_PARENT);
		splash.setLayoutParams(splashLayoutParams);
		view.addView(splash);

		return view;
	}

	@Override
	public void onResume() {
		super.onResume();
		getMapActivity().disableDrawer();
	}

	@Override
	public void onPause() {
		super.onPause();
		getMapActivity().enableDrawer();
	}

	@Override
	public int getStatusBarColorId() {
		return nightMode ? R.color.status_bar_main_dark : R.color.status_bar_transparent_light;
	}

	public static boolean showInstance(@NonNull FragmentManager manager) {
		if (AndroidUtils.isFragmentCanBeAdded(manager, TAG)) {
			manager.beginTransaction()
					.add(R.id.fragmentContainer, new SecondSplashScreenFragment(), TAG)
					.commitAllowingStateLoss();
			return true;
		}
		return false;
	}
}
