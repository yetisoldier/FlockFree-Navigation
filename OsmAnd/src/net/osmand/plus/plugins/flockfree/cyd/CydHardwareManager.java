package net.osmand.plus.plugins.flockfree.cyd;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.PlatformUtil;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.utils.AndroidUtils;
import net.osmand.util.Algorithms;

import org.apache.commons.logging.Log;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

public final class CydHardwareManager implements AutoCloseable, CydBleUartClient.Listener {

	private static final Log LOG = PlatformUtil.getLog(CydHardwareManager.class);
	private static final long SCAN_TIMEOUT_MS = 15_000L;
	private static final long GPS_SEND_INTERVAL_MS = 1_000L;
	private static final long CONNECT_RETRY_DELAY_MS = 1_500L;
	private static final int MAX_CONNECT_RETRIES = 3;
	private static final int MAX_RECENT_DETECTIONS = 20;
	private static final long MAX_PHONE_LOCATION_AGE_MS = 30_000L; // 30 seconds

	private final OsmandApplication app;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final CydBleUartClient client = new CydBleUartClient(this);
	private final Object lock = new Object();
	private final Runnable scanTimeoutRunnable = this::handleScanTimeout;
	private final Runnable connectRetryRunnable = this::retryScanAndConnect;

	@Nullable
	private BluetoothLeScanner scanner;
	@Nullable
	private ScanCallback scanCallback;
	@Nullable
	private CydPairStatus lastPairStatus;
	@Nullable
	private CydDetectionCandidate lastDetection;
	@NonNull
	private List<CydDetectionCandidate> recentDetections = Collections.emptyList();
	@NonNull
	private State state = State.IDLE;
	@Nullable
	private String lastMessage;
	private long lastGpsSentAtMs;
	@Nullable
	private Double lastPhoneLatitude;
	@Nullable
	private Double lastPhoneLongitude;
	@Nullable
	private Float lastPhoneAccuracy;
	private long lastPhoneLocationAtMs;
	@Nullable
	private CydConnectionListener connectionListener;
	private int connectRetryAttempts;
	private boolean manualDisconnect;

	public CydHardwareManager(@NonNull OsmandApplication app) {
		this.app = app;
		loadPersistedDetections();
	}

	public interface CydConnectionListener {
		void onCydConnectionStateChanged(@NonNull State state);
	}

	public void setConnectionListener(@Nullable CydConnectionListener listener) {
		this.connectionListener = listener;
	}

	private void notifyCydConnectionStateChanged() {
		if (connectionListener != null) {
			State currentState;
			synchronized (lock) {
				currentState = state;
			}
			connectionListener.onCydConnectionStateChanged(currentState);
		}
	}

	@NonNull
	public State getState() {
		synchronized (lock) {
			return state;
		}
	}

	@Nullable
	public CydPairStatus getLastPairStatus() {
		synchronized (lock) {
			return lastPairStatus;
		}
	}

	@Nullable
	public CydDetectionCandidate getLastDetection() {
		synchronized (lock) {
			return lastDetection;
		}
	}

	@NonNull
	public List<CydDetectionCandidate> getRecentDetections() {
		synchronized (lock) {
			return new ArrayList<>(recentDetections);
		}
	}

	public int getRecentDetectionCount() {
		synchronized (lock) {
			return recentDetections.size();
		}
	}

	@NonNull
	public String getStatusSummary() {
		synchronized (lock) {
			StringBuilder builder = new StringBuilder();
			if (lastPairStatus != null) {
				CydJsonUtils.appendPart(builder, lastPairStatus.getStatusSummary());
			} else if (!Algorithms.isEmpty(lastMessage)) {
				CydJsonUtils.appendPart(builder, lastMessage);
			} else {
				CydJsonUtils.appendPart(builder, app.getString(R.string.flockfree_cyd_status_idle));
			}
			CydJsonUtils.appendPart(builder, getPhoneGpsStatusSummary(System.currentTimeMillis()));
			return builder.toString();
		}
	}

	public boolean startScanAndConnect(@NonNull MapActivity activity) {
		if (!AndroidUtils.requestBLEPermissions(activity)) {
			setState(State.PERMISSION_NEEDED, app.getString(R.string.flockfree_cyd_status_permission_requested));
			app.showShortToastMessage(R.string.flockfree_cyd_status_permission_requested);
			return false;
		}
		return startScanAndConnectWithGrantedPermissions(activity, true);
	}

