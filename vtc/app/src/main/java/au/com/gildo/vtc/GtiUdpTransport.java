package au.com.gildo.vtc;

import android.net.Network;
import java.net.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class GtiUdpTransport implements GtiClient.Transport {
    private final Network network;
    private final InetAddress address;
    private final int port;
    private final SessionLog log;
    private long transaction;
    GtiUdpTransport(Network network,String host,int port,SessionLog log)throws IOException {
        this.network=network;this.address=InetAddress.getByName(host);this.port=port;this.log=log;
    }
    public String exchange(String command)throws IOException {
        long start=System.nanoTime();
        long id=++transaction;
        int localPort=-1;
        String expected=GtiReplyReader.expected(command);
        // A fresh port isolates replies addressed to old transactions. Some bridges
        // may redirect a delayed reply to the newest peer, so also validate its shape.
        try(DatagramSocket socket=new DatagramSocket(null)) {
            network.bindSocket(socket);socket.bind(new InetSocketAddress(0));socket.connect(address,port);
            localPort=socket.getLocalPort();
            log.record("mount_tx","transaction",id,"command",command,"host",address.getHostAddress(),"port",port,
                "localPort",localPort,"responseTimeoutMs",GtiReplyReader.timeoutMs(command),"expectedReply",expected);
            byte[] bytes=command.getBytes(StandardCharsets.US_ASCII);
            socket.send(new DatagramPacket(bytes,bytes.length));
            return GtiReplyReader.receive(command,remaining->{
                socket.setSoTimeout(remaining);
                byte[] buffer=new byte[256];DatagramPacket reply=new DatagramPacket(buffer,buffer.length);
                socket.receive(reply);
                String value=new String(buffer,0,reply.getLength(),StandardCharsets.US_ASCII);
                boolean matched=GtiReplyReader.matches(command,value);
                log.record(matched?"mount_rx":"mount_rx_discarded","transaction",id,"command",command,"reply",value,
                    "expectedReply",expected,"matchesExpectedReply",matched,
                    "rawBase64",android.util.Base64.encodeToString(buffer,0,reply.getLength(),android.util.Base64.NO_WRAP),
                    "byteCount",reply.getLength(),"elapsedMs",(System.nanoTime()-start)/1_000_000.0,
                    "remoteHost",reply.getAddress().getHostAddress(),"remotePort",reply.getPort(),"localPort",socket.getLocalPort());
                return value;
            },()->System.nanoTime()/1_000_000);
        }catch(IOException e){log.error("mount_error",e,"transaction",id,"command",command,"host",address.getHostAddress(),"port",port,
            "localPort",localPort,"responseTimeoutMs",GtiReplyReader.timeoutMs(command),"expectedReply",expected,
            "elapsedMs",(System.nanoTime()-start)/1_000_000.0);throw e;}
    }
    public void close(){}
}

