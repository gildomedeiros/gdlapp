package au.com.gildo.vtc;
import org.junit.Test;
import static org.junit.Assert.*;
public class SettingsFileNamesTest {
    @Test public void acceptsAndroidCollisionNames(){assertTrue(SettingsFileNames.matches("setup.json","setup.json"));assertTrue(SettingsFileNames.matches("setup.json","setup (1).json"));assertTrue(SettingsFileNames.matches("setup.json","setup (123).json"));}
    @Test public void doesNotConfuseBackupsOrBookmarks(){for(String n:new String[]{"setup - Copy.json","setup-before-1.0.17.json","last-landmark.json","setup (1).json.bak","setupXjson","other (2).json"})assertFalse(SettingsFileNames.matches("setup.json",n));}
    @Test public void supportsBookmarkStoreSeparately(){assertTrue(SettingsFileNames.matches("last-landmark.json","last-landmark (2).json"));assertFalse(SettingsFileNames.matches("last-landmark.json","setup (2).json"));}
}
