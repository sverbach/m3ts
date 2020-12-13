package cz.fmo;

import com.pubnub.api.Pubnub;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import java.util.Random;

import ch.m3ts.display.DisplayScoreEventCallback;
import ch.m3ts.pubnub.DisplayPubNub;
import ch.m3ts.pubnub.JSONInfo;
import ch.m3ts.tabletennis.helper.Side;
import ch.m3ts.tabletennis.match.UICallback;

import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyInt;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.powermock.api.mockito.PowerMockito.mock;
import static org.powermock.api.mockito.PowerMockito.spy;

@RunWith(PowerMockRunner.class)
@PrepareForTest(Pubnub.class)
public class DisplayPubNubTest {
    private Random random = new Random();

    @Test
    public void testWithValidJSON() {
        try {
            UICallback spyCallback = spy(mock(UICallback.class));
            DisplayScoreEventCallback deCallback = spy(mock(DisplayScoreEventCallback.class));
            Pubnub pubnubSpy = spy(mock(Pubnub.class));
            PowerMockito.whenNew(Pubnub.class).withArguments("invalid", "invalid").thenReturn(pubnubSpy);
            DisplayPubNub displayPubNub = new DisplayPubNub("invalid", "invalid", "invalid");
            displayPubNub.setUiCallback(spyCallback);
            displayPubNub.setDisplayScoreEventCallback(deCallback);

            // test onScore
            JSONObject jsonScore = makeJSONObject("onScore", Side.RIGHT, random.nextInt(999), null, Side.LEFT);
            displayPubNub.connectCallback("invalid", jsonScore);
            verify(spyCallback, times(0)).onScore(Side.RIGHT, jsonScore.getInt("score"), Side.LEFT);
            displayPubNub.successCallback("invalid", jsonScore);
            verify(spyCallback, times(1)).onScore(Side.RIGHT, jsonScore.getInt("score"), Side.LEFT);

            // test onWin
            JSONObject jsonWin = makeJSONObject("onWin", Side.LEFT, null, random.nextInt(999), null);
            displayPubNub.connectCallback("invalid", jsonWin);
            verify(spyCallback, times(0)).onWin(Side.LEFT, jsonWin.getInt("wins"));
            displayPubNub.successCallback("invalid", jsonWin);
            verify(spyCallback, times(1)).onWin(Side.LEFT, jsonWin.getInt("wins"));

            // test onMatchEnded
            JSONObject jsonMatchEnded = makeJSONObject("onMatchEnded", Side.LEFT, null, null, null);
            displayPubNub.connectCallback("invalid", jsonMatchEnded);
            verify(spyCallback, times(0)).onMatchEnded(Side.LEFT.toString());
            displayPubNub.successCallback("invalid", jsonMatchEnded);
            verify(spyCallback, times(1)).onMatchEnded(Side.LEFT.toString());

        } catch (Exception ex) {
            fail(ex.getMessage());
        }
    }

    @Test
    public void testWithInvalidJSON() {
        try {
            UICallback spyCallback = spy(mock(UICallback.class));
            DisplayScoreEventCallback deCallback = spy(mock(DisplayScoreEventCallback.class));
            Pubnub pubnubSpy = spy(mock(Pubnub.class));
            PowerMockito.whenNew(Pubnub.class).withArguments("invalid", "invalid").thenReturn(pubnubSpy);
            DisplayPubNub displayPubNub = new DisplayPubNub("invalid", "invalid", "invalid");
            displayPubNub.setUiCallback(spyCallback);
            displayPubNub.setDisplayScoreEventCallback(deCallback);

            for (int i = 0; i<100; i++) {
                JSONObject invalidJSON = makeJSONObject(generateRandomAlphabeticString(random.nextInt(20)),
                        Side.values()[random.nextInt(4)], random.nextInt(999), random.nextInt(999), Side.values()[random.nextInt(4)]);
                displayPubNub.connectCallback("invalid", invalidJSON);
                displayPubNub.disconnectCallback("invalid", invalidJSON);
                displayPubNub.successCallback("invalid", invalidJSON);
                verify(spyCallback, times(0)).onScore(any(Side.class), anyInt(), any(Side.class));
                verify(spyCallback, times(0)).onWin(any(Side.class), anyInt());
                verify(spyCallback, times(0)).onMatchEnded(any(String.class));
            }
        } catch (Exception ex) {
            fail(ex.getMessage());
        }
    }


    private JSONObject makeJSONObject(String event, Side side, Integer score, Integer wins, Side nextServer) {
        JSONObject json = new JSONObject();
        try {
            json.put(JSONInfo.EVENT_PROPERTY, event);
            json.put(JSONInfo.SIDE_PROPERTY, side);
            json.put(JSONInfo.SCORE_PROPERTY, score);
            json.put(JSONInfo.WINS_PROPERTY, wins);
            json.put(JSONInfo.NEXT_SERVER_PROPERTY, nextServer);
        } catch (JSONException ex) {
            fail(ex.getMessage());
        }
        return json;
    }

    private String generateRandomAlphabeticString(int length) {
        int leftLimit = 97; // letter 'a'
        int rightLimit = 122; // letter 'z'
        Random random = new Random();
        StringBuilder buffer = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            int randomLimitedInt = leftLimit + (int)
                    (random.nextFloat() * (rightLimit - leftLimit + 1));
            buffer.append((char) randomLimitedInt);
        }
        return buffer.toString();
    }

}