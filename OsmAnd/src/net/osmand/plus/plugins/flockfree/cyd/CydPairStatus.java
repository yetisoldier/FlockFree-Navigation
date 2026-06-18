package net.osmand.plus.plugins.flockfree.cyd;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CydPairStatus {

	@NonNull
	public final String rawJson;
	@Nullable
	public final String device;
	public final int protocolVersion;
	@NonNull
	public final List<String> features;
	public final boolean gps;
	public final boolean sd;
	public final int detections;
	public final int csvRows;
	public final int rxFrames;
	public final int rxManagementFrames;
	public final int rxDataFrames;
	public final int queueDrops;
	public final int channel;
	@Nullable
	public final String scanMode;

	private CydPairStatus(@NonNull JSONObject json) {
		rawJson = json.toString();
		device = optNonEmptyString(json, "device");
		protocolVersion = json.optInt("protocol_version", 0);
		features = Collections.unmodifiableList(parseFeatures(json.optJSONArray("features")));
		gps = json.optBoolean("gps", false);
		sd = json.optBoolean("sd", false);
		detections = json.optInt("detections", 0);
		csvRows = json.optInt("csv_rows", 0);
		rxFrames = json.optInt("rx_frames", 0);
		rxManagementFrames = json.optInt("rx_mgmt", 0);
		rxDataFrames = json.optInt("rx_data", 0);
		queueDrops = json.optInt("queue_drops", 0);
		channel = json.optInt("channel", 0);
		scanMode = optNonEmptyString(json, "scan_mode");
	}

	@NonNull
	static CydPairStatus fromJson(@NonNull JSONObject json) {
		return new CydPairStatus(json);
	}

	private static List<String> parseFeatures(@Nullable JSONArray array) {
		if (array == null) {
			return Collections.emptyList();
		}
		List<String> result = new ArrayList<>(array.length());
		for (int i = 0; i < array.length(); i++) {
			String value = array.optString(i, null);
			if (value != null && value.length() > 0) {
				result.add(value);
			}
		}
		return result;
	}

	@Nullable
	private static String optNonEmptyString(@NonNull JSONObject json, @NonNull String key) {
		String value = json.optString(key, null);
		return value == null || value.length() == 0 ? null : value;
	}
}
