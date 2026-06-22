package net.osmand.plus.plugins.flockfree.cyd;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.BluetoothStatusCodes;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.PlatformUtil;
import net.osmand.plus.utils.AndroidUtils;

import org.apache.commons.logging.Log;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Queue;
import java.util.UUID;

public final class CydBleUartClient implements AutoCloseable {

	private static final Log LOG = PlatformUtil.getLog(CydBleUartClient.class);

	public static final String DEFAULT_DEVICE_NAME_PREFIX = "CYD-Flock-You";
	public static final UUID UART_SERVICE_UUID =
			UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
	public static final UUID UART_RX_CHARACTERISTIC_UUID =
			UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
	public static final UUID UART_TX_CHARACTERISTIC_UUID =
			UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
	public static final UUID CLIENT_CHARACTERISTIC_CONFIG_UUID =
			UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

	private static final int REQUESTED_MTU = 247;
	private static final int DEFAULT_ATT_PAYLOAD_BYTES = 20;
	private static final long INITIAL_WRITE_DELAY_MS = 200L;

	private final Handler mainHandler = new Handler(Looper.getMainLooper());
	private final Object lock = new Object();
	private final CydMessageParser parser = new CydMessageParser();
	private final Queue<byte[]> writeQueue = new ArrayDeque<>();

	@Nullable
	private Listener listener;
	@Nullable
	private BluetoothGatt bluetoothGatt;
	@Nullable
	private BluetoothGattCharacteristic rxCharacteristic;
	private int maxPayloadBytes = DEFAULT_ATT_PAYLOAD_BYTES;
	private boolean writeInProgress;
	private boolean ready;
	private boolean closed = true;

	public CydBleUartClient(@Nullable Listener listener) {
		this.listener = listener;
	}

	public void setListener(@Nullable Listener listener) {
		this.listener = listener;
	}

	public boolean isReady() {
		synchronized (lock) {
			return ready;
		}
	}

	@SuppressLint("MissingPermission")
	public boolean connect(@NonNull Context context, @NonNull BluetoothDevice device) {
		if (!AndroidUtils.hasBLEPermission(context)) {
			emitError("Missing Bluetooth permission for CYD BLE connection", null);
			return false;
		}
		synchronized (lock) {
			if (!closed) {
				closeLocked(false);
			}
			closed = false;
			ready = false;
			parser.reset();
			writeQueue.clear();
			writeInProgress = false;
			maxPayloadBytes = DEFAULT_ATT_PAYLOAD_BYTES;
			rxCharacteristic = null;
			bluetoothGatt = device.connectGatt(context.getApplicationContext(), false, gattCallback,
					BluetoothDevice.TRANSPORT_LE);
			if (bluetoothGatt == null) {
				closed = true;
				emitError("Unable to create CYD BLE GATT connection", null);
				return false;
			}
		}
		emitConnecting(device);
		return true;
	}

	public boolean sendHello() {
		return sendLine("FYHELLO");
	}

	public boolean sendStatusRequest() {
		return sendLine("FYSTATUS");
	}

	public boolean sendSimulationRequest() {
		return sendLine("FYSIM");
	}

	public boolean sendGpsFix(double latitude, double longitude, float accuracyMeters,
	                          float speedMetersPerSecond, float courseDegrees,
	                          int satellites, float hdop, long unixTimeSeconds,
	                          int utcOffsetMinutes) {
		float speedKmph = speedMetersPerSecond * 3.6f;
		return sendLine(String.format(Locale.US,
				"FYGPS,%.6f,%.6f,%.1f,%.1f,%.1f,%d,%.1f,%d,%d",
				latitude, longitude, accuracyMeters, speedKmph, courseDegrees,
				satellites, hdop, unixTimeSeconds, utcOffsetMinutes));
	}

	public boolean sendGpsFix(double latitude, double longitude, float accuracyMeters,
	                          float speedMetersPerSecond, float courseDegrees,
	                          int satellites, float hdop) {
		float speedKmph = speedMetersPerSecond * 3.6f;
		return sendLine(String.format(Locale.US,
				"FYGPS,%.6f,%.6f,%.1f,%.1f,%.1f,%d,%.1f",
				latitude, longitude, accuracyMeters, speedKmph, courseDegrees,
				satellites, hdop));
	}

	public boolean sendLine(@NonNull String line) {
		String payload = line.endsWith("\n") ? line : line + "\n";
		return enqueueWrite(payload.getBytes(StandardCharsets.UTF_8));
	}

	private boolean enqueueWrite(@NonNull byte[] data) {
		synchronized (lock) {
			if (!ready || bluetoothGatt == null || rxCharacteristic == null || closed) {
				return false;
			}
			int chunkSize = DEFAULT_ATT_PAYLOAD_BYTES;
			for (int offset = 0; offset < data.length; offset += chunkSize) {
				int end = Math.min(data.length, offset + chunkSize);
				writeQueue.add(Arrays.copyOfRange(data, offset, end));
			}
		}
		writeNextChunk();
		return true;
	}

