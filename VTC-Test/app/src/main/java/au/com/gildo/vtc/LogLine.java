package au.com.gildo.vtc;
/** Single-line CSV fields preserve raw CR/LF as visible escapes. */
final class LogLine {
    static String csv(String value) {
        if(value==null) value="";
        return "\""+value.replace("\r","\\r").replace("\n","\\n").replace("\"","\"\"")+"\"";
    }
    static String record(String timestamp,long elapsed,String event,String detail) {
        return csv(timestamp)+","+elapsed+","+csv(event)+","+csv(detail)+"\n";
    }
}
