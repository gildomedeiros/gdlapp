package au.com.gildo.fm;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiNetworkSpecifier;
import android.os.Build;
import android.os.Handler;
import java.io.IOException;
import java.util.function.Consumer;

/** The exact-SSID request is owned by the service so it survives closing the settings screen. */
final class GdlWifi {
    private final ConnectivityManager manager;
    private final Handler main;
    private final Consumer<Boolean> changed;
    private ConnectivityManager.NetworkCallback callback;
    private volatile Network network;
    private volatile boolean requested;
    private volatile ConnectionFlag flag=ConnectionFlag.failed("Disconnected");

    GdlWifi(ConnectivityManager manager,Handler main,Consumer<Boolean> changed) {
        this.manager=manager;
        this.main=main;
        this.changed=changed;
    }

    boolean requested() { return requested; }
    ConnectionFlag flag() { return flag; }

    Network route() throws IOException {
        Network selected=network;
        if(requested && selected==null) throw new IOException("GDL Wi-Fi is disconnected");
        return selected;
    }

    void request(String security,String password) {
        release();
        requested=true;
        if(Build.VERSION.SDK_INT<29) {
            fail("Requires Android 10 or newer");
            return;
        }
        try {
            WifiNetworkSpecifier.Builder wifi=new WifiNetworkSpecifier.Builder().setSsid("GDL");
            if("WPA2".equals(security)) wifi.setWpa2Passphrase(password);
            else if("WPA3".equals(security)) wifi.setWpa3Passphrase(password);
            NetworkRequest request=new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).setNetworkSpecifier(wifi.build()).build();
            callback=new ConnectivityManager.NetworkCallback() {
                @Override public void onLinkPropertiesChanged(Network available,android.net.LinkProperties properties) {
                    if(callback!=this || available.equals(network)) return;
                    for(android.net.LinkAddress address:properties.getLinkAddresses()) {
                        if(address.getAddress() instanceof java.net.Inet4Address) {
                            network=available;
                            flag=ConnectionFlag.good("Connected");
                            changed.accept(true);
                            break;
                        }
                    }
                }
                @Override public void onLost(Network lost) {
                    if(callback!=this || (network!=null && !lost.equals(network))) return;
                    network=null;
                    fail("Connection lost");
                }
                @Override public void onUnavailable() {
                    if(callback!=this) return;
                    callback=null;
                    fail("Unavailable or denied");
                }
            };
            flag=ConnectionFlag.pending("Connecting");
            manager.requestNetwork(request,callback,main);
        } catch(SecurityException e) { release(); fail("Wi-Fi permission required"); }
          catch(IllegalArgumentException e) { release(); fail("Check GDL security and password"); }
          catch(RuntimeException e) { release(); fail("Connection request failed"); }
    }

    void fail(String message) {
        flag=ConnectionFlag.failed(message);
        changed.accept(false);
    }

    void release() {
        ConnectivityManager.NetworkCallback old=callback;
        callback=null;
        requested=false;
        network=null;
        flag=ConnectionFlag.failed("Disconnected");
        if(old!=null) try { manager.unregisterNetworkCallback(old); } catch(IllegalArgumentException ignored) { }
    }
}
