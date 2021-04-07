package ch.m3ts.tabletennis.events;

import android.support.annotation.NonNull;

import com.google.audio.ImplAudioRecorderCallback;

import java.util.Collections;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

import ch.m3ts.tabletennis.Table;
import ch.m3ts.tabletennis.events.timeouts.TimeoutTimerTask;
import ch.m3ts.tabletennis.helper.DirectionX;
import ch.m3ts.tabletennis.helper.DirectionY;
import ch.m3ts.tabletennis.helper.Side;
import ch.m3ts.tracker.ZPositionCalc;
import cz.fmo.Lib;
import cz.fmo.data.Track;
import cz.fmo.data.TrackSet;
import cz.fmo.util.Config;

/**
 * This class tries to interpret the Detections and Tracks generated by FMO and calls methods when
 * it perceives an event. As the FMO library generates detections not only caused by the ball,
 * a filtering mechanism is implemented (selectTrack).
 * <p>
 * Currently an event can be:
 * - A bounce (onBounce)
 * - A table side change (onTableSideChange)
 * - A direction change by the ball (onSideChange)
 * - A trace of the ball (onStrikeFound)
 * - A ball moving out of the frame (onOutOfFrame)
 * - A ball falling off sideways the table (onBallDroppedSideWays)
 * - A ball detection missing for some time (onTimeout)
 */
public class EventDetector implements Lib.Callback, ImplAudioRecorderCallback.Callback {
    private static final double PERCENTAGE_OF_NEARLY_OUT_OF_FRAME = 0.07;
    private static final int MILLISECONDS_TILL_TIMEOUT = 1500;
    private final Object mLock = new Object();
    private final TrackSet tracks;
    private final List<EventDetectionCallback> callbacks;
    private final int[] nearlyOutOfFrameThresholds;
    private final int srcWidth;
    private final int srcHeight;
    private Side currentBallSide;
    private Lib.Detection previousDetection;
    private int previousDirectionY;
    private int previousDirectionX;
    private int previousCenterX;
    private int previousCenterY;
    private final Table table;
    private int numberOfDetections;
    private final ZPositionCalc zPositionCalc;
    private Track currentTrack;

    public EventDetector(Config config, int srcWidth, int srcHeight, EventDetectionCallback callback, TrackSet tracks, @NonNull Table table, ZPositionCalc calc) {
        this(config, srcWidth, srcHeight, Collections.singletonList(callback), tracks, table, calc);
    }

    public EventDetector(Config config, int srcWidth, int srcHeight, List<EventDetectionCallback> callbacks, TrackSet tracks, @NonNull Table table, ZPositionCalc calc) {
        this.srcHeight = srcHeight;
        this.srcWidth = srcWidth;
        this.tracks = tracks;
        this.nearlyOutOfFrameThresholds = new int[]{
                (int) (srcWidth * PERCENTAGE_OF_NEARLY_OUT_OF_FRAME),
                (int) (srcWidth * (1 - PERCENTAGE_OF_NEARLY_OUT_OF_FRAME)),
                (int) (srcHeight * PERCENTAGE_OF_NEARLY_OUT_OF_FRAME),
                (int) (srcHeight * (1 - PERCENTAGE_OF_NEARLY_OUT_OF_FRAME)),
        };
        this.callbacks = callbacks;
        this.table = table;
        this.numberOfDetections = 0;
        this.zPositionCalc = calc;
        tracks.setConfig(config);
    }

    @Override
    public void log(String message) {
        // Lib logs will be ignored for now
    }

    @Override
    public void onObjectsDetected(Lib.Detection[] detections) {
        this.onObjectsDetected(detections, System.nanoTime());
    }

    @Override
    public void onAudioBounceDetected() {
        if (currentTrack != null &&
                TimeUnit.MILLISECONDS.convert(System.nanoTime() - currentTrack.getLastDetectionTime(), TimeUnit.NANOSECONDS) < 30) {
            Side ballBouncedOnSide = table.getHorizontalSideOfDetection(previousDetection.centerX);
            for (EventDetectionCallback callback : callbacks) {
                callback.onAudioBounce(ballBouncedOnSide);
            }
        }
    }

