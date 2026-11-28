package au.com.gildo.fm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class HotspotSettingsTest {
    @Test public void olderFilesAcquireOnlyMissingHotspotFields() throws Exception {
        JSONObject fields=new JSONObject();
        var defaults=SettingsFile.readSnapshot(fields,FmService.DEFAULTS);
        assertEquals("GDL_LORA",defaults.hotspot().name());
        assertEquals("gdl12345",defaults.hotspot().password());
        fields.put(HotspotSettings.NAME_KEY,"Beach East");
        assertTrue(SettingsFile.upgradeSettingsFields(fields,defaults.values()));
        assertEquals("Beach East",fields.getString(HotspotSettings.NAME_KEY));
        assertEquals("gdl12345",fields.getString(HotspotSettings.PASSWORD_KEY));
        assertFalse(SettingsFile.upgradeSettingsFields(fields,defaults.values()));
    }

    @Test public void hotspotOnlyEditSurvivesTheNextUnrelatedSave() throws Exception {
        JSONObject fields=new JSONObject().put(SettingsFile.VERSION_KEY,"000");
        var first=SettingsFile.readSnapshot(fields,FmService.DEFAULTS);
        fields.put(HotspotSettings.NAME_KEY,"Beach west").put(HotspotSettings.PASSWORD_KEY," my pass ");
        var disk=SettingsFile.readSnapshot(fields,first.values());
        assertArrayEquals(first.values(),disk.values());
        assertFalse(Arrays.asList(disk.values()).contains(disk.hotspot().password()));
        var edit=disk.values().clone();
        edit[FmService.LIVE_MIN_RANGE]="8";
        var saved=disk.withValues(edit).document().getJSONObject("settings");
        assertEquals("Beach west",saved.getString(HotspotSettings.NAME_KEY));
        assertEquals(" my pass ",saved.getString(HotspotSettings.PASSWORD_KEY));
        assertEquals("000",saved.getString(SettingsFile.VERSION_KEY));
    }

    @Test public void acceptsOpenNetworksAndChecksSsidByteLength() throws Exception {
        assertEquals("",new HotspotSettings("é".repeat(16),"").password());
        assertThrows(IllegalArgumentException.class,()->new HotspotSettings("é".repeat(17),""));
        assertThrows(IllegalArgumentException.class,()->new HotspotSettings("",""));
        assertThrows(IllegalArgumentException.class,()->new HotspotSettings("Beach","short"));
        assertThrows(IllegalArgumentException.class,()->HotspotSettings.read(new JSONObject().put(HotspotSettings.PASSWORD_KEY,12345678)));
    }

    @Test public void malformedFileCannotExposePasswordInStartupLogError() {
        String malformed="{\"settings\":{\"Wi-Fi hotspot password\":\"private example\"";
        IOException error=assertThrows(IOException.class,()->SettingsFile.parseDocument(malformed.getBytes(StandardCharsets.UTF_8)));
        assertFalse(error.getMessage().contains("private example"));
    }
}
