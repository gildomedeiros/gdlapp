package au.com.gildo.fm;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class CameraSettingsFileTest {
    private final String[] defaults=FmService.DEFAULTS.clone();
    private final String bandsKey=FmService.KEYS[CameraSettings.BANDS];
    private final String mappingKey=FmService.KEYS[CameraSettings.MAPPING];
    private final String scalesKey=FmService.KEYS[CameraSettings.SCALES];

    @Test public void upgradesMissingAndQuotedCameraFieldsOnce() throws Exception {
        JSONObject fields=new JSONObject();
        fields.put("Canon clip limit (minutes)","3");
        fields.put("other","preserved");
        fields.put(mappingKey,defaults[CameraSettings.MAPPING]);
        fields.put(scalesKey,"1.5,2");
        String[] loaded=SettingsFile.decode(fields,defaults);
        assertTrue(SettingsFile.upgradeSettingsFields(fields,loaded));
        assertEquals(18,fields.getJSONObject(bandsKey).getJSONArray("bands").length());
        assertEquals(102,fields.getJSONArray(mappingKey).length());
        assertEquals(1.5,fields.getJSONArray(scalesKey).getDouble(0),0);
        assertEquals("3",fields.getString("Canon clip limit (minutes)"));
        assertEquals("preserved",fields.getString("other"));
        assertFalse(SettingsFile.upgradeSettingsFields(fields,SettingsFile.decode(fields,defaults)));
    }

    @Test public void tiltDisableDefaultsOffAndRoundTripsAsBoolean() throws Exception {
        JSONObject old=new JSONObject().put("Live HS pan speed (× sidereal)",200);
        String[] migrated=SettingsFile.decode(old,defaults);
        assertFalse(FmService.tiltDisabled(migrated));
        SettingsFile.upgradeSettingsFields(old,migrated);
        assertFalse(old.has("Live HS pan speed (× sidereal)"));
        assertEquals(Boolean.FALSE,old.get("tilt-disable"));
        old.put("tilt-disable",true);
        String[] enabled=SettingsFile.decode(old,defaults);
        assertTrue(FmService.tiltDisabled(enabled));
        assertEquals(Boolean.TRUE,SettingsFile.settingsDocument(enabled).getJSONObject("settings").get("tilt-disable"));
    }

    @Test public void externalZoomScaleSurvivesConcurrentChangeToAnotherField() throws Exception {
        JSONObject fields=SettingsFile.settingsDocument(defaults).getJSONObject("settings");
        fields.put(scalesKey,new JSONArray("[1.2]"));
        String[] disk=SettingsFile.decode(fields,defaults);
        String[] edit=defaults.clone();
        edit[FmService.LIVE_MIN_RANGE]="7";
        String[] merged=SettingsFile.mergeEdit(defaults,edit,disk);
        assertEquals("7",merged[FmService.LIVE_MIN_RANGE]);
        assertEquals(1.2,new CameraSettings(merged).zoomMultipliers[0],0);
    }

    @Test public void manualCameraDefaultsOffAndRoundTripsAsBoolean() throws Exception {
        JSONObject fields=SettingsFile.settingsDocument(defaults).getJSONObject("settings");
        assertEquals(Boolean.FALSE,fields.get("Manual camera"));
        fields.put("Manual camera",true);
        assertTrue(new CameraSettings(SettingsFile.decode(fields,defaults)).manual);
        fields.put("Manual camera","maybe");
        try {
            SettingsFile.decode(fields,defaults);
            fail("Invalid external edit was accepted");
        } catch(IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("Manual camera")); }
    }

    @Test public void handEditedFileWinsAndSavesAsEditableTables() throws Exception {
        JSONObject fields=SettingsFile.settingsDocument(defaults).getJSONObject("settings");
        fields.getJSONObject(bandsKey).put("bufferMetres",2);
        fields.getJSONArray(mappingKey).getJSONObject(1).put("ccapiValue",2);
        fields.put(scalesKey,new JSONArray("[1.5]"));
        fields.put("Canon clip limit (minutes)",7);
        String[] loaded=SettingsFile.decode(fields,defaults);
        JSONObject saved=SettingsFile.settingsDocument(loaded).getJSONObject("settings");
        assertEquals(2,saved.getJSONObject(bandsKey).getInt("bufferMetres"));
        assertEquals(2,saved.getJSONArray(mappingKey).getJSONObject(1).getInt("ccapiValue"));
        assertEquals(1.5,saved.getJSONArray(scalesKey).getDouble(0),0);
        assertEquals(7,saved.getInt("Canon clip limit (minutes)"));
    }
}
