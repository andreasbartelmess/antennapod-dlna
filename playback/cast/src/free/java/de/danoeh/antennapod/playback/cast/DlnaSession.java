// AntennaPod DLNA (inoffiziell): neu hinzugefügt am 2026-11-10, siehe NOTICE. GPL-3.0.
package de.danoeh.antennapod.playback.cast;

import android.os.Handler;
import android.os.Looper;
import androidx.annotation.Nullable;
import java.util.concurrent.CopyOnWriteArrayList;

public final class DlnaSession {
    public interface Listener {
        void onSelectedDeviceChanged(@Nullable DlnaDevice device);
    }

    private static final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    @Nullable
    private static volatile DlnaDevice selected;

    private DlnaSession() {
    }

    @Nullable
    public static DlnaDevice getSelected() {
        return selected;
    }

    public static void select(@Nullable DlnaDevice device) {
        mainHandler.post(() -> {
            if (device == null ? selected == null : device.equals(selected)) {
                return;
            }
            selected = device;
            for (Listener listener : listeners) {
                listener.onSelectedDeviceChanged(device);
            }
        });
    }

    public static void addListener(Listener listener) {
        listeners.add(listener);
    }

    public static void removeListener(Listener listener) {
        listeners.remove(listener);
    }
}
