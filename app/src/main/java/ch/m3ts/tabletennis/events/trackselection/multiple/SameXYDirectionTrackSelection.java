package ch.m3ts.tabletennis.events.trackselection.multiple;

import java.util.List;

import ch.m3ts.tabletennis.events.trackselection.TrackSelectionStrategy;
import cz.fmo.Lib;
import cz.fmo.data.Track;

public class SameXYDirectionTrackSelection implements TrackSelectionStrategy {
    @Override
    public Track selectTrack(List<Track> tracks, int previousDirectionX, int previousDirectionY, int previousCenterX, int previousCenterY) {
        Track selectedTrack = null;
        for (Track t : tracks) {
            Lib.Detection d = t.getLatest();
            if (d.directionX == previousDirectionX && d.directionY == previousDirectionY && t.hasCrossedTable()) {
                selectedTrack = t;
            }
        }
        return selectedTrack;
    }
}