	public boolean startScanAndConnectFromService(@NonNull Context context) {
		if (!AndroidUtils.hasBLEPermission(context)) {
			setState(State.PERMISSION_NEEDED, app.getString(R.string.flockfree_cyd_status_permission_requested));
			return false;
		}
		return startScanAndConnectWithGrantedPermissions(context, true);
	}

	private boolean startScanAndConnectWithGrantedPermissions(@NonNull Context context, boolean resetRetries) {
		BluetoothManager manager = (BluetoothManager) app.getSystemService(Context.BLUETOOTH_SERVICE);
		BluetoothAdapter adapter = manager != null ? manager.getAdapter() : null;
		if (adapter == null) {
			setState(State.ERROR, app.getString(R.string.flockfree_cyd_status_no_adapter));
			app.showShortToastMessage(R.string.flockfree_cyd_status_no_adapter);
			return false;
		}
		if (!adapter.isEnabled()) {
			setState(State.ERROR, app.getString(R.string.flockfree_cyd_status_bluetooth_disabled));
			app.showShortToastMessage(R.string.flockfree_cyd_status_bluetooth_disabled);
			return false;
		}
		BluetoothLeScanner bluetoothLeScanner = adapter.getBluetoothLeScanner();
		if (bluetoothLeScanner == null) {
			setState(State.ERROR, app.getString(R.string.flockfree_cyd_status_scanner_unavailable));
			app.showShortToastMessage(R.string.flockfree_cyd_status_scanner_unavailable);
			return false;
		}
		stopScan();
		handler.removeCallbacks(scanTimeoutRunnable);
		handler.removeCallbacks(connectRetryRunnable);
		client.close();
		synchronized (lock) {
			if (resetRetries) {
				connectRetryAttempts = 0;
			}
			manualDisconnect = false;
			lastPairStatus = null;
			lastDetection = null;
			lastGpsSentAtMs = 0L;
			scanner = bluetoothLeScanner;
			state = State.SCANNING;
			lastMessage = app.getString(R.string.flockfree_cyd_status_scanning);
		}
		app.showShortToastMessage(R.string.flockfree_cyd_status_scanning);
		ScanSettings settings = new ScanSettings.Builder()
				.setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
				.setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
				.setReportDelay(0L)
				.build();
		ScanCallback callback = createScanCallback(context);
		synchronized (lock) {
			scanCallback = callback;
		}
		try {
			bluetoothLeScanner.startScan(createScanFilters(), settings, callback);
			handler.postDelayed(scanTimeoutRunnable, SCAN_TIMEOUT_MS);
			return true;
		} catch (RuntimeException e) {
			LOG.error("CYD BLE scan could not be started", e);
			setState(State.ERROR, app.getString(R.string.flockfree_cyd_status_scan_failed));
			app.showShortToastMessage(R.string.flockfree_cyd_status_scan_failed);
			stopScan();
			return false;
		}
	}

	public boolean requestStatus() {
		if (client.isReady()) {
			return client.sendStatusRequest();
		}
		app.showShortToastMessage(R.string.flockfree_cyd_status_not_connected);
		return false;
	}

	public boolean simulateDetection(@Nullable MapActivity activity) {
		if (client.isReady()) {
			return client.sendSimulationRequest();
		}
		return simulateLocalDetection(activity);
	}

	public boolean updatePhoneLocation(@NonNull Location location) {
		if (!isValidGpsFix(location)) {
			return false;
		}
		rememberPhoneLocation(location);
		if (!client.isReady()) {
			return false;
		}
		long nowMs = System.currentTimeMillis();
		synchronized (lock) {
			if (nowMs - lastGpsSentAtMs < GPS_SEND_INTERVAL_MS) {
				return false;
			}
			lastGpsSentAtMs = nowMs;
		}
		long locationTimeMs = location.getTime() > 0 ? location.getTime() : nowMs;
		boolean sent = client.sendGpsFix(
				location.getLatitude(),
				location.getLongitude(),
				location.hasAccuracy() ? location.getAccuracy() : 0f,
				location.hasSpeed() ? Math.max(0f, location.getSpeed()) : 0f,
				location.hasBearing() ? location.getBearing() : 0f,
				0,
				0f,
				locationTimeMs / 1000L,
				TimeZone.getDefault().getOffset(locationTimeMs) / 60_000);
		if (!sent) {
			synchronized (lock) {
				lastGpsSentAtMs = 0L;
			}
		}
		return sent;
	}