    public void onObjectsDetected(Lib.Detection[] detections, long detectionTime) {
        synchronized (mLock) {
            tracks.addDetections(detections, this.srcWidth, this.srcHeight, detectionTime); // after this, object direction is up to date
            if (!tracks.getTracks().isEmpty()) {
                Track track = selectTrack(tracks.getTracks());
                if (track != null && track.getLatest() != previousDetection) {
                    numberOfDetections++;
                    Lib.Detection latestDetection = track.getLatest();
                    calcDirectionY(latestDetection);
                    calcDirectionX(latestDetection);
                    callAllOnStrikeFound(track);
                    if (hasBallFallenOffSideWays(latestDetection)) {
                        callAllOnBallDroppedSideWays();
                    }
                    // todo ev. if has table side changed, then no bounce check
                    hasTableSideChanged(latestDetection.centerX);
                    boolean tableSideChanged = hasSideChanged(latestDetection);
                    hasBouncedOnTable(latestDetection, tableSideChanged);
                    Side nearlyOutOfFrameSide = getNearlyOutOfFrameSide(latestDetection);
                    if (nearlyOutOfFrameSide != null) {
                        callAllOnNearlyOutOfFrame(latestDetection, nearlyOutOfFrameSide);
                    }
                    savePreviousDetection(latestDetection);
                    setTimeoutTimer(numberOfDetections);
                }
            }
        }
    }

    public Track selectTrack(List<Track> tracks) {
        // first tag all tracks which have crossed the table once
        for (Track t : tracks) {
            Lib.Detection latestDetection = t.getLatest();
            if (table.isOnOrAbove(latestDetection.centerX, latestDetection.centerY) && zPositionCalc.isBallZPositionOnTable(latestDetection.radius)) {
                t.setTableCrossed();
            }
        }

        if (tracks.size() > 1) {
            // check if the distance between the two latest detections is humanly possible
            Lib.Detection d0 = tracks.get(tracks.size() - 1).getLatest();
            Lib.Detection d1 = tracks.get(tracks.size() - 2).getLatest();
            double mmPerPixelOnFrontPlane = zPositionCalc.getMmPerPixelFrontEdge();
            int dx = Math.abs(d0.centerX - d1.centerX);
            int dy = Math.abs(d0.centerY - d1.centerY);
            double distanceInMm = Math.sqrt(Math.pow(dx, 2) + Math.pow(dy, 2)) * mmPerPixelOnFrontPlane;
            double dTime = (double) Math.abs(tracks.get(tracks.size() - 1).getLastDetectionTime() - tracks.get(tracks.size() - 2).getLastDetectionTime()) / 1_000_000_000;
            if (dTime == 0) dTime = 1.0 / 30.0;
            if ((distanceInMm / 1000) / (dTime) >= 32) {
                tracks.remove(1);
            }

        }

        // now select a track which has crossed the table, preferably the newer one (index high), if there are none return null
        Track selectedTrack = null;
        for (int i = tracks.size() - 1; i >= 0; i--) {
            if (isOnTable(tracks.get(i))) {
                selectedTrack = tracks.get(i);
                currentTrack = selectedTrack;
                break;
            }
        }
        return selectedTrack;
    }

    public int[] getNearlyOutOfFrameThresholds() {
        return nearlyOutOfFrameThresholds;
    }

    public void callAllOnTimeout() {
        for (EventDetectionCallback callback : callbacks) {
            callback.onTimeout();
        }
    }

    public int getNumberOfDetections() {
        return numberOfDetections;
    }

    private void setTimeoutTimer(int currentNumberOfDetections) {
        TimerTask timeoutTimerTask = new TimeoutTimerTask(this, currentNumberOfDetections);
        Timer timeoutTimer = new Timer("timeoutTimer");
        timeoutTimer.schedule(timeoutTimerTask, MILLISECONDS_TILL_TIMEOUT);
    }

    private void callAllOnStrikeFound(Track track) {
        for (EventDetectionCallback callback : callbacks) {
            callback.onStrikeFound(track);
        }
    }

