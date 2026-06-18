package net.osmand.plus.plugins.flockfree;

import static net.osmand.osm.edit.Entity.POI_TYPE_TAG;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.PlatformUtil;
import net.osmand.osm.PoiType;
import net.osmand.osm.edit.Node;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.activities.MapActivity;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.plugins.osmedit.OsmEditingPlugin;
import net.osmand.plus.plugins.osmedit.dialogs.EditPoiDialogFragment;

import org.apache.commons.logging.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Provides OSM tagging presets for ALPR camera reporting and integrates
 * with OsmAnd's existing POI editor (EditPoiDialogFragment).
 */
public class CameraReporter {

    private static final Log LOG = PlatformUtil.getLog(CameraReporter.class);

    private final OsmandApplication app;
    @Nullable
    private String lastReportDraftSummary;

    // Brand presets with their OSM tag values
    private static final Map<String, Map<String, String>> BRAND_PRESETS = new LinkedHashMap<>();
    static {
        // Flock Safety
        Map<String, String> flock = new LinkedHashMap<>();
        flock.put("man_made", "surveillance");
        flock.put("surveillance:type", "ALPR");
        flock.put("brand", "Flock Safety");
        flock.put("camera:type", "fixed");
        flock.put("surveillance:zone", "traffic");
        BRAND_PRESETS.put("Flock Safety", flock);

        // Motorola Solutions
        Map<String, String> motorola = new LinkedHashMap<>();
        motorola.put("man_made", "surveillance");
        motorola.put("surveillance:type", "ALPR");
        motorola.put("brand", "Motorola Solutions");
        motorola.put("camera:type", "fixed");
        motorola.put("surveillance:zone", "traffic");
        BRAND_PRESETS.put("Motorola Solutions", motorola);

        // Genetec
        Map<String, String> genetec = new LinkedHashMap<>();
        genetec.put("man_made", "surveillance");
        genetec.put("surveillance:type", "ALPR");
        genetec.put("brand", "Genetec");
        genetec.put("camera:type", "fixed");
        genetec.put("surveillance:zone", "traffic");
        BRAND_PRESETS.put("Genetec", genetec);

        // Leonardo
        Map<String, String> leonardo = new LinkedHashMap<>();
        leonardo.put("man_made", "surveillance");
        leonardo.put("surveillance:type", "ALPR");
        leonardo.put("brand", "Leonardo");
        leonardo.put("camera:type", "fixed");
        leonardo.put("surveillance:zone", "traffic");
        BRAND_PRESETS.put("Leonardo", leonardo);

        // Generic ALPR (no brand)
        Map<String, String> generic = new LinkedHashMap<>();
        generic.put("man_made", "surveillance");
        generic.put("surveillance:type", "ALPR");
        generic.put("camera:type", "fixed");
        generic.put("surveillance:zone", "traffic");
        BRAND_PRESETS.put("Generic ALPR", generic);
    }

    public CameraReporter(@NonNull OsmandApplication app) {
        this.app = app;
    }

    public Map<String, Map<String, String>> getBrandPresets() {
        return BRAND_PRESETS;
    }

    @NonNull
    public synchronized String getLastReportDraftSummary() {
        return lastReportDraftSummary != null
                ? lastReportDraftSummary
                : app.getString(R.string.flockfree_report_last_draft_none);
    }

    private synchronized void setLastReportDraftSummary(@NonNull String summary) {
        lastReportDraftSummary = summary;
    }

