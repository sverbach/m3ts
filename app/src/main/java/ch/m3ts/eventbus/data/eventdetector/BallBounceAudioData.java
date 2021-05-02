package ch.m3ts.eventbus.data.eventdetector;

import ch.m3ts.tabletennis.events.EventDetectionListener;
import ch.m3ts.tabletennis.helper.Side;

public class BallBounceAudioData implements EventDetectorEventData {
    private final Side tableSide;

    public BallBounceAudioData(Side tableSide) {
        this.tableSide = tableSide;
    }

    @Override
    public void call(EventDetectionListener eventDetectionListener) {
        eventDetectionListener.onAudioBounce(tableSide);
    }
}