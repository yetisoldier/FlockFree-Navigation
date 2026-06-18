package net.osmand.plus.plugins.flockfree.cyd;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import net.osmand.PlatformUtil;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.plugins.flockfree.FlockFreePlugin;

import org.apache.commons.logging.Log;

/**
 * Foreground service that keeps the CYD BLE connection alive when the app goes to background.
 * <p>
 * The service owns a {@link CydHardwareManager} instance and exposes it through a binder.
 * When CYD BLE is enabled in settings, {@link FlockFreePlugin} starts this service so that
 * GPS streaming to the CYD hardware and detection reception continue even when the map is
 * not in the foreground.
 * <p>
 * The foreground notification is low-priority and silent, simply indicating that FlockFree
 * hardware monitoring is active.
 */
public class CydBleService extends Service {

	private static final Log LOG = PlatformUtil.getLog(CydBleService.class);

	public static final String CHANNEL_ID = "flockfree_cyd_service";
	public static final int NOTIFICATION_ID = 200;
	private static final String EXTRA_ACTION = "action";
	private static final String ACTION_START = "start";
	private static final String ACTION_STOP = "stop";

	private final CydBleServiceBinder binder = new CydBleServiceBinder();
	@Nullable
	private OsmandApplication app;
	@Nullable
	private CydHardwareManager hardwareManager;
	private boolean started;

	public class CydBleServiceBinder extends Binder {
		@NonNull
		public CydBleService getService() {
			return CydBleService.this;
		}
	}

	@Override
	public void onCreate() {
		super.onCreate();
		app = (OsmandApplication) getApplication();
		createNotificationChannel();
	}

	@Override
	public int onStartCommand(@NonNull Intent intent, int flags, int startId) {
		String action = intent.getStringExtra(EXTRA_ACTION);
		if (ACTION_STOP.equals(action)) {
			stopSelf();
			return START_NOT_STICKY;
		}
		if (started) {
			return START_STICKY;
		}
		started = true;
		Notification notification = buildNotification();
		try {
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
				startForeground(NOTIFICATION_ID, notification,
						android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
			} else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				startForeground(NOTIFICATION_ID, notification,
						android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
			} else {
				startForeground(NOTIFICATION_ID, notification);
			}
		} catch (Exception e) {
			LOG.error("Failed to start CydBleService foreground", e);
			started = false;
			stopSelf();
			return START_NOT_STICKY;
		}
		maybeStartBackgroundScan();
		return START_STICKY;
	}

	@Nullable
	@Override
	public IBinder onBind(@NonNull Intent intent) {
		return binder;
	}

	@Override
	public void onDestroy() {
		stopForeground(STOP_FOREGROUND_REMOVE);
		started = false;
		hardwareManager = null;
		app = null;
		super.onDestroy();
	}

	@NonNull
	public CydHardwareManager getHardwareManager() {
		OsmandApplication currentApp = app;
		if (currentApp == null) {
			currentApp = (OsmandApplication) getApplication();
			app = currentApp;
		}
		if (hardwareManager == null && currentApp != null) {
			FlockFreePlugin plugin = PluginsHelper.getPlugin(FlockFreePlugin.class);
			if (plugin != null) {
				hardwareManager = plugin.getCydHardwareManager();
			} else {
				hardwareManager = new CydHardwareManager(currentApp);
			}
		}
		if (hardwareManager == null) {
			throw new IllegalStateException("CydBleService: unable to obtain CydHardwareManager");
		}
		return hardwareManager;
	}

	public boolean isStarted() {
		return started;
	}

	private void maybeStartBackgroundScan() {
		FlockFreePlugin plugin = PluginsHelper.getPlugin(FlockFreePlugin.class);
		if (plugin == null || !plugin.CYD_BLE_ENABLED.get()) {
			return;
		}
		CydHardwareManager manager = getHardwareManager();
		CydHardwareManager.State state = manager.getState();
		if (state == CydHardwareManager.State.IDLE) {
			manager.startScanAndConnectFromService(this);
		}
	}

	private Notification buildNotification() {
		OsmandApplication currentApp = app != null ? app : (OsmandApplication) getApplication();
		String title = currentApp != null
				? currentApp.getString(R.string.flockfree_cyd_service_notification_title)
				: "FlockFree CYD Active";
		String text = currentApp != null
				? currentApp.getString(R.string.flockfree_cyd_service_notification_text)
				: "Monitoring for CYD-Flock-You hardware";
		Intent contentIntent = new Intent(currentApp, MapActivity.class);
		contentIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
		PendingIntent pendingIntent = PendingIntent.getActivity(currentApp, 0, contentIntent,
				PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
		return new NotificationCompat.Builder(currentApp, CHANNEL_ID)
				.setContentTitle(title)
				.setContentText(text)
				.setSmallIcon(R.drawable.ic_action_bluetooth)
				.setOngoing(true)
				.setPriority(NotificationCompat.PRIORITY_LOW)
				.setCategory(NotificationCompat.CATEGORY_SERVICE)
				.setContentIntent(pendingIntent)
				.build();
	}

	private void createNotificationChannel() {
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			OsmandApplication currentApp = app != null ? app : (OsmandApplication) getApplication();
			if (currentApp == null) return;
			NotificationChannel channel = new NotificationChannel(
					CHANNEL_ID,
					currentApp.getString(R.string.flockfree_cyd_service_channel_name),
					NotificationManager.IMPORTANCE_LOW);
			channel.setDescription(currentApp.getString(R.string.flockfree_cyd_service_channel_desc));
			NotificationManager manager = (NotificationManager) currentApp
					.getSystemService(Context.NOTIFICATION_SERVICE);
			if (manager != null) {
				manager.createNotificationChannel(channel);
			}
		}
	}

	public static void start(@NonNull Context context) {
		Intent intent = new Intent(context, CydBleService.class);
		intent.putExtra(EXTRA_ACTION, ACTION_START);
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
			context.startForegroundService(intent);
		} else {
			context.startService(intent);
		}
	}

	public static void stop(@NonNull Context context) {
		Intent intent = new Intent(context, CydBleService.class);
		intent.putExtra(EXTRA_ACTION, ACTION_STOP);
		context.startService(intent);
	}
}
