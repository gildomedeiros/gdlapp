package au.com.gildo.fm;

import java.lang.reflect.Array;
import java.util.Map;

/** Structured JSONL encoding without Android dependencies. */
final class LogJson {
    private LogJson(){}
    static String encode(Object value) {
        if(value==null)return "null";
        if(value instanceof Boolean)return value.toString();
        if(value instanceof Number)return Double.isFinite(((Number)value).doubleValue())?value.toString():"null";
        if(value instanceof Map) {
            StringBuilder b=new StringBuilder("{");boolean first=true;
            for(Object item:((Map<?,?>)value).entrySet()) {
                Map.Entry<?,?> e=(Map.Entry<?,?>)item;
                if(!first)b.append(',');first=false;
                b.append(encode(String.valueOf(e.getKey()))).append(':').append(encode(e.getValue()));
            }
            return b.append('}').toString();
        }
        if(value instanceof Iterable) {
            StringBuilder b=new StringBuilder("[");boolean first=true;
            for(Object item:(Iterable<?>)value){if(!first)b.append(',');first=false;b.append(encode(item));}
            return b.append(']').toString();
        }
        if(value.getClass().isArray()) {
            StringBuilder b=new StringBuilder("[");
            for(int i=0;i<Array.getLength(value);i++){if(i>0)b.append(',');b.append(encode(Array.get(value,i)));}
            return b.append(']').toString();
        }
        StringBuilder b=new StringBuilder("\"");
        for(char c:value.toString().toCharArray()) {
            if(c=='"'||c=='\\')b.append('\\').append(c);
            else if(c<32||Character.isSurrogate(c))b.append(String.format(java.util.Locale.US,"\\u%04x",(int)c));
            else b.append(c);
        }
        return b.append('"').toString();
    }
}

