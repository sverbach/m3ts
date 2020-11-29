package com.android.grafika;

import android.support.annotation.NonNull;

import cz.fmo.Lib;
import cz.fmo.camera.CameraThread;
import cz.fmo.tabletennis.MatchType;
import cz.fmo.tabletennis.Player;
import cz.fmo.tabletennis.Side;

public class LiveDebugHandler extends DebugHandler implements CameraThread.Callback {
    private static final int CAMERA_ERROR = 2;

    public LiveDebugHandler(@NonNull DebugActivity activity, String matchID) {
        super(activity, matchID, false);
    }

    public void initMatch(Side servingSide,  MatchType matchType, Player playerLeft, Player playerRight) {
        super.initMatch(servingSide, matchType, playerLeft, playerRight);
    }

    @Override
    public void onCameraRender() {
        // no implementation
    }

    @Override
    public void onCameraFrame(byte[] dataYUV420SP) {
        Lib.detectionFrame(dataYUV420SP);
        super.onFrame(dataYUV420SP);
    }

    @Override
    public void onCameraError() {
        if (hasMessages(CAMERA_ERROR)) return;
        sendMessage(obtainMessage(CAMERA_ERROR));
    }
}
