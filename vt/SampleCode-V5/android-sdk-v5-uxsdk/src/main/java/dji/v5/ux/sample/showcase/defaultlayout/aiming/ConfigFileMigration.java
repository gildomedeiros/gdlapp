package dji.v5.ux.sample.showcase.defaultlayout.aiming;
import java.io.IOException;
/** VT 3.7: Existing destinations always win; writes are verified before adopting a folder. */
public final class ConfigFileMigration {
    public interface Files {
        boolean exists(String name)throws IOException;
        String read(String name)throws IOException;
        void create(String name,String contents)throws IOException;
    }
    public interface Seed { String read()throws IOException; }
    public static boolean ensure(Files files,String name,Seed seed)throws IOException {
        if(files.exists(name))return false;
        String contents=seed.read();files.create(name,contents);
        if(!contents.equals(files.read(name)))throw new IOException("Configuration copy verification failed: "+name);
        return true;
    }
}
