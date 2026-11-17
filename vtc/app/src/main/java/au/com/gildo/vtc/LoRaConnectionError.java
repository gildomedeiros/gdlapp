package au.com.gildo.vtc;

import java.util.Locale;

final class LoRaConnectionError {
    static String describe(Throwable failure,int port){
        for(Throwable cause=failure;cause!=null;cause=cause.getCause()){
            String detail=String.valueOf(cause.getMessage()).toLowerCase(Locale.ROOT);
            if(detail.contains("eaddrinuse")||detail.contains("address already in use"))
                return "Phone UDP port "+port+" is already in use. VTC cannot open LoRa reception. "
                    +"The port owner is unknown; stop other GPS receiver services or restart the phone, then reconnect VTC.";
        }
        return "LoRa connection failed: "+failure.getMessage();
    }
}