    /**
     * Show a dialog for the user to add a new ALPR camera at the given location.
     * The user selects a brand preset and optional direction, then submits via
     * OsmAnd's existing POI editor flow.
     */
    public void showAddCameraDialog(@NonNull MapActivity mapActivity, double lat, double lon) {
        Context ctx = mapActivity;
        setLastReportDraftSummary(app.getString(R.string.flockfree_report_last_draft_dialog, lat, lon));
        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
        builder.setTitle(R.string.flockfree_add_camera);

        android.widget.LinearLayout layout = new android.widget.LinearLayout(ctx);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(50, 30, 50, 30);

        android.widget.TextView brandLabel = new android.widget.TextView(ctx);
        brandLabel.setText(R.string.flockfree_report_brand_label);
        layout.addView(brandLabel);

        Spinner brandSpinner = new Spinner(ctx);
        ArrayAdapter<String> brandAdapter = new ArrayAdapter<>(ctx,
                android.R.layout.simple_spinner_item,
                new ArrayList<>(BRAND_PRESETS.keySet()));
        brandAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        brandSpinner.setAdapter(brandAdapter);
        layout.addView(brandSpinner);

        android.widget.TextView dirLabel = new android.widget.TextView(ctx);
        dirLabel.setText(R.string.flockfree_report_direction_label);
        layout.addView(dirLabel);

        EditText dirInput = new EditText(ctx);
        dirInput.setHint(R.string.flockfree_report_direction_hint);
        layout.addView(dirInput);

        android.widget.TextView opLabel = new android.widget.TextView(ctx);
        opLabel.setText(R.string.flockfree_report_operator_label);
        layout.addView(opLabel);

        EditText opInput = new EditText(ctx);
        opInput.setHint(R.string.flockfree_report_operator_hint);
        layout.addView(opInput);

        builder.setView(layout);
        builder.setPositiveButton(R.string.flockfree_open_osm_editor, (dialog, which) -> {
            String brand = (String) brandSpinner.getSelectedItem();
            String brandName = brand != null ? brand : "Generic ALPR";
            String direction = dirInput.getText().toString().trim();
            String operator = opInput.getText().toString().trim();

            Map<String, String> tags = getTagsForBrand(brandName);
            if (!direction.isEmpty()) {
                tags.put("direction", direction);
            }
            if (!operator.isEmpty()) {
                tags.put("operator", operator);
            }

            openPoiEditorWithTags(mapActivity, lat, lon, brandName, tags);
        });
        builder.setNegativeButton(R.string.shared_string_cancel, null);
        builder.show();
    }

    /**
     * Opens OsmAnd's existing EditPoiDialogFragment with pre-filled ALPR tags.
     */
    private void openPoiEditorWithTags(@NonNull MapActivity mapActivity, double lat, double lon,
                                       @NonNull String brandName,
                                       @NonNull Map<String, String> tags) {
        try {
            OsmEditingPlugin editingPlugin = PluginsHelper.getPlugin(OsmEditingPlugin.class);
            if (editingPlugin == null) {
                setLastReportDraftSummary(app.getString(R.string.flockfree_report_last_draft_fallback,
                        brandName, lat, lon, tags.size()));
                showTagsFallback(mapActivity, tags);
                return;
            }
            Map<String, String> editorTags = new LinkedHashMap<>(tags);
            addEditorPoiType(editorTags);
            Node node = new Node(lat, lon, -1);
            node.replaceTags(editorTags);
            EditPoiDialogFragment.showInstance(mapActivity, node, true, editorTags);
            setLastReportDraftSummary(app.getString(R.string.flockfree_report_last_draft_editor,
                    brandName, lat, lon, editorTags.size()));
            LOG.info("Opened POI editor for camera at " + lat + "," + lon + " with tags: " + editorTags);
        } catch (Exception e) {
            LOG.error("Failed to open POI editor", e);
            setLastReportDraftSummary(app.getString(R.string.flockfree_report_last_draft_fallback,
                    brandName, lat, lon, tags.size()));
            showTagsFallback(mapActivity, tags);
        }
    }

    private void addEditorPoiType(@NonNull Map<String, String> editorTags) {
        PoiType surveillanceType = app.getPoiTypes().getPoiTypeByTagValue("man_made", "surveillance");
        if (surveillanceType != null) {
            editorTags.put(POI_TYPE_TAG, surveillanceType.getTranslation());
        }
    }

    private void showTagsFallback(@NonNull MapActivity mapActivity, @NonNull Map<String, String> tags) {
        StringBuilder sb = new StringBuilder();
        sb.append(app.getString(R.string.flockfree_osm_editor_unavailable_message)).append("\n\n");
        for (Map.Entry<String, String> entry : tags.entrySet()) {
            sb.append(entry.getKey()).append(" = ").append(entry.getValue()).append("\n");
        }
        new AlertDialog.Builder(mapActivity)
                .setTitle(R.string.flockfree_osm_editor_unavailable_title)
                .setMessage(sb.toString())
                .setPositiveButton(R.string.shared_string_ok, null)
                .show();
    }

    /**
     * Returns the OSM tags for a given brand preset.
     */
    @NonNull
    public Map<String, String> getTagsForBrand(@NonNull String brandName) {
        Map<String, String> preset = BRAND_PRESETS.get(brandName);
        if (preset != null) {
            return new LinkedHashMap<>(preset);
        }
        // Return generic ALPR tags
        Map<String, String> generic = new LinkedHashMap<>();
        generic.put("man_made", "surveillance");
        generic.put("surveillance:type", "ALPR");
        generic.put("camera:type", "fixed");
        generic.put("surveillance:zone", "traffic");
        return generic;
    }
}
