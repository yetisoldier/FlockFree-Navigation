package net.osmand.plus.poi;

import net.osmand.PlatformUtil;
import net.osmand.ResultMatcher;
import net.osmand.binary.ObfConstants;
import net.osmand.data.Amenity;
import net.osmand.osm.MapPoiTypes;
import net.osmand.osm.PoiType;
import net.osmand.osm.edit.Entity.EntityType;
import net.osmand.osm.io.NetworkUtils;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.Version;
import net.osmand.plus.poi.PoiFilterUtils.AmenityNameFilter;
import net.osmand.util.Algorithms;
import net.osmand.util.MapUtils;
import net.osmand.util.TransliterationHelper;

import org.apache.commons.logging.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;


public class NominatimPoiFilter extends PoiUIFilter {

	private static final Log log = PlatformUtil.getLog(NominatimPoiFilter.class);

	private static final String FILTER_ID = "name_finder";
	private static final String NOMINATIM_API = "https://nominatim.openstreetmap.org/search";
	private static final String CENSUS_ONELINE_ADDRESS_API =
			"https://geocoding.geo.census.gov/geocoder/locations/onelineaddress";
	private static final int MIN_SEARCH_DISTANCE_ON_MAP = 20000;
	private static final int LIMIT = 300;
	private static final String[] US_STREET_ADDRESS_HINTS = {
			" st ", " street ", " ave ", " avenue ", " rd ", " road ", " dr ", " drive ",
			" blvd ", " boulevard ", " ln ", " lane ", " ct ", " court ", " pl ", " place ",
			" ter ", " terrace ", " way ", " hwy ", " highway ", " pkwy ", " parkway ",
			" cir ", " circle ", " trl ", " trail "
	};

	private String lastError = "";
	private final boolean bboxSearch;
	
	public NominatimPoiFilter(OsmandApplication application, boolean noBbox) {
		super(application);
		this.bboxSearch = !noBbox;
		this.name = app.getString(R.string.poi_filter_nominatim);
		if (!bboxSearch) {
			this.name += " - " + app.getString(R.string.shared_string_address);
			this.distanceToSearchValues = new double[] {500, 10000};
			this.filterId = FILTER_ID + "_address";
		} else {
			this.name += " - " + app.getString(R.string.shared_string_places);
			this.distanceToSearchValues = new double[] {1, 2, 5, 10, 20, 100, 500, 10000};
			this.filterId = FILTER_ID + "_places";
		}
	}
	

	@Override
	public boolean isAutomaticallyIncreaseSearch() {
		return false;
	}
	
	// do nothing test jackdaw lane, oxford"
	@Override
	public AmenityNameFilter getNameFilter() {
		return a -> true;
	}
	
