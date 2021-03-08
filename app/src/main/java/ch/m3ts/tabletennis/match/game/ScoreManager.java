package ch.m3ts.tabletennis.match.game;

import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedList;
import java.util.Map;

import ch.m3ts.tabletennis.helper.Side;

/**
 * Helper class of Game.
 * Keeps track of the current score and server, allows manual manipulation by the players.
 */
public class ScoreManager {
    private Map<Side, Integer> pointsMap;
    private Deque<Side> servers;
    private Side startServingSide;

    public ScoreManager(Side startServingSide) {
        this.startServingSide = startServingSide;
        this.pointsMap = new EnumMap<>(Side.class);
        pointsMap.put(Side.LEFT, 0);
        pointsMap.put(Side.RIGHT, 0);
        this.servers = new LinkedList<>();
    }

    public void score(Side winner, Side server) {
        updatePoints(winner, true);
        this.servers.addLast(server);
    }

    /**
     * Reverts the score by one (-1 manipulation).
     * @return server of the previous round
     */
    public Side revertLastScore(Side player) {
        Side lastServer = getLastServer();
        if(!this.servers.isEmpty()) {
            this.servers.removeLast();
            updatePoints(player, false);
        }
        return lastServer;
    }

    public int getScore(Side player) {
        return pointsMap.get(player);
    }

    public Side getLastServer() {
        Side server = servers.peekLast();
        if (server == null) {
            server = startServingSide;
        }
        return server;
    }

    /**
     * Updates points of the provided player by +-1
     * @param player specifies to adjust the points of
     * @param isIncrease specifies a +1 or -1 manipulation
     */
    private void updatePoints(Side player, boolean isIncrease) {
        int currentPoints = pointsMap.get(player);
        if (isIncrease) {
            currentPoints++;
        } else if (currentPoints != 0) {
            currentPoints--;
        }
        pointsMap.put(player, currentPoints);
    }
}
