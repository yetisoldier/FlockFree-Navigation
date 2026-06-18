package net.osmand.plus.plugins.flockfree.cyd;

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

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError(message);
		}
	}
}