	@Override
	protected List<Amenity> searchAmenitiesInternal(double lat, double lon, double topLatitude,
	                                                double bottomLatitude, double leftLongitude,
	                                                double rightLongitude, int zoom,
	                                                ResultMatcher<Amenity> matcher) {
		currentSearchResult = new ArrayList<>();
		if (Algorithms.isEmpty(getFilterByName())) {
			return currentSearchResult;
		}
		if (!bboxSearch && searchCensusAddress(matcher)) {
			MapUtils.sortListOfMapObject(currentSearchResult, lat, lon);
			return currentSearchResult;
		}

		double baseDistY = MapUtils.getDistance(lat, lon, lat - 1, lon);
		double baseDistX = MapUtils.getDistance(lat, lon, lat, lon - 1);
		double distance = MIN_SEARCH_DISTANCE_ON_MAP;
		topLatitude = Math.max(topLatitude, Math.min(lat + (distance / baseDistY), 84.));
		bottomLatitude = Math.min(bottomLatitude, Math.max(lat - (distance / baseDistY), -84.));
		leftLongitude = Math.min(leftLongitude, Math.max(lon - (distance / baseDistX), -180));
		rightLongitude = Math.max(rightLongitude, Math.min(lon + (distance / baseDistX), 180));

		String viewbox = "viewboxlbrt=" + ((float) leftLongitude) + "," + ((float) bottomLatitude)
				+ "," + ((float) rightLongitude) + "," + ((float) topLatitude);
		try {
			lastError = "";
			String urlq = NOMINATIM_API + "?format=xml" +
					"&accept-language=" + Locale.getDefault().getLanguage() +
					"&q=" + URLEncoder.encode(getFilterByName()) +
					"&extratags=1" +
					"&addressdetails=1" + // nclude a breakdown of the address into elements
					"&limit=" + LIMIT;
			if (bboxSearch) {
				urlq += "&bounded=1&" + viewbox;
			}
			log.info("Online search: " + urlq);
			URLConnection connection = NetworkUtils.getHttpURLConnection(urlq); //$NON-NLS-1$
			connection.setRequestProperty("User-Agent", Version.getFullVersion(app));

			InputStream stream = connection.getInputStream();
			XmlPullParser parser = PlatformUtil.newXMLPullParser();
			parser.setInput(stream, "UTF-8"); //$NON-NLS-1$
			int eventType;
			int namedDepth = 0;
			Amenity a = null;
			boolean extratags = false;
			MapPoiTypes poiTypes = ((OsmandApplication) getApplication()).getPoiTypes();
			while ((eventType = parser.next()) != XmlPullParser.END_DOCUMENT) {
				if (eventType == XmlPullParser.START_TAG) {
					if (parser.getName().equals("searchresults")) { //$NON-NLS-1$
						String err = parser.getAttributeValue("", "error"); //$NON-NLS-1$ //$NON-NLS-2$
						if (err != null && err.length() > 0) {
							lastError = err;
							stream.close();
							break;
						}
					}
					if (parser.getName().equals("place")) { //$NON-NLS-1$
						namedDepth++;
						if (namedDepth == 1) {
							try {
								a = new Amenity();
								a.setLocation(Double.parseDouble(parser.getAttributeValue("", "lat")), //$NON-NLS-1$//$NON-NLS-2$
										Double.parseDouble(parser.getAttributeValue("", "lon"))); //$NON-NLS-1$//$NON-NLS-2$
								long osmId = Long.parseLong(parser.getAttributeValue("", "osm_id"));
								EntityType osmType = EntityType.valueOf(parser.getAttributeValue("", "osm_type").toUpperCase());
								long id = ObfConstants.createMapObjectIdFromCleanOsmId(osmId, osmType);
								a.setId(id);
								String name = parser.getAttributeValue("", "display_name"); //$NON-NLS-1$//$NON-NLS-2$
								a.setName(name);
								a.setEnName(TransliterationHelper.transliterate(name));
								a.setSubType(parser.getAttributeValue("", "type")); //$NON-NLS-1$//$NON-NLS-2$
								PoiType pt = poiTypes.getPoiTypeByKey(a.getSubType());
								a.setType(pt != null ? pt.getCategory() : poiTypes.getOtherPoiCategory());
								if (matcher == null || matcher.publish(a)) {
									currentSearchResult.add(a);
								}
							} catch (NumberFormatException e) {
								log.info("Invalid attributes", e); //$NON-NLS-1$
							}
						}
					}
					if (extratags && a != null) {
						String tag = parser.getAttributeValue("", "key");
						String val = parser.getAttributeValue("", "value");
						a.setAdditionalInfo(tag, val);
					}
					if (parser.getName().equals("extratags")) {
						extratags = true;
					}
					if (a != null && parser.getName().equals(a.getSubType())) {
						if (parser.next() == XmlPullParser.TEXT) {
							String name = parser.getText();
							if (name != null) {
								a.setName(name);
								a.setEnName(TransliterationHelper.transliterate(name));
							}
						}
					}
				} else if (eventType == XmlPullParser.END_TAG) {
					if (parser.getName().equals("place")) { //$NON-NLS-1$
						namedDepth--;
						if (namedDepth == 0) {
							a = null;
						}
					}
					if (parser.getName().equals("extratags")) {
						extratags = false;
					}
				}
			}
			stream.close();
		} catch (IOException e) {
			log.error("Error loading name finder poi", e); //$NON-NLS-1$
			lastError = getApplication().getString(R.string.shared_string_io_error); //$NON-NLS-1$
		} catch (XmlPullParserException e) {
			log.error("Error parsing name finder poi", e); //$NON-NLS-1$
			lastError = getApplication().getString(R.string.shared_string_io_error); //$NON-NLS-1$
		}
		MapUtils.sortListOfMapObject(currentSearchResult, lat, lon);
		return currentSearchResult;
	}

