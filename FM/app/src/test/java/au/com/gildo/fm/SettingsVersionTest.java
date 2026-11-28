package au.com.gildo.fm;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class SettingsVersionTest {
    private final String[] defaults=FmService.DEFAULTS.clone();
    private static final String KEY=SettingsFile.VERSION_KEY;

    @Test public void ageUsesElapsedTimeSinceAcceptance() {
        var load=new SettingsFile.LastLoad("beach-02",5000,new byte[]{1});
        assertEquals("beach-02 [just now]",load.labelAt(5999));
        assertEquals("beach-02 [1 s ago]",load.labelAt(6000));
        assertEquals("beach-02 [59 s ago]",load.labelAt(64999));
        assertEquals("beach-02 [1 min ago]",load.labelAt(65000));
        assertEquals("beach-02 [2 min ago]",load.labelAt(125000));
        assertEquals("beach-02 [1 h ago]",load.labelAt(3605000));
        assertEquals("beach-02 [1 d ago]",load.labelAt(86405000));
    }

    @Test public void watcherComparesFileContentsEvenWhenTheVersionStaysTheSame() {
        var first=new SettingsFile.LastLoad("beach-02",0,new byte[]{1});
        assertTrue(first.matches(new byte[]{1}));
        assertFalse(first.matches(new byte[]{2}));
        var saved=new SettingsFile.LastLoad(first.version(),120000,new byte[]{2});
        assertTrue(saved.matches(new byte[]{2}));
        assertEquals("beach-02 [2 min ago]",first.labelAt(120000));
        assertEquals("beach-02 [just now]",saved.labelAt(120000));
    }

    @Test public void versionOnlyEditAndRemovalUseTheCurrentFile() throws Exception {
        JSONObject fields=new JSONObject().put(KEY,"beach-01");
        var first=SettingsFile.readSnapshot(fields,defaults);
        fields.put(KEY,"beach-02");
        var next=SettingsFile.readSnapshot(fields,first.values());
        assertArrayEquals(first.values(),next.values());
        assertEquals("beach-02",next.displayVersion());
        fields.remove(KEY);
        assertEquals("Not set",SettingsFile.readSnapshot(fields,next.values()).displayVersion());
    }

    @Test public void upgradeAndAutosaveKeepTheUserLabelAndItsJsonType() throws Exception {
        for(Object label:new Object[]{"001.20",7}) {
            JSONObject fields=new JSONObject().put(KEY,label);
            var disk=SettingsFile.readSnapshot(fields,defaults);
            SettingsFile.upgradeSettingsFields(fields,disk.values());
            assertEquals(label,fields.get(KEY));
            String[] edit=disk.values().clone();
            edit[FmService.LIVE_PAN_SPEED_MULTIPLIER]="1.3";
            var saved=disk.withValues(edit).document().getJSONObject("settings");
            assertEquals(label,saved.get(KEY));
            assertEquals(label.toString(),SettingsFile.readSnapshot(saved,defaults).displayVersion());
        }
    }

    @Test public void oldFilesGetABlankFieldAndRejectedSettingsCannotPublishANewVersion() throws Exception {
        JSONObject fields=new JSONObject();
        SettingsFile.upgradeSettingsFields(fields,defaults);
        assertEquals("",fields.getString(KEY));
        var valid=SettingsFile.readSnapshot(fields,defaults);
        assertEquals("Not set",valid.displayVersion());
        fields.put(KEY,"bad-edit").put("Live pan speed multiplier",-1);
        assertThrows(IllegalArgumentException.class,()->SettingsFile.readSnapshot(fields,valid.values()));
        assertEquals("Not set",valid.displayVersion());
    }
}
