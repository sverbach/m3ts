package ch.m3ts.tabletennis.match.referee;

import android.content.Context;

import java.io.File;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;

import ch.m3ts.event.Event;
import ch.m3ts.event.Subscribable;
import ch.m3ts.event.TTEvent;
import ch.m3ts.event.TTEventBus;
import ch.m3ts.event.data.GestureData;
import ch.m3ts.event.data.eventdetector.EventDetectorEventData;
import ch.m3ts.event.data.scoremanipulation.ScoreManipulationData;
import ch.m3ts.event.data.todisplay.InvalidServeData;
import ch.m3ts.event.data.todisplay.ReadyToServeData;
import ch.m3ts.tabletennis.events.EventDetectionListener;
import ch.m3ts.tabletennis.events.ReadyToServeCallback;
import ch.m3ts.tabletennis.helper.DirectionX;
import ch.m3ts.tabletennis.helper.Duration;
import ch.m3ts.tabletennis.helper.Side;
import ch.m3ts.tabletennis.match.game.Game;
import ch.m3ts.tabletennis.match.game.GameCallback;
import ch.m3ts.tabletennis.match.game.ScoreManipulationListener;
import ch.m3ts.tabletennis.timeouts.OutOfFrameTimerTask;
import ch.m3ts.util.CSVStringBuilder;
import ch.m3ts.util.Log;
import cz.fmo.Lib;
import cz.fmo.data.Track;
import cz.fmo.util.FileManager;

/**
 * Represents a referee inside a table tennis match.
 * Based off the events generated by the EventDetector, decides whether a player has scored or
 * made a fault depending of the state of the Referee.
 * <p>
 * Currently the referee states are as follows:
 * - PLAY -> both players are currently playing the game
 * - SERVING -> a player is serving (when the ball is still on the servers' side)
 * - WAIT_FOR_SERVE -> a player will be serving shortly
 * - PAUSE -> a player just scored
 * - OUT_OF_FRAME -> the ball is not inside the frame anymore, the referee needs to wait and see
 * if a player can shoot the ball back onto the table.
 */
public class Referee implements EventDetectionListener, ScoreManipulationListener, ReadyToServeCallback, Subscribable {
    private static final String FILENAME = "recording_%s.csv";
    private static final String DATE_FORMAT = "yyyy-mm-dd_hh_mm_ss";
    private static final int OUT_OF_FRAME_MAX_DELAY = 1500;
    private final String currentFileName;
    private Timer outOfFrameTimer;
    private Timer timeOutNextServeTimer;
    private GameCallback gameCallback;
    private Game currentGame;
    private Side currentStriker;
    private Side currentBallSide;
    private State state;
    private int bounces;
    private int audioBounces;
    private int strikes;
    private FileManager fm;
    private boolean isUsingReadyToServeGesture = true;
    private List<Track> strikeLogs = new ArrayList<>();
    private final Duration duration;

    public Referee(Side servingSide) {
        this.currentStriker = servingSide;
        this.currentBallSide = servingSide;
        this.bounces = 0;
        this.audioBounces = 0;
        this.state = State.WAIT_FOR_SERVE;
        Date date = Calendar.getInstance().getTime();
        DateFormat dateFormat = new SimpleDateFormat(DATE_FORMAT, Locale.GERMANY);
        this.currentFileName = String.format(FILENAME, dateFormat.format(date));
        this.duration = new Duration();
    }

    public void debugToFile(Context context) {
        this.fm = new FileManager(context);
        File csvFile = this.fm.open(this.currentFileName);
        String csvFormatString = CSVStringBuilder.builder()
                .add("msg")
                .add("point_for_side")
                .add("score_left")
                .add("score_right")
                .add("strikes_for_point")
                .add("ball_side")
                .add("striker_side")
                .add("server_side")
                .add("duration_seconds")
                .add("detections_grouped_by_strikes(track_x_y_z_velocity_isbounce)")
                .toString();
        Log.d(csvFormatString, csvFile);
    }

    private void logScoring(String reasonMsg, Side pointForSide) {
        Log.d(reasonMsg);
        if (this.fm != null) {
            File csvFile = this.fm.open(this.currentFileName);
            String csvString = CSVStringBuilder.builder()
                    .add(reasonMsg)
                    .add(pointForSide)
                    .add(this.currentGame.getScore(Side.LEFT))
                    .add(this.currentGame.getScore(Side.RIGHT))
                    .add(this.strikes)
                    .add(this.currentBallSide)
                    .add(this.currentStriker)
                    .add(this.getServer())
                    .add(this.duration.getSeconds())
                    .add(this.strikeLogs)
                    .toString();
            strikes = 0;
            Log.d(csvString, csvFile);
            this.strikeLogs.clear();
        }
    }

    public void setGame(Game game, boolean firstGame) {
        this.gameCallback = game;
        this.currentGame = game;
        if (!firstGame) {
            initState();
        }
    }

