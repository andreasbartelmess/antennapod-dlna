// AntennaPod DLNA (inoffiziell): gegenüber AntennaPod verändert am 2026-11-10, siehe NOTICE. GPL-3.0.
package de.danoeh.antennapod.playback.cast;

import android.content.ComponentName;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

/**
 * Activity that allows for showing the MediaRouter button whenever there's a cast device in the
 * network.
 */
public abstract class CastEnabledActivity extends AppCompatActivity {
    public static final String TAG = "CastEnabledActivity";

    private static final List<DlnaDevice> knownDevices = new ArrayList<>();
    private final List<WeakReference<MenuItem>> menuItems = new ArrayList<>();
    private final DlnaSession.Listener sessionListener = device -> updateMenuIcons();

    @Override
    protected void onStart() {
        super.onStart();
        DlnaSession.addListener(sessionListener);
        updateMenuIcons();
    }

    @Override
    protected void onStop() {
        DlnaSession.removeListener(sessionListener);
        super.onStop();
    }

    public final void requestCastButton(Menu menu) {
        if (menu.findItem(R.id.dlna_menu_item) != null) {
            return;
        }
        MenuItem item = menu.add(Menu.NONE, R.id.dlna_menu_item, Menu.NONE, R.string.dlna_play_on);
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        item.setOnMenuItemClickListener(clicked -> {
            showDeviceDialog();
            return true;
        });
        menuItems.add(new WeakReference<>(item));
        updateMenuIcons();
    }

    private void updateMenuIcons() {
        int icon = DlnaSession.getSelected() != null ? R.drawable.ic_dlna_cast_connected : R.drawable.ic_dlna_cast;
        for (WeakReference<MenuItem> reference : menuItems) {
            MenuItem item = reference.get();
            if (item != null) {
                item.setIcon(icon);
            }
        }
    }

    private void showDeviceDialog() {
        List<DlnaDevice> devices = new ArrayList<>(knownDevices);
        View content = LayoutInflater.from(this).inflate(R.layout.dlna_device_dialog, null);
        LinearLayout list = content.findViewById(R.id.dlna_device_list);
        View progress = content.findViewById(R.id.dlna_progress);
        TextView status = content.findViewById(R.id.dlna_status);
        DlnaDiscovery[] discovery = {null};
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.dlna_play_on)
                .setView(content)
                .setNegativeButton(android.R.string.cancel, null)
                .setNeutralButton(R.string.dlna_search_again, null)
                .create();
        Runnable[] refresh = {null};
        refresh[0] = () -> {
            list.removeAllViews();
            boolean tablet = getResources().getConfiguration().smallestScreenWidthDp >= 600;
            addRow(list, tablet ? R.drawable.ic_dlna_tablet : R.drawable.ic_dlna_phone,
                    getString(R.string.dlna_this_device), getString(R.string.dlna_this_device_detail),
                    DlnaSession.getSelected() == null, () -> {
                        DlnaSession.select(null);
                        dialog.dismiss();
                    });
            for (DlnaDevice device : devices) {
                addRow(list, R.drawable.ic_dlna_speaker, device.friendlyName,
                        getString(R.string.dlna_renderer_detail, Uri.parse(device.avTransportUrl).getHost()),
                        device.equals(DlnaSession.getSelected()), () -> {
                            startPlaybackService();
                            DlnaSession.select(device);
                            dialog.dismiss();
                        });
            }
        };
        Runnable search = () -> {
            if (discovery[0] != null) {
                discovery[0].cancel();
            }
            progress.setVisibility(View.VISIBLE);
            status.setText(R.string.dlna_searching);
            status.setVisibility(devices.isEmpty() ? View.VISIBLE : View.GONE);
            DlnaDiscovery current = new DlnaDiscovery(this);
            discovery[0] = current;
            current.start(new DlnaDiscovery.Callback() {
                @Override
                public void onDeviceFound(DlnaDevice device) {
                    if (current != discovery[0]) {
                        return;
                    }
                    devices.remove(device);
                    devices.add(device);
                    synchronized (knownDevices) {
                        knownDevices.remove(device);
                        knownDevices.add(device);
                    }
                    status.setVisibility(View.GONE);
                    refresh[0].run();
                }

                @Override
                public void onSearchFinished() {
                    if (current != discovery[0]) {
                        return;
                    }
                    progress.setVisibility(View.INVISIBLE);
                    status.setText(R.string.dlna_no_devices);
                    status.setVisibility(devices.isEmpty() ? View.VISIBLE : View.GONE);
                }
            });
        };
        refresh[0].run();
        dialog.setOnDismissListener(d -> {
            if (discovery[0] != null) {
                discovery[0].cancel();
            }
        });
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> search.run());
        search.run();
    }

    private void addRow(LinearLayout list, int icon, String name, String detail, boolean selected,
                        Runnable onClick) {
        View row = LayoutInflater.from(this).inflate(R.layout.dlna_device_row, list, false);
        ImageView iconView = row.findViewById(R.id.dlna_icon);
        TextView nameView = row.findViewById(R.id.dlna_name);
        iconView.setImageResource(icon);
        nameView.setText(name);
        ((TextView) row.findViewById(R.id.dlna_detail)).setText(detail);
        row.findViewById(R.id.dlna_check).setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        if (selected) {
            int primary = MaterialColors.getColor(row, android.R.attr.colorPrimary);
            nameView.setTextColor(primary);
            iconView.setImageTintList(ColorStateList.valueOf(primary));
        }
        row.setOnClickListener(v -> onClick.run());
        list.addView(row);
    }

    private void startPlaybackService() {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(this,
                "de.danoeh.antennapod.playback.service.Media3PlaybackService"));
        try {
            startService(intent);
        } catch (IllegalStateException e) {
            Log.e(TAG, "Unable to start playback service", e);
        }
    }

    @Nullable
    public static DlnaDevice getSelectedDevice() {
        return DlnaSession.getSelected();
    }
}
