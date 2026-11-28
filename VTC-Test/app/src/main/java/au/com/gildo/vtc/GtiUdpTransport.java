package au.com.gildo.vtc;
import android.net.Network;
import java.net.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class GtiUdpTransport implements GtiClient.Transport {
    private final DiagnosticLog log;
    private final Network network;
    private final InetAddress address;
    private final int port;
    GtiUdpTransport(Network network,String host,int port,DiagnosticLog log) throws IOException {
        this.log=log;
        if(port<1 || port>65535) throw new IOException("UDP port must be 1–65535");
        this.network=network; this.port=port;
        address=network.getByName(host.trim());
    }
    public String exchange(String command) throws IOException {
        long sent=System.nanoTime();
        log.event("GTI_TX",address.getHostAddress()+":"+port+" "+command);
        // One socket per exchange prevents a late reply being matched to the next command.
        try(DatagramSocket socket=new DatagramSocket(null)) {
            network.bindSocket(socket);
            socket.bind(new InetSocketAddress(0));
            socket.connect(address,port);
            socket.setSoTimeout(900);
            byte[] data=command.getBytes(StandardCharsets.US_ASCII);
            socket.send(new DatagramPacket(data,data.length));
            byte[] buffer=new byte[256];
            DatagramPacket reply=new DatagramPacket(buffer,buffer.length);
            socket.receive(reply);
            String response=new String(reply.getData(),reply.getOffset(),reply.getLength(),StandardCharsets.US_ASCII);
            log.event("GTI_RX","request="+command+" response="+response+" elapsed_ms="+((System.nanoTime()-sent)/1_000_000.0));
            return response;
        } catch(IOException error) {
            log.event("GTI_ERROR","request="+command+" elapsed_ms="+((System.nanoTime()-sent)/1_000_000.0)+" error="+error);
            throw error;
        }
    }
    public void close() { }
}

