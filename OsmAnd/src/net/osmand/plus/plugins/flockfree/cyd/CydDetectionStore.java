package net.osmand.plus.plugins.flockfree.cyd;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class CydDetectionStore {

	private static final long MAX_STORE_BYTES = 256L * 1024L;

	private CydDetectionStore() {
	}

	@NonNull
	static List<CydDetectionCandidate> load(@NonNull File file, int maxCount) throws IOException {
		if (!file.exists() || file.length() == 0) {
			return Collections.emptyList();
		}
		if (file.length() > MAX_STORE_BYTES) {
			throw new IOException("CYD detection store is too large: " + file.length() + " bytes");
		}
		JSONArray array;
		try {
			array = new JSONArray(readFile(file));
		} catch (JSONException e) {
			throw new IOException("Malformed CYD detection store", e);
		}
		ArrayList<CydDetectionCandidate> detections = new ArrayList<>();
		for (int i = 0; i < array.length() && detections.size() < maxCount; i++) {
			JSONObject item = array.optJSONObject(i);
			if (item == null) {
				continue;
			}
			String raw = CydJsonUtils.optNonEmptyString(item, "raw");
			if (raw == null) {
				continue;
			}
			try {
				long receivedAtMs = item.optLong("received_at_ms", System.currentTimeMillis());
				CydDetectionCandidate candidate = CydDetectionCandidate.fromJson(new JSONObject(raw), receivedAtMs);
				if (candidate.hasGpsFix()) {
					detections.add(candidate);
				}
			} catch (Exception ignored) {
				// Skip malformed persisted entries; a fresh detection will rewrite the store.
			}
		}
		return Collections.unmodifiableList(detections);
	}

	static void save(@NonNull File file, @NonNull List<CydDetectionCandidate> detections,
	                 int maxCount) throws IOException {
		JSONArray array = new JSONArray();
		for (int i = 0; i < detections.size() && i < maxCount; i++) {
			CydDetectionCandidate detection = detections.get(i);
			if (!detection.hasGpsFix()) {
				continue;
			}
			try {
				JSONObject item = new JSONObject();
				item.put("raw", detection.rawJson);
				item.put("received_at_ms", detection.receivedAtMs);
				array.put(item);
			} catch (JSONException e) {
				// Skip this detection if serialization fails.
			}
		}
		writeFileAtomically(file, array.toString());
	}

	static void clear(@NonNull File file) throws IOException {
		if (file.exists() && !file.delete()) {
			writeFileAtomically(file, "[]");
		}
	}

	@NonNull
	private static String readFile(@NonNull File file) throws IOException {
		try (BufferedInputStream in = new BufferedInputStream(new FileInputStream(file));
		     ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			byte[] buffer = new byte[4096];
			int read;
			long total = 0;
			while ((read = in.read(buffer)) != -1) {
				total += read;
				if (total > MAX_STORE_BYTES) {
					throw new IOException("CYD detection store exceeds " + MAX_STORE_BYTES + " bytes");
				}
				out.write(buffer, 0, read);
			}
			return out.toString(StandardCharsets.UTF_8.name());
		}
	}

	private static void writeFileAtomically(@NonNull File file, @NonNull String content) throws IOException {
		File parent = file.getParentFile();
		if (parent != null && !parent.exists() && !parent.mkdirs()) {
			throw new IOException("Unable to create CYD detection store directory: " + parent);
		}
		File temp = new File(file.getParentFile(), file.getName() + ".tmp");
		try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(temp))) {
			out.write(content.getBytes(StandardCharsets.UTF_8));
		}
		if (!temp.renameTo(file)) {
			if (file.exists() && file.delete() && temp.renameTo(file)) {
				return;
			}
			throw new IOException("Unable to replace CYD detection store: " + file);
		}
	}
}
