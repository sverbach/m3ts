package ch.m3ts.tracker.visualization.live;

import android.support.annotation.NonNull;
import android.view.View;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Date;
import java.util.Timer;

import ch.m3ts.Log;
import ch.m3ts.connection.ConnectionCallback;
import ch.m3ts.connection.ConnectionHelper;
import ch.m3ts.connection.NearbyTrackerConnection;
import ch.m3ts.connection.TrackerConnection;
import ch.m3ts.connection.pubnub.PubNubFactory;
import ch.m3ts.tabletennis.helper.Side;
import ch.m3ts.tabletennis.match.Match;
import ch.m3ts.tabletennis.match.MatchSettings;
import ch.m3ts.tabletennis.match.MatchType;
import ch.m3ts.tabletennis.match.Player;
import ch.m3ts.tabletennis.match.ServeRules;
import ch.m3ts.tabletennis.match.UICallback;
import ch.m3ts.tabletennis.match.game.GameType;
import ch.m3ts.tracker.visualization.DebugHandlerRefreshTimerTask;
import ch.m3ts.tracker.visualization.MatchVisualizeActivity;
import ch.m3ts.tracker.visualization.MatchVisualizeHandler;
import cz.fmo.Lib;
import cz.fmo.R;
import cz.fmo.camera.CameraThread;
import cz.fmo.data.Track;
import cz.fmo.util.Config;

/**
 * Renders the images received by the camera API onto the screen and also passes them to FMO.
 *
 * FMO then finds detections and tracks and forwards them to the EventDetector, which then calls
 * for events on this Handler.
 **/
public class LiveHandler extends MatchVisualizeHandler implements CameraThread.Callback, ConnectionCallback {
    private static final int CAMERA_ERROR = 2;
    private TrackerConnection connection;
    private final boolean doDrawDebugInfo;
    private final WeakReference<LiveActivity> mLiveActivity;

    public LiveHandler(@NonNull MatchVisualizeActivity activity, String matchID) {
        super(activity);
        this.doDrawDebugInfo = new Config(activity).isUseDebug();
        this.mLiveActivity = new WeakReference<>((LiveActivity) activity);
        TextView displayConnectedText = (TextView) activity.findViewById(R.id.display_connected_status);
        try {
            if(new Config(mLiveActivity.get()).isUsingPubnub()) {
                this.connection = (TrackerConnection) PubNubFactory.createTrackerPubNub(activity.getApplicationContext(), matchID);
            } else {
                this.connection = (TrackerConnection) NearbyTrackerConnection.getInstance();
                ((NearbyTrackerConnection)this.connection).setConnectionCallback(this);
            }
            this.uiCallback = (UICallback) this.connection;
        } catch (PubNubFactory.NoPropertiesFileFoundException ex) {
            Log.d("No properties file found, using display of this device...");
            displayConnectedText.setText(activity.getString(R.string.trackerStatusConnectedWithDisplayFail));
            displayConnectedText.setTextColor(activity.getColor(R.color.color_error));
        }
        if (!doDrawDebugInfo) {
            activity.findViewById(R.id.playMovie_debugGrid).setVisibility(View.INVISIBLE);
            activity.findViewById(R.id.debugScoreLayoutWrapper).setVisibility(View.INVISIBLE);
        }
    }

    @Override
    public void onCameraRender() {
        LiveActivity liveActivity = mLiveActivity.get();
        if (liveActivity == null) return;
        if (liveActivity.getmEncode() == null) return;
        liveActivity.getmEncode().getHandler().sendFlush();
    }

    @Override
    public void onCameraFrame(byte[] dataYUV420SP) {
        Lib.detectionFrame(dataYUV420SP);
        if(isWaitingForGesture()) {
            setWaitingForGesture(!getServeDetector().isReadyToServe(dataYUV420SP));
        }
    }

    @Override
    public void onCameraError() {
        if (hasMessages(CAMERA_ERROR)) return;
        sendMessage(obtainMessage(CAMERA_ERROR));
    }

    @Override
    public void initMatch(Side servingSide, MatchType matchType, Player playerLeft, Player playerRight) {
        this.matchSettings = new MatchSettings(matchType, GameType.G11, ServeRules.S2, playerLeft, playerRight, servingSide);
        this.match = new Match(matchSettings, uiCallback, this);
        this.connection.setTrackerPubNubCallback(match);
        this.connection.setMatchVisualizeHandlerCallback(this);
        this.connection.setScoreManipulationCallback(match.getReferee());
        this.connection.sendStatusUpdate(playerLeft.getName(), playerRight.getName(), 0,0,0,0,servingSide, matchType.gamesNeededToWin);
        startMatch();
        if(doDrawDebugInfo) {
            setTextInTextView(R.id.txtDebugPlayerNameLeft, playerLeft.getName());
            setTextInTextView(R.id.txtDebugPlayerNameRight, playerRight.getName());
            Timer refreshTimer = new Timer();
            refreshTimer.scheduleAtFixedRate(new DebugHandlerRefreshTimerTask(this), new Date(), MAX_REFRESHING_TIME_MS);
        }
    }

    @Override
    public void onStrikeFound(Track track) {
        if(doDrawDebugInfo) {
            super.onStrikeFound(track);
        }
    }

    @Override
    public void onSideChange(final Side side) {
        // use the referees current striker (might be different then side in parameter!)
        if(doDrawDebugInfo) {
            super.onSideChange(side);
        }
    }

    @Override
    protected void setCallbackForNewGame() {
        this.connection.setScoreManipulationCallback(match.getReferee());
    }

    @Override
    public void restartMatch() {
        this.match.restartMatch();
        this.connection.sendStatusUpdate(this.matchSettings.getPlayerLeft().getName(), this.matchSettings.getPlayerRight().getName(), 0,0,0,0,this.matchSettings.getStartingServer(), this.matchSettings.getMatchType().gamesNeededToWin);
        refreshDebugTextViews();
    }

    @Override
    public void onDiscoverFailure() {
        // not needed
    }

    @Override
    public void onRejection() {
        // not needed
    }

    @Override
    public void onConnection(String endpoint) {
        // not needed
    }

    @Override
    public void onConnecting(String endpoint) {
        // not needed
    }

    @Override
    public void onDisconnection(String endpoint) {
        ConnectionHelper.makeDisconnectDialog(mLiveActivity.get());
    }

    public void setConnectCallback(ConnectionCallback callback) {
        if (this.connection instanceof NearbyTrackerConnection) {
            ((NearbyTrackerConnection) this.connection).setConnectionCallback(callback);
        }
    }
}