	private void rememberPhoneLocation(@NonNull Location location) {
		long nowMs = System.currentTimeMillis();
		synchronized (lock) {
			lastPhoneLatitude = location.getLatitude();
			lastPhoneLongitude = location.getLongitude();
			lastPhoneAccuracy = location.hasAccuracy() ? location.getAccuracy() : null;
			lastPhoneLocationAtMs = location.getTime() > 0 ? location.getTime() : nowMs;
		}
	}

	private boolean simulateLocalDetection(@Nullable MapActivity activity) {
		rememberLastKnownLocationIfAvailable();
		LocalSimulationFix simulationFix = getLocalSimulationFix(activity);
		if (simulationFix == null) {
			setState(State.ERROR, app.getString(R.string.flockfree_cyd_local_location_unavailable));
			app.showShortToastMessage(R.string.flockfree_cyd_local_location_unavailable);
			return false;
		}
		try {
			long nowMs = System.currentTimeMillis();
			JSONObject gps = new JSONObject()
					.put("latitude", simulationFix.latitude)
					.put("longitude", simulationFix.longitude)
					.put("accuracy", simulationFix.accuracyMeters != null
							? simulationFix.accuracyMeters : JSONObject.NULL)
					.put("age_ms", Math.max(0L, nowMs - simulationFix.locationAtMs))
					.put("source", simulationFix.source);
			JSONObject json = new JSONObject()
					.put("event", "detection")
					.put("detection_method", "Simulated CYD")
					.put("protocol", "local-test")
					.put("device_name", "FlockFree local test")
					.put("rssi", -42)
					.put("channel", 0)
					.put("gps", gps);
			storeDetection(CydDetectionCandidate.fromJson(json), R.string.flockfree_cyd_local_simulated_detection);
			return true;
		} catch (JSONException e) {
			LOG.warn("Unable to create local CYD simulation", e);
			setState(State.ERROR, app.getString(R.string.flockfree_cyd_status_not_connected));
			app.showShortToastMessage(R.string.flockfree_cyd_status_not_connected);
			return false;
		}
	}

	@Nullable
	private LocalSimulationFix getLocalSimulationFix(@Nullable MapActivity activity) {
		synchronized (lock) {
			if (lastPhoneLatitude != null && lastPhoneLongitude != null
					&& isValidCoordinate(lastPhoneLatitude, lastPhoneLongitude)) {
				return new LocalSimulationFix(lastPhoneLatitude, lastPhoneLongitude, lastPhoneAccuracy,
						lastPhoneLocationAtMs, "phone-local-test");
			}
		}
		if (activity != null && activity.getMapView() != null) {
			double latitude = activity.getMapView().getLatitude();
			double longitude = activity.getMapView().getLongitude();
			if (isValidCoordinate(latitude, longitude)) {
				return new LocalSimulationFix(latitude, longitude, null,
						System.currentTimeMillis(), "map-center-local-test");
			}
		}
		return null;
	}

	private void rememberLastKnownLocationIfAvailable() {
		Location location = app.getLocationProvider().getLastKnownLocation();
		if (location == null) {
			location = app.getLocationProvider().getLastStaleKnownLocation();
		}
		if (location != null && isValidGpsFix(location)) {
			rememberPhoneLocation(location);
		}
	}

	public void clearDetections() {
		synchronized (lock) {
			lastDetection = null;
			recentDetections = Collections.emptyList();
			if (lastPairStatus == null) {
				lastMessage = app.getString(R.string.flockfree_cyd_status_idle);
			}
		}
		clearPersistedDetections();
		refreshMap();
	}

