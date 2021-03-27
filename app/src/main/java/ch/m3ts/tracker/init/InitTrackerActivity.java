package ch.m3ts.tracker.init;

import android.content.Intent;
import android.graphics.Point;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import ch.m3ts.connection.ConnectionCallback;
import ch.m3ts.connection.ConnectionHelper;
import ch.m3ts.connection.NearbyTrackerConnection;
import ch.m3ts.connection.pubnub.PubNubFactory;
import ch.m3ts.connection.pubnub.PubNubTrackerConnection;
import ch.m3ts.tracker.visualization.CameraPreviewActivity;
import ch.m3ts.tracker.visualization.live.LiveActivity;
import cz.fmo.R;
import cz.fmo.util.Config;

/**
 * "Main Activity" of the Tracker device.
 *
 * First it scans the QR-Code generated by the Display device and processes the there stored
 * information (PubNub Room ID and Match Settings).
 *
 * Then it waits until the handler tells this activity to switch to the LiveActivity -> the players
 * have decided to start the game.
 */
@SuppressWarnings("squid:S110")
public final class InitTrackerActivity extends CameraPreviewActivity implements SensorEventListener, ConnectionCallback {

    private static final String CORNERS_PARAM = "CORNERS_UNSORTED";
    private static final String MATCH_TYPE_PARAM = "MATCH_TYPE";
    private static final String SERVING_SIDE_PARAM = "SERVING_SIDE";
    private static final String MATCH_ID = "MATCH_ID";
    private static final int MAX_ALLOWED_ADJUSTMENT_OFFSET = 3;
    private static final int MAX_ALLOWED_ADJUSTMENT_OFFSET_TOP = 20;
    private PubNubTrackerConnection pubNubTrackerConnection;
    private SensorManager mSensorManager;
    private Sensor accelerometer;
    private Sensor magnetometer;
    private float[] mGravity;
    private float[] mGeomagnetic;
    private TextView pitchText;
    private TextView rollText;
    private TextView adjustDeviceText;
    private Button moveDeviceButton;
    private boolean isDeviceCentered = false;
    private ImageView adjustDeviceIcon;
    private View adjustDeviceOverlay;
    private View qrCodeOverlay;
    private View connectOverlay;
    private long countSensorRefresh;
    private NearbyTrackerConnection nearbyTrackerConnection;
    private boolean hasStartedConnecting = false;

    @Override
    protected void onCreate(android.os.Bundle savedBundle) {
        super.onCreate(savedBundle);
        cameraCallback = new InitTrackerHandler(this);
    }

