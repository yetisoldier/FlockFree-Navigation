package net.osmand.plus.views;

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import net.osmand.core.android.MapRendererView;
import net.osmand.plus.settings.enums.ThemeUsageContext;
import net.osmand.plus.views.layers.base.OsmandMapLayer.DrawSettings;

import java.util.concurrent.atomic.AtomicBoolean;

public class OsmAndMapLayersView extends View {

	private OsmandMapTileView mapView;
	private final AtomicBoolean overlayOnlyDraw = new AtomicBoolean();

	public OsmAndMapLayersView(Context context, AttributeSet attrs) {
		super(context, attrs);
	}

	public OsmAndMapLayersView(Context context) {
		super(context);
	}

	public void setMapView(@Nullable OsmandMapTileView mapView) {
		if (this.mapView != null && mapView == null) {
			this.mapView.setView(null);
		}
		this.mapView = mapView;
		if (mapView != null) {
			mapView.setView(this);
		}
	}

	@Override
	public boolean onTrackballEvent(MotionEvent event) {
		if (mapView == null) {
			return super.onTrackballEvent(event);
		}
		Boolean r = mapView.onTrackballEvent(event);
		if (r == null) {
			return super.onTrackballEvent(event);
		}
		return r;
	}
	
	@Override
	public boolean onKeyDown(int keyCode, KeyEvent event) {
		if (mapView == null) {
			return super.onKeyDown(keyCode, event);
		}
		Boolean r = mapView.onKeyDown(keyCode, event);
		if (r == null) {
			return super.onKeyDown(keyCode, event);
		}
		return r;
	}
	
	@Override
	public boolean onGenericMotionEvent(MotionEvent event) {
		if (mapView == null) {
			return super.onGenericMotionEvent(event);
		}
		return mapView.onGenericMotionEvent(event);
	}

	@Override
	public boolean onTouchEvent(MotionEvent event) {
		if (mapView == null) {
			return super.onTouchEvent(event);
		}
		return mapView.onTouchEvent(event);
	}
	
	@Override
	protected void onDraw(Canvas canvas) {
		boolean overlayOnly = overlayOnlyDraw.getAndSet(false);
		if (mapView == null) {
			return;
		}
		boolean nightMode = mapView.getApplication().getDaynightHelper().isNightMode(ThemeUsageContext.MAP);
		DrawSettings drawSettings = new DrawSettings(nightMode, false);
		mapView.drawOverMap(canvas, mapView.getRotatedTileBox(), drawSettings);

		MapRendererView mapRenderer = mapView.getMapRenderer();
		if (mapRenderer != null && !overlayOnly) {
			mapRenderer.requestRender();
		}
	}

	/**
	 * Repaint Android canvas layers to match a native renderer frame without asking
	 * the native renderer for another frame. This avoids a render feedback loop while
	 * keeping canvas markers anchored during native map animations.
	 */
	public void requestOverlayRedraw() {
		overlayOnlyDraw.set(true);
		postInvalidateOnAnimation();
	}
}
