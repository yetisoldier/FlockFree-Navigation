package net.osmand.plus.activities;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;

import net.osmand.plus.R;

public class FlockFreeSplashActivity extends Activity {

	private static final long SPLASH_DURATION_MS = 2000L;

	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Runnable openMapRunnable = this::openMap;
	private boolean openScheduled;

	@Override
	protected void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		FrameLayout root = new FrameLayout(this);
		root.setBackgroundColor(getColor(R.color.splash_screen_background_color));

		ImageView splash = new ImageView(this);
		splash.setImageDrawable(AppCompatResources.getDrawable(this, R.drawable.flockfree_startup_splash));
		splash.setScaleType(ImageView.ScaleType.CENTER_CROP);
		root.addView(splash, new FrameLayout.LayoutParams(
				ViewGroup.LayoutParams.MATCH_PARENT,
				ViewGroup.LayoutParams.MATCH_PARENT));

		setContentView(root);
		root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
			@Override
			public boolean onPreDraw() {
				root.getViewTreeObserver().removeOnPreDrawListener(this);
				scheduleOpenMap();
				return true;
			}
		});
	}

	private void scheduleOpenMap() {
		if (!openScheduled) {
			openScheduled = true;
			handler.postDelayed(openMapRunnable, SPLASH_DURATION_MS);
		}
	}

	private void openMap() {
		Intent intent = new Intent(this, MapActivity.class);
		intent.addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
		startActivity(intent);
		overridePendingTransition(0, 0);
		finish();
	}

	@Override
	protected void onDestroy() {
		handler.removeCallbacks(openMapRunnable);
		super.onDestroy();
	}
}
