// AntennaPod DLNA (inoffiziell): neu hinzugefügt am 2026-11-10, siehe NOTICE. GPL-3.0.
package de.danoeh.antennapod.playback.cast;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.DeviceInfo;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.SimpleBasePlayer;
import androidx.media3.common.util.UnstableApi;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import de.danoeh.antennapod.playback.base.MediaItemAdapter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@OptIn(markerClass = UnstableApi.class)
public class DlnaPlayer extends SimpleBasePlayer {
    private static final String TAG = "DlnaPlayer";
    private static final long POLL_INTERVAL_MS = 1000;
    private static final long COMMAND_GRACE_MS = 4000;
    private static final long START_TIMEOUT_MS = 30000;
    private static final int VOLUME_STEP = 5;

    private final DlnaDevice device;
    private final DlnaClient client;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pollRunnable = this::poll;

    private final List<MediaItem> mediaItems = new ArrayList<>();
    private int currentIndex = 0;
    private boolean playWhenReady = false;
    private int playbackState = STATE_IDLE;
    @Nullable
    private PlaybackException playerError = null;
    private long positionMs = 0;
    private long positionUpdatedAt = 0;
    private long durationMs = C.TIME_UNSET;
    private int volume = 50;
    private boolean muted = false;
    private boolean loaded = false;
    private boolean sawPlaying = false;
    private long pendingSeekMs = -1;
    private long loadStartedAt = 0;
    private long lastCommandAt = 0;
    private int loadGeneration = 0;
    private int pollCount = 0;
    private boolean pollInFlight = false;
    private boolean released = false;

    public DlnaPlayer(DlnaDevice device) {
        super(Looper.getMainLooper());
        this.device = device;
        this.client = new DlnaClient(device);
    }

    public DlnaDevice getDevice() {
        return device;
    }