    private void savePreviousDetection(Lib.Detection detection) {
        // important check, if removed dirX and dirY will be set to 0 sometimes
        if (detection != this.previousDetection) {
            this.previousCenterX = detection.centerX;
            this.previousCenterY = detection.centerY;
            this.previousDirectionX = (int) detection.directionX;
            this.previousDirectionY = (int) detection.directionY;
            this.previousDetection = detection;
        }
    }

    private void callAllOnBounce(Lib.Detection detection, Side side) {
        for (EventDetectionCallback callback : callbacks) {
            callback.onBounce(detection, side);
        }
    }

    private void callAllOnSideChange(Side side) {
        for (EventDetectionCallback callback : callbacks) {
            callback.onSideChange(side);
        }
    }

    private void callAllOnNearlyOutOfFrame(Lib.Detection latestDetection, Side side) {
        for (EventDetectionCallback callback : callbacks) {
            callback.onNearlyOutOfFrame(latestDetection, side);
        }
    }

    private void callAllOnTableSideChange(Side side) {
        for (EventDetectionCallback callback : callbacks) {
            callback.onTableSideChange(side);
        }
    }

    private void callAllOnBallDroppedSideWays() {
        for (EventDetectionCallback callback : callbacks) {
            callback.onBallDroppedSideWays();
        }
    }

    private boolean hasSideChanged(Lib.Detection detection) {
        boolean hasSideChanged = false;
        if (detection.directionX != previousDirectionX) {
            Side striker = Side.getOppositeX(detection.directionX);
            callAllOnSideChange(striker);
            hasSideChanged = true;
        }
        return hasSideChanged;
    }

    private void hasBouncedOnTable(Lib.Detection detection, boolean hasSideChanged) {
        if (!hasSideChanged && previousDirectionY != detection.directionY &&
                (previousDirectionX == detection.directionX) &&
                (table.isBounceOn(previousCenterX, previousCenterY) || table.isBounceOn(detection.centerX, detection.centerY)) &&
                ((previousDirectionY == DirectionY.DOWN) && (detection.directionY == DirectionY.UP))) {
            Side ballBouncedOnSide = table.getHorizontalSideOfDetection(previousDetection.centerX);
            callAllOnBounce(previousDetection, ballBouncedOnSide);
        }
    }

    private Side getNearlyOutOfFrameSide(Lib.Detection detection) {
        Side side = null;
        if (detection.predecessor != null) {
            if (detection.centerX < nearlyOutOfFrameThresholds[0] && detection.directionX == DirectionX.LEFT) {
                side = Side.LEFT;
            } else if (detection.centerX > nearlyOutOfFrameThresholds[1] && detection.directionX == DirectionX.RIGHT) {
                side = Side.RIGHT;
            } else if (detection.centerY < nearlyOutOfFrameThresholds[2] && detection.directionY == DirectionY.UP) {
                side = Side.TOP;
            } else if (detection.centerY > nearlyOutOfFrameThresholds[3] && detection.directionY == DirectionY.DOWN) {
                side = Side.BOTTOM;
            }
        }
        return side;
    }

    private boolean isOnTable(Track track) {
        return track.hasCrossedTable();
    }

    private void calcDirectionY(Lib.Detection detection) {
        detection.directionY = Integer.compare(detection.centerY, previousCenterY);
    }

    private void calcDirectionX(Lib.Detection detection) {
        if (detection.predecessor != null)
            detection.directionX = Integer.compare(detection.centerX, detection.predecessor.centerX);
        else detection.directionX = Integer.compare(detection.centerX, previousCenterX);
    }

    private void hasTableSideChanged(int currentXPosition) {
        if (previousDetection != null) {
            if (currentXPosition > table.getCloseNetEnd().x && previousCenterX < table.getCloseNetEnd().x) {
                callAllOnTableSideChange(Side.RIGHT);
            } else if (currentXPosition < table.getCloseNetEnd().x && previousCenterX > table.getCloseNetEnd().x) {
                callAllOnTableSideChange(Side.LEFT);
            }
        }
    }

    private boolean hasBallFallenOffSideWays(Lib.Detection detection) {
        return (detection.predecessor != null &&
                table.isBelow(detection.centerX, detection.centerY) &&
                detection.directionY == DirectionY.DOWN);
    }
}