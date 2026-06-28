package net.osmand.plus.views.mapwidgets.widgets;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.data.RotatedTileBox;
import net.osmand.plus.R;
import net.osmand.plus.helpers.AndroidUiHelper;
import net.osmand.plus.views.controls.ViewChangeProvider;
import net.osmand.plus.widgets.FrameLayoutEx;

public class RulerWidget extends FrameLayoutEx implements ViewChangeProvider {

	private View layout;
	private ImageView icon;
	private TextView text;
	private TextView textShadow;

	public RulerWidget(@NonNull Context context) {
		this(context, null);
	}

	public RulerWidget(@NonNull Context context, @Nullable AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public RulerWidget(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
		this(context, attrs, defStyleAttr, 0);
	}

	public RulerWidget(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
		super(context, attrs, defStyleAttr, defStyleRes);
	}

	@Override
	protected void onFinishInflate() {
		super.onFinishInflate();

		layout = findViewById(R.id.map_ruler_layout);
		icon = findViewById(R.id.map_ruler_image);
		text = findViewById(R.id.map_ruler_text);
		textShadow = findViewById(R.id.map_ruler_text_shadow);
	}

	public void updateTextSize(boolean isNight, int textColor, int textShadowColor, int shadowRadius) {
		TextInfoWidget.updateTextColor(text, textShadow, textColor, textShadowColor, false, shadowRadius);
		icon.setBackgroundResource(isNight ? R.drawable.ruler_night : R.drawable.ruler);
	}

	public boolean updateInfo(@NonNull RotatedTileBox tileBox) {
		AndroidUiHelper.updateVisibility(layout, false);
		return true;
	}
}
