package ch.m3ts.event.data.scoremanipulation;

import ch.m3ts.tabletennis.match.game.ScoreManipulationListener;

public class PauseMatch implements ScoreManipulationData {
    @Override
    public void call(ScoreManipulationListener scoreManipulationListener) {
        scoreManipulationListener.onPause();
    }
}
