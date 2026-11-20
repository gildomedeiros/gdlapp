package au.com.gildo.vtc;

import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Read-only probes on the local link. Uses no tracking or camera-control sockets. */
final class DeviceDiscovery {
    private static final byte[] FIRMWARE_QUERY={':','e','1','\r'};
    private static final int PROBE_TIMEOUT_MS=450;
    private static final int CAMERA_TIMEOUT_MS=1500;
    private static final int MAX_SCAN_HOSTS=1022;
    private static final int WORKERS=12;

    record Device(String address,String identity) {
        String label(){return identity+" · "+address;}
    }

    private record Link(Network network,Inet4Address local,int prefix) {
        long first(){return number(local)&(0xffffffffL<<(32-prefix));}
        long last(){return first()+(1L<<(32-prefix))-1;}
        boolean contains(long ip){return ip>=first()&&ip<=last();}
        boolean canScan(){return last()-first()-1<=MAX_SCAN_HOSTS;}

        DatagramSocket udp()throws IOException {
            DatagramSocket socket=new DatagramSocket(null);
            try{
                if(network!=null)network.bindSocket(socket);
                socket.bind(new InetSocketAddress(local,0));
                return socket;
            }catch(IOException|RuntimeException e){socket.close();throw e;}
        }

        Socket tcp()throws IOException {
            Socket socket=network==null?new Socket():network.getSocketFactory().createSocket();
            try{socket.bind(new InetSocketAddress(local,0));return socket;}
            catch(IOException|RuntimeException e){socket.close();throw e;}
        }
    }

