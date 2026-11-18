package au.com.gildo.vtc;
import java.util.regex.Pattern;
final class SettingsFileNames {
    static boolean matches(String requested,String candidate){
        if(requested.equals(candidate))return true;
        int dot=requested.lastIndexOf('.');if(dot<0||candidate==null)return false;
        return candidate.matches(Pattern.quote(requested.substring(0,dot))+" \\([1-9][0-9]*\\)"+Pattern.quote(requested.substring(dot)));
    }
}
