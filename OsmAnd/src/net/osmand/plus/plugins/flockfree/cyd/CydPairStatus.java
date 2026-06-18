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
	@Nullable
	public final Integer protocolVersionValue;
	public final int protocolVersion;
	@NonNull
	public final List<String> features;
	@Nullable
	public final Boolean gpsValue;
	public final boolean gps;
	@Nullable
	public final Boolean sdValue;
	public final boolean sd;
	@Nullable
	public final Integer detectionsValue;
	public final int detections;
	@Nullable
	public final Integer csvRowsValue;
	public final int csvRows;
	@Nullable
	public final Integer rxFramesValue;
	public final int rxFrames;
	@Nullable
	public final Integer rxManagementFramesValue;
	public final int rxManagementFrames;
	@Nullable
	public final Integer rxDataFramesValue;
	public final int rxDataFrames;
	@Nullable
	public final Integer queueDropsValue;
	public final int queueDrops;
	@Nullable
	public final Integer channelValue;
	public final int channel;
	@Nullable
	public final String scanMode;

	private CydPairStatus(@NonNull JSONObject json) {
		rawJson = json.toString();
		device = CydJsonUtils.optNonEmptyString(json, "device");
		protocolVersionValue = CydJsonUtils.optInteger(json, "protocol_version");
		protocolVersion = valueOrZero(protocolVersionValue);
		features = Collections.unmodifiableList(parseFeatures(json.optJSONArray("features")));
		gpsValue = CydJsonUtils.optBoolean(json, "gps");
		gps = Boolean.TRUE.equals(gpsValue);
		sdValue = CydJsonUtils.optBoolean(json, "sd");
		sd = Boolean.TRUE.equals(sdValue);
		detectionsValue = CydJsonUtils.optInteger(json, "detections");
		detections = valueOrZero(detectionsValue);
		csvRowsValue = CydJsonUtils.optInteger(json, "csv_rows");
		csvRows = valueOrZero(csvRowsValue);
		rxFramesValue = CydJsonUtils.optInteger(json, "rx_frames");
		rxFrames = valueOrZero(rxFramesValue);
		rxManagementFramesValue = CydJsonUtils.optInteger(json, "rx_mgmt");
		rxManagementFrames = valueOrZero(rxManagementFramesValue);
		rxDataFramesValue = CydJsonUtils.optInteger(json, "rx_data");
		rxDataFrames = valueOrZero(rxDataFramesValue);
		queueDropsValue = CydJsonUtils.optInteger(json, "queue_drops");
		queueDrops = valueOrZero(queueDropsValue);
		channelValue = CydJsonUtils.optInteger(json, "channel");
		channel = valueOrZero(channelValue);
		scanMode = CydJsonUtils.optNonEmptyString(json, "scan_mode");
	}

	@NonNull
	static CydPairStatus fromJson(@NonNull JSONObject json) {
		return new CydPairStatus(json);
	}

	@NonNull
	public String getDeviceLabel() {
		return device != null ? device : "Unknown CYD device";
	}

	@Nullable
	public Integer getProtocolVersionValue() {
		return protocolVersionValue;
	}

	@NonNull
	public List<String> getFeatures() {
		return features;
	}

	@Nullable
	public Boolean getGpsValue() {
		return gpsValue;
	}

	@Nullable
	public Boolean getSdValue() {
		return sdValue;
	}

	@Nullable
	public Integer getDetectionsValue() {
		return detectionsValue;
	}

	@Nullable
	public Integer getCsvRowsValue() {
		return csvRowsValue;
	}

	@Nullable
	public Integer getRxFramesValue() {
		return rxFramesValue;
	}

	@Nullable
	public Integer getRxManagementFramesValue() {
		return rxManagementFramesValue;
	}

	@Nullable
	public Integer getRxDataFramesValue() {
		return rxDataFramesValue;
	}

	@Nullable
	public Integer getQueueDropsValue() {
		return queueDropsValue;
	}

	@Nullable
	public Integer getChannelValue() {
		return channelValue;
	}

	@NonNull
	public String getProtocolVersionStatus() {
		return protocolVersionValue != null ? "Protocol v" + protocolVersionValue : "Protocol unknown";
	}

	@NonNull
	public String getGpsStatus() {
		return booleanStatus("GPS", gpsValue, "ready", "unavailable");
	}

	@NonNull
	public String getSdStatus() {
		return booleanStatus("SD", sdValue, "ready", "unavailable");
	}

	@NonNull
	public String getDetectionsStatus() {
		return countStatus("Detections", detectionsValue);
	}

	@NonNull
	public String getCsvRowsStatus() {
		return countStatus("CSV rows", csvRowsValue);
	}

	@NonNull
	public String getRadioStatus() {
		StringBuilder builder = new StringBuilder();
		if (scanMode != null) {
			CydJsonUtils.appendPart(builder, "Scan " + scanMode);
		} else {
			CydJsonUtils.appendPart(builder, "Scan unknown");
		}
		CydJsonUtils.appendPart(builder, channelValue != null ? "Channel " + channelValue : "Channel unknown");
		CydJsonUtils.appendPart(builder, rxFramesValue != null ? "RX frames " + rxFramesValue : "RX frames unknown");
		if (queueDropsValue != null && queueDropsValue > 0) {
			CydJsonUtils.appendPart(builder, "Queue drops " + queueDropsValue);
		}
		return builder.toString();
	}

	@NonNull
	public String getStatusSummary() {
		StringBuilder builder = new StringBuilder();
		CydJsonUtils.appendPart(builder, getDeviceLabel());
		CydJsonUtils.appendPart(builder, getProtocolVersionStatus());
		CydJsonUtils.appendPart(builder, getGpsStatus());
		CydJsonUtils.appendPart(builder, getSdStatus());
		CydJsonUtils.appendPart(builder, getDetectionsStatus());
		CydJsonUtils.appendPart(builder, getRadioStatus());
		return builder.toString();
	}

	private static List<String> parseFeatures(@Nullable JSONArray array) {
		if (array == null) {
			return Collections.emptyList();
		}
		List<String> result = new ArrayList<>(array.length());
		for (int i = 0; i < array.length(); i++) {
			String value = CydJsonUtils.optNonEmptyString(array, i);
			if (value != null) {
				result.add(value);
			}
		}
		return result;
	}

	private static int valueOrZero(@Nullable Integer value) {
		return value != null ? value : 0;
	}

	@NonNull
	private static String booleanStatus(@NonNull String label, @Nullable Boolean value,
	                                    @NonNull String trueStatus, @NonNull String falseStatus) {
		if (value == null) {
			return label + " unknown";
		}
		return label + " " + (value ? trueStatus : falseStatus);
	}

	@NonNull
	private static String countStatus(@NonNull String label, @Nullable Integer value) {
		return label + " " + CydJsonUtils.missingIfNull(value);
	}
}