    public void initState() {
        if (isUsingReadyToServeGesture) {
            this.state = State.PAUSE;
            TTEventBus.getInstance().dispatch(new TTEvent<>(new GestureData(getServer())));
        } else {
            this.state = State.WAIT_FOR_SERVE;
        }
    }

    public State getState() {
        return state;
    }

    public Side getServer() {
        return currentGame.getServer();
    }

    public Side getCurrentStriker() {
        return currentStriker;
    }

    @Override
    public void onBounce(Lib.Detection detection, Side ballBouncedOnSide) {
        switch (this.state) {
            case SERVING:
                if (ballBouncedOnSide == currentBallSide) {
                    bounces++;
                    applyRuleSetServing();
                }
                break;
            case PLAY:
                if (ballBouncedOnSide == currentBallSide) {
                    bounces++;
                    applyRuleSet();
                }
                break;
            default:
                break;
        }
    }

    @Override
    public void onAudioBounce(Side ballBouncedOnSide) {
        switch (this.state) {
            case SERVING:
            case PLAY:
                if (ballBouncedOnSide == currentBallSide) {
                    Log.d("REFEREE:Referee: Audio bounce detected in PLAY state");
                    audioBounces++;
                }
                break;
            default:
                break;
        }
    }

    @Override
    public void onSideChange(Side side) {
        switch (this.state) {
            case PLAY:
                // do not change striker if the ball was sent back by net
                if (currentBallSide == side) {
                    bounces = 0;
                    audioBounces = 0;
                    currentStriker = side;
                    strikes++;
                }
                break;
            case SERVING:
                if (side != getServer()) {
                    bounces = 0;
                    audioBounces = 0;
                }
                if (currentBallSide == side) {
                    currentStriker = side;
                }
                break;
            case PAUSE:
                if (side == getServer()) {
                    TTEventBus.getInstance().dispatch(new TTEvent<>(new InvalidServeData()));
                }
                currentStriker = side;
                break;
            default:
                currentStriker = side;
                break;
        }
    }

    @Override
    public void onNearlyOutOfFrame(Lib.Detection detection, Side side) {
        if (this.state == State.PLAY && side != Side.TOP)
            handleOutOfFrame();
    }

    @Override
    public void onStrikeFound(Track track) {
        switch (this.state) {
            case WAIT_FOR_SERVE:
                Lib.Detection latestDetection = track.getLatest();
                if (((getServer() == Side.LEFT && currentBallSide == Side.LEFT && latestDetection.directionX == DirectionX.RIGHT) ||
                        (getServer() == Side.RIGHT && currentBallSide == Side.RIGHT && latestDetection.directionX == DirectionX.LEFT)) &&
                        (latestDetection.predecessor != null)) {
                    this.state = State.SERVING;
                    currentBallSide = getServer();
                    this.currentStriker = getServer();
                }
                break;
            case OUT_OF_FRAME:
                // if ball was out of frame for too long, a point would have been scored.
                this.outOfFrameTimer.cancel();
                this.outOfFrameTimer = null;
                this.state = State.PLAY;
                break;
            default:
                break;
        }
        if (this.state != State.PAUSE) {
            this.strikeLogs.add(track);
        }
    }

    @Override
    public void onTableSideChange(Side side) {
        // set the currentStriker in case the tracker didn't find any detections
        this.currentStriker = Side.getOpposite(side);
        this.currentBallSide = side;
        switch (this.state) {
            case SERVING:
                if (this.getServer() != side) {
                    Log.d("REFEREE:Table Side Change:Changing to PLAY");
                    this.state = State.PLAY;
                }
            case PLAY:
                this.bounces = 0;
                this.audioBounces = 0;
                break;
            default:
                break;
        }
    }

    @Override
    public void onBallDroppedSideWays() {
        String msg;
        switch (this.state) {
            case PLAY:
                if (bounces == 0) {
                    msg = "REFEREE:Fault by Striker: Ball has fallen off side ways and had no bounce";
                    faultBySide(currentStriker);
                    logScoring(msg, Side.getOpposite(currentStriker));
                } else if (bounces == 1) {
                    msg = "REFEREE:Point by Striker: Ball has fallen off side ways and had a bounce";
                    pointBySide(currentStriker);
                    logScoring(msg, currentStriker);
                }
                break;
            default:
                break;
        }
    }

    @Override
    public void onTimeout() {
        if (this.state == State.PLAY || this.state == State.SERVING)
            handleOutOfFrame();
    }

    @Override
    public void onPointDeduction(Side side) {
        gameCallback.onPointDeduction(side);
        initPoint();
        logScoring("REFEREE: On point deduction ", side);
    }

    @Override
    public void onPointAddition(Side side) {
        gameCallback.onPoint(side);
        initPoint();
        logScoring("REFEREE: On point addition ", side);
    }

