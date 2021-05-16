package ch.m3ts.tabletennis.events.trackselection.multiple;

import java.util.List;

import ch.m3ts.tabletennis.events.trackselection.TrackSelectionStrategy;
import cz.fmo.data.Track;

public class DirectionAndDistanceTrackSelection implements TrackSelectionStrategy {
    private final SameXDirectionTrackSelection xDirectionTrackSelection;
    private final MinimumDistanceTrackSelection distanceTrackSelection;

    public DirectionAndDistanceTrackSelection() {
        xDirectionTrackSelection = new SameXDirectionTrackSelection();
        distanceTrackSelection = new MinimumDistanceTrackSelection();
    }

    @Override
    public Track selectTrack(List<Track> tracks, int previousDirectionX, int previousDirectionY, int previousCenterX, int previousCenterY) {
        Track selectedTrack;
        selectedTrack = xDirectionTrackSelection.selectTrack(tracks, previousDirectionX, previousDirectionY, previousCenterX, previousCenterY);
        if (selectedTrack == null) {
            selectedTrack = distanceTrackSelection.selectTrack(tracks, previousDirectionX, previousDirectionY, previousCenterX, previousCenterY);
        }
        return selectedTrack;
    }
}
