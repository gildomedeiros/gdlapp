package com.gdl.loratester;

import android.hardware.usb.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.TimeoutException;

final class Cp2102 implements AutoCloseable {
    private UsbDeviceConnection connection;
    private UsbInterface port;
    private UsbRequest request;
    private final ByteBuffer buffer=ByteBuffer.allocateDirect(4096);
    private boolean queued;
    static boolean supports(UsbDevice d) { return d.getVendorId()==0x10c4 && d.getProductId()==0xea60; }

    Cp2102(UsbManager manager, UsbDevice device) throws IOException {
        try {
            connection=manager.openDevice(device);
            if(connection==null) throw new IOException("USB permission missing or device unavailable");
            UsbEndpoint input=null;
            for(int i=0;i<device.getInterfaceCount() && input==null;i++) {
                UsbInterface candidate=device.getInterface(i);
                for(int j=0;j<candidate.getEndpointCount();j++) {
                    UsbEndpoint e=candidate.getEndpoint(j);
                    if(e.getType()==UsbConstants.USB_ENDPOINT_XFER_BULK && e.getDirection()==UsbConstants.USB_DIR_IN) {
                        port=candidate; input=e; break;
                    }
                }
            }
            if(input==null || !connection.claimInterface(port,true)) throw new IOException("Cannot claim CP2102 interface");
            control(0x00,1,null);
            control(0x1e,0,new byte[]{0x00,(byte)0xc2,0x01,0x00});
            control(0x03,0x0800,null);
            control(0x13,0,new byte[16]);
            control(0x07,0x0300,null);
            request=new UsbRequest();
            if(!request.initialize(connection,input)) throw new IOException("Cannot initialise USB reader");
        } catch(Exception e) { close(); throw new IOException(e.getMessage(),e); }
    }
    private void control(int command,int value,byte[] data) throws IOException {
        int length=data==null?0:data.length;
        if(connection.controlTransfer(0x41,command,value,port.getId(),data,length,2000)!=length)
            throw new IOException("CP2102 configuration failed: "+command);
    }
    int read(byte[] out) throws IOException {
        if(!queued) {
            buffer.clear();
            if(!request.queue(buffer)) throw new IOException("USB read queue failed");
            queued=true;
        }
        try {
            UsbRequest completed=connection.requestWait(1000);
            if(completed!=request) throw new IOException("USB disconnected or read failed");
            queued=false;
            int count=buffer.position(); buffer.flip(); buffer.get(out,0,count);
            return count;
        } catch(TimeoutException e) { return 0; }
    }
    public void close() {
        if(request!=null) { try { request.cancel(); request.close(); } catch(Exception ignored) {} request=null; }
        if(connection!=null) {
            if(port!=null) { try { control(0x00,0,null); connection.releaseInterface(port); } catch(Exception ignored) {} }
            connection.close(); connection=null;
        }
    }
}