	@SuppressLint("MissingPermission")
	public void disconnect() {
		synchronized (lock) {
			manualDisconnect = true;
			connectRetryAttempts = 0;
		}
		handler.removeCallbacks(connectRetryRunnable);
		stopScan();
		client.disconnect();
		synchronized (lock) {
			lastGpsSentAtMs = 0L;
		}
		setState(State.IDLE, app.getString(R.string.flockfree_cyd_status_idle));
	}

	@SuppressLint("MissingPermission")
	private void stopScan() {
		BluetoothLeScanner activeScanner;
		ScanCallback activeCallback;
		synchronized (lock) {
			activeScanner = scanner;
			activeCallback = scanCallback;
			scanner = null;
			scanCallback = null;
		}
		if (activeScanner != null && activeCallback != null) {
			try {
				activeScanner.stopScan(activeCallback);
			} catch (RuntimeException e) {
				LOG.warn("CYD BLE scan stop failed", e);
			}
		}
		handler.removeCallbacks(scanTimeoutRunnable);
	}

	private void handleScanTimeout() {
		if (getState() == State.SCANNING) {
			stopScan();
			setState(State.ERROR, app.getString(R.string.flockfree_cyd_status_not_found));
			app.showShortToastMessage(R.string.flockfree_cyd_status_not_found);
		}
	}

	private void retryScanAndConnect() {
		synchronized (lock) {
			if (manualDisconnect || state == State.READY) {
				return;
			}
		}
		startScanAndConnectWithGrantedPermissions(app, false);
	}

	private boolean scheduleConnectRetry(@NonNull State previousState) {
		if (manualDisconnect || previousState == State.IDLE || previousState == State.PERMISSION_NEEDED) {
			return false;
		}
		int attempt;
		synchronized (lock) {
			if (connectRetryAttempts >= MAX_CONNECT_RETRIES) {
				return false;
			}
			attempt = ++connectRetryAttempts;
			state = State.SCANNING;
			lastMessage = app.getString(R.string.flockfree_cyd_status_scanning)
					+ " (" + attempt + "/" + MAX_CONNECT_RETRIES + ")";
		}
		handler.removeCallbacks(connectRetryRunnable);
		handler.postDelayed(connectRetryRunnable, CONNECT_RETRY_DELAY_MS * attempt);
		return true;
	}

	@NonNull
	private List<ScanFilter> createScanFilters() {
		List<ScanFilter> filters = new ArrayList<>();
		filters.add(new ScanFilter.Builder()
				.setServiceUuid(new ParcelUuid(CydBleUartClient.UART_SERVICE_UUID))
				.build());
		filters.add(new ScanFilter.Builder()
				.setDeviceName(CydBleUartClient.DEFAULT_DEVICE_NAME_PREFIX)
				.build());
		return filters;
	}

	private ScanCallback createScanCallback(@NonNull Context context) {
		return new ScanCallback() {
			@Override
			public void onScanResult(int callbackType, ScanResult result) {
				handleScanResult(context, result);
			}

			@Override
			public void onBatchScanResults(List<ScanResult> results) {
				for (ScanResult result : results) {
					handleScanResult(context, result);
				}
			}

			@Override
			public void onScanFailed(int errorCode) {
				stopScan();
				setState(State.ERROR, app.getString(R.string.flockfree_cyd_status_scan_failed_code, errorCode));
				app.showShortToastMessage(R.string.flockfree_cyd_status_scan_failed);
			}
		};
	}

	@SuppressLint("MissingPermission")
	private void handleScanResult(@NonNull Context context, @Nullable ScanResult result) {
		if (result == null || getState() != State.SCANNING || !AndroidUtils.hasBLEPermission(context)) {
			return;
		}
		BluetoothDevice device = result.getDevice();
		if (device == null) {
			return;
		}
		ScanRecord record = result.getScanRecord();
		String name = device.getName();
		if (name == null && record != null) {
			name = record.getDeviceName();
		}
		if (!isLikelyCydResult(name, record)) {
			return;
		}
		stopScan();
		setState(State.CONNECTING, app.getString(R.string.flockfree_cyd_status_connecting,
				name != null ? name : CydBleUartClient.DEFAULT_DEVICE_NAME_PREFIX));
		client.connect(context, device);
	}

