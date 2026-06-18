package net.osmand.plus.plugins.flockfree.cyd;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

public final class CydDetectionCandidate {

	@NonNull
	public final String rawJson;
	@Nullable
	public final String detectionMethod;
	@Nullable
	public final String protocol;
	@Nullable
	public final String macAddress;
	@Nullable
	public final String oui;
	@Nullable
	public final String deviceName;
	@Nullable
	public final String ssid;
	@Nullable
	public final Integer rssi;
	@Nullable
	public final Integer channel;
	@Nullable
	public final Integer frequency;
	@Nullable
	public final Double latitude;
	@Nullable
	public final Double longitude;
	@Nullable
	public final Float accuracyMeters;
	@Nullable
	public final Long gpsAgeMs;
	@Nullable
	public final String gpsSource;
	public final long receivedAtMs;

	private CydDetectionCandidate(@NonNull JSONObject json, long receivedAtMs) {
		rawJson = json.toString();
		detectionMethod = optNonEmptyString(json, "detection_method");
		protocol = optNonEmptyString(json, "protocol");
		macAddress = optNonEmptyString(json, "mac_address");
		oui = optNonEmptyString(json, "oui");
		deviceName = optNonEmptyString(json, "device_name");
		ssid = optNonEmptyString(json, "ssid");
		rssi = optInteger(json, "rssi");
		channel = optInteger(json, "channel");
		frequency = optInteger(json, "frequency");
		this.receivedAtMs = receivedAtMs;

		JSONObject gps = json.optJSONObject("gps");
		if (gps != null) {
			latitude = optDouble(gps, "latitude");
			longitude = optDouble(gps, "longitude");
			Float accuracy = optFloat(gps, "accuracy");
			accuracyMeters = accuracy != null ? accuracy : optFloat(gps, "accuracy_m");
			gpsAgeMs = optLong(gps, "age_ms");
			gpsSource = optNonEmptyString(gps, "source");
		} else {
			latitude = optDouble(json, "latitude");
			longitude = optDouble(json, "longitude");
			Float accuracy = optFloat(json, "accuracy");
			accuracyMeters = accuracy != null ? accuracy : optFloat(json, "accuracy_m");
			gpsAgeMs = optLong(json, "gps_age_ms");
			gpsSource = optNonEmptyString(json, "gps_source");
		}
	}

	@NonNull
	static CydDetectionCandidate fromJson(@NonNull JSONObject json) {
		return new CydDetectionCandidate(json, System.currentTimeMillis());
	}

	public boolean hasGpsFix() {
		return latitude != null && longitude != null;
	}

	private static boolean hasValue(@NonNull JSONObject json, @NonNull String key) {
		return json.has(key) && !json.isNull(key);
	}

	@Nullable
	private static String optNonEmptyString(@NonNull JSONObject json, @NonNull String key) {
		if (!hasValue(json, key)) {
			return null;
		}
		String value = json.optString(key, null);
		return value == null || value.length() == 0 ? null : value;
	}

	@Nullable
	private static Integer optInteger(@NonNull JSONObject json, @NonNull String key) {
		return hasValue(json, key) ? json.optInt(key) : null;
	}

	@Nullable
	private static Long optLong(@NonNull JSONObject json, @NonNull String key) {
		return hasValue(json, key) ? json.optLong(key) : null;
	}

	@Nullable
	private static Double optDouble(@NonNull JSONObject json, @NonNull String key) {
		return hasValue(json, key) ? json.optDouble(key) : null;
	}

	@Nullable
	private static Float optFloat(@NonNull JSONObject json, @NonNull String key) {
		return hasValue(json, key) ? (float) json.optDouble(key) : null;
	}
}
