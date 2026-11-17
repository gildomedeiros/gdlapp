package au.com.gildo.vtc;
import java.util.*;

/** Small strict JSON reader for portable configuration and JVM validation. */
final class ConfigJson {
    private final String text;private int at,depth;
    private ConfigJson(String text){this.text=text;}
    static Object parse(String text){ConfigJson p=new ConfigJson(text);Object v=p.value();p.ws();if(p.at!=text.length())throw p.bad();return v;}
    private IllegalArgumentException bad(){return new IllegalArgumentException("Invalid configuration JSON at character "+at);}
    private void ws(){while(at<text.length()&&Character.isWhitespace(text.charAt(at)))at++;}
    private boolean take(char c){ws();if(at<text.length()&&text.charAt(at)==c){at++;return true;}return false;}
    private void need(char c){if(!take(c))throw bad();}
    private Object value(){
        ws();if(++depth>32||at>=text.length())throw bad();
        try{
            char c=text.charAt(at);
            if(c=='"')return string();
            if(take('{')){Map<String,Object> m=new LinkedHashMap<>();if(take('}'))return m;do{ws();String k=string();need(':');if(m.containsKey(k))throw bad();m.put(k,value());}while(take(','));need('}');return m;}
            if(take('[')){List<Object> list=new ArrayList<>();if(take(']'))return list;do{list.add(value());}while(take(','));need(']');return list;}
            for(String token:new String[]{"null","true","false"})if(text.startsWith(token,at)){at+=token.length();return token.equals("null")?null:Boolean.valueOf(token);}
            int start=at;while(at<text.length()&&"-+0123456789.eE".indexOf(text.charAt(at))>=0)at++;
            String n=text.substring(start,at);if(!n.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))throw bad();
            double number=Double.parseDouble(n);if(!Double.isFinite(number))throw bad();return number;
        }finally{depth--;}
    }
    private String string(){
        need('"');StringBuilder b=new StringBuilder();
        while(at<text.length()){
            char c=text.charAt(at++);if(c=='"')return b.toString();if(c<32)throw bad();
            if(c=='\\'){
                if(at>=text.length())throw bad();char e=text.charAt(at++);
                switch(e){case '"':case '\\':case '/':c=e;break;case 'b':c='\b';break;case 'f':c='\f';break;case 'n':c='\n';break;case 'r':c='\r';break;case 't':c='\t';break;
                    case 'u':if(at+4>text.length())throw bad();try{c=(char)Integer.parseInt(text.substring(at,at+4),16);}catch(Exception ex){throw bad();}at+=4;break;default:throw bad();}
            }b.append(c);
        }throw bad();
    }
    static Map<String,String> settings(String json){
        Object parsed=parse(json);if(!(parsed instanceof Map))throw new IllegalArgumentException("Settings must be a JSON object");
        Map<String,String> out=new LinkedHashMap<>();
        for(Map.Entry<?,?> e:((Map<?,?>)parsed).entrySet()){Object v=e.getValue();out.put((String)e.getKey(),v instanceof String?(String)v:LogJson.encode(v));}
        return out;
    }
    static Map<String,Object> structured(Map<String,String> values){
        Map<String,Object> out=new LinkedHashMap<>(values);
        for(String key:new String[]{"zoomBands","zoom_scales","zoomMapping"})if(values.containsKey(key)&&(values.get(key).trim().startsWith("[")||values.get(key).trim().startsWith("{")))out.put(key,parse(values.get(key)));
        return out;
    }
}
