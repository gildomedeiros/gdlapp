package com.gdl.loratester;
import java.util.*;
public class WifiCheck {
 public static void main(String[] args) {
  Telemetry t=Telemetry.parse("RX,GPS,14500,14501278,-28.07914,153.40107,8,1.6,-107,7.7,0");
  if(t.snr!=7.7)throw new AssertionError("Decimal SNR lost");
  for(String v:new String[]{"NaN","Infinity","128"}) {
   try{Telemetry.parse("RX,GPS,1,1,1,1,8,1,-90,"+v+",0");throw new AssertionError("Invalid SNR accepted");}catch(IllegalArgumentException expected){}
  }
  KmlDocument k=new KmlDocument("lora_wifi");
  Map<String,Object> m=new LinkedHashMap<>();
  m.put("kind","derived_reading");m.put("classification","gps");m.put("sequence",1);m.put("latitude",1.0);m.put("longitude",2.0);m.put("elapsed_realtime_ns",1000000000L);m.put("snr",t.snr);
  if(!k.record(m).contains("7.7"))throw new AssertionError("KML SNR lost");
  if(!k.record(Map.of("kind","udp_datagram")).isEmpty())throw new AssertionError("Duplicate raw KML record");
  k.record(Map.of("kind","wifi_disconnected"));m.put("sequence",2);m.put("elapsed_realtime_ns",2000000000L);
  if(k.record(m).contains("LineString"))throw new AssertionError("Track crosses reconnect");
  System.out.println("PASS: TTGO decimal SNR, invalid SNR, KML metadata and Wi-Fi track boundaries");
 }
}
