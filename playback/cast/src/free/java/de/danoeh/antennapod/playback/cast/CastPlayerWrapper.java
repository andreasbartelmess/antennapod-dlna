// AntennaPod DLNA (inoffiziell): gegenüber AntennaPod verändert am 2026-11-10, siehe NOTICE. GPL-3.0.
package de.danoeh.antennapod.playback.cast;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.ForwardingSimpleBasePlayer;
import androidx.media3.common.Player;
import androidx.media3.common.PlayerTransferState;
import androidx.media3.common.util.UnstableApi;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

public class CastPlayerWrapper {
    public static Player wrap(Player player, Context context) {
        return new DlnaSwitchingPlayer(player);
    }

    public static boolean hasPlaybackJustFinished(Context context) {
        return false;
    }

    @OptIn(markerClass = UnstableApi.class)
    private static class DlnaSwitchingPlayer extends ForwardingSimpleBasePlayer implements DlnaSession.Listener {
        private final Player localPlayer;

        DlnaSwitchingPlayer(Player localPlayer) {
            super(DlnaSession.getSelected() != null ? new DlnaPlayer(DlnaSession.getSelected()) : localPlayer);
            this.localPlayer = localPlayer;
            DlnaSession.addListener(this);
        }

        @Override
        public void onSelectedDeviceChanged(@Nullable DlnaDevice device) {
            Player previousPlayer = getPlayer();
            if (previousPlayer instanceof DlnaPlayer && ((DlnaPlayer) previousPlayer).getDevice().equals(device)) {
                return;
            }
            if (previousPlayer == localPlayer && device == null) {
                return;
            }
            Player newPlayer = device == null ? localPlayer : new DlnaPlayer(device);
            PlayerTransferState.fromPlayer(previousPlayer).setToPlayer(newPlayer);
            if (previousPlayer.getPlaybackState() != STATE_IDLE) {
                newPlayer.prepare();
            }
            previousPlayer.stop();
            if (previousPlayer instanceof DlnaPlayer) {
                previousPlayer.release();
            }
            setPlayer(newPlayer);
        }

        @NonNull
        @Override
        protected State getState() {
            State currentState = super.getState();
            Commands availableCommands = currentState.availableCommands;
            if (!availableCommands.contains(COMMAND_RELEASE)) {
                Commands newCommands = availableCommands.buildUpon().add(COMMAND_RELEASE).build();
                currentState = currentState.buildUpon().setAvailableCommands(newCommands).build();
            }
            return currentState;
        }

        @NonNull
        @Override
        protected ListenableFuture<?> handleRelease() {
            DlnaSession.removeListener(this);
            Player current = getPlayer();
            if (current instanceof DlnaPlayer) {
                current.release();
            }
            if (localPlayer.isCommandAvailable(COMMAND_RELEASE)) {
                localPlayer.release();
            }
            return Futures.immediateVoidFuture();
        }
    }
}
