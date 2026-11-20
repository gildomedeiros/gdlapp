package au.com.gildo.vtc;

import android.net.Network;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import java.net.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class GtiUdpTransport implements GtiClient.Transport {
    private final Network network;
    private final ConnectivityManager manager;
    private final InetAddress address;
    private final int port;
    private final SessionLog log;
    private final java.util.concurrent.atomic.AtomicLong transaction=new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.ExecutorService replies=java.util.concurrent.Executors.newCachedThreadPool(r->{
        Thread t=new Thread(r,"vtc-mount-replies");t.setDaemon(true);return t;
    });
    GtiUdpTransport(ConnectivityManager manager,Network network,String host,int port,SessionLog log)throws IOException {
        this.manager=manager;this.network=network;this.address=InetAddress.getByName(host);this.port=port;this.log=log;
    }
    public String exchange(String command)throws IOException {return exchange(command,()->{});}
    @Override public String exchange(String command,Runnable sent)throws IOException {
        return exchange(command,sent,GtiReplyReader.timeoutMs(command));
    }
    @Override public String exchange(String command,Runnable sent,int timeoutMs)throws IOException {
        long start=System.nanoTime();
        long id=transaction.incrementAndGet();
        int localPort=-1;
        String expected=GtiReplyReader.expected(command);
        // A fresh port isolates replies addressed to old transactions. Some bridges
        // may redirect a delayed reply to the newest peer, so also validate its shape.
        try(DatagramSocket socket=new DatagramSocket(null)) {
            networkFor(command).bindSocket(socket);socket.bind(new InetSocketAddress(0));socket.connect(address,port);
            localPort=socket.getLocalPort();
            log.record("mount_tx","transaction",id,"command",command,"host",address.getHostAddress(),"port",port,
                "localPort",localPort,"responseTimeoutMs",timeoutMs,"expectedReply",expected);
            byte[] bytes=command.getBytes(StandardCharsets.US_ASCII);
            socket.send(new DatagramPacket(bytes,bytes.length));
            sent.run();
            return GtiReplyReader.receive(command,remaining->{
                socket.setSoTimeout(remaining);
                byte[] buffer=new byte[256];DatagramPacket reply=new DatagramPacket(buffer,buffer.length);
                socket.receive(reply);
                String value=new String(buffer,0,reply.getLength(),StandardCharsets.US_ASCII);
                boolean matched=GtiReplyReader.matches(command,value);
                log.record(matched?"mount_rx":"mount_rx_discarded","transaction",id,"command",command,"reply",value,
                    "expectedReply",expected,"matchesExpectedReply",matched,
                    "outcome",!matched?"unmatched":value.startsWith("!")?"rejected":"reply",
                    "rawBase64",android.util.Base64.encodeToString(buffer,0,reply.getLength(),android.util.Base64.NO_WRAP),
                    "byteCount",reply.getLength(),"elapsedMs",(System.nanoTime()-start)/1_000_000.0,
                    "remoteHost",reply.getAddress().getHostAddress(),"remotePort",reply.getPort(),"localPort",socket.getLocalPort());
                return value;
            },()->System.nanoTime()/1_000_000,timeoutMs);
        }catch(IOException e){
            if(e instanceof SocketTimeoutException)log.record("mount_reply_missing","transaction",id,"command",command,"localPort",localPort,"async",false);
            log.error("mount_error",e,"transaction",id,"command",command,"host",address.getHostAddress(),"port",port,
            "localPort",localPort,"responseTimeoutMs",timeoutMs,"expectedReply",expected,
            "elapsedMs",(System.nanoTime()-start)/1_000_000.0);throw e;}
    }
    @Override public void send(String command)throws IOException {send(command,()->{});}
    @Override public void send(String command,Runnable sent)throws IOException {
        long start=System.nanoTime(),id=transaction.incrementAndGet();
        DatagramSocket socket=new DatagramSocket(null);
        try {
            networkFor(command).bindSocket(socket);
            socket.bind(new InetSocketAddress(0));socket.connect(address,port);
            log.record("mount_tx","transaction",id,"command",command,"host",address.getHostAddress(),"port",port,
                "localPort",socket.getLocalPort(),"responseTimeoutMs",GtiReplyReader.timeoutMs(command),"async",true);
            byte[] bytes=command.getBytes(StandardCharsets.US_ASCII);
            socket.send(new DatagramPacket(bytes,bytes.length));
            sent.run();
            replies.execute(()->collectReplies(socket,command,id,start));
        }catch(IOException|RuntimeException e){
            socket.close();
            log.error("mount_error",e,"transaction",id,"command",command,"async",true);
            throw e;
        }
    }

    private void collectReplies(DatagramSocket socket,String command,long id,long start) {
        boolean matched=false;
        int localPort=socket.getLocalPort();
        try(DatagramSocket receiver=socket) {
            long deadline=start+GtiReplyReader.timeoutMs(command)*1_000_000L;
            byte[] buffer=new byte[256];
            while(true) {
                long remaining=deadline-System.nanoTime();
                if(remaining<=0)break;
                receiver.setSoTimeout((int)Math.max(1,remaining/1_000_000));
                DatagramPacket packet=new DatagramPacket(buffer,buffer.length);
                receiver.receive(packet);
                String reply=new String(buffer,0,packet.getLength(),StandardCharsets.US_ASCII);
                boolean belongs=!matched&&GtiReplyReader.matches(command,reply);
                matched|=belongs;
                log.record(belongs?"mount_rx":"mount_rx_discarded","transaction",id,"command",command,"reply",reply,
                    "matchesExpectedReply",belongs,"localPort",localPort,"async",true,
                    "outcome",belongs?(reply.startsWith("!")?"rejected":"acknowledged"):"unmatched",
                    "elapsedMs",(System.nanoTime()-start)/1_000_000.0);
            }
        }catch(SocketTimeoutException ignored){
            // The receive window has ended.
        }catch(IOException e){log.error("mount_error",e,"transaction",id,"command",command,"localPort",localPort,"async",true);}
        if(!matched)log.record("mount_reply_missing","transaction",id,"command",command,"localPort",localPort,"async",true);
    }

    private Network networkFor(String command)throws IOException {
        NetworkCapabilities cap=manager.getNetworkCapabilities(network);
        if(cap!=null&&cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))return network;
        if(!StopCompletion.recoveryCommand(command))throw new IOException("Original Wi-Fi lost; reconnect before motion");
        Network wifi=null;
        for(Network candidate:manager.getAllNetworks()) {
            NetworkCapabilities available=manager.getNetworkCapabilities(candidate);
            if(available!=null&&available.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                if(wifi!=null)throw new IOException("Multiple Wi-Fi networks; cannot select Stop connection");
                wifi=candidate;
            }
        }
        if(wifi==null)throw new IOException("No Wi-Fi available for Stop");
        log.record("stop_network_reselected","oldNetwork",network.toString(),"network",wifi.toString(),"command",command);
        return wifi;
    }
    public void close(){replies.shutdown();}
}

