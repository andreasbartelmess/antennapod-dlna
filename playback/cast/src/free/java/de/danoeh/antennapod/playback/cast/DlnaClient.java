// AntennaPod DLNA (inoffiziell): neu hinzugefügt am 2026-11-10, siehe NOTICE. GPL-3.0.
package de.danoeh.antennapod.playback.cast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DlnaClient {
    private static final int TIMEOUT_MS = 5000;

    private final DlnaDevice device;

    public DlnaClient(DlnaDevice device) {
        this.device = device;
    }

    public void setUri(String url, String title, @Nullable String artist,
                       @Nullable String artUrl, String mimeType) throws IOException {
        StringBuilder didl = new StringBuilder();
        didl.append("<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\"")
                .append(" xmlns:dc=\"http://purl.org/dc/elements/1.1/\"")
                .append(" xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">")
                .append("<item id=\"0\" parentID=\"-1\" restricted=\"1\">")
                .append("<dc:title>").append(escape(title)).append("</dc:title>");
        if (artist != null) {
            didl.append("<upnp:artist>").append(escape(artist)).append("</upnp:artist>");
        }
        if (artUrl != null) {
            didl.append("<upnp:albumArtURI>").append(escape(artUrl)).append("</upnp:albumArtURI>");
        }
        didl.append("<upnp:class>object.item.audioItem.musicTrack</upnp:class>")
                .append("<res protocolInfo=\"http-get:*:").append(escape(mimeType)).append(":*\">")
                .append(escape(url)).append("</res></item></DIDL-Lite>");
        avTransport("SetAVTransportURI", "<CurrentURI>" + escape(url) + "</CurrentURI>"
                + "<CurrentURIMetaData>" + escape(didl.toString()) + "</CurrentURIMetaData>");
    }

    public void play() throws IOException {
        avTransport("Play", "<Speed>1</Speed>");
    }

    public void pause() throws IOException {
        avTransport("Pause", "");
    }

    public void stop() throws IOException {
        avTransport("Stop", "");
    }

    public void seek(long positionMs) throws IOException {
        avTransport("Seek", "<Unit>REL_TIME</Unit><Target>" + formatTime(positionMs) + "</Target>");
    }

    @NonNull
    public String getTransportState() throws IOException {
        String state = extract(avTransport("GetTransportInfo", ""), "CurrentTransportState");
        return state == null ? "" : state;
    }

    public long[] getPosition() throws IOException {
        String response = avTransport("GetPositionInfo", "");
        return new long[] {parseTime(extract(response, "RelTime")), parseTime(extract(response, "TrackDuration"))};
    }

    public int getVolume() throws IOException {
        if (device.renderingControlUrl == null) {
            return -1;
        }
        String value = extract(renderingControl("GetVolume", "<Channel>Master</Channel>"), "CurrentVolume");
        try {
            return value == null ? -1 : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public void setVolume(int volume) throws IOException {
        if (device.renderingControlUrl != null) {
            renderingControl("SetVolume", "<Channel>Master</Channel><DesiredVolume>" + volume + "</DesiredVolume>");
        }
    }

    public void setMute(boolean muted) throws IOException {
        if (device.renderingControlUrl != null) {
            renderingControl("SetMute", "<Channel>Master</Channel><DesiredMute>" + (muted ? 1 : 0) + "</DesiredMute>");
        }
    }

    private String avTransport(String action, String args) throws IOException {
        return soap(device.avTransportUrl, device.avTransportType, action, "<InstanceID>0</InstanceID>" + args);
    }

    private String renderingControl(String action, String args) throws IOException {
        return soap(device.renderingControlUrl, device.renderingControlType, action, "<InstanceID>0</InstanceID>" + args);
    }

    private static String soap(String controlUrl, String serviceType, String action, String args)
            throws IOException {
        String body = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\""
                + " s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>"
                + "<u:" + action + " xmlns:u=\"" + serviceType + "\">" + args + "</u:" + action + ">"
                + "</s:Body></s:Envelope>";
        HttpURLConnection connection = (HttpURLConnection) new URL(controlUrl).openConnection();
        try {
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"");
            connection.setRequestProperty("SOAPACTION", "\"" + serviceType + "#" + action + "\"");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            try (OutputStream out = connection.getOutputStream()) {
                out.write(bytes);
            }
            int code = connection.getResponseCode();
            InputStream in = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String response = in == null ? "" : readAll(in);
            if (code >= 400) {
                String upnpError = extract(response, "errorCode");
                throw new IOException(action + " failed: HTTP " + code
                        + (upnpError != null ? ", UPnP " + upnpError : ""));
            }
            return response;
        } finally {
            connection.disconnect();
        }
    }

    static String readAll(InputStream in) throws IOException {
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toString("UTF-8");
        }
    }

    @Nullable
    static String extract(String xml, String tag) {
        Matcher matcher = Pattern.compile("<(?:\\w+:)?" + tag + "(?:\\s[^>]*)?>([^<]*)</(?:\\w+:)?" + tag + ">")
                .matcher(xml);
        return matcher.find() ? unescape(matcher.group(1)) : null;
    }

    static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String unescape(String text) {
        return text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
    }

    static String formatTime(long ms) {
        long seconds = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%d:%02d:%02d", seconds / 3600, (seconds / 60) % 60, seconds % 60);
    }

    static long parseTime(@Nullable String time) {
        if (time == null || time.isEmpty() || time.startsWith("NOT_IMPLEMENTED")) {
            return -1;
        }
        String[] parts = time.trim().split(":");
        if (parts.length != 3) {
            return -1;
        }
        try {
            return (Long.parseLong(parts[0]) * 3600 + Long.parseLong(parts[1]) * 60) * 1000
                    + (long) (Double.parseDouble(parts[2]) * 1000);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
