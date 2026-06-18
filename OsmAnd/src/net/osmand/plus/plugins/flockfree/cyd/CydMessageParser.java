package net.osmand.plus.plugins.flockfree.cyd;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class CydMessageParser {

	private static final int MAX_LINE_CHARS = 16 * 1024;

	private final StringBuilder pendingLine = new StringBuilder();

	@NonNull
	public synchronized List<ParsedMessage> accept(@Nullable byte[] data) {
		if (data == null || data.length == 0) {
			return Collections.emptyList();
		}
		return accept(new String(data, StandardCharsets.UTF_8));
	}

	@NonNull
	public synchronized List<ParsedMessage> accept(@NonNull String chunk) {
		if (chunk.length() == 0) {
			return Collections.emptyList();
		}
		List<ParsedMessage> messages = new ArrayList<>();
		for (int i = 0; i < chunk.length(); i++) {
			char c = chunk.charAt(i);
			if (c == '\n') {
				addParsedLine(messages, pendingLine.toString());
				pendingLine.setLength(0);
			} else if (c != '\r') {
				if (pendingLine.length() >= MAX_LINE_CHARS) {
					pendingLine.setLength(0);
				}
				pendingLine.append(c);
			}
		}
		return messages;
	}

	public synchronized void reset() {
		pendingLine.setLength(0);
	}

	private static void addParsedLine(@NonNull List<ParsedMessage> messages, @NonNull String line) {
		String trimmed = line.trim();
		if (trimmed.length() == 0) {
			return;
		}
		messages.add(parseLine(trimmed));
	}

	@NonNull
	public static ParsedMessage parseLine(@NonNull String line) {
		if (!line.startsWith("{")) {
			return ParsedMessage.text(line);
		}
		try {
			JSONObject json = new JSONObject(line);
			String event = json.optString("event", "");
			if ("detection".equals(event)) {
				return ParsedMessage.detection(line, CydDetectionCandidate.fromJson(json));
			} else if ("pair_status".equals(event)) {
				return ParsedMessage.pairStatus(line, CydPairStatus.fromJson(json));
			}
		} catch (Exception e) {
			return ParsedMessage.text(line);
		}
		return ParsedMessage.text(line);
	}

	public enum MessageType {
		TEXT,
		PAIR_STATUS,
		DETECTION
	}

	public static final class ParsedMessage {
		@NonNull
		public final MessageType type;
		@NonNull
		public final String rawLine;
		@Nullable
		public final CydPairStatus pairStatus;
		@Nullable
		public final CydDetectionCandidate detectionCandidate;

		private ParsedMessage(@NonNull MessageType type, @NonNull String rawLine,
		                      @Nullable CydPairStatus pairStatus,
		                      @Nullable CydDetectionCandidate detectionCandidate) {
			this.type = type;
			this.rawLine = rawLine;
			this.pairStatus = pairStatus;
			this.detectionCandidate = detectionCandidate;
		}

		@NonNull
		private static ParsedMessage text(@NonNull String rawLine) {
			return new ParsedMessage(MessageType.TEXT, rawLine, null, null);
		}

		@NonNull
		private static ParsedMessage pairStatus(@NonNull String rawLine, @NonNull CydPairStatus pairStatus) {
			return new ParsedMessage(MessageType.PAIR_STATUS, rawLine, pairStatus, null);
		}

		@NonNull
		private static ParsedMessage detection(@NonNull String rawLine,
		                                       @NonNull CydDetectionCandidate detectionCandidate) {
			return new ParsedMessage(MessageType.DETECTION, rawLine, null, detectionCandidate);
		}
	}
}
