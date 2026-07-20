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
 * SQLite-backed persistent storage for Flock camera data.
 * <p>
 * Adds a persistent database behind the in-memory spatial grid
 * that supports range queries by latitude/longitude bounding box.
 * The database is stored in app-private storage and survives app restarts
 * without needing to reload the full GeoJSON source snapshot.
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

	private static final int DATABASE_VERSION = 4;
	private static final String DATABASE_NAME = "flockfree_cameras.db";
	private static final String TABLE_NAME = "cameras";
	private static final String OSM_TABLE_NAME = "osm_cameras";
	private static final double MIN_LAT = -90d;
	private static final double MAX_LAT = 90d;
	private static final double MIN_LON = -180d;
	private static final double MAX_LON = 180d;

	private static final String COL_LAT = "lat";
	private static final String COL_LON = "lon";
	private static final String COL_OSM_ID = "osm_id";
	private static final String COL_OSM_TYPE = "osm_type";
	private static final String COL_MANUFACTURER = "manufacturer";
	private static final String COL_BRAND = "brand";
	private static final String COL_DIRECTION = "direction";
	private static final String COL_OPERATOR = "operator";
	private static final String COL_MOUNT_TYPE = "mount_type";
	private static final String COL_SURVEILLANCE_ZONE = "surveillance_zone";
	private static final String COL_OSM_TIMESTAMP = "osm_timestamp";
	private static final String FLOCK_SELECTION =
			"(LOWER(COALESCE(" + COL_MANUFACTURER + ", '')) LIKE '%flock%' OR " +
			"LOWER(COALESCE(" + COL_BRAND + ", '')) LIKE '%flock%' OR " +
			"LOWER(COALESCE(" + COL_OPERATOR + ", '')) LIKE '%flock%')";

	private static final String CREATE_TABLE_SQL =
			"CREATE TABLE " + TABLE_NAME + " (" +
			COL_LAT + " REAL NOT NULL, " +
			COL_LON + " REAL NOT NULL, " +
			COL_OSM_ID + " TEXT, " +
			COL_OSM_TYPE + " TEXT, " +
			COL_MANUFACTURER + " TEXT, " +
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
			"SELECT COUNT(*) FROM " + TABLE_NAME + " WHERE " + FLOCK_SELECTION;

	// OSM overlay table columns
	private static final String OSM_COL_ID = "id";
	private static final String OSM_COL_LAT = "lat";
	private static final String OSM_COL_LON = "lon";
	private static final String OSM_COL_BRAND = "brand";
	private static final String OSM_COL_OPERATOR = "operator";
	private static final String OSM_COL_MANUFACTURER = "manufacturer";
	private static final String OSM_COL_DIRECTION = "direction";
	private static final String OSM_COL_SOURCE = "source";
	private static final String OSM_COL_SOURCE_TIMESTAMP = "source_timestamp";

	private static final String CREATE_OSM_TABLE_SQL =
			"CREATE TABLE " + OSM_TABLE_NAME + " (" +
			OSM_COL_ID + " INTEGER PRIMARY KEY, " +
			OSM_COL_LAT + " REAL NOT NULL, " +
			OSM_COL_LON + " REAL NOT NULL, " +
			OSM_COL_BRAND + " TEXT, " +
			OSM_COL_OPERATOR + " TEXT, " +
			OSM_COL_MANUFACTURER + " TEXT, " +
			OSM_COL_DIRECTION + " TEXT, " +
			OSM_COL_SOURCE + " TEXT DEFAULT 'osm', " +
			OSM_COL_SOURCE_TIMESTAMP + " INTEGER, " +
			"UNIQUE(lat, lon));";

	private static final String CREATE_OSM_INDEX_SQL =
			"CREATE INDEX idx_osm_cameras_lat_lon ON " + OSM_TABLE_NAME +
			" (" + OSM_COL_LAT + ", " + OSM_COL_LON + ");";

	private static final String OSM_COUNT_SQL =
			"SELECT COUNT(*) FROM " + OSM_TABLE_NAME;

	public CameraDatabaseHelper(@NonNull Context context) {
		super(context, DATABASE_NAME, null, DATABASE_VERSION);
	}

	@Override
	public void onCreate(@NonNull SQLiteDatabase db) {
		db.execSQL(CREATE_TABLE_SQL);
		db.execSQL(CREATE_INDEX_SQL);
		db.execSQL(CREATE_OSM_TABLE_SQL);
		db.execSQL(CREATE_OSM_INDEX_SQL);
		LOG.info("Camera database created (v4 with OSM overlay)");
	}

	@Override
	public void onUpgrade(@NonNull SQLiteDatabase db, int oldVersion, int newVersion) {
		if (oldVersion < 3) {
			// v3: add manufacturer column for OSM canonical tag
			db.execSQL("ALTER TABLE " + TABLE_NAME + " ADD COLUMN " + COL_MANUFACTURER + " TEXT");
			LOG.info("Camera database upgraded: added manufacturer column");
		}
		if (oldVersion < 4) {
			// v4: add OSM overlay table for second data source
			db.execSQL(CREATE_OSM_TABLE_SQL);
			db.execSQL(CREATE_OSM_INDEX_SQL);
			LOG.info("Camera database upgraded: added osm_cameras overlay table");
		}
		LOG.info("Camera database upgraded from " + oldVersion + " to " + newVersion);
	}

	/**
	 * Replaces all camera data in the database with Flock-only rows.
	 * Uses a transaction for atomicity.
	 *
	 * @param cameras the full list of camera points to store
	 * @return true if the replace succeeded
	 */
	public boolean replaceAllCameras(@NonNull List<CameraData.CameraPoint> cameras) {
		SQLiteDatabase db = getWritableDatabase();
		db.beginTransaction();
		int inserted = 0;
		try {
			db.delete(TABLE_NAME, null, null);
			for (CameraData.CameraPoint cam : cameras) {
				if (!CameraData.isFlockCamera(cam)) {
					continue;
				}
				ContentValues values = new ContentValues(11);
				values.put(COL_LAT, cam.lat);
				values.put(COL_LON, cam.lon);
				putIfNotNull(values, COL_OSM_ID, cam.osmId);
				putIfNotNull(values, COL_OSM_TYPE, cam.osmType);
				putIfNotNull(values, COL_MANUFACTURER, cam.manufacturer);
				putIfNotNull(values, COL_BRAND, cam.brand);
				putIfNotNull(values, COL_DIRECTION, cam.direction);
				putIfNotNull(values, COL_OPERATOR, cam.operator);
				putIfNotNull(values, COL_MOUNT_TYPE, cam.mountType);
				putIfNotNull(values, COL_SURVEILLANCE_ZONE, cam.surveillanceZone);
				putIfNotNull(values, COL_OSM_TIMESTAMP, cam.osmTimestamp);
				db.insert(TABLE_NAME, null, values);
				inserted++;
			}
			db.setTransactionSuccessful();
			LOG.info("Replaced camera database with " + inserted + " Flock camera rows");
			return true;
		} catch (Exception e) {
			LOG.error("Failed to replace camera database", e);
			return false;
		} finally {
			db.endTransaction();
		}
	}

	/**
	 * Returns Flock cameras within the given bounding box.
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
		String selection = FLOCK_SELECTION + " AND " + COL_LAT + " >= ? AND " + COL_LAT + " <= ? AND "
				+ COL_LON + " >= ? AND " + COL_LON + " <= ?";
		String[] selectionArgs = {
				String.valueOf(bottom),
				String.valueOf(top),
				String.valueOf(left),
				String.valueOf(right)
		};
		try (Cursor cursor = db.query(TABLE_NAME, null, selection, selectionArgs,
				null, null, null)) {
			addCursorCamerasIfFlock(cursor, result);
		} catch (Exception e) {
			LOG.error("Failed to query cameras in bounding box", e);
		}
		return result;
	}

	/**
	 * Returns Flock cameras within the given radius of the given point.
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
	 * Returns all Flock cameras from the database for rebuilding in-memory route helpers.
	 *
	 * @return full list of camera points, or an empty list if loading fails
	 */
	@NonNull
	public List<CameraData.CameraPoint> getAllCameras() {
		List<CameraData.CameraPoint> result = new ArrayList<>();
		SQLiteDatabase db = getReadableDatabase();
		try (Cursor cursor = db.query(TABLE_NAME, null, FLOCK_SELECTION, null,
				null, null, null)) {
			addCursorCamerasIfFlock(cursor, result);
		} catch (Exception e) {
			LOG.error("Failed to load Flock cameras from database", e);
		}
		return result;
	}

	/**
	 * Returns the total number of Flock cameras in the database.
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
	 * Returns true if the database has any Flock camera data.
	 *
	 * @return true if the database contains at least one camera
	 */
	public boolean hasData() {
		SQLiteDatabase db = getReadableDatabase();
		try (Cursor cursor = db.rawQuery("SELECT EXISTS(SELECT 1 FROM " + TABLE_NAME
				+ " WHERE " + FLOCK_SELECTION + " LIMIT 1)", null)) {
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

	private static void addCursorCamerasIfFlock(@NonNull Cursor cursor,
	                                            @NonNull List<CameraData.CameraPoint> result) {
		while (cursor.moveToNext()) {
			CameraData.CameraPoint point = cursorToCameraPoint(cursor);
			if (CameraData.isFlockCamera(point)) {
				result.add(point);
			}
		}
	}

	@NonNull
	private static CameraData.CameraPoint cursorToCameraPoint(@NonNull Cursor cursor) {
		CameraData.CameraPoint point = new CameraData.CameraPoint();
		point.lat = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_LAT));
		point.lon = cursor.getDouble(cursor.getColumnIndexOrThrow(COL_LON));
		point.osmId = getStringOrNull(cursor, COL_OSM_ID);
		point.osmType = getStringOrNull(cursor, COL_OSM_TYPE);
		point.manufacturer = getStringOrNull(cursor, COL_MANUFACTURER);
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

	// ── OSM Overlay Table Methods ──

	/**
	 * Replaces all camera data in the OSM overlay table.
	 * Uses a transaction for atomicity.
	 *
	 * @param cameras the full list of OSM camera points to store
	 * @return true if the replace succeeded
	 */
	public boolean replaceAllOsmCameras(@NonNull List<CameraData.CameraPoint> cameras) {
		SQLiteDatabase db = getWritableDatabase();
		db.beginTransaction();
		int inserted = 0;
		try {
			db.delete(OSM_TABLE_NAME, null, null);
			long timestamp = System.currentTimeMillis();
			int idCounter = 0;
			for (CameraData.CameraPoint cam : cameras) {
				ContentValues values = new ContentValues(8);
				values.put(OSM_COL_ID, idCounter++);
				values.put(OSM_COL_LAT, cam.lat);
				values.put(OSM_COL_LON, cam.lon);
				putIfNotNull(values, OSM_COL_BRAND, cam.brand);
				putIfNotNull(values, OSM_COL_OPERATOR, cam.operator);
				putIfNotNull(values, OSM_COL_MANUFACTURER, cam.manufacturer);
				putIfNotNull(values, OSM_COL_DIRECTION, cam.direction);
				values.put(OSM_COL_SOURCE, "osm");
				values.put(OSM_COL_SOURCE_TIMESTAMP, timestamp);
				db.insert(OSM_TABLE_NAME, null, values);
				inserted++;
			}
			db.setTransactionSuccessful();
			LOG.info("Replaced OSM overlay database with " + inserted + " camera rows");
			return true;
		} catch (Exception e) {
			LOG.error("Failed to replace OSM overlay database", e);
			return false;
		} finally {
			db.endTransaction();
		}
	}

	/**
	 * Returns OSM overlay cameras within the given bounding box.
	 *
	 * @param top    northern latitude boundary
	 * @param left   western longitude boundary
	 * @param bottom southern latitude boundary
	 * @param right  eastern longitude boundary
	 * @return list of OSM camera points in the bounding box
	 */
	@NonNull
	public List<CameraData.CameraPoint> queryOsmCamerasInBounds(
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
			List<CameraData.CameraPoint> west = queryOsmCamerasInBoundingBox(top, left, bottom, MAX_LON);
			List<CameraData.CameraPoint> east = queryOsmCamerasInBoundingBox(top, MIN_LON, bottom, right);
			List<CameraData.CameraPoint> result = new ArrayList<>(west.size() + east.size());
			result.addAll(west);
			result.addAll(east);
			return result;
		}

		return queryOsmCamerasInBoundingBox(top, left, bottom, right);
	}

	@NonNull
	private List<CameraData.CameraPoint> queryOsmCamerasInBoundingBox(
			double top, double left, double bottom, double right) {
		List<CameraData.CameraPoint> result = new ArrayList<>();
		SQLiteDatabase db = getReadableDatabase();
		String selection = OSM_COL_LAT + " >= ? AND " + OSM_COL_LAT + " <= ? AND "
				+ OSM_COL_LON + " >= ? AND " + OSM_COL_LON + " <= ?";
		String[] selectionArgs = {
				String.valueOf(bottom),
				String.valueOf(top),
				String.valueOf(left),
				String.valueOf(right)
		};
		try (Cursor cursor = db.query(OSM_TABLE_NAME, null, selection, selectionArgs,
				null, null, null)) {
			while (cursor.moveToNext()) {
				result.add(osmCursorToCameraPoint(cursor));
			}
		} catch (Exception e) {
			LOG.error("Failed to query OSM cameras in bounding box", e);
		}
		return result;
	}

	/**
	 * Returns the total number of OSM overlay cameras in the database.
	 *
	 * @return OSM camera count, or 0 if the query fails
	 */
	public int getOsmCameraCount() {
		SQLiteDatabase db = getReadableDatabase();
		try (Cursor cursor = db.rawQuery(OSM_COUNT_SQL, null)) {
			if (cursor.moveToFirst()) {
				return cursor.getInt(0);
			}
		} catch (Exception e) {
			LOG.error("Failed to count OSM cameras", e);
		}
		return 0;
	}

	/**
	 * Returns true if the OSM overlay table has any camera data.
	 *
	 * @return true if the OSM overlay table contains at least one camera
	 */
	public boolean hasOsmData() {
		SQLiteDatabase db = getReadableDatabase();
		try (Cursor cursor = db.rawQuery("SELECT EXISTS(SELECT 1 FROM " + OSM_TABLE_NAME + " LIMIT 1)", null)) {
			return cursor.moveToFirst() && cursor.getInt(0) == 1;
		} catch (Exception e) {
			LOG.error("Failed to check OSM camera database for data", e);
			return false;
		}
	}

	@NonNull
	private static CameraData.CameraPoint osmCursorToCameraPoint(@NonNull Cursor cursor) {
		CameraData.CameraPoint point = new CameraData.CameraPoint();
		point.lat = cursor.getDouble(cursor.getColumnIndexOrThrow(OSM_COL_LAT));
		point.lon = cursor.getDouble(cursor.getColumnIndexOrThrow(OSM_COL_LON));
		point.brand = getStringOrNull(cursor, OSM_COL_BRAND);
		point.operator = getStringOrNull(cursor, OSM_COL_OPERATOR);
		point.manufacturer = getStringOrNull(cursor, OSM_COL_MANUFACTURER);
		point.direction = getStringOrNull(cursor, OSM_COL_DIRECTION);
		return point;
	}
}