	private boolean isLikelyCydResult(@Nullable String name, @Nullable ScanRecord record) {
		if (CydBleUartClient.isLikelyCydDevice(name)) {
			return true;
		}
		if (record == null || record.getServiceUuids() == null) {
			return false;
		}
		for (ParcelUuid uuid : record.getServiceUuids()) {
			if (CydBleUartClient.UART_SERVICE_UUID.equals(uuid.getUuid())) {
				return true;
			}
		}
		return false;
	}

	private boolean isValidGpsFix(@NonNull Location location) {
		return isValidCoordinate(location.getLatitude(), location.getLongitude());
	}

	private boolean isValidCoordinate(double latitude, double longitude) {
		return !Double.isNaN(latitude) && !Double.isInfinite(latitude)
				&& !Double.isNaN(longitude) && !Double.isInfinite(longitude)
				&& latitude >= -90d && latitude <= 90d
				&& longitude >= -180d && longitude <= 180d;
	}

	@Nullable
	private String getPhoneGpsStatusSummary(long nowMs) {
		if (lastGpsSentAtMs > 0L) {
			long ageSeconds = Math.max(0L, (nowMs - lastGpsSentAtMs) / 1000L);
			return app.getString(R.string.flockfree_cyd_phone_gps_sent, ageSeconds);
		}
		if (lastPhoneLocationAtMs > 0L) {
			long ageSeconds = Math.max(0L, (nowMs - lastPhoneLocationAtMs) / 1000L);
			return app.getString(R.string.flockfree_cyd_phone_gps_ready, ageSeconds);
		}
		return state == State.READY ? app.getString(R.string.flockfree_cyd_phone_gps_waiting) : null;
	}

	private void setState(@NonNull State state, @Nullable String message) {
		synchronized (lock) {
			this.state = state;
			lastMessage = message;
		}
	}

	@SuppressLint("MissingPermission")
	@Override
	public void onCydConnecting(@NonNull BluetoothDevice device) {
		String name = AndroidUtils.hasBLEPermission(app) ? device.getName() : null;
		setState(State.CONNECTING, app.getString(R.string.flockfree_cyd_status_connecting,
				name != null ? name : CydBleUartClient.DEFAULT_DEVICE_NAME_PREFIX));
	}

	@Override
	public void onCydReady() {
		synchronized (lock) {
			lastGpsSentAtMs = 0L;
			connectRetryAttempts = 0;
			manualDisconnect = false;
		}
		setState(State.READY, app.getString(R.string.flockfree_cyd_status_ready));
		app.showShortToastMessage(R.string.flockfree_cyd_status_ready);
		// Notify plugin so it can pause WiFi scanning (CYD does full promiscuous scanning)
		notifyCydConnectionStateChanged();
	}

	@Override
	public void onCydDisconnected() {
		State previousState;
		synchronized (lock) {
			previousState = state;
		}
		if (scheduleConnectRetry(previousState)) {
			notifyCydConnectionStateChanged();
			return;
		}
		setState(State.IDLE, app.getString(R.string.flockfree_cyd_status_disconnected));
		// Notify plugin so it can resume WiFi scanning if enabled
		notifyCydConnectionStateChanged();
	}

	@Override
	public void onCydLine(@NonNull String line) {
		synchronized (lock) {
			lastMessage = line;
		}
	}

	@Override
	public void onCydPairStatus(@NonNull CydPairStatus status) {
		synchronized (lock) {
			lastPairStatus = status;
			lastMessage = status.getStatusSummary();
		}
	}

	@Override
	public void onCydDetection(@NonNull CydDetectionCandidate candidate) {
		CydDetectionCandidate enriched = enrichWithPhoneLocation(candidate);
		storeDetection(enriched, R.string.flockfree_cyd_detection_received);
	}