	@SuppressLint("MissingPermission")
	private void writeNextChunk() {
		BluetoothGatt gatt;
		BluetoothGattCharacteristic characteristic;
		byte[] chunk;
		synchronized (lock) {
			if (writeInProgress || closed) {
				return;
			}
			gatt = bluetoothGatt;
			characteristic = rxCharacteristic;
			chunk = writeQueue.poll();
			if (gatt == null || characteristic == null || chunk == null) {
				return;
			}
			writeInProgress = true;
		}

		int writeType = getSupportedWriteType(characteristic);
		boolean started;
		characteristic.setWriteType(writeType);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
			int result = gatt.writeCharacteristic(characteristic, chunk, writeType);
			started = result == BluetoothStatusCodes.SUCCESS;
		} else {
			characteristic.setValue(chunk);
			started = gatt.writeCharacteristic(characteristic);
		}
		if (!started) {
			synchronized (lock) {
				writeInProgress = false;
				writeQueue.clear();
			}
			emitError("CYD BLE write could not be started", null);
			close();
		}
	}

	private int getSupportedWriteType(@NonNull BluetoothGattCharacteristic characteristic) {
		int properties = characteristic.getProperties();
		if ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) {
			return BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
		}
		if ((properties & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
			return BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE;
		}
		return BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT;
	}

	@SuppressLint("MissingPermission")
	private void enableTxNotifications(@NonNull BluetoothGatt gatt,
	                                   @NonNull BluetoothGattCharacteristic txCharacteristic) {
		if (!gatt.setCharacteristicNotification(txCharacteristic, true)) {
			emitError("CYD BLE TX notification setup failed", null);
			close();
			return;
		}
		BluetoothGattDescriptor descriptor = txCharacteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID);
		if (descriptor == null) {
			emitError("CYD BLE TX characteristic is missing CCCD", null);
			close();
			return;
		}
		descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
		if (!gatt.writeDescriptor(descriptor)) {
			emitError("CYD BLE TX notification descriptor write failed", null);
			close();
		}
	}

	private void handleNotification(@Nullable byte[] data) {
		List<CydMessageParser.ParsedMessage> messages = parser.accept(data);
		for (CydMessageParser.ParsedMessage message : messages) {
			emitLine(message.rawLine);
			if (message.type == CydMessageParser.MessageType.PAIR_STATUS && message.pairStatus != null) {
				emitPairStatus(message.pairStatus);
			} else if (message.type == CydMessageParser.MessageType.DETECTION
					&& message.detectionCandidate != null) {
				emitDetection(message.detectionCandidate);
			}
		}
	}

	@SuppressLint("MissingPermission")
	public void disconnect() {
		BluetoothGatt gatt;
		synchronized (lock) {
			gatt = bluetoothGatt;
			ready = false;
			writeQueue.clear();
			writeInProgress = false;
			parser.reset();
		}
		if (gatt != null) {
			gatt.disconnect();
		} else {
			close();
		}
	}

	@SuppressLint("MissingPermission")
	@Override
	public void close() {
		synchronized (lock) {
			closeLocked(true);
		}
	}

	@SuppressLint("MissingPermission")
	private void closeLocked(boolean notify) {
		boolean wasClosed = closed;
		BluetoothGatt gatt = bluetoothGatt;
		bluetoothGatt = null;
		rxCharacteristic = null;
		ready = false;
		closed = true;
		writeInProgress = false;
		writeQueue.clear();
		parser.reset();
		if (gatt != null) {
			gatt.disconnect();
			gatt.close();
		}
		if (notify && (!wasClosed || gatt != null)) {
			emitDisconnected();
		}
	}

	private final BluetoothGattCallback gattCallback = new BluetoothGattCallback() {
		@SuppressLint("MissingPermission")
		@Override
		public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
			if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
				if (!gatt.discoverServices()) {
					emitError("CYD BLE service discovery could not be started", null);
					close();
				}
				return;
			}
			if (newState == BluetoothProfile.STATE_DISCONNECTED) {
				synchronized (lock) {
					closeLocked(true);
				}
			} else if (status != BluetoothGatt.GATT_SUCCESS) {
				emitError("CYD BLE connection state failed: " + status, null);
				synchronized (lock) {
					closeLocked(true);
				}
			}
		}

		@SuppressLint("MissingPermission")
		@Override
		public void onServicesDiscovered(BluetoothGatt gatt, int status) {
			if (status != BluetoothGatt.GATT_SUCCESS) {
				emitError("CYD BLE service discovery failed: " + status, null);
				close();
				return;
			}
			BluetoothGattService uartService = gatt.getService(UART_SERVICE_UUID);
			if (uartService == null) {
				emitError("CYD BLE UART service not found", null);
				close();
				return;
			}
			BluetoothGattCharacteristic rx = uartService.getCharacteristic(UART_RX_CHARACTERISTIC_UUID);
			BluetoothGattCharacteristic tx = uartService.getCharacteristic(UART_TX_CHARACTERISTIC_UUID);
			if (rx == null || tx == null) {
				emitError("CYD BLE UART characteristics not found", null);
				close();
				return;
			}
			synchronized (lock) {
				rxCharacteristic = rx;
			}
			gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
			if (!gatt.requestMtu(REQUESTED_MTU)) {
				enableTxNotifications(gatt, tx);
			}
		}

		@Override
		public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
			if (status == BluetoothGatt.GATT_SUCCESS) {
				synchronized (lock) {
					maxPayloadBytes = Math.max(DEFAULT_ATT_PAYLOAD_BYTES, mtu - 3);
				}
			}
			BluetoothGattService uartService = gatt.getService(UART_SERVICE_UUID);
			BluetoothGattCharacteristic tx = uartService != null
					? uartService.getCharacteristic(UART_TX_CHARACTERISTIC_UUID) : null;
			if (tx != null) {
				enableTxNotifications(gatt, tx);
			} else {
				emitError("CYD BLE UART TX characteristic not available after MTU request", null);
				close();
			}
		}

		@Override
		public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
			if (CLIENT_CHARACTERISTIC_CONFIG_UUID.equals(descriptor.getUuid())) {
				if (status == BluetoothGatt.GATT_SUCCESS) {
					synchronized (lock) {
						ready = true;
					}
					emitReady();
					mainHandler.postDelayed(CydBleUartClient.this::sendHello, INITIAL_WRITE_DELAY_MS);
				} else {
					emitError("CYD BLE notification descriptor write failed: " + status, null);
					close();
				}
			}
		}

		@Override
		public void onCharacteristicWrite(BluetoothGatt gatt,
		                                  BluetoothGattCharacteristic characteristic,
		                                  int status) {
			if (status != BluetoothGatt.GATT_SUCCESS) {
				emitError("CYD BLE characteristic write failed: " + status, null);
				synchronized (lock) {
					writeInProgress = false;
					writeQueue.clear();
				}
				close();
				return;
			}
			synchronized (lock) {
				writeInProgress = false;
			}
			writeNextChunk();
		}

		@Override
		public void onCharacteristicChanged(BluetoothGatt gatt,
		                                    BluetoothGattCharacteristic characteristic) {
			handleNotification(characteristic.getValue());
		}

		@Override
		public void onCharacteristicChanged(BluetoothGatt gatt,
		                                    BluetoothGattCharacteristic characteristic,
		                                    byte[] value) {
			handleNotification(value);
		}
	};

	private void emitConnecting(@NonNull BluetoothDevice device) {
		Listener current = listener;
		if (current != null) {
			mainHandler.post(() -> current.onCydConnecting(device));
		}
	}

	private void emitReady() {
		Listener current = listener;
		if (current != null) {
			mainHandler.post(current::onCydReady);
		}
	}

	private void emitDisconnected() {
		Listener current = listener;
		if (current != null) {
			mainHandler.post(current::onCydDisconnected);
		}
	}

	private void emitLine(@NonNull String line) {
		Listener current = listener;
		if (current != null) {
			mainHandler.post(() -> current.onCydLine(line));
		}
	}

	private void emitPairStatus(@NonNull CydPairStatus status) {
		Listener current = listener;
		if (current != null) {
			mainHandler.post(() -> current.onCydPairStatus(status));
		}
	}

	private void emitDetection(@NonNull CydDetectionCandidate candidate) {
		Listener current = listener;
		if (current != null) {
			mainHandler.post(() -> current.onCydDetection(candidate));
		}
	}

	private void emitError(@NonNull String message, @Nullable Throwable error) {
		if (error != null) {
			LOG.error(message, error);
		} else {
			LOG.error(message);
		}
		Listener current = listener;
		if (current != null) {
			mainHandler.post(() -> current.onCydError(message, error));
		}
	}

	public static boolean isLikelyCydDevice(@Nullable String name) {
		return name != null && name.startsWith(DEFAULT_DEVICE_NAME_PREFIX);
	}

	public interface Listener {
		default void onCydConnecting(@NonNull BluetoothDevice device) {
		}

		default void onCydReady() {
		}

		default void onCydDisconnected() {
		}

		default void onCydLine(@NonNull String line) {
		}

		default void onCydPairStatus(@NonNull CydPairStatus status) {
		}

		default void onCydDetection(@NonNull CydDetectionCandidate candidate) {
		}

		default void onCydError(@NonNull String message, @Nullable Throwable error) {
		}
	}
}
