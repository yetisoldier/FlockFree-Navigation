package net.osmand.plus.plugins.flockfree.cyd;

import java.io.File;
import java.util.List;

final class CydParserSelfCheck {

	private CydParserSelfCheck() {
	}

	public static void main(String[] args) {
		checkPartialLineParsing();
		checkMalformedJson();
		checkPartialPairStatus();
		checkInvalidDetectionGps();
		checkTopLevelDetectionGps();
		checkMapCenterLocalDetectionGps();
		checkDetectionStoreRoundTrip();
		System.out.println("CydParserSelfCheck passed");
	}

	private static void checkPartialLineParsing() {
		CydMessageParser parser = new CydMessageParser();
		check(parser.accept("{\"event\":\"detection\",").isEmpty(), "partial line should be buffered");
		List<CydMessageParser.ParsedMessage> messages = parser.accept("\"rssi\":-72}\n");
		check(messages.size() == 1, "completed line should emit one message");
		CydMessageParser.ParsedMessage message = messages.get(0);
		check(message.type == CydMessageParser.MessageType.DETECTION, "completed line should parse detection");
		check(message.detectionCandidate != null, "detection model should be available");
		check(message.detectionCandidate.rssi != null && message.detectionCandidate.rssi == -72,
				"detection RSSI should be preserved");
	}

	private static void checkMalformedJson() {
		CydMessageParser.ParsedMessage message = CydMessageParser.parseLine("{\"event\":\"detection\"");
		check(message.type == CydMessageParser.MessageType.MALFORMED_JSON, "bad JSON should be explicit");
		check(message.isMalformedJson(), "bad JSON helper should be true");
		check(message.getStatusText().startsWith("Malformed JSON"), "bad JSON should expose status text");
	}

	private static void checkPartialPairStatus() {
		CydMessageParser.ParsedMessage message = CydMessageParser.parseLine(
				"{\"event\":\"pair_status\",\"gps\":\"maybe\",\"sd\":true,\"detections\":\"bad\"}");
		check(message.type == CydMessageParser.MessageType.PAIR_STATUS, "partial pair status should parse");
		CydPairStatus status = message.pairStatus;
		check(status != null, "pair status model should be available");
		check(status.gpsValue == null && !status.gps, "invalid GPS flag should be unknown, not true");
		check(Boolean.TRUE.equals(status.sdValue) && status.sd, "valid SD flag should be true");
		check(status.detectionsValue == null && status.detections == 0,
				"invalid detection count should remain unknown with zero fallback");
		check(status.getStatusSummary().contains("GPS unknown"), "summary should mention missing GPS state");
	}

	private static void checkInvalidDetectionGps() {
		CydMessageParser.ParsedMessage message = CydMessageParser.parseLine(
				"{\"event\":\"detection\",\"gps\":{\"latitude\":999,\"longitude\":\"oops\"}}");
		check(message.type == CydMessageParser.MessageType.DETECTION, "partial detection should parse");
		CydDetectionCandidate candidate = message.detectionCandidate;
		check(candidate != null, "detection candidate should be available");
		check(!candidate.hasGpsFix(), "invalid GPS values should not count as a fix");
		check(candidate.getGpsStatus().contains("GPS incomplete"), "partial GPS should be named clearly");
	}

	private static void checkTopLevelDetectionGps() {
		CydMessageParser.ParsedMessage message = CydMessageParser.parseLine(
				"{\"event\":\"detection\",\"latitude\":45.1,\"longitude\":-93.2,"
						+ "\"accuracy_m\":8.5,\"rssi\":\"-61\",\"channel\":\"6\"}");
		check(message.type == CydMessageParser.MessageType.DETECTION, "top-level GPS detection should parse");
		CydDetectionCandidate candidate = message.detectionCandidate;
		check(candidate != null, "detection candidate should be available");
		check(candidate.hasGpsFix(), "valid top-level GPS should count as a fix");
		check(candidate.rssi != null && candidate.rssi == -61, "string RSSI should parse when numeric");
		check(candidate.channel != null && candidate.channel == 6, "string channel should parse when numeric");
	}

	private static void checkMapCenterLocalDetectionGps() {
		CydMessageParser.ParsedMessage message = CydMessageParser.parseLine(
				"{\"event\":\"detection\",\"detection_method\":\"Simulated CYD\","
						+ "\"device_name\":\"FlockFree local test\",\"gps\":{\"latitude\":45.17,"
						+ "\"longitude\":-93.22,\"source\":\"map-center-local-test\"}}");
		check(message.type == CydMessageParser.MessageType.DETECTION,
				"map-center local detection should parse");
		CydDetectionCandidate candidate = message.detectionCandidate;
		check(candidate != null, "map-center detection candidate should be available");
		check(candidate.hasGpsFix(), "valid map-center GPS should count as a fix");
		check(candidate.getGpsStatus().contains("map-center-local-test"),
				"map-center source should be visible in status");
	}

	private static void checkDetectionStoreRoundTrip() {
		try {
			File file = File.createTempFile("cyd-detections", ".json");
			if (!file.delete()) {
				throw new AssertionError("temp file cleanup failed before store check");
			}
			CydMessageParser.ParsedMessage message = CydMessageParser.parseLine(
					"{\"event\":\"detection\",\"latitude\":45.1,\"longitude\":-93.2,"
							+ "\"accuracy_m\":8.5,\"rssi\":\"-61\",\"channel\":\"6\"}");
			check(message.detectionCandidate != null, "store check should have a detection candidate");
			CydDetectionStore.save(file, java.util.Collections.singletonList(message.detectionCandidate), 20);
			List<CydDetectionCandidate> loaded = CydDetectionStore.load(file, 20);
			check(loaded.size() == 1, "store should load one detection");
			check(loaded.get(0).hasGpsFix(), "stored detection should keep GPS");
			check(loaded.get(0).getReceivedAgeMs(System.currentTimeMillis()) >= 0,
					"stored detection should keep received time");
			CydDetectionStore.clear(file);
			check(CydDetectionStore.load(file, 20).isEmpty(), "store clear should remove detections");
			if (file.exists() && !file.delete()) {
				throw new AssertionError("temp file cleanup failed after store check");
			}
		} catch (Exception e) {
			throw new AssertionError("store round trip failed", e);
		}
	}

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
