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

import ch.m3ts.display.stats.StatsCreator;
import ch.m3ts.eventbus.Event;
import ch.m3ts.eventbus.Subscribable;
import ch.m3ts.eventbus.TTEvent;
import ch.m3ts.eventbus.TTEventBus;
import ch.m3ts.eventbus.data.GestureData;
import ch.m3ts.eventbus.data.eventdetector.EventDetectorEventData;
import ch.m3ts.eventbus.data.scoremanipulation.ScoreManipulationData;
import ch.m3ts.eventbus.data.todisplay.InvalidServeData;
import ch.m3ts.eventbus.data.todisplay.ReadyToServeData;
import ch.m3ts.eventbus.data.todisplay.ScoreData;
import ch.m3ts.tabletennis.events.EventDetectionListener;
import ch.m3ts.tabletennis.events.gesture.ReadyToServeCallback;
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
import cz.fmo.util.Config;
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
    private static final String DATE_FORMAT = "yyyy-MM-dd_hh_mm_ss";
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
    private boolean isUsingReadyToServeGesture;
    private List<Track> strikeLogs = new ArrayList<>();
    private final Duration duration;
    private final Date startTime;
    private String lastDecision = "";
    private Side lastPointWinner;
    private boolean isBallMovingIntoNet;

    public Referee(Side servingSide) {
        this.currentStriker = servingSide;
        this.currentBallSide = servingSide;
        this.bounces = 0;
        this.audioBounces = 0;
        this.isBallMovingIntoNet = false;
        this.isUsingReadyToServeGesture = true;
        this.state = State.WAIT_FOR_SERVE;
        this.startTime = Calendar.getInstance().getTime();
        DateFormat dateFormat = new SimpleDateFormat(DATE_FORMAT, Locale.US);
        this.currentFileName = String.format(FILENAME, dateFormat.format(startTime));
        this.duration = new Duration();
    }

    public void debugToFile(Context context) {
        this.fm = new FileManager(context);
        Config config = new Config(context);
        File csvFile = this.fm.open(this.currentFileName);
        String start = new SimpleDateFormat(DATE_FORMAT, Locale.GERMANY).format(startTime);
        String metaData = CSVStringBuilder.builder()
                .add(start)
                .add(config.getPlayer1Name())
                .add(config.getPlayer2Name())
                .toString();
        StatsCreator.getInstance().addMetaData(start, config.getPlayer1Name(), config.getPlayer2Name());
        Log.d(metaData, csvFile);
        String csvFormatString = CSVStringBuilder.builder()
                .add("msg")
                .add("point_for_side")
                .add("score_left")
                .add("score_right")
                .add("strikes_for_point")
                .add("server_side")
                .add("duration_seconds")
                .toString();
        Log.d(csvFormatString, csvFile);
    }

    private void logScoring(Side lastServer) {
        Log.d(lastDecision);
        if (this.fm != null) {
            File csvFile = this.fm.open(this.currentFileName);
            String csvString = CSVStringBuilder.builder()
                    .add(this.lastDecision)
                    .add(this.lastPointWinner)
                    .add(this.currentGame.getScore(Side.LEFT))
                    .add(this.currentGame.getScore(Side.RIGHT))
                    .add(this.strikes)
                    .add(lastServer)
                    .add(this.duration.getSeconds())
                    .toString();
            strikes = 0;
            Log.d(csvString, csvFile);
        }
        int scoreLeft = this.currentGame.getScore(Side.LEFT);
        int scoreRight = this.currentGame.getScore(Side.RIGHT);
        StatsCreator.getInstance().addPoint(lastDecision, lastPointWinner, scoreLeft, scoreRight, this.strikes, this.currentBallSide, this.currentStriker, lastServer, this.duration.getSeconds(), this.strikeLogs);
        this.strikeLogs = new ArrayList<>();
    }

    private void logNewGame() {
        if (this.fm != null) {
            File csvFile = this.fm.open(this.currentFileName);
            String csvFormatString = CSVStringBuilder.builder()
                    .add("newGame")
                    .toString();
            Log.d(csvFormatString, csvFile);
        }
    }

    public void setGame(Game game, boolean firstGame) {
        this.gameCallback = game;
        this.currentGame = game;
        if (!firstGame) {
            initState();
            logNewGame();
        }
        this.duration.reset();
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
                    Log.d("Referee: Audio bounce detected in PLAY state");
                    audioBounces++;
                }
                break;
            default:
                break;
        }
    }

    @Override
    public void onBallMovingIntoNet() {
        switch (this.state) {
            case PLAY:
            case SERVING:
                this.isBallMovingIntoNet = true;
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
            track.setStriker(this.currentStriker);
            if (!this.strikeLogs.contains(track)) this.strikeLogs.add(track);
        }
    }

    @Override
    public void onTableSideChange(Side side) {
        // set the currentStriker in case the tracker didn't find any detections
        this.currentStriker = Side.getOpposite(side);
        this.currentBallSide = side;
        this.isBallMovingIntoNet = false;
        switch (this.state) {
            case SERVING:
                if (this.getServer() != side) {
                    Log.d("Table Side Change:Changing to PLAY");
                    this.state = State.PLAY;
                }
                this.bounces = 0;
                this.audioBounces = 0;
                break;
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
        if (this.state == State.PLAY) {
            if (bounces == 0) {
                lastDecision = "Fault by Striker: Ball has fallen off side ways and had no bounce";
                lastPointWinner = Side.getOpposite(currentStriker);
                faultBySide(currentStriker);
            } else if (bounces == 1) {
                lastDecision = "Point by Striker: Ball has fallen off side ways and had a bounce";
                lastPointWinner = currentStriker;
                pointBySide(currentStriker);
            }
        }
    }

    @Override
    public void onTimeout() {
        if (this.state == State.PLAY ||
                (this.state == State.SERVING && (this.audioBounces > 0 || this.bounces > 0 || this.isBallMovingIntoNet))) {
            handleOutOfFrame();
        }
    }

    @Override
    public void onPointDeduction(Side side) {
        this.duration.reset();
        lastDecision = "On point deduction";
        lastPointWinner = side;
        gameCallback.onPointDeduction(side);
        initPoint();
    }

    @Override
    public void onPointAddition(Side side) {
        this.duration.reset();
        lastDecision = "On point addition";
        lastPointWinner = side;
        gameCallback.onPoint(side);
        initPoint();
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
        if (this.state == State.OUT_OF_FRAME) {
            if (currentBallSide == currentStriker) {
                lastDecision = "Out of Frame for too long - Striker most likely shot the ball into the net";
                lastPointWinner = Side.getOpposite(currentStriker);
                faultBySide(currentStriker);
            } else if (this.bounces >= 1 || this.audioBounces >= 1) {
                String msg;
                if (this.audioBounces >= 1 && this.bounces == 0) {
                    msg = "Out of Frame for too long (AUDIO_BOUNCE ONLY) - Strike received no return";
                } else if (this.bounces >= 1 && this.audioBounces == 0) {
                    msg = "Out of Frame for too long (VIDEO_BOUNCE ONLY) - Strike received no return";
                } else {
                    msg = "Out of Frame for too long (AUDIO AND VIDEO_BOUNCE DETECTED) - Strike received no return";
                }
                lastDecision = msg;
                lastPointWinner = currentStriker;
                pointBySide(currentStriker);
            } else {
                lastDecision = "Out of Frame for too long - Strike did not bounce";
                lastPointWinner = Side.getOpposite(currentStriker);
                faultBySide(currentStriker);
            }
        } else {
            this.outOfFrameTimer = null;
        }
    }

    public void resume() {
        this.state = State.WAIT_FOR_SERVE;
        this.currentBallSide = getServer();
        this.currentStriker = Side.getOpposite(getServer());
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
        if (this.outOfFrameTimer != null) {
            this.outOfFrameTimer.cancel();
            this.outOfFrameTimer = null;
        }
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
        if (bounces == 1) {
            if (this.currentStriker == this.currentBallSide) {
                lastDecision = "Bounce on strikers' Side";
                lastPointWinner = Side.getOpposite(this.currentStriker);
                faultBySide(this.currentStriker);
            }
        } else if (bounces >= 2 && this.currentStriker != this.currentBallSide) {
            lastDecision = "Bounced multiple times on strikers' opponent Side";
            lastPointWinner = this.currentStriker;
            pointBySide(this.currentStriker);
        }
    }

    private void applyRuleSetServing() {
        if (bounces > 1 && currentBallSide == getServer()) {
            lastDecision = "Server Fault: Multiple Bounces on servers' Side";
            lastPointWinner = Side.getOpposite(getServer());
            faultBySide(getServer());
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
        } else if (data instanceof ScoreData) {
            ScoreData scoreData = (ScoreData) data;
            logScoring(scoreData.getLastServer());
        }
    }
}