    @NonNull
    @Override
    protected State getState() {
        Commands commands = new Commands.Builder()
                .addAll(COMMAND_PLAY_PAUSE, COMMAND_PREPARE, COMMAND_STOP, COMMAND_RELEASE,
                        COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM, COMMAND_SEEK_TO_DEFAULT_POSITION,
                        COMMAND_SEEK_BACK, COMMAND_SEEK_FORWARD, COMMAND_SEEK_TO_MEDIA_ITEM,
                        COMMAND_SET_MEDIA_ITEM, COMMAND_CHANGE_MEDIA_ITEMS,
                        COMMAND_GET_CURRENT_MEDIA_ITEM, COMMAND_GET_TIMELINE, COMMAND_GET_METADATA,
                        COMMAND_GET_DEVICE_VOLUME, COMMAND_SET_DEVICE_VOLUME,
                        COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS, COMMAND_ADJUST_DEVICE_VOLUME,
                        COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS)
                .build();
        List<MediaItemData> playlist = new ArrayList<>();
        for (int i = 0; i < mediaItems.size(); i++) {
            MediaItem item = mediaItems.get(i);
            MediaItemData.Builder builder = new MediaItemData.Builder(i + ":" + item.mediaId)
                    .setMediaItem(item)
                    .setIsSeekable(true);
            if (i == currentIndex && durationMs > 0) {
                builder.setDurationUs(durationMs * 1000);
            }
            playlist.add(builder.build());
        }
        int state = mediaItems.isEmpty() && playbackState != STATE_IDLE ? STATE_ENDED : playbackState;
        boolean advancing = state == STATE_READY && playWhenReady && sawPlaying;
        long basePosition = positionMs;
        long baseTime = positionUpdatedAt;
        PositionSupplier position = advancing
                ? () -> basePosition + (SystemClock.elapsedRealtime() - baseTime)
                : PositionSupplier.getConstant(positionMs);
        return new State.Builder()
                .setAvailableCommands(commands)
                .setPlayWhenReady(playWhenReady, PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
                .setPlaybackState(state)
                .setPlayerError(playerError)
                .setPlaylist(playlist)
                .setCurrentMediaItemIndex(mediaItems.isEmpty() ? C.INDEX_UNSET : currentIndex)
                .setContentPositionMs(position)
                .setDeviceInfo(new DeviceInfo.Builder(DeviceInfo.PLAYBACK_TYPE_REMOTE)
                        .setMinVolume(0).setMaxVolume(100).build())
                .setDeviceVolume(volume)
                .setIsDeviceMuted(muted)
                .build();
    }

    @NonNull
    @Override
    protected ListenableFuture<?> handleSetMediaItems(@NonNull List<MediaItem> items, int startIndex,
                                                      long startPositionMs) {
        mediaItems.clear();
        mediaItems.addAll(items);
        currentIndex = startIndex == C.INDEX_UNSET ? 0 : Math.min(startIndex, Math.max(0, items.size() - 1));
        setLocalPosition(startPositionMs == C.TIME_UNSET ? 0 : startPositionMs);
        unload();
        if (playbackState != STATE_IDLE) {
            load();
        }
        return Futures.immediateVoidFuture();
    }

    @NonNull
    @Override
    protected ListenableFuture<?> handleAddMediaItems(int index, @NonNull List<MediaItem> items) {
        mediaItems.addAll(index, items);
        if (mediaItems.size() > items.size() && index <= currentIndex) {
            currentIndex += items.size();
        }
        return Futures.immediateVoidFuture();
    }

    @NonNull
    @Override
    protected ListenableFuture<?> handleRemoveMediaItems(int fromIndex, int toIndex) {
        boolean currentRemoved = currentIndex >= fromIndex && currentIndex < toIndex;
        mediaItems.subList(fromIndex, toIndex).clear();
        if (currentRemoved) {
            currentIndex = Math.min(fromIndex, Math.max(0, mediaItems.size() - 1));
            setLocalPosition(0);
            stopRemote();
            unload();
            if (!mediaItems.isEmpty() && playbackState != STATE_IDLE) {
                load();
            }
        } else if (currentIndex >= toIndex) {
            currentIndex -= toIndex - fromIndex;
        }
        return Futures.immediateVoidFuture();
    }

    @NonNull
    @Override
    protected ListenableFuture<?> handleReplaceMediaItems(int fromIndex, int toIndex,
                                                          @NonNull List<MediaItem> items) {
        boolean currentReplaced = currentIndex >= fromIndex && currentIndex < toIndex;
        String oldUrl = currentReplaced ? urlOf(mediaItems.get(currentIndex)) : null;
        mediaItems.subList(fromIndex, toIndex).clear();
        mediaItems.addAll(fromIndex, items);
        if (currentReplaced) {
            currentIndex = Math.min(currentIndex, Math.max(0, mediaItems.size() - 1));
            boolean sameMedia = !mediaItems.isEmpty() && oldUrl != null
                    && oldUrl.equals(urlOf(mediaItems.get(currentIndex)));
            if (!sameMedia) {
                setLocalPosition(0);
                unload();
                if (!mediaItems.isEmpty() && playbackState != STATE_IDLE) {
                    load();
                }
            }
        } else if (currentIndex >= toIndex) {
            currentIndex += items.size() - (toIndex - fromIndex);
        }
        return Futures.immediateVoidFuture();
    }

    @NonNull
    @Override
    protected ListenableFuture<?> handlePrepare() {
        playerError = null;
        if (mediaItems.isEmpty()) {
            playbackState = STATE_ENDED;
            return Futures.immediateVoidFuture();
        }
        if (!loaded) {
            load();
        }
        return Futures.immediateVoidFuture();
    }

    @NonNull
    @Override
    protected ListenableFuture<?> handleSetPlayWhenReady(boolean playWhenReady) {
        this.playWhenReady = playWhenReady;
        if (playbackState == STATE_IDLE || mediaItems.isEmpty()) {
            return Futures.immediateVoidFuture();
        }
        if (playbackState == STATE_ENDED) {
            return Futures.immediateVoidFuture();
        }
        if (!loaded) {
            load();
            return Futures.immediateVoidFuture();
        }
        setLocalPosition(currentPositionEstimate());
        markCommand();
        if (playWhenReady) {
            long seekTo = pendingSeekMs;
            pendingSeekMs = -1;
            run(() -> playAndSeek(seekTo));
        } else {
            run(this::pauseRemote);
        }
        return Futures.immediateVoidFuture();
    }

    @NonNull
    @Override
    protected ListenableFuture<?> handleSeek(int mediaItemIndex, long positionMs, int seekCommand) {
        long target = positionMs == C.TIME_UNSET ? 0 : Math.max(0, positionMs);
        if (mediaItemIndex != C.INDEX_UNSET && mediaItemIndex != currentIndex
                && mediaItemIndex < mediaItems.size()) {
            currentIndex = mediaItemIndex;
            setLocalPosition(target);
            stopRemote();
            unload();
            if (playbackState != STATE_IDLE) {
                load();
            }
            return Futures.immediateVoidFuture();
        }
        setLocalPosition(target);
        if (playbackState == STATE_ENDED) {
            playbackState = STATE_BUFFERING;
            unload();
            load();
        } else if (loaded && sawPlaying) {
            markCommand();
            run(() -> client.seek(target));
        } else {
            pendingSeekMs = target;
        }
        return Futures.immediateVoidFuture();
    }

    @NonNull
    @Override
    protected ListenableFuture<?> handleStop() {
        stopRemote();
        unload();
        playbackState = STATE_IDLE;
        playerError = null;
        return Futures.immediateVoidFuture();
    }

    @NonNull
    @Override
    protected ListenableFuture<?> handleRelease() {
        released = true;
        handler.removeCallbacks(pollRunnable);
        executor.shutdown();
        return Futures.immediateVoidFuture();
    }

    @NonNull
    @Override
    protected ListenableFuture<?> handleSetDeviceVolume(int deviceVolume, int flags) {
        volume = Math.max(0, Math.min(100, deviceVolume));
        int target = volume;
        run(() -> client.setVolume(target));
        return Futures.immediateVoidFuture();
    }

    @NonNull
    @Override
    protected ListenableFuture<?> handleIncreaseDeviceVolume(int flags) {
        return handleSetDeviceVolume(volume + VOLUME_STEP, flags);
    }

    @NonNull
    @Override
    protected ListenableFuture<?> handleDecreaseDeviceVolume(int flags) {
        return handleSetDeviceVolume(volume - VOLUME_STEP, flags);
    }

    @NonNull
    @Override
    protected ListenableFuture<?> handleSetDeviceMuted(boolean muted, int flags) {
        this.muted = muted;
        run(() -> client.setMute(muted));
        return Futures.immediateVoidFuture();
    }

    private void load() {
        if (mediaItems.isEmpty()) {
            return;
        }
        MediaItem item = mediaItems.get(currentIndex);
        String url = urlOf(item);
        playbackState = STATE_BUFFERING;
        playerError = null;
        loaded = true;
        sawPlaying = false;
        durationMs = C.TIME_UNSET;
        loadStartedAt = SystemClock.elapsedRealtime();
        markCommand();
        int generation = ++loadGeneration;
        long startAt = positionMs;
        boolean autoplay = playWhenReady;
        if (url == null) {
            fail(new IOException("No network URL for media item " + item.mediaId), generation);
            return;
        }
        String title = item.mediaMetadata.title != null ? item.mediaMetadata.title.toString() : "AntennaPod";
        String artist = item.mediaMetadata.artist != null ? item.mediaMetadata.artist.toString()
                : item.mediaMetadata.subtitle != null ? item.mediaMetadata.subtitle.toString() : null;
        String art = item.mediaMetadata.artworkUri != null
                && item.mediaMetadata.artworkUri.toString().startsWith("http")
                ? item.mediaMetadata.artworkUri.toString() : null;
        String mime = item.localConfiguration != null && item.localConfiguration.mimeType != null
                ? item.localConfiguration.mimeType : "audio/mpeg";
        executor.execute(() -> {
            try {
                try {
                    client.stop();
                } catch (IOException ignored) {
                    // Renderer may reject Stop when nothing is loaded
                }
                client.setUri(url, title, artist, art, mime);
                if (autoplay) {
                    playAndSeek(startAt > 1000 ? startAt : -1);
                } else {
                    handler.post(() -> {
                        if (generation == loadGeneration) {
                            pendingSeekMs = startAt > 1000 ? startAt : -1;
                            playbackState = STATE_READY;
                            invalidateState();
                        }
                    });
                }
            } catch (IOException e) {
                fail(e, generation);
            }
        });
        schedulePoll();
    }

    private void playAndSeek(long seekMs) throws IOException {
        client.play();
        for (int i = 0; i < 20; i++) {
            sleep(i < 3 ? 700 : 500);
            String state = client.getTransportState();
            if ("PLAYING".equals(state)) {
                break;
            }
            if (i < 3 && !"TRANSITIONING".equals(state)) {
                try {
                    client.play();
                } catch (IOException ignored) {
                    // Retried by the next iteration
                }
            }
        }
        if (seekMs > 0) {
            client.seek(seekMs);
        }
    }

    private void pauseRemote() {
        try {
            client.pause();
        } catch (IOException e) {
            Log.w(TAG, "Pause rejected", e);
        }
    }

    private void stopRemote() {
        if (!loaded) {
            return;
        }
        executor.execute(() -> {
            try {
                client.stop();
            } catch (IOException e) {
                Log.w(TAG, "Stop rejected", e);
            }
        });
    }

    private void unload() {
        loaded = false;
        sawPlaying = false;
        pendingSeekMs = -1;
        durationMs = C.TIME_UNSET;
        loadGeneration++;
        handler.removeCallbacks(pollRunnable);
    }

    private void fail(Exception e, int generation) {
        Log.e(TAG, "DLNA playback failed", e);
        handler.post(() -> {
            if (generation != loadGeneration || released) {
                return;
            }
            loaded = false;
            playbackState = STATE_IDLE;
            playerError = new PlaybackException(device.friendlyName + ": " + e.getMessage(), e,
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED);
            invalidateState();
        });
    }

    private void schedulePoll() {
        handler.removeCallbacks(pollRunnable);
        handler.postDelayed(pollRunnable, POLL_INTERVAL_MS);
    }

    private void poll() {
        if (released || !loaded || playbackState == STATE_IDLE) {
            return;
        }
        if (pollInFlight) {
            schedulePoll();
            return;
        }
        pollInFlight = true;
        int generation = loadGeneration;
        boolean readVolume = pollCount++ % 5 == 0;
        executor.execute(() -> {
            String state;
            long[] position;
            int remoteVolume = -1;
            try {
                state = client.getTransportState();
                position = client.getPosition();
                if (readVolume) {
                    remoteVolume = client.getVolume();
                }
            } catch (IOException e) {
                Log.w(TAG, "Poll failed", e);
                handler.post(() -> {
                    pollInFlight = false;
                    schedulePoll();
                });
                return;
            }
            int newVolume = remoteVolume;
            handler.post(() -> {
                pollInFlight = false;
                if (generation == loadGeneration && !released) {
                    applyRemoteState(state, position[0], position[1], newVolume);
                }
                schedulePoll();
            });
        });
    }

    private void applyRemoteState(String state, long remotePosition, long remoteDuration, int remoteVolume) {
        long now = SystemClock.elapsedRealtime();
        boolean inGrace = now - lastCommandAt < COMMAND_GRACE_MS;
        if (remoteDuration > 0) {
            durationMs = remoteDuration;
        }
        if (remoteVolume >= 0) {
            volume = remoteVolume;
        }
        switch (state) {
            case "PLAYING":
                sawPlaying = true;
                if (pendingSeekMs < 0 && remotePosition >= 0) {
                    setLocalPosition(remotePosition);
                }
                playbackState = STATE_READY;
                if (!playWhenReady && !inGrace) {
                    playWhenReady = true;
                }
                break;
            case "PAUSED_PLAYBACK":
            case "PAUSED_RECORDING":
                sawPlaying = true;
                if (remotePosition >= 0) {
                    setLocalPosition(remotePosition);
                }
                playbackState = STATE_READY;
                if (playWhenReady && !inGrace) {
                    playWhenReady = false;
                }
                break;
            case "TRANSITIONING":
                playbackState = STATE_BUFFERING;
                break;
            case "STOPPED":
            case "NO_MEDIA_PRESENT":
                if (inGrace) {
                    break;
                }
                if (!sawPlaying) {
                    if (playWhenReady && now - loadStartedAt > START_TIMEOUT_MS) {
                        fail(new IOException("Renderer did not start playback"), loadGeneration);
                    } else if (!playWhenReady) {
                        playbackState = STATE_READY;
                    }
                    break;
                }
                if (durationMs > 0 && positionMs >= durationMs - 5000) {
                    setLocalPosition(durationMs);
                    playbackState = STATE_ENDED;
                    unload();
                } else {
                    playWhenReady = false;
                    playbackState = STATE_READY;
                    unload();
                    loaded = false;
                    pendingSeekMs = positionMs;
                }
                break;
            default:
                break;
        }
        invalidateState();
    }

    private void setLocalPosition(long position) {
        positionMs = position;
        positionUpdatedAt = SystemClock.elapsedRealtime();
    }

    private long currentPositionEstimate() {
        if (playbackState == STATE_READY && playWhenReady && sawPlaying) {
            return positionMs + (SystemClock.elapsedRealtime() - positionUpdatedAt);
        }
        return positionMs;
    }

    private void markCommand() {
        lastCommandAt = SystemClock.elapsedRealtime();
    }

    private void run(RemoteCall call) {
        if (released) {
            return;
        }
        executor.execute(() -> {
            try {
                call.run();
            } catch (IOException e) {
                Log.w(TAG, "DLNA command failed", e);
            }
        });
    }

    @Nullable
    private static String urlOf(MediaItem item) {
        if (item.mediaMetadata.extras != null) {
            String streamUrl = item.mediaMetadata.extras.getString(MediaItemAdapter.KEY_STREAM_URL);
            if (streamUrl != null && streamUrl.startsWith("http")) {
                return streamUrl;
            }
        }
        if (item.localConfiguration != null && item.localConfiguration.uri.toString().startsWith("http")) {
            return item.localConfiguration.uri.toString();
        }
        return null;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private interface RemoteCall {
        void run() throws IOException;
    }
}