	/**
	 * If the CYD detection lacks GPS coordinates, enrich it with the phone's last known location.
	 * The CYD reports "GPS OK" based on its own GPS module, but may not include coordinates
	 * in every detection packet if the phone's GPS fix hasn't been forwarded yet or is stale.
	 */
	@NonNull
	private CydDetectionCandidate enrichWithPhoneLocation(@NonNull CydDetectionCandidate candidate) {
		if (candidate.hasGpsFix()) {
			return candidate;
		}
		synchronized (lock) {
			if (lastPhoneLatitude == null || lastPhoneLongitude == null
					|| !isValidCoordinate(lastPhoneLatitude, lastPhoneLongitude)) {
				return candidate;
			}
			long ageMs = System.currentTimeMillis() - lastPhoneLocationAtMs;
			if (ageMs > MAX_PHONE_LOCATION_AGE_MS) {
				return candidate;
			}
		}
		try {
			JSONObject json = new JSONObject(candidate.rawJson);
			JSONObject gps = json.optJSONObject("gps");
			if (gps == null) {
				gps = new JSONObject();
				json.put("gps", gps);
			}
			if (!gps.has("latitude")) {
				gps.put("latitude", lastPhoneLatitude);
			}
			if (!gps.has("longitude")) {
				gps.put("longitude", lastPhoneLongitude);
			}
			synchronized (lock) {
				if (lastPhoneAccuracy != null && !gps.has("accuracy")) {
					gps.put("accuracy", lastPhoneAccuracy);
				}
				if (!gps.has("age_ms")) {
					gps.put("age_ms", System.currentTimeMillis() - lastPhoneLocationAtMs);
				}
				if (!gps.has("source")) {
					gps.put("source", "phone");
				}
			}
			return CydDetectionCandidate.fromJson(json, candidate.receivedAtMs);
		} catch (Exception e) {
			return candidate;
		}
	}

	private void storeDetection(@NonNull CydDetectionCandidate candidate, int toastStringId) {
		synchronized (lock) {
			lastDetection = candidate;
			ArrayList<CydDetectionCandidate> updated = new ArrayList<>(recentDetections);
			updated.add(0, candidate);
			while (updated.size() > MAX_RECENT_DETECTIONS) {
				updated.remove(updated.size() - 1);
			}
			recentDetections = Collections.unmodifiableList(updated);
			lastMessage = candidate.getStatusSummary();
		}
		app.showShortToastMessage(toastStringId);
		persistRecentDetections();
		refreshMap();
	}

	@Override
	public void onCydError(@NonNull String message, @Nullable Throwable error) {
		setState(State.ERROR, message);
		app.showShortToastMessage(message);
	}

	@Override
	public void close() {
		disconnect();
		client.close();
	}

	private void refreshMap() {
		handler.post(() -> app.getOsmandMap().refreshMap());
	}

	@NonNull
	private File getDetectionStoreFile() {
		return new File(app.getFilesDir(), "flockfree-cyd-detections.json");
	}

	private void loadPersistedDetections() {
		try {
			List<CydDetectionCandidate> detections =
					CydDetectionStore.load(getDetectionStoreFile(), MAX_RECENT_DETECTIONS);
			if (!detections.isEmpty()) {
				synchronized (lock) {
					recentDetections = detections;
					lastDetection = detections.get(0);
					lastMessage = lastDetection.getStatusSummary();
				}
			}
		} catch (Exception e) {
			LOG.warn("Unable to load persisted CYD detections", e);
		}
	}

	private void persistRecentDetections() {
		List<CydDetectionCandidate> detections;
		synchronized (lock) {
			detections = new ArrayList<>(recentDetections);
		}
		try {
			CydDetectionStore.save(getDetectionStoreFile(), detections, MAX_RECENT_DETECTIONS);
		} catch (Exception e) {
			LOG.warn("Unable to persist CYD detections", e);
		}
	}

	private void clearPersistedDetections() {
		try {
			CydDetectionStore.clear(getDetectionStoreFile());
		} catch (Exception e) {
			LOG.warn("Unable to clear persisted CYD detections", e);
		}
	}

	public enum State {
		IDLE,
		PERMISSION_NEEDED,
		SCANNING,
		CONNECTING,
		READY,
		ERROR
	}

	private static final class LocalSimulationFix {
		private final double latitude;
		private final double longitude;
		@Nullable
		private final Float accuracyMeters;
		private final long locationAtMs;
		@NonNull
		private final String source;

		private LocalSimulationFix(double latitude, double longitude,
		                           @Nullable Float accuracyMeters, long locationAtMs,
		                           @NonNull String source) {
			this.latitude = latitude;
			this.longitude = longitude;
			this.accuracyMeters = accuracyMeters;
			this.locationAtMs = locationAtMs;
			this.source = source;
		}
	}
}
