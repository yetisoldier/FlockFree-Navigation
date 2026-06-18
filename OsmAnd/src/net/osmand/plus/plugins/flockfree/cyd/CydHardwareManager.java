package net.osmand.plus.plugins.flockfree.cyd;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanRecord;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.PlatformUtil;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.utils.AndroidUtils;
import net.osmand.util.Algorithms;

import org.apache.commons.logging.Log;

import java.util.List;

public final class CydHardwareManager implements AutoCloseable, CydBleUartClient.Listener {

	private static final Log LOG = PlatformUtil.getLog(CydHardwareManager.class);
	private static final long SCAN_TIMEOUT_MS = 15_000L;

	private final OsmandApplication app;
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final CydBleUartClient client = new CydBleUartClient(this);
	private final Object lock = new Object();

	@Nullable
	private BluetoothLeScanner scanner;
	@Nullable
	private ScanCallback scanCallback;
	@Nullable
	private CydPairStatus lastPairStatus;
	@Nullable
	private CydDetectionCandidate lastDetection;
	@NonNull
	private State state = State.IDLE;
	@Nullable
	private String lastMessage;

	public CydHardwareManager(@NonNull OsmandApplication app) {
		this.app = app;
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
	public String getStatusSummary() {
		synchronized (lock) {
			if (lastPairStatus != null) {
				return lastPairStatus.getStatusSummary();
			}
			if (!Algorithms.isEmpty(lastMessage)) {
				return lastMessage;
			}
			return app.getString(R.string.flockfree_cyd_status_idle);
		}
	}

	public boolean startScanAndConnect(@NonNull MapActivity activity) {
		if (!AndroidUtils.requestBLEPermissions(activity)) {
			setState(State.PERMISSION_NEEDED, app.getString(R.string.flockfree_cyd_status_permission_requested));
			app.showShortToastMessage(R.string.flockfree_cyd_status_permission_requested);
			return false;
		}
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
		client.close();
		synchronized (lock) {
			lastPairStatus = null;
			lastDetection = null;
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
		ScanCallback callback = createScanCallback(activity);
		synchronized (lock) {
			scanCallback = callback;
		}
		try {
			bluetoothLeScanner.startScan(null, settings, callback);
			handler.postDelayed(this::handleScanTimeout, SCAN_TIMEOUT_MS);
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

	public boolean simulateDetection() {
		if (client.isReady()) {
			return client.sendSimulationRequest();
		}
		app.showShortToastMessage(R.string.flockfree_cyd_status_not_connected);
		return false;
	}

	@SuppressLint("MissingPermission")
	public void disconnect() {
		stopScan();
		client.disconnect();
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
	}

	private void handleScanTimeout() {
		if (getState() == State.SCANNING) {
			stopScan();
			setState(State.ERROR, app.getString(R.string.flockfree_cyd_status_not_found));
			app.showShortToastMessage(R.string.flockfree_cyd_status_not_found);
		}
	}

	private ScanCallback createScanCallback(@NonNull MapActivity activity) {
		return new ScanCallback() {
			@Override
			public void onScanResult(int callbackType, ScanResult result) {
				handleScanResult(activity, result);
			}

			@Override
			public void onBatchScanResults(List<ScanResult> results) {
				for (ScanResult result : results) {
					handleScanResult(activity, result);
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
	private void handleScanResult(@NonNull MapActivity activity, @Nullable ScanResult result) {
		if (result == null || getState() != State.SCANNING || !AndroidUtils.hasBLEPermission(activity)) {
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
		client.connect(activity, device);
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
		setState(State.READY, app.getString(R.string.flockfree_cyd_status_ready));
		app.showShortToastMessage(R.string.flockfree_cyd_status_ready);
		client.sendStatusRequest();
	}

	@Override
	public void onCydDisconnected() {
		setState(State.IDLE, app.getString(R.string.flockfree_cyd_status_disconnected));
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
		synchronized (lock) {
			lastDetection = candidate;
			lastMessage = candidate.getStatusSummary();
		}
		app.showShortToastMessage(R.string.flockfree_cyd_detection_received);
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

	public enum State {
		IDLE,
		PERMISSION_NEEDED,
		SCANNING,
		CONNECTING,
		READY,
		ERROR
	}
}
