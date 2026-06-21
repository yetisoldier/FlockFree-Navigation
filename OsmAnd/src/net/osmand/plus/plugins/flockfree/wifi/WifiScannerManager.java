package net.osmand.plus.plugins.flockfree.wifi;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.PlatformUtil;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.util.Algorithms;

import org.apache.commons.logging.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * WiFi-based Flock Safety camera detector using Android's standard WifiManager scan API.
 *
 * On stock Android (no root), we can read {@link WifiManager#getScanResults()} which returns
 * BSSID (MAC), SSID, RSSI, channel and frequency for nearby WiFi devices. We match the first
 * three octets (OUI) of each BSSID against the 31 known Flock Safety infrastructure OUI prefixes
 * discovered by @NitekryDPaul and DeFlockJoplin through ESP32 promiscuous-mode research.
 *
 * This approach requires no extra hardware and no root. It catches any Flock device that
 * appears in the system WiFi scan cache — which includes cameras acting as APs or that
 * respond to probe requests. It will NOT catch sleeping/client-mode cameras that never
 * transmit during the scan window (the ESP32 promiscuous approach catches those via
 * addr1-receiver matching and wildcard-probe signatures).
 *
 * On Android 9+ startScan() is deprecated and throttled. The system does background scans
 * automatically; we poll getScanResults() and listen for SCAN_RESULTS_AVAILABLE_ACTION.
 */
public class WifiScannerManager {

	private static final Log LOG = PlatformUtil.getLog(WifiScannerManager.class);

	/** Interval between polling getScanResults() / attempting startScan(). */
	private static final long SCAN_INTERVAL_MS = 15_000L;

	/** Detections older than this are considered stale and pruned. */
	private static final long DETECTION_STALE_MS = 120_000L;

	/** RSSI threshold — ignore signals weaker than this. */
	private static final int RSSI_MIN = -100;

	/**
	 * 31 Flock Safety WiFi OUI prefixes (first 3 octets, lowercase, colon-separated).
	 * Research by @NitekryDPaul (30 prefixes) + DeFlockJoplin (31st: 82:6b:f2).
	 * Source: https://github.com/colonelpanichacks/flock-you (promiscious-dev branch)
	 */
	private static final String[] FLOCK_OUIS = {
			"70:c9:4e", "3c:91:80", "d8:f3:bc", "80:30:49", "b8:35:32",
			"14:5a:fc", "74:4c:a1", "08:3a:88", "9c:2f:9d", "c0:35:32",
			"94:08:53", "e4:aa:ea", "f4:6a:dd", "f8:a2:d6", "24:b2:b9",
			"00:f4:8d", "d0:39:57", "e8:d0:fc", "e0:4f:43", "b8:1e:a4",
			"70:08:94", "58:8e:81", "ec:1b:bd", "3c:71:bf", "58:00:e3",
			"90:35:ea", "5c:93:a2", "64:6e:69", "48:27:ea", "a4:cf:12",
			"82:6b:f2"
	};

	private final OsmandApplication app;
	private final Handler handler = new Handler(Looper.getMainLooper());

	@Nullable
	private WifiManager wifiManager;
	@Nullable
	private BroadcastReceiver scanResultsReceiver;

	private boolean scanning = false;
	private long lastScanAttemptMs = 0;
	private int totalScanCycles = 0;
	private int totalFlockMatches = 0;

	@NonNull
	private final Map<String, WifiFlockDetection> detectionMap = new HashMap<>();

	private final Runnable scanRunnable = this::performScanCycle;

	@Nullable
	private Listener listener;

	// ───────────────────────────────────────────────────────────
	//  Public API
	// ───────────────────────────────────────────────────────────

	public interface Listener {
		void onWifiFlockDetection(@NonNull WifiFlockDetection detection, boolean isNew);
		void onWifiScanCycleCompleted(int totalDevicesScanned, int flockMatchesTotal);
	}

	public WifiScannerManager(@NonNull OsmandApplication app) {
		this.app = app;
	}

	public void setListener(@Nullable Listener listener) {
		this.listener = listener;
	}

	public boolean start() {
		if (scanning) return true;
		if (!hasLocationPermission()) {
			LOG.warn("WiFi scan requires ACCESS_FINE_LOCATION permission");
			return false;
		}
		if (!isLocationEnabled()) {
			LOG.warn("WiFi scan requires device Location to be enabled");
			return false;
		}
		wifiManager = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
		if (wifiManager == null) {
			LOG.warn("WifiManager unavailable on this device");
			return false;
		}

		// Register for system scan-results broadcast
		scanResultsReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				if (WifiManager.SCAN_RESULTS_AVAILABLE_ACTION.equals(intent.getAction())) {
					processScanResults();
				}
			}
		};
		IntentFilter filter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			app.registerReceiver(scanResultsReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
		} else {
			app.registerReceiver(scanResultsReceiver, filter);
		}

		scanning = true;
		LOG.info("WiFi Flock scanner started");
		// Start first scan cycle immediately
		handler.post(scanRunnable);
		return true;
	}

	public void stop() {
		if (!scanning) return;
		handler.removeCallbacks(scanRunnable);
		if (scanResultsReceiver != null) {
			try {
				app.unregisterReceiver(scanResultsReceiver);
			} catch (IllegalArgumentException e) {
				// Receiver not registered — ignore
			}
			scanResultsReceiver = null;
		}
		wifiManager = null;
		scanning = false;
		LOG.info("WiFi Flock scanner stopped");
	}

	public boolean isScanning() {
		return scanning;
	}

	@NonNull
	public List<WifiFlockDetection> getRecentDetections() {
		synchronized (detectionMap) {
			return new ArrayList<>(detectionMap.values());
		}
	}

	public int getDetectionCount() {
		synchronized (detectionMap) {
			return detectionMap.size();
		}
	}

	public void clearDetections() {
		synchronized (detectionMap) {
			detectionMap.clear();
		}
	}

	@NonNull
	public String getStatusSummary() {
		if (!scanning) {
			return app.getString(R.string.flockfree_wifi_scan_status_off);
		}
		int count = getDetectionCount();
		if (count == 0) {
			return app.getString(R.string.flockfree_wifi_scan_status_no_detections);
		}
		WifiFlockDetection strongest = getStrongestDetection();
		if (strongest != null) {
			return app.getString(R.string.flockfree_wifi_scan_status_active,
					count, strongest.bssid, strongest.rssi,
					(int) Math.round(strongest.getEstimatedDistanceMeters()));
		}
		return app.getString(R.string.flockfree_wifi_scan_status_count, count);
	}

	@Nullable
	public WifiFlockDetection getStrongestDetection() {
		synchronized (detectionMap) {
			WifiFlockDetection strongest = null;
			for (WifiFlockDetection d : detectionMap.values()) {
				if (strongest == null || d.rssi > strongest.rssi) {
					strongest = d;
				}
			}
			return strongest;
		}
	}

	// ───────────────────────────────────────────────────────────
	//  Internal scan logic
	// ───────────────────────────────────────────────────────────

	private boolean hasLocationPermission() {
		return app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
				== PackageManager.PERMISSION_GRANTED;
	}

	public boolean isReadyToScan() {
		return hasLocationPermission() && isLocationEnabled();
	}

	private boolean isLocationEnabled() {
		LocationManager locationManager = (LocationManager) app.getSystemService(Context.LOCATION_SERVICE);
		if (locationManager == null) {
			return false;
		}
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
				return locationManager.isLocationEnabled();
			}
			return locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
					|| locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER);
		} catch (RuntimeException e) {
			LOG.warn("Unable to check Location state for WiFi scan", e);
			return false;
		}
	}

	private void performScanCycle() {
		if (!scanning || wifiManager == null) return;

		// Try to trigger a fresh scan (may be throttled on Android 9+)
		try {
			boolean started = wifiManager.startScan();
			if (!started) {
				LOG.debug("startScan() returned false (throttled or unavailable)");
			}
		} catch (SecurityException e) {
			LOG.warn("startScan() security exception", e);
		}
		lastScanAttemptMs = System.currentTimeMillis();

		// Process whatever results are available (cached or fresh)
		processScanResults();

		totalScanCycles++;
		// Schedule next cycle
		handler.postDelayed(scanRunnable, SCAN_INTERVAL_MS);
	}

	private void processScanResults() {
		if (wifiManager == null) return;
		List<ScanResult> results;
		try {
			results = wifiManager.getScanResults();
		} catch (SecurityException e) {
			LOG.warn("getScanResults() security exception", e);
			return;
		}
		if (results == null) return;

		long now = System.currentTimeMillis();
		int devicesScanned = results.size();

		for (ScanResult result : results) {
			String bssid = result.BSSID;
			if (bssid == null || bssid.length() < 8) continue;
			if (result.level < RSSI_MIN) continue;

			String oui = extractOui(bssid);
			if (oui == null || !isFlockOui(oui)) continue;

			// We have a Flock OUI match
			WifiFlockDetection existing;
			synchronized (detectionMap) {
				existing = detectionMap.get(bssid);
			}
			boolean isNew = existing == null;
			boolean significantChange = existing != null
					&& (Math.abs(existing.rssi - result.level) > 5
					|| !Algorithms.stringsEqual(existing.ssid, result.SSID));

			if (isNew || significantChange || (now - existing.timestampMs) > 30_000L) {
				String ssid = result.SSID != null ? result.SSID : "";
				int channel = getChannelFromFrequency(result.frequency);
				String security = getSecurityFromCapabilities(result.capabilities);

				WifiFlockDetection detection = new WifiFlockDetection(
						bssid, oui, ssid, result.level, channel,
						result.frequency, security, now);

				synchronized (detectionMap) {
					detectionMap.put(bssid, detection);
				}

				if (listener != null) {
					listener.onWifiFlockDetection(detection, isNew);
				}

				if (isNew) {
					totalFlockMatches++;
					LOG.info("Flock WiFi detected: " + bssid + " OUI=" + oui + " RSSI=" + result.level + " SSID=" + ssid);
				}
			}
		}

		// Prune stale detections
		synchronized (detectionMap) {
			detectionMap.entrySet().removeIf(e ->
					now - e.getValue().timestampMs > DETECTION_STALE_MS);
		}

		if (listener != null) {
			listener.onWifiScanCycleCompleted(devicesScanned, detectionMap.size());
		}
	}

	@Nullable
	private static String extractOui(@NonNull String bssid) {
		String lower = bssid.toLowerCase();
		if (lower.length() >= 8 && lower.charAt(2) == ':' && lower.charAt(5) == ':') {
			return lower.substring(0, 8);
		}
		return null;
	}

	private static boolean isFlockOui(@NonNull String oui) {
		for (String target : FLOCK_OUIS) {
			if (target.equals(oui)) return true;
		}
		return false;
	}

	private static int getChannelFromFrequency(int freqMhz) {
		if (freqMhz >= 2412 && freqMhz <= 2484) {
			if (freqMhz == 2484) return 14;
			return (freqMhz - 2412) / 5 + 1;
		} else if (freqMhz >= 5180 && freqMhz <= 5900) {
			return (freqMhz - 5180) / 5 + 36;
		}
		return 0;
	}

	private static String getSecurityFromCapabilities(@Nullable String capabilities) {
		if (capabilities == null || capabilities.isEmpty()) return "unknown";
		if (capabilities.contains("WPA3")) return "WPA3";
		if (capabilities.contains("WPA2")) return "WPA2";
		if (capabilities.contains("WPA")) return "WPA";
		if (capabilities.contains("WEP")) return "WEP";
		if (capabilities.contains("ESS")) return "open";
		return "unknown";
	}

	/**
	 * Rough RSSI-to-distance estimation using free-space path loss model.
	 * Assumes TxPower of -59 dBm at 1 metre and path-loss exponent of 2.7 (indoor).
	 * Real-world accuracy is ±50% but gives a useful ballpark.
	 */
	public static double rssiToDistanceMeters(int rssi) {
		if (rssi == 0) return -1;
		double txPower = -59.0;
		double n = 2.7;
		double ratio = (txPower - rssi) / (10 * n);
		return Math.pow(10, ratio);
	}

	// ───────────────────────────────────────────────────────────
	//  Detection model
	// ───────────────────────────────────────────────────────────

	public static class WifiFlockDetection {

		@NonNull public final String bssid;
		@NonNull public final String oui;
		@NonNull public final String ssid;
		public final int rssi;
		public final int channel;
		public final int frequencyMhz;
		@NonNull public final String security;
		public final long timestampMs;

		public WifiFlockDetection(@NonNull String bssid, @NonNull String oui,
		                          @NonNull String ssid, int rssi, int channel,
		                          int frequencyMhz, @NonNull String security,
		                          long timestampMs) {
			this.bssid = bssid;
			this.oui = oui;
			this.ssid = ssid;
			this.rssi = rssi;
			this.channel = channel;
			this.frequencyMhz = frequencyMhz;
			this.security = security;
			this.timestampMs = timestampMs;
		}

		public double getEstimatedDistanceMeters() {
			return rssiToDistanceMeters(rssi);
		}

		@NonNull
		public String getSummary() {
			double dist = getEstimatedDistanceMeters();
			return String.format("%s  RSSI:%d dBm  ~%.0fm  ch:%d  SSID:%s",
					bssid, rssi, dist, channel, ssid.isEmpty() ? "(hidden)" : ssid);
		}
	}
}
