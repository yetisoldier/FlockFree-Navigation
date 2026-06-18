package net.osmand.plus.plugins.flockfree;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.PlatformUtil;

import org.apache.commons.logging.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * SQLite-backed persistent storage for ALPR camera data.
 * <p>
 * Adds a persistent database behind the in-memory spatial grid
 * that supports range queries by latitude/longitude bounding box.
 * The database is stored in app-private storage and survives app restarts
 * without needing to reload the full 104K-camera GeoJSON.
 * <p>
 * Schema:
 * <pre>
 * CREATE TABLE cameras (
 *   lat      REAL NOT NULL,
 *   lon      REAL NOT NULL,
 *   osm_id   TEXT,
 *   osm_type TEXT,
 *   brand    TEXT,
 *   direction TEXT,
 *   operator TEXT,
 *   mount_type TEXT,
 *   surveillance_zone TEXT,
 *   osm_timestamp TEXT
 * );
 * CREATE INDEX idx_cameras_lat_lon ON cameras(lat, lon);
 * </pre>
 */
public class CameraDatabaseHelper extends SQLiteOpenHelper {

	private static final Log LOG = PlatformUtil.getLog(CameraDatabaseHelper.class);

	private static final int DATABASE_VERSION = 1;
	private static final String DATABASE_NAME = "flockfree_cameras.db";
	private static final String TABLE_NAME = "cameras";
	private static final double MIN_LAT = -90d;
	private static final double MAX_LAT = 90d;
	private static final double MIN_LON = -180d;
	private static final double MAX_LON = 180d;

	private static final String COL_LAT = "lat";
	private static final String COL_LON = "lon";
	private static final String COL_OSM_ID = "osm_id";
	private static final String COL_OSM_TYPE = "osm_type";
	private static final String COL_BRAND = "brand";
	private static final String COL_DIRECTION = "direction";
	private static final String COL_OPERATOR = "operator";
	private static final String COL_MOUNT_TYPE = "mount_type";
	private static final String COL_SURVEILLANCE_ZONE = "surveillance_zone";
	private static final String COL_OSM_TIMESTAMP = "osm_timestamp";

	private static final String CREATE_TABLE_SQL =
			"CREATE TABLE " + TABLE_NAME + " (" +
			COL_LAT + " REAL NOT NULL, " +
			COL_LON + " REAL NOT NULL, " +
			COL_OSM_ID + " TEXT, " +
			COL_OSM_TYPE + " TEXT, " +
			COL_BRAND + " TEXT, " +
			COL_DIRECTION + " TEXT, " +
			COL_OPERATOR + " TEXT, " +
			COL_MOUNT_TYPE + " TEXT, " +
			COL_SURVEILLANCE_ZONE + " TEXT, " +
			COL_OSM_TIMESTAMP + " TEXT);";

	private static final String CREATE_INDEX_SQL =
			"CREATE INDEX idx_cameras_lat_lon ON " + TABLE_NAME +
			" (" + COL_LAT + ", " + COL_LON + ");";

	private static final String COUNT_SQL =
			"SELECT COUNT(*) FROM " + TABLE_NAME;

	public CameraDatabaseHelper(@NonNull Context context) {
		super(context, DATABASE_NAME, null, DATABASE_VERSION);
	}

	@Override
	public void onCreate(@NonNull SQLiteDatabase db) {
		db.execSQL(CREATE_TABLE_SQL);
		db.execSQL(CREATE_INDEX_SQL);
		LOG.info("Camera database created");
	}

	@Override
	public void onUpgrade(@NonNull SQLiteDatabase db, int oldVersion, int newVersion) {
		db.execSQL("DROP TABLE IF EXISTS " + TABLE_NAME);
		onCreate(db);
		LOG.info("Camera database upgraded from " + oldVersion + " to " + newVersion);
	}