    private static Link selectLink(ConnectivityManager manager,Network preferred,String saved)throws IOException {
        List<Link> links=new ArrayList<>();
        for(Network n:manager.getAllNetworks()){
            NetworkCapabilities caps=manager.getNetworkCapabilities(n);
            LinkProperties props=manager.getLinkProperties(n);
            if(caps==null||props==null||!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))continue;
            for(LinkAddress a:props.getLinkAddresses())
                if(a.getAddress() instanceof Inet4Address ip&&usablePrefix(a.getPrefixLength()))
                    links.add(new Link(n,ip,a.getPrefixLength()));
        }
        if(links.isEmpty()){
            for(NetworkInterface nic:Collections.list(NetworkInterface.getNetworkInterfaces())){
                if(!nic.isUp()||!nic.getName().matches("(?i)(wlan|swlan|softap|ap|wlp)[a-z0-9_]*"))continue;
                for(InterfaceAddress a:nic.getInterfaceAddresses())
                    if(a.getAddress() instanceof Inet4Address ip&&usablePrefix(a.getNetworkPrefixLength()))
                        links.add(new Link(null,ip,a.getNetworkPrefixLength()));
            }
        }
        if(preferred!=null){
            List<Link> preferredLinks=links.stream().filter(l->preferred.equals(l.network())).collect(java.util.stream.Collectors.toList());
            if(!preferredLinks.isEmpty())links=preferredLinks;
        }
        if(links.size()==1)return links.get(0);
        if(Settings.ipv4(saved)){
            long ip=number(InetAddress.getByName(saved));
            List<Link> matches=links.stream().filter(l->l.contains(ip)).collect(java.util.stream.Collectors.toList());
            if(matches.size()==1)return matches.get(0);
        }
        throw new IOException(links.isEmpty()?"Join the device Wi-Fi or turn on the hotspot first.":
            "More than one local Wi-Fi interface is available. Enter the device IP or select a single network.");
    }

    static List<Device> find(ConnectivityManager manager,Network preferred,boolean mount,String saved,int port)
        throws IOException,InterruptedException {
        Link link=selectLink(manager,preferred,saved);
        if(mount){
            List<Device> broadcast=broadcast(link,port);
            if(!broadcast.isEmpty())return broadcast;
        }
        long tried=-1;
        if(Settings.ipv4(saved)){
            long ip=number(InetAddress.getByName(saved));
            if(link.contains(ip)&&ip!=number(link.local())){
                tried=ip;
                Device hit=probe(link,mount,ip,port);
                if(hit!=null)return List.of(hit);
            }
        }
        if(!link.canScan())throw new IOException("This subnet is too large for a local search. Enter the device IP.");
        return scan(link,mount,port,tried);
    }

    private static List<Device> broadcast(Link link,int port)throws IOException,InterruptedException {
        if(link.prefix()>=31)return List.of();
        Map<String,Device> found=new TreeMap<>();
        try(DatagramSocket socket=link.udp()){
            socket.setBroadcast(true);
            socket.send(new DatagramPacket(FIRMWARE_QUERY,FIRMWARE_QUERY.length,ip(link.last()),port));
            long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(650);
            while(!Thread.currentThread().isInterrupted()){
                long left=TimeUnit.NANOSECONDS.toMillis(end-System.nanoTime());
                if(left<1)break;
                socket.setSoTimeout((int)left);
                DatagramPacket reply=new DatagramPacket(new byte[32],32);
                try{socket.receive(reply);}catch(SocketTimeoutException expired){break;}
                if(reply.getPort()==port&&reply.getAddress() instanceof Inet4Address&&link.contains(number(reply.getAddress()))){
                    Device device=mountReply(reply);
                    if(device!=null)found.put(device.address(),device);
                }
            }
        }catch(SocketException e){
            // Some Wi-Fi networks disable broadcast delivery. Direct queries can still work.
            return List.copyOf(found.values());
        }
        if(Thread.currentThread().isInterrupted())throw new InterruptedException();
        return List.copyOf(found.values());
    }

    private static List<Device> scan(Link link,boolean mount,int port,long tried)throws InterruptedException {
        ExecutorService pool=Executors.newFixedThreadPool(WORKERS);
        try{
            List<Callable<List<Device>>> workers=new ArrayList<>();
            long first=link.first()+(link.prefix()<31?1:0);
            long last=link.last()-(link.prefix()<31?1:0);
            long own=number(link.local());
            for(int i=0;i<WORKERS;i++){
                long start=first+i;
                workers.add(()->{
                    List<Device> matches=new ArrayList<>();
                    for(long n=start;n<=last&&!Thread.currentThread().isInterrupted();n+=WORKERS){
                        if(n==own||n==tried)continue;
                        Device d=probe(link,mount,n,port);
                        if(d!=null)matches.add(d);
                    }
                    return matches;
                });
            }
            List<Device> found=new ArrayList<>();
            for(Future<List<Device>> result:pool.invokeAll(workers)){
                try{found.addAll(result.get());}
                catch(ExecutionException e){throw new IllegalStateException("Discovery failed",e.getCause());}
            }
            found.sort(Comparator.comparing(Device::address));
            return List.copyOf(found);
        }finally{pool.shutdownNow();}
    }

    private static Device probe(Link link,boolean mount,long number,int port)throws InterruptedException {
        if(Thread.currentThread().isInterrupted())throw new InterruptedException();
        try{
            InetAddress address=ip(number);
            if(!mount)return camera(link,address,port);
            try(DatagramSocket socket=link.udp()){
                socket.connect(address,port);
                socket.setSoTimeout(PROBE_TIMEOUT_MS);
                socket.send(new DatagramPacket(FIRMWARE_QUERY,FIRMWARE_QUERY.length,address,port));
                DatagramPacket reply=new DatagramPacket(new byte[32],32);
                socket.receive(reply);
                return mountReply(reply);
            }
        }catch(IOException|IllegalArgumentException ignored){return null;}
    }

    private static Device mountReply(DatagramPacket reply) {
        String value=new String(reply.getData(),reply.getOffset(),reply.getLength(),StandardCharsets.US_ASCII);
        if(!value.matches("=[0-9A-Fa-f]{6}\\r"))return null;
        return new Device(reply.getAddress().getHostAddress(),"GTi · firmware "+value.substring(1,7));
    }

    private static Device camera(Link link,InetAddress address,int port)throws IOException {
        String host=address.getHostAddress();
        try(Socket socket=link.tcp()){
            socket.connect(new InetSocketAddress(address,port),PROBE_TIMEOUT_MS);
            socket.setSoTimeout(CAMERA_TIMEOUT_MS);
            String request="GET /ccapi/ver100/deviceinformation HTTP/1.1\r\nHost: "+host+":"+port+
                "\r\nAccept: application/json\r\nConnection: close\r\n\r\n";
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            return identifyCamera(host,readCameraBody(socket.getInputStream()));
        }
    }

    static Device identifyCamera(String host,String body) {
        if(!(ConfigJson.parse(body) instanceof Map<?,?> data))return null;
        if(!(data.get("productname") instanceof String name)||name.isBlank())return null;
        String model=name.trim();
        return "Canon".equalsIgnoreCase(String.valueOf(data.get("manufacturer")))||
            model.matches("(?i)^(?:Canon\\s+)?(?:PowerShot|EOS)(?:[\\s-].*|$)")?new Device(host,model):null;
    }

    static String readCameraBody(InputStream stream)throws IOException {
        BufferedInputStream in=new BufferedInputStream(stream);
        ByteArrayOutputStream header=new ByteArrayOutputStream();
        int tail=0;
        long deadline=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(CAMERA_TIMEOUT_MS);
        while(tail!=0x0d0a0d0a){
            int value=in.read();
            if(value<0)throw new EOFException("Incomplete camera HTTP response");
            if(header.size()>=8192||System.nanoTime()>deadline)throw new IOException("Camera HTTP headers exceeded discovery limits");
            header.write(value);
            tail=(tail<<8)|value;
        }
        String[] lines=new String(header.toByteArray(),StandardCharsets.US_ASCII).split("\r\n");
        if(!lines[0].matches("HTTP/1\\.[01] 200(?: .*)?"))throw new IOException(lines[0]);
        for(String line:lines){
            if(!line.regionMatches(true,0,"Content-Length:",0,15))continue;
            final int length;
            try{length=Integer.parseInt(line.substring(15).trim());}
            catch(NumberFormatException e){throw new IOException("Invalid camera Content-Length",e);}
            if(length<1||length>16384)throw new IOException("Camera identification response exceeded discovery limits");
            byte[] body=new byte[length];
            int offset=0;
            while(offset<body.length){
                int count=in.read(body,offset,body.length-offset);
                if(count<0)throw new EOFException("Incomplete camera HTTP body");
                if(System.nanoTime()>deadline)throw new SocketTimeoutException("Camera identification reply timed out");
                offset+=count;
            }
            return new String(body,StandardCharsets.UTF_8);
        }
        throw new IOException("Camera response omitted Content-Length");
    }

    private static boolean usablePrefix(int prefix){return prefix>=1&&prefix<=32;}

    private static long number(InetAddress address){
        long n=0;
        for(byte b:address.getAddress())n=(n<<8)|(b&255);
        return n;
    }

    private static InetAddress ip(long n)throws UnknownHostException {
        return InetAddress.getByAddress(new byte[]{(byte)(n>>24),(byte)(n>>16),(byte)(n>>8),(byte)n});
    }
}