	private boolean searchCensusAddress(ResultMatcher<Amenity> matcher) {
		String query = getFilterByName();
		if (!isLikelyUsStreetAddress(query)) {
			return false;
		}
		try {
			String urlq = CENSUS_ONELINE_ADDRESS_API + "?benchmark=Public_AR_Current&format=json"
					+ "&address=" + URLEncoder.encode(query);
			log.info("US Census address search: " + urlq);
			URLConnection connection = NetworkUtils.getHttpURLConnection(urlq);
			connection.setRequestProperty("User-Agent", Version.getFullVersion(app));
			String body = Algorithms.readFromInputStream(connection.getInputStream()).toString();
			JSONObject result = new JSONObject(body).optJSONObject("result");
			JSONArray matches = result != null ? result.optJSONArray("addressMatches") : null;
			if (matches == null || matches.length() == 0) {
				return false;
			}
			MapPoiTypes poiTypes = ((OsmandApplication) getApplication()).getPoiTypes();
			for (int i = 0; i < matches.length(); i++) {
				Amenity amenity = createAmenityFromCensusMatch(matches.optJSONObject(i), poiTypes);
				if (amenity != null && (matcher == null || matcher.publish(amenity))) {
					currentSearchResult.add(amenity);
				}
			}
			return !currentSearchResult.isEmpty();
		} catch (IOException | JSONException e) {
			log.error("Error loading US Census address search", e);
			return false;
		}
	}

	private Amenity createAmenityFromCensusMatch(JSONObject match, MapPoiTypes poiTypes) {
		if (match == null) {
			return null;
		}
		JSONObject coordinates = match.optJSONObject("coordinates");
		if (coordinates == null) {
			return null;
		}
		double lon = coordinates.optDouble("x", Double.NaN);
		double lat = coordinates.optDouble("y", Double.NaN);
		if (Double.isNaN(lat) || Double.isNaN(lon)) {
			return null;
		}
		String matchedAddress = match.optString("matchedAddress", "").trim();
		if (Algorithms.isEmpty(matchedAddress)) {
			return null;
		}
		Amenity amenity = new Amenity();
		amenity.setLocation(lat, lon);
		amenity.setName(matchedAddress);
		amenity.setEnName(TransliterationHelper.transliterate(matchedAddress));
		amenity.setSubType("house");
		PoiType pt = poiTypes.getPoiTypeByKey(amenity.getSubType());
		amenity.setType(pt != null ? pt.getCategory() : poiTypes.getOtherPoiCategory());
		amenity.setAdditionalInfo("source", "US Census Geocoder");
		JSONObject tigerLine = match.optJSONObject("tigerLine");
		if (tigerLine != null) {
			long tigerLineId = tigerLine.optLong("tigerLineId", 0);
			if (tigerLineId != 0) {
				amenity.setId(tigerLineId);
			}
		}
		return amenity;
	}

	private boolean isLikelyUsStreetAddress(String query) {
		String trimmed = query == null ? "" : query.trim();
		if (trimmed.length() < 8 || !Character.isDigit(trimmed.charAt(0))) {
			return false;
		}
		String normalized = " " + trimmed.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", " ") + " ";
		for (String hint : US_STREET_ADDRESS_HINTS) {
			if (normalized.contains(hint)) {
				return true;
			}
		}
		return trimmed.contains(",") && normalized.matches(".* \\d{5}( \\d{4})? .*");
	}
	
	public String getLastError() {
		return lastError;
	}
}
