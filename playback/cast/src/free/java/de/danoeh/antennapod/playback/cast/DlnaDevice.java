package de.danoeh.antennapod.playback.cast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

public class DlnaDevice {
    public final String udn;
    public final String friendlyName;
    public final String avTransportUrl;
    public final String avTransportType;
    @Nullable
    public final String renderingControlUrl;
    @Nullable
    public final String renderingControlType;

    public DlnaDevice(@NonNull String udn, @NonNull String friendlyName,
                      @NonNull String avTransportUrl, @NonNull String avTransportType,
                      @Nullable String renderingControlUrl, @Nullable String renderingControlType) {
        this.udn = udn;
        this.friendlyName = friendlyName;
        this.avTransportUrl = avTransportUrl;
        this.avTransportType = avTransportType;
        this.renderingControlUrl = renderingControlUrl;
        this.renderingControlType = renderingControlType;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof DlnaDevice && ((DlnaDevice) o).udn.equals(udn);
    }

    @Override
    public int hashCode() {
        return udn.hashCode();
    }
}
