package ch.m3ts.tabletennis.events;

import android.graphics.Point;

import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import ch.m3ts.tabletennis.Table;
import ch.m3ts.tabletennis.helper.Side;

import static org.opencv.core.Core.inRange;

/**
 * Takes a frame in YUV format and checks if a player held his racket in the ready to serve area.
 **/
public class ReadyToServeDetector {
    private static final double PERCENTAGE_THRESHOLD = 0.5;
    private static final double PERCENTAGE_THRESHOLD_BLACK = 0.8;
    private static final int GESTURE_HOLD_TIME_IN_FRAMES = 15;
    private static final double GESTURE_AREA_PERCENTAGE_RELATIVE_TO_TABLE = 0.05;
    private static final double MAX_COLOR_CHANNEL_OFFSET = 10;
    private final Table table;
    private final ReadyToServeCallback callback;
    private final Side server;
    private final boolean useBlackSide;
    private int gestureFrameCounter = 0;

    public ReadyToServeDetector(Table table, Side server, ReadyToServeCallback callback, boolean useBlackSide) {
        this.table = table;
        this.server = server;
        this.callback = callback;
        this.useBlackSide = useBlackSide;
    }

    /**
     * Checks every three frames if the player held his racket into the ready for serve area,
     * If the gesture was active for 15 frames (0.5s on a 30 FPS camera) it returns true and
     * triggers an event
     *
     * @param bgrMat frame as a BGR OpenCV Mat
     * @return true if gesture was active for 15 frames and false otherwise
     */
    public boolean isReadyToServe(Mat bgrMat) {
        boolean isReady = false;
        gestureFrameCounter++;
        if (gestureFrameCounter % 3 == 0 && OpenCVLoader.initDebug()) {
            if (isRacketInArea(bgrMat)) {
                if (gestureFrameCounter >= GESTURE_HOLD_TIME_IN_FRAMES) {
                    this.callback.onGestureDetected();
                    isReady = true;
                }
            } else {
                gestureFrameCounter = 0;
            }
        }
        return isReady;
    }

    private boolean isRacketInArea(Mat bgrMat) {
        if (useBlackSide) {
            return (getRedPercentage(bgrMat) > PERCENTAGE_THRESHOLD) ||
                    (getBlackPercentage(bgrMat) > PERCENTAGE_THRESHOLD_BLACK);
        } else {
            return (getRedPercentage(bgrMat) > PERCENTAGE_THRESHOLD);
        }
    }

    private double getRedPercentage(Mat bgrMat) {
        Mat resized = resizeMatToAreaSize(bgrMat);
        Mat maskWithInvert = segmentRedColorViaInverting(resized);
        Mat maskWithTwoThresh = segmentRedColor(resized);
        Mat mask = new Mat();
        Core.bitwise_or(maskWithInvert, maskWithTwoThresh, mask);
        return Core.countNonZero(mask) / (double) mask.total();
    }

    private double getBlackPercentage(Mat bgrMat) {
        Mat resized = resizeMatToAreaSize(bgrMat);
        Mat mask = segmentBlackColor(resized);
        return Core.countNonZero(mask) / (double) mask.total();
    }

    private Mat resizeMatToAreaSize(Mat bgrMat) {
        return bgrMat.submat(getGestureArea());
    }

    /**
     * Calculates square around table corner of server
     *
     * @return rect with with sides in the size of GESTURE_AREA_PERCENTAGE_RELATIVE_TO_TABLE of the table width
     */
    private Rect getGestureArea() {
        int width = (int) (this.table.getWidth() * GESTURE_AREA_PERCENTAGE_RELATIVE_TO_TABLE);

        Point position = this.table.getCornerDownLeft();
        int positionX = position.x;
        if (this.server == Side.RIGHT) {
            position = this.table.getCornerDownRight();
            positionX = position.x - width;
            if (positionX < 0) positionX = 0;
        }
        return new Rect(positionX, position.y - width / 2, width, width);
    }

    /**
     * Segments red color from image with two color ranges.
     * Needs more resources than segmentation with inversion.
     *
     * @param bgr mat with bgr color space
     * @return image where red pixels in original images are 255 and others 0
     */
    private Mat segmentRedColor(Mat bgr) {
        Mat mask1 = new Mat();
        Mat mask2 = new Mat();
        Mat normal = new Mat();
        Mat hsv = new Mat();
        Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV, 3);
        inRange(hsv, new Scalar(0, 120, 50), new Scalar(MAX_COLOR_CHANNEL_OFFSET, 255, 255), mask1);
        inRange(hsv, new Scalar(170, 120, 50), new Scalar(180, 255, 255), mask2);
        Core.bitwise_or(mask1, mask2, normal);
        return mask1;
    }

    /**
     * Segments black color from image with two color ranges.
     *
     * @param bgr mat with bgr color space
     * @return image where black pixels in original images are 255 and others 0
     */
    private Mat segmentBlackColor(Mat bgr) {
        Mat mask1 = new Mat();
        Mat hsv = new Mat();
        Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV, 3);
        inRange(hsv, new Scalar(0, 0, 0), new Scalar(255, 255, 70), mask1);
        return mask1;
    }


    /**
     * Segments red color from image by inverting first and then looking for cyan parts in image.
     * Needs less resources than segmentRedColor.
     *
     * @param bgr mat with bgr color space
     * @return image where red pixels in original images are 255 and others 0
     */
    private Mat segmentRedColorViaInverting(Mat bgr) {
        Mat bgrInverted = new Mat();
        Mat hsvInverted = new Mat();
        Mat maskInv = new Mat();
        Core.bitwise_not(bgr, bgrInverted);
        Imgproc.cvtColor(bgrInverted, hsvInverted, Imgproc.COLOR_BGR2HSV);
        inRange(hsvInverted, new Scalar(90 - MAX_COLOR_CHANNEL_OFFSET, 70, 50), new Scalar(90 + MAX_COLOR_CHANNEL_OFFSET, 255, 255), maskInv);
        return maskInv;
    }
}
