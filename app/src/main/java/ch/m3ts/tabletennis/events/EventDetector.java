package ch.m3ts.tabletennis.events;

import android.support.annotation.NonNull;

import com.google.audio.ImplAudioRecorderCallback;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.TimeUnit;

import ch.m3ts.eventbus.EventBus;
import ch.m3ts.eventbus.TTEvent;
import ch.m3ts.eventbus.TTEventBus;
import ch.m3ts.eventbus.data.eventdetector.BallBounceAudioData;
import ch.m3ts.eventbus.data.eventdetector.BallBounceData;
import ch.m3ts.eventbus.data.eventdetector.BallDroppedSideWaysData;
import ch.m3ts.eventbus.data.eventdetector.BallMovingIntoNetData;
import ch.m3ts.eventbus.data.eventdetector.BallNearlyOutOfFrameData;
import ch.m3ts.eventbus.data.eventdetector.BallTrackData;
import ch.m3ts.eventbus.data.eventdetector.DetectionTimeOutData;
import ch.m3ts.eventbus.data.eventdetector.StrikerSideChangeData;
import ch.m3ts.eventbus.data.eventdetector.TableSideChangeData;
import ch.m3ts.tabletennis.Table;
import ch.m3ts.tabletennis.events.timeouts.TimeoutTimerTask;
import ch.m3ts.tabletennis.events.trackselection.TrackSelectionStrategy;
import ch.m3ts.tabletennis.events.trackselection.multiple.ChooseNewestTrackSelection;
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
    private final TrackSet trackSet;
    private final int[] nearlyOutOfFrameThresholds;
    private final int srcWidth;
    private final int srcHeight;
    private Lib.Detection previousDetection;
    private int previousDirectionY;
    private int previousDirectionX;
    private int previousCenterX;
    private int previousCenterY;
    private final Table table;
    private int numberOfDetections;
    private final ZPositionCalc zPositionCalc;
    private Track currentTrack;
    private final EventBus eventBus;
    private final Timer timeoutTimer;
    private final BallCurvePredictor ballCurvePredictor;
    private final TrackSelectionStrategy trackSelectionStrategy;
    private boolean checkForBallMovingIntoNet;
    private boolean checkForSideChange;

    public EventDetector(Config config, int srcWidth, int srcHeight, TrackSet trackSet, @NonNull Table table, ZPositionCalc calc) {
        this.eventBus = TTEventBus.getInstance();
        this.srcHeight = srcHeight;
        this.srcWidth = srcWidth;
        this.trackSet = trackSet;
        this.nearlyOutOfFrameThresholds = new int[]{
                (int) (srcWidth * PERCENTAGE_OF_NEARLY_OUT_OF_FRAME),
                (int) (srcWidth * (1 - PERCENTAGE_OF_NEARLY_OUT_OF_FRAME)),
                (int) (srcHeight * PERCENTAGE_OF_NEARLY_OUT_OF_FRAME),
                (int) (srcHeight * (1 - PERCENTAGE_OF_NEARLY_OUT_OF_FRAME)),
        };
        this.table = table;
        this.numberOfDetections = 0;
        this.zPositionCalc = calc;
        this.timeoutTimer = new Timer("timeoutTimer");
        this.ballCurvePredictor = new LinearBallCurvePredictor();
        this.checkForBallMovingIntoNet = true;
        this.checkForSideChange = true;
        this.trackSelectionStrategy = new ChooseNewestTrackSelection();
        trackSet.setConfig(config);
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
            Side ballBouncedOnSide = table.getHorizontalSideOfDetection(previousCenterX);
            eventBus.dispatch(new TTEvent<>(new BallBounceAudioData(ballBouncedOnSide)));
        }
    }

    public void onObjectsDetected(Lib.Detection[] detections, long detectionTime) {
        synchronized (mLock) {
            trackSet.addDetections(detections, this.srcWidth, this.srcHeight, detectionTime);
            if (!trackSet.getTracks().isEmpty()) {
                Track track = selectTrack(trackSet.getTracks());
                if (track != null && track.getLatest() != previousDetection) {
                    numberOfDetections++;
                    Lib.Detection latestDetection = track.getLatest();
                    checkForEvents(track, latestDetection);
                    savePreviousDetection(latestDetection);
                    setTimeoutTimer(numberOfDetections);
                }
            }
        }
    }

    private void checkForEvents(Track track, Lib.Detection latestDetection) {
        if (latestDetection.directionX != DirectionX.NONE || latestDetection.directionY != DirectionY.NONE) {
            callAllOnStrikeFound(track);
            hasTableSideChanged(latestDetection.centerX);
            hasBallFallenOffSideWays(latestDetection);
            if (latestDetection.directionX != DirectionX.NONE) {
                boolean strikerSideChanged = hasSideChanged(latestDetection);
                if (latestDetection.directionY != DirectionY.NONE) {
                    isMovingIntoNet(latestDetection);
                    hasBouncedOnTable(latestDetection, strikerSideChanged);
                    Side nearlyOutOfFrameSide = getNearlyOutOfFrameSide(latestDetection);
                    if (nearlyOutOfFrameSide != null) {
                        callAllOnNearlyOutOfFrame(latestDetection, nearlyOutOfFrameSide);
                    }
                }
            }
        }
    }

    /**
     * Selects the ball track out of several tracks
     *
     * @param tracks all current tracks (detected by FMO)
     * @return a track or null (if no track is the ball)
     */
    public Track selectTrack(List<Track> tracks) {
        validateAndMarkTracks(tracks);
        return selectAndSetCurrentTrack(tracks);
    }

    private void validateAndMarkTracks(List<Track> tracks) {
        for (Track t : tracks) {
            Lib.Detection latestDetection = t.getLatest();
            calcDirectionOfDetection(latestDetection);
            latestDetection.centerZ = zPositionCalc.findZPosOfBallRel(latestDetection.radius);
            if (table.isOnOrAbove(latestDetection.centerX, latestDetection.centerY) && zPositionCalc.isBallZPositionOnTable(latestDetection.radius)) {
                t.setTableCrossed();
            }
        }
    }

    private Track selectAndSetCurrentTrack(List<Track> tracks) {
        Track selectedTrack = null;
        if (tracks.size() > 1) {
            selectedTrack = trackSelectionStrategy.selectTrack(tracks, previousDirectionX, previousDirectionY, previousCenterX, previousCenterY);
            if (selectedTrack == null) {
                for (Track t : tracks) {
                    if (t.hasCrossedTable()) {
                        selectedTrack = t;
                        break;
                    }
                }
            }
        } else {
            if (tracks.get(0).hasCrossedTable()) {
                selectedTrack = tracks.get(0);
            }
        }

        // save current selected Track
        if (selectedTrack != null) {
            currentTrack = selectedTrack;
        }
        return selectedTrack;
    }

    public int[] getNearlyOutOfFrameThresholds() {
        return nearlyOutOfFrameThresholds;
    }

    public void callAllOnTimeout() {
        eventBus.dispatch(new TTEvent<>(new DetectionTimeOutData()));
    }

    public int getNumberOfDetections() {
        return numberOfDetections;
    }

    private void setTimeoutTimer(int currentNumberOfDetections) {
        TimerTask timeoutTimerTask = new TimeoutTimerTask(this, currentNumberOfDetections);
        timeoutTimer.schedule(timeoutTimerTask, MILLISECONDS_TILL_TIMEOUT);
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

    private void callAllOnStrikeFound(Track track) {
        eventBus.dispatch(new TTEvent<>(new BallTrackData(track)));
    }

    private void callAllOnBounce(Lib.Detection detection, Side side) {
        eventBus.dispatch(new TTEvent<>(new BallBounceData(detection, side)));
    }

    private void callAllOnSideChange(Side side) {
        eventBus.dispatch(new TTEvent<>(new StrikerSideChangeData(side)));
    }

    private void callAllOnNearlyOutOfFrame(Lib.Detection latestDetection, Side side) {
        eventBus.dispatch(new TTEvent<>(new BallNearlyOutOfFrameData(latestDetection, side)));
    }

    private void callAllOnTableSideChange(Side side) {
        eventBus.dispatch(new TTEvent<>(new TableSideChangeData(side)));
    }

    private void callAllOnBallDroppedSideWays() {
        eventBus.dispatch(new TTEvent<>(new BallDroppedSideWaysData()));
    }

    private boolean hasSideChanged(Lib.Detection detection) {
        boolean hasSideChanged = false;
        if ((checkForSideChange &&
                detection.predecessor != null && detection.predecessor.directionX == detection.directionX) &&
                ((detection.centerX < table.getNetBottom().x && detection.directionX == DirectionX.RIGHT) || (
                        detection.centerX > table.getNetBottom().x && detection.directionX == DirectionX.LEFT))) {
            Side striker = Side.getOppositeX(detection.directionX);
            callAllOnSideChange(striker);
            hasSideChanged = true;
            checkForSideChange = false;
            checkForBallMovingIntoNet = true;
        }
        return hasSideChanged;
    }

    private void hasBouncedOnTable(Lib.Detection detection, boolean hasSideChanged) {
        if (!hasSideChanged && previousDirectionY != detection.directionY &&
                (previousDirectionX == detection.directionX) &&
                table.isBounceOn(previousCenterX, previousCenterY) &&
                ((previousDirectionY == DirectionY.DOWN) && (detection.directionY == DirectionY.UP))) {
            Side ballBouncedOnSide = table.getHorizontalSideOfDetection(previousCenterX);
            detection.isBounce = true;
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

    /**
     * Calculates the x and y direction of a detection using the detections predecessor or the latest saved
     * position.
     *
     * @param detection Detection of which to calculate the direction to.
     */
    private void calcDirectionOfDetection(Lib.Detection detection) {
        if (detection.predecessor != null) {
            detection.directionY = Integer.compare(detection.centerY, detection.predecessor.centerY);
            detection.directionX = Integer.compare(detection.centerX, detection.predecessor.centerX);
        } else {
            detection.directionY = Integer.compare(detection.centerY, previousCenterY);
            detection.directionX = Integer.compare(detection.centerX, previousCenterX);
        }
    }

    private void hasTableSideChanged(int currentXPosition) {
        if (currentXPosition >= table.getNetBottom().x && previousCenterX < table.getNetBottom().x) {
            callAllOnTableSideChange(Side.RIGHT);
            this.checkForSideChange = true;
        } else if (currentXPosition <= table.getNetBottom().x && previousCenterX > table.getNetBottom().x) {
            callAllOnTableSideChange(Side.LEFT);
            this.checkForSideChange = true;
        }
    }

    private void hasBallFallenOffSideWays(Lib.Detection detection) {
        if (detection.predecessor != null && detection.predecessor.directionY == DirectionY.DOWN &&
                table.isBelow(detection.centerX, detection.centerY) &&
                detection.directionY == DirectionY.DOWN) {
            callAllOnBallDroppedSideWays();
        }
    }

    private void isMovingIntoNet(Lib.Detection detection) {
        if (checkForBallMovingIntoNet &&
                detection.predecessor != null &&
                detection.directionY == DirectionY.DOWN &&
                table.isOnOrAbove(detection.centerX, detection.centerY) &&
                ((detection.directionX == DirectionX.RIGHT && detection.centerX < table.getNetBottom().x) ||
                        (detection.directionX == DirectionX.LEFT && detection.centerX > table.getNetBottom().x))) {
            int[] lastCXs = {
                    detection.centerX,
                    detection.predecessor.centerX,
            };
            int[] lastCYs = {
                    detection.centerY,
                    detection.predecessor.centerY,
            };
            if (ballCurvePredictor.willBallMoveIntoNet(lastCXs, lastCYs, table)) {
                eventBus.dispatch(new TTEvent<>(new BallMovingIntoNetData()));
                // no need to check twice in same strike
                checkForBallMovingIntoNet = false;
            }
        }
    }
}