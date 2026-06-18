package net.osmand.plus.plugins.flockfree.cyd;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

final class CydJsonUtils {

	private CydJsonUtils() {
	}

	static boolean hasValue(@NonNull JSONObject json, @NonNull String key) {
		return json.has(key) && !json.isNull(key);
	}

	@Nullable
	static String optNonEmptyString(@NonNull JSONObject json, @NonNull String key) {
		if (!hasValue(json, key)) {
			return null;
		}
		return cleanString(json.opt(key));
	}

	@Nullable
	static String optNonEmptyString(@NonNull JSONArray array, int index) {
		if (index < 0 || index >= array.length() || array.isNull(index)) {
			return null;
		}
		return cleanString(array.opt(index));
	}

	@Nullable
	static Boolean optBoolean(@NonNull JSONObject json, @NonNull String key) {
		if (!hasValue(json, key)) {
			return null;
		}
		Object value = json.opt(key);
		if (value instanceof Boolean) {
			return (Boolean) value;
		}
		if (value instanceof Number) {
			int number = ((Number) value).intValue();
			if (number == 0) {
				return Boolean.FALSE;
			}
			if (number == 1) {
				return Boolean.TRUE;
			}
			return null;
		}
		String stringValue = cleanString(value);
		if (stringValue == null) {
			return null;
		}
		String normalized = stringValue.toLowerCase(Locale.US);
		if ("true".equals(normalized) || "yes".equals(normalized)
				|| "on".equals(normalized) || "1".equals(normalized)) {
			return Boolean.TRUE;
		}
		if ("false".equals(normalized) || "no".equals(normalized)
				|| "off".equals(normalized) || "0".equals(normalized)) {
			return Boolean.FALSE;
		}
		return null;
	}

	@Nullable
	static Integer optInteger(@NonNull JSONObject json, @NonNull String key) {
		if (!hasValue(json, key)) {
			return null;
		}
		Long value = optLongValue(json.opt(key));
		if (value == null || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
			return null;
		}
		return value.intValue();
	}

	@Nullable
	static Long optLong(@NonNull JSONObject json, @NonNull String key) {
		if (!hasValue(json, key)) {
			return null;
		}
		return optLongValue(json.opt(key));
	}

	@Nullable
	static Double optDouble(@NonNull JSONObject json, @NonNull String key) {
		if (!hasValue(json, key)) {
			return null;
		}
		return optDoubleValue(json.opt(key));
	}

	@Nullable
	static Float optFloat(@NonNull JSONObject json, @NonNull String key) {
		Double value = optDouble(json, key);
		if (value == null || value < -Float.MAX_VALUE || value > Float.MAX_VALUE) {
			return null;
		}
		return value.floatValue();
	}

	@NonNull
	static String missingIfNull(@Nullable Object value) {
		return value != null ? String.valueOf(value) : "unknown";
	}

	static void appendPart(@NonNull StringBuilder builder, @Nullable String value) {
		if (value == null || value.length() == 0) {
			return;
		}
		if (builder.length() > 0) {
			builder.append(" | ");
		}
		builder.append(value);
	}

	@Nullable
	private static String cleanString(@Nullable Object value) {
		if (value == null || value == JSONObject.NULL) {
			return null;
		}
		String stringValue = String.valueOf(value).trim();
		return stringValue.length() == 0 ? null : stringValue;
	}

	@Nullable
	private static Long optLongValue(@Nullable Object value) {
		if (value == null || value == JSONObject.NULL) {
			return null;
		}
		if (value instanceof Number) {
			Number number = (Number) value;
			double doubleValue = number.doubleValue();
			if (!Double.isNaN(doubleValue) && !Double.isInfinite(doubleValue)
					&& doubleValue == Math.rint(doubleValue)
					&& doubleValue >= Long.MIN_VALUE && doubleValue <= Long.MAX_VALUE) {
				return number.longValue();
			}
			return null;
		}
		String stringValue = cleanString(value);
		if (stringValue == null) {
			return null;
		}
		try {
			return Long.parseLong(stringValue);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	@Nullable
	private static Double optDoubleValue(@Nullable Object value) {
		if (value == null || value == JSONObject.NULL) {
			return null;
		}
		double doubleValue;
		if (value instanceof Number) {
			doubleValue = ((Number) value).doubleValue();
		} else {
			String stringValue = cleanString(value);
			if (stringValue == null) {
				return null;
			}
			try {
				doubleValue = Double.parseDouble(stringValue);
			} catch (NumberFormatException e) {
				return null;
			}
		}
		return Double.isNaN(doubleValue) || Double.isInfinite(doubleValue) ? null : doubleValue;
	}
}