    @Override
    public void onPause() {
        this.pause();
    }

    @Override
    public void onResume() {
        this.resume();
    }

    public Side getCurrentBallSide() {
        return currentBallSide;
    }

    public void onOutOfFrameForTooLong() {
        String msg;
        if (this.state == State.OUT_OF_FRAME) {
            if (currentBallSide == currentStriker) {
                msg = "REFEREE:Out of Frame for too long - Striker most likely shot the ball into the net";
                faultBySide(currentStriker);
                logScoring(msg, Side.getOpposite(currentStriker));
            } else if (this.bounces >= 1 || this.audioBounces >= 1) {
                if (this.audioBounces >= 1 && this.bounces == 0) {
                    msg = "REFEREE:Out of Frame for too long (AUDIO_BOUNCE ONLY) - Strike received no return";
                } else if (this.bounces >= 1 && this.audioBounces == 0) {
                    msg = "REFEREE:Out of Frame for too long (VIDEO_BOUNCE ONLY) - Strike received no return";
                } else {
                    msg = "REFEREE:Out of Frame for too long (AUDIO AND VIDEO_BOUNCE DETECTED) - Strike received no return";
                }
                pointBySide(currentStriker);
                logScoring(msg, currentStriker);
            } else {
                msg = "REFEREE:Out of Frame for too long - Strike did not bounce";
                faultBySide(currentStriker);
                logScoring(msg, Side.getOpposite(currentStriker));
            }
        } else {
            this.outOfFrameTimer = null;
        }
    }

    public void resume() {
        this.state = State.WAIT_FOR_SERVE;
        if (this.isUsingReadyToServeGesture) {
            TTEventBus.getInstance().dispatch(new TTEvent<>(new ReadyToServeData(getServer())));
        }
        this.duration.reset();
        TTEventBus.getInstance().register(this);
    }

    public void deactivateReadyToServeGesture() {
        this.isUsingReadyToServeGesture = false;
    }

    private void pause() {
        this.state = State.PAUSE;
        cancelTimers();
        TTEventBus.getInstance().unregister(this);
    }

    private void cancelTimers() {
        if (this.outOfFrameTimer != null) {
            this.outOfFrameTimer.cancel();
            this.outOfFrameTimer = null;
        }
        if (this.timeOutNextServeTimer != null) {
            this.timeOutNextServeTimer.cancel();
            this.timeOutNextServeTimer = null;
        }
    }

    private void handleOutOfFrame() {
        // schedule out of frame timer
        TimerTask outOfFrameTask = new OutOfFrameTimerTask(this);
        outOfFrameTimer = new Timer("outOfFrameTimer");
        outOfFrameTimer.schedule(outOfFrameTask, OUT_OF_FRAME_MAX_DELAY);
        this.state = State.OUT_OF_FRAME;
    }

    private void pointBySide(Side side) {
        gameCallback.onPoint(side);
        initPoint();
    }

    private void faultBySide(Side side) {
        gameCallback.onPoint(Side.getOpposite(side));
        initPoint();
    }

    private void initPoint() {
        this.bounces = 0;
        this.audioBounces = 0;
        this.cancelTimers();
        this.duration.stop();
        if (isUsingReadyToServeGesture) {
            this.state = State.PAUSE;
            TTEventBus.getInstance().dispatch(new TTEvent<>(new GestureData(getServer())));
        } else {
            this.state = State.WAIT_FOR_SERVE;
        }
    }

    private void applyRuleSet() {
        String msg;
        if (bounces == 1) {
            if (this.currentStriker == this.currentBallSide) {
                msg = "REFEREE: Bounce on strikers' Side";
                faultBySide(this.currentStriker);
                logScoring(msg, Side.getOpposite(this.currentStriker));
            }
        } else if (bounces >= 2 && this.currentStriker != this.currentBallSide) {
            msg = "REFEREE: Bounced multiple times on strikers' opponent Side";
            pointBySide(this.currentStriker);
            logScoring(msg, this.currentStriker);
        }
    }

    private void applyRuleSetServing() {
        String msg;
        if (bounces > 1 && currentBallSide == getServer()) {
            msg = "REFEREE:Server Fault: Multiple Bounces on servers' Side";
            faultBySide(getServer());
            logScoring(msg, Side.getOpposite(getServer()));
        }
    }

    @Override
    public void onGestureDetected() {
        resume();
    }

    @Override
    public void handle(Event<?> event) {
        Object data = event.getData();
        if (data instanceof EventDetectorEventData) {
            EventDetectorEventData eventDetectorEventData = (EventDetectorEventData) data;
            eventDetectorEventData.call(this);
        } else if (data instanceof ScoreManipulationData) {
            ScoreManipulationData scoreManipulationData = (ScoreManipulationData) data;
            scoreManipulationData.call(this);
        }
    }
}