	/**
	 * Replaces all camera data in the database with the given list.
	 * Uses a transaction for atomicity.
	 *
	 * @param cameras the full list of camera points to store
	 * @return true if the replace succeeded
	 */
	public boolean replaceAllCameras(@NonNull List<CameraData.CameraPoint> cameras) {
		SQLiteDatabase db = getWritableDatabase();
		db.beginTransaction();
		try {
			db.delete(TABLE_NAME, null, null);
			for (CameraData.CameraPoint cam : cameras) {
				ContentValues values = new ContentValues(10);
				values.put(COL_LAT, cam.lat);
				values.put(COL_LON, cam.lon);
				putIfNotNull(values, COL_OSM_ID, cam.osmId);
				putIfNotNull(values, COL_OSM_TYPE, cam.osmType);
				putIfNotNull(values, COL_BRAND, cam.brand);
				putIfNotNull(values, COL_DIRECTION, cam.direction);
				putIfNotNull(values, COL_OPERATOR, cam.operator);
				putIfNotNull(values, COL_MOUNT_TYPE, cam.mountType);
				putIfNotNull(values, COL_SURVEILLANCE_ZONE, cam.surveillanceZone);
				putIfNotNull(values, COL_OSM_TIMESTAMP, cam.osmTimestamp);
				db.insert(TABLE_NAME, null, values);
			}
			db.setTransactionSuccessful();
			LOG.info("Replaced camera database with " + cameras.size() + " rows");
			return true;
		} catch (Exception e) {
			LOG.error("Failed to replace camera database", e);
			return false;
		} finally {
			db.endTransaction();
		}
	}

	/**
	 * Returns cameras within the given bounding box.
	 * Uses the lat/lon index for efficient range queries.
	 *
	 * @param top    northern latitude boundary
	 * @param left   western longitude boundary
	 * @param bottom southern latitude boundary
	 * @param right  eastern longitude boundary
	 * @return list of camera points in the bounding box
	 */
	@NonNull
	public List<CameraData.CameraPoint> getCamerasInBoundingBox(
			double top, double left, double bottom, double right) {
		if (top < bottom) {
			double temp = top;
			top = bottom;
			bottom = temp;
		}
		top = clamp(top, MIN_LAT, MAX_LAT);
		bottom = clamp(bottom, MIN_LAT, MAX_LAT);
		left = clamp(left, MIN_LON, MAX_LON);
		right = clamp(right, MIN_LON, MAX_LON);

		if (left > right) {
			List<CameraData.CameraPoint> west = queryCamerasInBoundingBox(top, left, bottom, MAX_LON);
			List<CameraData.CameraPoint> east = queryCamerasInBoundingBox(top, MIN_LON, bottom, right);
			List<CameraData.CameraPoint> result = new ArrayList<>(west.size() + east.size());
			result.addAll(west);
			result.addAll(east);
			return result;
		}

		return queryCamerasInBoundingBox(top, left, bottom, right);
	}

	@NonNull
	private List<CameraData.CameraPoint> queryCamerasInBoundingBox(
			double top, double left, double bottom, double right) {
		List<CameraData.CameraPoint> result = new ArrayList<>();
		SQLiteDatabase db = getReadableDatabase();
		String selection = COL_LAT + " >= ? AND " + COL_LAT + " <= ? AND "
				+ COL_LON + " >= ? AND " + COL_LON + " <= ?";
		String[] selectionArgs = {
				String.valueOf(bottom),
				String.valueOf(top),
				String.valueOf(left),
				String.valueOf(right)
		};
		try (Cursor cursor = db.query(TABLE_NAME, null, selection, selectionArgs,
				null, null, null)) {
			while (cursor.moveToNext()) {
				result.add(cursorToCameraPoint(cursor));
			}
		} catch (Exception e) {
			LOG.error("Failed to query cameras in bounding box", e);
		}
		return result;
	}

	/**
	 * Returns cameras within the given radius of the given point.
	 * Uses a bounding-box pre-filter then precise distance filtering.
	 *
	 * @param lat          center latitude
	 * @param lon          center longitude
	 * @param radiusMeters search radius in meters
	 * @return list of cameras within the radius
	 */
	@NonNull
	public List<CameraData.CameraPoint> getCamerasNear(double lat, double lon, double radiusMeters) {
		double latitudeDelta = radiusMeters / 111_000d;
		double longitudeScale = Math.max(0.01d, Math.cos(Math.toRadians(lat)));
		double longitudeDelta = radiusMeters / (111_000d * longitudeScale);
		double top = Math.min(90d, lat + latitudeDelta);
		double bottom = Math.max(-90d, lat - latitudeDelta);
		double left = lon - longitudeDelta;
		double right = lon + longitudeDelta;

		List<CameraData.CameraPoint> candidates;
		if (left < -180d) {
			List<CameraData.CameraPoint> west = getCamerasInBoundingBox(top, left + 360d, bottom, 180d);
			List<CameraData.CameraPoint> east = getCamerasInBoundingBox(top, -180d, bottom, right);
			candidates = new ArrayList<>(west.size() + east.size());
			candidates.addAll(west);
			candidates.addAll(east);
		} else if (right > 180d) {
			List<CameraData.CameraPoint> west = getCamerasInBoundingBox(top, left, bottom, 180d);
			List<CameraData.CameraPoint> east = getCamerasInBoundingBox(top, -180d, bottom, right - 360d);
			candidates = new ArrayList<>(west.size() + east.size());
			candidates.addAll(west);
			candidates.addAll(east);
		} else {
			candidates = getCamerasInBoundingBox(top, left, bottom, right);
		}

		List<CameraData.CameraPoint> result = new ArrayList<>();
		for (CameraData.CameraPoint cam : candidates) {
			double dist = net.osmand.util.MapUtils.getDistance(cam.lat, cam.lon, lat, lon);
			if (dist <= radiusMeters) {
				result.add(cam);
			}
		}
		return result;
	}

