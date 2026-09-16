package dji.v5.ux.sample.showcase.defaultlayout.aiming;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.os.SystemClock;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.function.BiConsumer;

/** Receives target fixes only. This class has no DJI or flight-control interface. */
public final class LoRaTargetLocationSource {
    private final Context context;
    private final BiConsumer<String, String> diagnostic;
    private volatile AimingSession.Fix latest;
    private volatile String status = "Connect phone Wi-Fi to GDL_LORA";
    private volatile String metrics = "No LoRa telemetry yet";
    private volatile String signal = "";
    private Run current;
    private static final class Run {
        volatile boolean running = true;
        volatile DatagramSocket socket;
        final LoRaTelemetry.Tracker tracker = new LoRaTelemetry.Tracker();
    }
    public LoRaTargetLocationSource(Context context, BiConsumer<String, String> diagnostic) {
        this.context = context.getApplicationContext(); this.diagnostic = diagnostic;
    }
    public synchronized void start() {
        if (current != null) return;
        latest = null; metrics = "No LoRa telemetry yet"; signal = "";
        Run run = new Run(); current = run;
        Thread thread = new Thread(() -> receive(run), "cam3-lora-wifi");
        thread.setDaemon(true); thread.start();
    }
    public synchronized void stop() {
        Run run = current; current = null; latest = null;
        if (run != null) { run.running = false; if (run.socket != null) run.socket.close(); }
        status = "LoRa monitoring stopped";
    }
    public AimingSession.Fix getLatestFix() { return latest; }
    public String signalSummary() { return signal; }
    public String details() { return status + "\n" + metrics; }
    private synchronized boolean live(Run run) { return current == run && run.running; }
    private synchronized void connection(Run run, String value, boolean clear) {
        if (!live(run)) return;
        status = value; if (clear) latest = null;
    }
    private Network wifiNetwork() {
        ConnectivityManager cm = context.getSystemService(ConnectivityManager.class);
        for (Network network : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return network;
        }
        return null;
    }
    private void receive(Run run) {
        WifiManager.WifiLock lock = null;
        try {
            WifiManager wm = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            lock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "cam3:lora"); lock.acquire();
            InetAddress receiver = InetAddress.getByName("192.168.4.1");
            byte[] buffer = new byte[65535];
            while (live(run)) {
                Network network = wifiNetwork();
                if (network == null) {
                    connection(run, "Connect phone Wi-Fi to GDL_LORA", true);
                    Thread.sleep(500); continue;
                }
                try (DatagramSocket socket = new DatagramSocket(null)) {
                    run.socket = socket;
                    network.bindSocket(socket);
                    socket.bind(new InetSocketAddress(5005)); socket.setSoTimeout(500);
                    connection(run, "Waiting for TTGO on GDL_LORA", true);
                    diagnostic.accept("lora_connected", "Wi-Fi socket ready; receive=5005 control=5006");
                    long helloAt = -1;
                    while (live(run) && network.equals(wifiNetwork())) {
                        long now = SystemClock.elapsedRealtime();
                        if (helloAt < 0 || now - helloAt >= 5000) {
                            byte[] hello = "HELLO".getBytes(StandardCharsets.US_ASCII);
                            socket.send(new DatagramPacket(hello, hello.length, receiver, 5006)); helloAt = now;
                        }
                        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                        try { socket.receive(packet); } catch (SocketTimeoutException timeout) { continue; }
                        long receivedAt = SystemClock.elapsedRealtime();
                        if (!receiver.equals(packet.getAddress()) || packet.getPort() != 5006) continue;
                        String message = new String(buffer, 0, packet.getLength(), StandardCharsets.UTF_8);
                        for (String line : message.split("\n")) handle(run, line.trim(), receivedAt);
                    }
                } catch (java.io.IOException error) {
                    if (live(run)) {
                        connection(run, "LoRa Wi-Fi unavailable; retrying (close lora_tester)", true);
                        diagnostic.accept("lora_connection_error", error.getClass().getSimpleName());
                        Thread.sleep(1000);
                    }
                } finally { run.socket = null; }
                connection(run, "LoRa Wi-Fi disconnected; reconnecting", true);
            }
        } catch (Exception error) {
            connection(run, "LoRa unavailable: " + error.getClass().getSimpleName(), true);
            diagnostic.accept("lora_error", error.getClass().getSimpleName());
        } finally {
            if (lock != null && lock.isHeld()) lock.release();
            synchronized (this) { if (current == run) { latest = null; current = null; } }
        }
    }
    private synchronized void handle(Run run, String line, long receivedAt) {
        if (!live(run)) return;
        if (line.equals("GDL_LILYGO_READY")) {
            status = "TTGO connected"; return; // A handshake never refreshes target freshness.
        }
        if (line.startsWith("RAW,NOFIX,")) {
            latest = null; status = "LoRa transmitter has no GPS fix";
            diagnostic.accept("lora_no_fix", "Target unavailable"); return;
        }
        try {
            LoRaTelemetry t = LoRaTelemetry.parse(line);
            boolean accepted = run.tracker.accept(t, receivedAt);
            metrics = String.format(Locale.US,
                    "RSSI %d dBm; SNR %.1f dB; satellites %d; HDOP %.2f%nSequence %d; gaps %d; repeats %d; older %d; restarts %d",
                    t.rssi, t.snr, t.satellites, t.hdop, t.sequence, run.tracker.gaps,
                    run.tracker.repeats, run.tracker.older, run.tracker.restarts);
            diagnostic.accept("lora_packet", "accepted=" + accepted + " sequence=" + t.sequence
                    + " senderMs=" + t.senderMs + " rssi=" + t.rssi + " snr=" + t.snr
                    + " satellites=" + t.satellites + " hdop=" + t.hdop + " receiverMissed=" + t.missed);
            if (accepted) {
                signal = String.format(Locale.US, "RSSI %d; SNR %.1f", t.rssi, t.snr);
                latest = new AimingSession.Fix(t.lat, t.lon, Double.NaN, receivedAt);
                status = "Receiving LoRa GPS";
            }
        } catch (IllegalArgumentException invalid) {
            diagnostic.accept("lora_rejected", "Malformed or non-GPS packet; fix age unchanged");
        }
    }
}
