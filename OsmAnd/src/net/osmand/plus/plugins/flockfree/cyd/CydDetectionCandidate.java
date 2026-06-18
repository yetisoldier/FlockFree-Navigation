package net.osmand.plus.plugins.flockfree.cyd;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.util.Locale;

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
		detectionMethod = CydJsonUtils.optNonEmptyString(json, "detection_method");
		protocol = CydJsonUtils.optNonEmptyString(json, "protocol");
		macAddress = CydJsonUtils.optNonEmptyString(json, "mac_address");
		oui = CydJsonUtils.optNonEmptyString(json, "oui");
		deviceName = CydJsonUtils.optNonEmptyString(json, "device_name");
		ssid = CydJsonUtils.optNonEmptyString(json, "ssid");
		rssi = CydJsonUtils.optInteger(json, "rssi");
		channel = CydJsonUtils.optInteger(json, "channel");
		frequency = CydJsonUtils.optInteger(json, "frequency");
		this.receivedAtMs = receivedAtMs;

		JSONObject gps = json.optJSONObject("gps");
		if (gps != null) {
			latitude = CydJsonUtils.optDouble(gps, "latitude");
			longitude = CydJsonUtils.optDouble(gps, "longitude");
			Float accuracy = CydJsonUtils.optFloat(gps, "accuracy");
			accuracyMeters = accuracy != null ? accuracy : CydJsonUtils.optFloat(gps, "accuracy_m");
			gpsAgeMs = CydJsonUtils.optLong(gps, "age_ms");
			gpsSource = CydJsonUtils.optNonEmptyString(gps, "source");
		} else {
			latitude = CydJsonUtils.optDouble(json, "latitude");
			longitude = CydJsonUtils.optDouble(json, "longitude");
			Float accuracy = CydJsonUtils.optFloat(json, "accuracy");
			accuracyMeters = accuracy != null ? accuracy : CydJsonUtils.optFloat(json, "accuracy_m");
			gpsAgeMs = CydJsonUtils.optLong(json, "gps_age_ms");
			gpsSource = CydJsonUtils.optNonEmptyString(json, "gps_source");
		}
	}

	@NonNull
	static CydDetectionCandidate fromJson(@NonNull JSONObject json) {
		return new CydDetectionCandidate(json, System.currentTimeMillis());
	}

	public boolean hasGpsFix() {
		return isValidLatitude(latitude) && isValidLongitude(longitude);
	}

	@NonNull
	public String getDetectionTypeLabel() {
		if (detectionMethod != null) {
			return detectionMethod;
		}
		if (protocol != null) {
			return protocol;
		}
		return "Unknown detection";
	}

	@NonNull
	public String getSourceLabel() {
		if (deviceName != null) {
			return deviceName;
		}
		if (ssid != null) {
			return ssid;
		}
		if (macAddress != null) {
			return macAddress;
		}
		return "Unknown CYD source";
	}

	@Nullable
	public Integer getRssi() {
		return rssi;
	}

	@Nullable
	public Integer getChannel() {
		return channel;
	}

	@Nullable
	public Integer getFrequency() {
		return frequency;
	}

	@Nullable
	public Double getLatitude() {
		return latitude;
	}

	@Nullable
	public Double getLongitude() {
		return longitude;
	}

	@Nullable
	public Float getAccuracyMeters() {
		return accuracyMeters;
	}

	@Nullable
	public Long getGpsAgeMs() {
		return gpsAgeMs;
	}

	public long getReceivedAgeMs(long nowMs) {
		return Math.max(0, nowMs - receivedAtMs);
	}

	@NonNull
	public String getSignalStatus() {
		StringBuilder builder = new StringBuilder();
		CydJsonUtils.appendPart(builder, rssi != null ? "RSSI " + rssi + " dBm" : "RSSI unknown");
		CydJsonUtils.appendPart(builder, channel != null ? "Channel " + channel : "Channel unknown");
		if (frequency != null) {
			CydJsonUtils.appendPart(builder, frequency + " MHz");
		}
		return builder.toString();
	}

	@NonNull
	public String getGpsStatus() {
		StringBuilder builder = new StringBuilder();
		if (hasGpsFix()) {
			CydJsonUtils.appendPart(builder, String.format(Locale.US, "%.6f, %.6f", latitude, longitude));
			if (accuracyMeters != null) {
				CydJsonUtils.appendPart(builder, String.format(Locale.US, "+/- %.1f m", accuracyMeters));
			}
		} else if (latitude != null || longitude != null) {
			CydJsonUtils.appendPart(builder, "GPS incomplete");
		} else {
			CydJsonUtils.appendPart(builder, "GPS unavailable");
		}
		if (gpsSource != null) {
			CydJsonUtils.appendPart(builder, "Source " + gpsSource);
		}
		if (gpsAgeMs != null) {
			CydJsonUtils.appendPart(builder, "Age " + gpsAgeMs + " ms");
		}
		return builder.toString();
	}

	@NonNull
	public String getStatusSummary() {
		StringBuilder builder = new StringBuilder();
		CydJsonUtils.appendPart(builder, getDetectionTypeLabel());
		CydJsonUtils.appendPart(builder, getSourceLabel());
		CydJsonUtils.appendPart(builder, getSignalStatus());
		CydJsonUtils.appendPart(builder, getGpsStatus());
		return builder.toString();
	}

	private static boolean isValidLatitude(@Nullable Double value) {
		return value != null && value >= -90d && value <= 90d;
	}

	private static boolean isValidLongitude(@Nullable Double value) {
		return value != null && value >= -180d && value <= 180d;
	}
}