	/**
	 * Returns all cameras from the database for rebuilding in-memory route helpers.
	 *
	 * @return full list of camera points, or an empty list if loading fails
	 */
	@NonNull
	public List<CameraData.CameraPoint> getAllCameras() {
		List<CameraData.CameraPoint> result = new ArrayList<>();
		SQLiteDatabase db = getReadableDatabase();
		try (Cursor cursor = db.query(TABLE_NAME, null, null, null,
				null, null, null)) {
			while (cursor.moveToNext()) {
				result.add(cursorToCameraPoint(cursor));
			}
		} catch (Exception e) {
			LOG.error("Failed to load all cameras from database", e);
		}
		return result;
	}

	/**
	 * Returns the total number of cameras in the database.
	 *
	 * @return camera count, or 0 if the query fails
	 */
	public int getCameraCount() {
		SQLiteDatabase db = getReadableDatabase();
		try (Cursor cursor = db.rawQuery(COUNT_SQL, null)) {
			if (cursor.moveToFirst()) {
				return cursor.getInt(0);
			}
		} catch (Exception e) {
			LOG.error("Failed to count cameras", e);
		}
		return 0;
	}

	/**
	 * Returns true if the database has any camera data.
	 *
	 * @return true if the database contains at least one camera
	 */
	public boolean hasData() {
		SQLiteDatabase db = getReadableDatabase();
		try (Cursor cursor = db.rawQuery("SELECT EXISTS(SELECT 1 FROM " + TABLE_NAME + " LIMIT 1)", null)) {
			return cursor.moveToFirst() && cursor.getInt(0) == 1;
		} catch (Exception e) {
			LOG.error("Failed to check camera database for data", e);
			return false;
		}
	}

	/**
	 * Deletes all camera data from the database.
	 */
	public void clearAll() {
		SQLiteDatabase db = getWritableDatabase();
		db.beginTransaction();
		try {
			db.delete(TABLE_NAME, null, null);
			db.setTransactionSuccessful();
		} finally {
			db.endTransaction();
		}
	}

	@NonNull
	private static CameraData.CameraPoint cursorToCameraPoint(@NonNull Cursor cursor) {
		CameraData.CameraPoint point = new CameraData.CameraPoint();
		point.lat = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_LAT));
		point.lon = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_LON));
		point.osmId = getStringOrNull(cursor, COL_OSM_ID);
		point.osmType = getStringOrNull(cursor, COL_OSM_TYPE);
		point.brand = getStringOrNull(cursor, COL_BRAND);
		point.direction = getStringOrNull(cursor, COL_DIRECTION);
		point.operator = getStringOrNull(cursor, COL_OPERATOR);
		point.mountType = getStringOrNull(cursor, COL_MOUNT_TYPE);
		point.surveillanceZone = getStringOrNull(cursor, COL_SURVEILLANCE_ZONE);
		point.osmTimestamp = getStringOrNull(cursor, COL_OSM_TIMESTAMP);
		return point;
	}

	@Nullable
	private static String getStringOrNull(@NonNull Cursor cursor, @NonNull String columnName) {
		int index = cursor.getColumnIndex(columnName);
		if (index < 0 || cursor.isNull(index)) {
			return null;
		}
		return cursor.getString(index);
	}

	private static void putIfNotNull(@NonNull ContentValues values, @NonNull String key, @Nullable String value) {
		if (value != null) {
			values.put(key, value);
		} else {
			values.putNull(key);
		}
	}

	private static double clamp(double value, double min, double max) {
		return Math.max(min, Math.min(max, value));
	}
}