    /**
     * Responsible for querying and acquiring camera permissions. Whatever the response will be,
     * the permission request could result in the application being paused and resumed. For that
     * reason, requesting permissions at any later point, including in onResume(), might cause an
     * infinite loop.
     */
    @Override
    protected void onStart() {
        super.onStart();
        FrameLayout layout = findViewById(R.id.frameLayout);
        pitchText = findViewById(R.id.pitch);
        rollText = findViewById(R.id.roll);
        adjustDeviceText = findViewById(R.id.adjust_device_info_text);
        adjustDeviceOverlay = findViewById(R.id.adjust_device_overlay);
        qrCodeOverlay = findViewById(R.id.scan_overlay);
        adjustDeviceIcon = findViewById(R.id.adjust_device_info_icon);
        moveDeviceButton = findViewById(R.id.init_moveDeviceBtn);
        connectOverlay = findViewById(R.id.connection_info);
        ViewGroup.LayoutParams params = layout.getLayoutParams();
        Display display = getWindowManager().getDefaultDisplay();
        Point size = new Point();
        display.getSize(size);
        params.height = size.y;
        params.width = size.x;
        layout.setLayoutParams(params);
        moveDeviceButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                isDeviceCentered = true;
                moveDeviceButton.setVisibility(View.INVISIBLE);
            }
        });
    }

    @Override
    public void onStop() {
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        init();
        moveDeviceButton.setVisibility(View.VISIBLE);
        ((InitTrackerHandler) cameraCallback).setIsReadingQRCode(false);
        mSensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        accelerometer = mSensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        magnetometer = mSensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        mSensorManager.registerListener(this, accelerometer, SensorManager.SENSOR_DELAY_UI);
        mSensorManager.registerListener(this, magnetometer, SensorManager.SENSOR_DELAY_UI);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mSensorManager.unregisterListener(this);
    }

    @Override
    public void setCurrentContentView() {
        setContentView(R.layout.activity_initialize);
    }

    void enterPubNubRoom(String roomId) {
        mSensorManager.unregisterListener(this);
        this.pubNubTrackerConnection = PubNubFactory.createTrackerPubNub(this, roomId);
        this.pubNubTrackerConnection.setInitTrackerCallback((InitTrackerCallback) this.cameraCallback);
    }

    void switchToLiveActivity(String selectedMatchId, int selectedMatchType, int selectedServingSide, int[] tableCorners) {
        if (this.pubNubTrackerConnection != null) this.pubNubTrackerConnection.unsubscribe();
        if (this.nearbyTrackerConnection != null) {
            this.nearbyTrackerConnection.setConnectionCallback(null);
            this.nearbyTrackerConnection = null;
        }
        Intent intent = new Intent(this, LiveActivity.class);
        Bundle bundle = new Bundle();
        bundle.putString(MATCH_ID, selectedMatchId);
        bundle.putInt(MATCH_TYPE_PARAM, selectedMatchType);
        bundle.putInt(SERVING_SIDE_PARAM, selectedServingSide);
        bundle.putIntArray(CORNERS_PARAM, tableCorners);
        intent.putExtras(bundle);
        startActivity(intent);
        this.finish();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!isDeviceCentered) return;
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER)
            mGravity = event.values;
        if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD)
            mGeomagnetic = event.values;
        if (mGravity != null && mGeomagnetic != null) {
            countSensorRefresh++;
            float[] rot = new float[9];
            float[] in = new float[9];
            boolean success = SensorManager.getRotationMatrix(rot, in, mGravity, mGeomagnetic);
            if (success) {
                handleSensorData(rot);
            }
        }
    }

    private void handleSensorData(float[] rot) {
        float[] orientation = new float[3];
        SensorManager.getOrientation(rot, orientation);
        long rollDegrees = Math.round(Math.toDegrees(orientation[2]));
        long pitchDegrees = Math.round(Math.toDegrees(orientation[1]));
        rollText.setText(String.format(getString(R.string.adjustDeviceRollDegreeText), Math.abs(rollDegrees)));
        pitchText.setText(String.format(getString(R.string.adjustDeviceTiltDegreeText), pitchDegrees));
        if(countSensorRefresh % 50 == 0) {
            if(pitchDegrees > MAX_ALLOWED_ADJUSTMENT_OFFSET) {
                changeAdjustmentInfo(R.drawable.tilt_right, R.string.adjustDeviceTiltRightText);
            } else if (pitchDegrees < -1 * MAX_ALLOWED_ADJUSTMENT_OFFSET) {
                changeAdjustmentInfo(R.drawable.tilt_left, R.string.adjustDeviceTiltLeftText);
            } else if (rollDegrees < -90 - MAX_ALLOWED_ADJUSTMENT_OFFSET_TOP) {
                changeAdjustmentInfo(R.drawable.roll_back, R.string.adjustDeviceRollBottomText);
            } else if (rollDegrees > -90 + MAX_ALLOWED_ADJUSTMENT_OFFSET) {
                changeAdjustmentInfo(R.drawable.roll_front, R.string.adjustDeviceRollTopText);
            } else {
                if(new Config(this).isUsingPubnub()) {
                    adjustDeviceOverlay.setVisibility(View.INVISIBLE);
                    qrCodeOverlay.setVisibility(View.VISIBLE);
                    ((InitTrackerHandler) cameraCallback).setIsReadingQRCode(true);
                } else {
                    adjustDeviceOverlay.setVisibility(View.INVISIBLE);
                    if(!hasStartedConnecting) {
                        ConnectionHelper.createConnection(this);
                        this.nearbyTrackerConnection = NearbyTrackerConnection.getInstance();
                        this.nearbyTrackerConnection.init(this);
                        this.nearbyTrackerConnection.setInitTrackerCallback((InitTrackerHandler) cameraCallback);
                        this.nearbyTrackerConnection.setConnectionCallback(this);
                        this.nearbyTrackerConnection.startDiscovery();
                        hasStartedConnecting = true;
                        connectOverlay.setVisibility(View.VISIBLE);
                    }
                }
            }
            countSensorRefresh = 0;
        }
    }

    private void changeAdjustmentInfo(int iconId, int messageId) {
        adjustDeviceIcon.setImageDrawable(getDrawable(iconId));
        adjustDeviceText.setText(this.getString(messageId));
        qrCodeOverlay.setVisibility(View.INVISIBLE);
        adjustDeviceOverlay.setVisibility(View.VISIBLE);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i) {
        // no need
    }

    @Override
    public void onDiscoverFailure() {
        setConnectInfoText(R.string.connectDiscoverFailure);
    }

    @Override
    public void onRejection() {
        setConnectInfoText(R.string.connectRejection);
    }

    @Override
    public void onDisconnection(String endpoint) {
        adjustDeviceOverlay.setVisibility(View.GONE);
        qrCodeOverlay.setVisibility(View.GONE);
        connectOverlay.setVisibility(View.VISIBLE);
        ConnectionHelper.makeDisconnectDialog(this).show();
    }

    @Override
    public void onConnection(String endpoint) {
        connectOverlay.setVisibility(View.GONE);
        setConnectInfoText(R.string.connectPicture);
        connectOverlay.setVisibility(View.VISIBLE);
    }

    @Override
    public void onConnecting(String endpoint) {
        ((TextView) findViewById(R.id.connection_text)).setText(String.format(getString(R.string.connectConnecting), endpoint));
    }

    private void setConnectInfoText(int stringId) {
        ((TextView) findViewById(R.id.connection_text)).setText(getString(stringId));
    }
}
