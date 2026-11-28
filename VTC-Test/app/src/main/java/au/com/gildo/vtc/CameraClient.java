package au.com.gildo.vtc;

import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** All calls are serialized by the activity; POSTs are never automatically retried. */
final class CameraClient {
    private final ConnectivityManager connectivity;
    private final String base;
    private final Map<String,String> paths = new HashMap<>();
    private final Map<String,Boolean> writable = new HashMap<>();
    private Network wifi;

    CameraClient(ConnectivityManager connectivity, String address) throws Exception {
        this.connectivity = connectivity;
        URI uri = new URI(address.trim());
        if (!"http".equals(uri.getScheme()) && !"https".equals(uri.getScheme()))
            throw new IOException("Enter the full http:// camera URL.");
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw new IOException("Invalid camera URL.");
        String path = uri.getPath();
        if (path != null && !path.isEmpty() && !path.equals("/") && !path.equals("/ccapi") && !path.equals("/ccapi/"))
            throw new IOException("Use the camera's base /ccapi URL.");
        base = new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), "/ccapi", null, null).toString();
    }

    String base() { return base; }
    boolean supports(String name) { return paths.containsKey(name); }
    boolean canWrite(String name) { return Boolean.TRUE.equals(writable.get(name)); }

    void connect() throws Exception {
        wifi = null;
        for (Network candidate : connectivity.getAllNetworks()) {
            NetworkCapabilities caps = connectivity.getNetworkCapabilities(candidate);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                wifi = candidate;
                break;
            }
        }
        if (wifi == null) throw new IOException("Connect this phone to the camera's Wi-Fi first.");
        JSONObject discovery = request("GET", base, null);
        // Prefer ver100, matching the controls verified on the SX70. Fall back to advertised versions.
        List<String> versions = new ArrayList<>();
        Iterator<String> keys = discovery.keys();
        while (keys.hasNext()) versions.add(keys.next());
        Collections.sort(versions);
        if (versions.remove("ver100")) versions.add(0, "ver100");
        for (String version : versions) {
            JSONArray entries = discovery.optJSONArray(version);
            if (entries == null) continue;
            for (int i=0; i<entries.length(); i++) {
                JSONObject entry = entries.optJSONObject(i);
                if (entry == null) continue;
                URI advertised = new URI(entry.optString("url"));
                URI origin = new URI(base);
                if (!Objects.equals(origin.getHost(), advertised.getHost())
                        || origin.getPort() != advertised.getPort()
                        || !Objects.equals(origin.getScheme(), advertised.getScheme())) continue;
                String path = advertised.getPath();
                if (path != null && path.matches("/ccapi/ver[0-9]+/shooting/liveview")
                        && entry.optBoolean("post") && !paths.containsKey("liveview")) {
                    paths.put("liveview", advertised.toString());
                    writable.put("liveview", true);
                }
                if (path != null && path.matches("/ccapi/ver[0-9]+/shooting/liveview/flip")
                        && entry.optBoolean("get")) {
                    paths.put("frame:" + path.substring(0,path.length()-5), advertised.toString());
                }
                for (String name : new String[]{"zoom", "moviemode", "recbutton"}) {
                    if (path != null && path.matches("/ccapi/ver[0-9]+/shooting/control/" + name)
                            && !paths.containsKey(name)
                            && (name.equals("zoom") ? entry.optBoolean("get") : entry.optBoolean("post"))) {
                        paths.put(name, advertised.toString());
                        writable.put(name, entry.optBoolean("post"));
                    }
                }
            }
        }
    }

    boolean supportsPreview() {
        String live = paths.get("liveview");
        if (live == null) return false;
        return paths.containsKey("frame:" + URI.create(live).getPath());
    }
    void startPreview() throws Exception {
        request("POST", writeEndpoint("liveview"), new JSONObject()
                .put("cameradisplay","on").put("liveviewsize","small"));
    }
    void stopPreview() throws Exception {
        request("POST", writeEndpoint("liveview"), new JSONObject()
                .put("cameradisplay","on").put("liveviewsize","off"));
    }
    byte[] previewFrame() throws Exception {
        String frameUrl = endpoint("frame:" + URI.create(endpoint("liveview")).getPath());
        HttpURLConnection connection = (HttpURLConnection)wifi.openConnection(new URL(frameUrl));
        try {
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestProperty("Accept","image/jpeg");
            int status = connection.getResponseCode();
            if (status != 200) {
                String detail = "";
                InputStream error = connection.getErrorStream();
                if (error != null) try (InputStream in = error) {
                    byte[] buffer = new byte[2048];
                    int count = in.read(buffer);
                    if (count > 0) detail = new String(buffer,0,count,StandardCharsets.UTF_8);
                }
                throw new IOException("Preview HTTP " + status + ": " + detail);
            }
            try (InputStream in = connection.getInputStream()) {
                return JpegFrame.read(in, 2 * 1024 * 1024);
            }
        } finally { connection.disconnect(); }
    }

    JSONObject zoom() throws Exception { return request("GET", endpoint("zoom"), null); }
    JSONObject setZoom(int value) throws Exception {
        return request("POST", writeEndpoint("zoom"), new JSONObject().put("value",value));
    }
    void startRecording() throws Exception {
        if (canWrite("moviemode"))
            request("POST", writeEndpoint("moviemode"), new JSONObject().put("action","on"));
        request("POST", writeEndpoint("recbutton"), new JSONObject().put("action","start"));
    }
    void stopRecording() throws Exception {
        request("POST", writeEndpoint("recbutton"), new JSONObject().put("action","stop"));
    }
    private String endpoint(String name) throws IOException {
        String url = paths.get(name);
        if (url == null) throw new IOException("Camera does not advertise " + name + ".");
        return url;
    }
    private String writeEndpoint(String name) throws IOException {
        if (!canWrite(name)) throw new IOException("Camera does not advertise POST " + name + ".");
        return endpoint(name);
    }
    private JSONObject request(String method, String address, JSONObject body) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) wifi.openConnection(new URL(address));
        try {
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(8000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/json");
            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }
            }
            int status = connection.getResponseCode();
            InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
            String text = "";
            if (stream != null) {
                try (InputStream in = stream; ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] buffer = new byte[4096]; int count;
                    while ((count = in.read(buffer)) != -1) {
                        if (bytes.size() + count > 1048576) throw new IOException("Camera response is too large.");
                        bytes.write(buffer,0,count);
                    }
                    text = bytes.toString("UTF-8");
                }
            }
            if (status < 200 || status >= 300) throw new IOException("HTTP " + status + ": " + text);
            return text.trim().isEmpty() ? new JSONObject() : new JSONObject(text);
        } finally { connection.disconnect(); }
    }
}

