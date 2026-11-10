package de.danoeh.antennapod.playback.cast;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Xml;
import androidx.annotation.Nullable;
import java.io.IOException;
import java.io.StringReader;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

public class DlnaDiscovery {
    private static final String TAG = "DlnaDiscovery";
    private static final String SEARCH_TARGET = "urn:schemas-upnp-org:device:MediaRenderer:1";
    private static final int SEARCH_DURATION_MS = 4000;

    public interface Callback {
        void onDeviceFound(DlnaDevice device);

        void onSearchFinished();
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile boolean cancelled = false;

    public DlnaDiscovery(Context context) {
        this.context = context.getApplicationContext();
    }

    public void start(Callback callback) {
        new Thread(() -> {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            WifiManager.MulticastLock lock = wifi == null ? null : wifi.createMulticastLock(TAG);
            if (lock != null) {
                lock.setReferenceCounted(false);
                lock.acquire();
            }
            try {
                search(callback);
            } catch (IOException e) {
                Log.e(TAG, "Search failed", e);
            } finally {
                if (lock != null) {
                    lock.release();
                }
                mainHandler.post(callback::onSearchFinished);
            }
        }, TAG).start();
    }

    public void cancel() {
        cancelled = true;
    }

    private void search(Callback callback) throws IOException {
        String request = "M-SEARCH * HTTP/1.1\r\n"
                + "HOST: 239.255.255.250:1900\r\n"
                + "MAN: \"ssdp:discover\"\r\n"
                + "MX: 2\r\n"
                + "ST: " + SEARCH_TARGET + "\r\n\r\n";
        byte[] data = request.getBytes(StandardCharsets.US_ASCII);
        Set<String> seen = new HashSet<>();
        try (DatagramSocket socket = new DatagramSocket()) {
            InetAddress group = InetAddress.getByName("239.255.255.250");
            for (int i = 0; i < 3; i++) {
                socket.send(new DatagramPacket(data, data.length, group, 1900));
            }
            long end = System.currentTimeMillis() + SEARCH_DURATION_MS;
            byte[] buffer = new byte[4096];
            while (!cancelled) {
                long remaining = end - System.currentTimeMillis();
                if (remaining <= 0) {
                    break;
                }
                socket.setSoTimeout((int) remaining);
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException e) {
                    break;
                }
                String response = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                Matcher matcher = Pattern.compile("(?im)^LOCATION:\\s*(\\S+)").matcher(response);
                if (!matcher.find() || !seen.add(matcher.group(1))) {
                    continue;
                }
                String location = matcher.group(1);
                try {
                    DlnaDevice device = describe(location);
                    if (device != null && !cancelled) {
                        mainHandler.post(() -> callback.onDeviceFound(device));
                    }
                } catch (IOException | XmlPullParserException e) {
                    Log.w(TAG, "Unable to read device description " + location, e);
                }
            }
        }
    }

    @Nullable
    static DlnaDevice describe(String location) throws IOException, XmlPullParserException {
        HttpURLConnection connection = (HttpURLConnection) new URL(location).openConnection();
        String xml;
        try {
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            xml = DlnaClient.readAll(connection.getInputStream());
        } finally {
            connection.disconnect();
        }
        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new StringReader(xml));
        String friendlyName = null;
        String udn = null;
        String urlBase = null;
        String serviceType = null;
        String controlUrl = null;
        String avTransportUrl = null;
        String avTransportType = null;
        String renderingUrl = null;
        String renderingType = null;
        String tag = null;
        for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
            if (event == XmlPullParser.START_TAG) {
                tag = parser.getName();
                if ("service".equals(tag)) {
                    serviceType = null;
                    controlUrl = null;
                }
            } else if (event == XmlPullParser.TEXT && tag != null) {
                String text = parser.getText().trim();
                if (text.isEmpty()) {
                    continue;
                }
                switch (tag) {
                    case "friendlyName":
                        friendlyName = friendlyName == null ? text : friendlyName;
                        break;
                    case "UDN":
                        udn = udn == null ? text : udn;
                        break;
                    case "URLBase":
                        urlBase = text;
                        break;
                    case "serviceType":
                        serviceType = text;
                        break;
                    case "controlURL":
                        controlUrl = text;
                        break;
                    default:
                        break;
                }
            } else if (event == XmlPullParser.END_TAG) {
                if ("service".equals(parser.getName()) && serviceType != null && controlUrl != null) {
                    String lower = serviceType.toLowerCase(Locale.ROOT);
                    if (lower.contains(":service:avtransport:") && avTransportUrl == null) {
                        avTransportUrl = controlUrl;
                        avTransportType = serviceType;
                    } else if (lower.contains(":service:renderingcontrol:") && renderingUrl == null) {
                        renderingUrl = controlUrl;
                        renderingType = serviceType;
                    }
                }
                tag = null;
            }
        }
        if (avTransportUrl == null) {
            return null;
        }
        URL base = new URL(urlBase != null ? urlBase : location);
        return new DlnaDevice(udn != null ? udn : location,
                friendlyName != null ? friendlyName : base.getHost(),
                new URL(base, avTransportUrl).toString(), avTransportType,
                renderingUrl != null ? new URL(base, renderingUrl).toString() : null, renderingType);
    }
}
