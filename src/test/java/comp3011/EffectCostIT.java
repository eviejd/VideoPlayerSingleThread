
/*
 * Authors:
 *   1. Evie Durdin, a2957600
 *   
 * Measures how long each frame processor takes to process an HD (1920x1080) frame.
 * This runs as part of `mvn verify` and helps show why using a separate thread for each
 * effect can improve performance.
 *
 * In the original player, all effects run one after another on the same thread, so their
 * processing times add together. In the pipeline, each effect has its own thread, so the
 * processing time is mainly limited by whichever effect takes the longest.
 *
 * This test measures the effects using generated frames rather than playing an actual video.
 * It helps show the potential performance improvement, but doesn't measure the finished
 * player's actual playback speed. That still needs to be checked by running the player,
 * as explained in the README.
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(300)
class EffectCostIT {
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 1080;
    private static final int STRIDE = WIDTH * 3;
    private static final int WARM_UP = 8;
    private static final int MEASURED = 20;
    private static final String[] NAMES = {"number", "scratch", "flicker", "black&white", "yellow", "vignette",
        "dust", "jitter", "mottle", "bleed", "pepper"};
    private static final String FLAGS = "nsfwyvdjmbp"; // Keep this in the same order as NAMES.

    @TempDir
    Path tempDir;

    private List<FrameProcessor> effects(String flags) throws Exception {
        Path video = Files.createTempFile(tempDir, "clip", ".mp4");
        List<FrameProcessor> processors = new CommandLineController(new String[] {flags, video.toString()})
                .getFrameProcessors();
        for (FrameProcessor processor : processors) {
            processor.initialise(new InfoVideo("cost", 1000, WIDTH, HEIGHT, Frame.DEPTH_UBYTE, 3, STRIDE,
                    24.0, 24, 41_667, 0));
        }
        return processors;
    }

    // Measure the average time taken to process one frame with the given effects running in order.
    // The first few frames are used to warm things up and aren't included in the timing.
    private double millisPerFrame(List<FrameProcessor> stack) throws Exception {
        Frame frame = new Frame(WIDTH, HEIGHT, Frame.DEPTH_UBYTE, 3, STRIDE);
        ByteBuffer pixels = (ByteBuffer) frame.image[0];
        for (int i = 0; i < pixels.capacity(); i++) {
            pixels.put(i, (byte) (i * 31 + (i >> 9)));
        }
        long start = 0;
        for (int n = 0; n < WARM_UP + MEASURED; n++) {
            if (n == WARM_UP) {
                start = System.nanoTime();
            }
            InfoFrame info = new InfoFrame(n, n * 41_667L);
            for (FrameProcessor processor : stack) {
                processor.process(frame, info);
            }
        }
        return (System.nanoTime() - start) / 1e6 / MEASURED;
    }

    @Test
    void stackedEffectCostsAddUpSoPipeliningHasRoomToHelp() throws Exception {
        double sum = 0;
        double slowest = 0;
        String slowestName = "";
        System.out.printf("%n[cost] ms per frame for each effect on its own (%dx%d)%n", WIDTH, HEIGHT);
        for (int i = 0; i < NAMES.length; i++) {
            double ms = millisPerFrame(effects("-" + FLAGS.charAt(i)));
            System.out.printf("[cost]   %-12s %6.2f ms%n", NAMES[i], ms);
            sum += ms;
            if (ms > slowest) {
                slowest = ms;
                slowestName = NAMES[i];
            }
        }

        double allTogether = millisPerFrame(effects("-" + FLAGS));
        double bound = sum / slowest;
        System.out.printf("[cost] all 11 in one thread: %.2f ms/frame (parts add up to %.2f ms)%n", allTogether, sum);
        System.out.printf("[cost] slowest effect: %s (%.2f ms). Best possible pipeline speed-up for this stack: %.1fx%n",
                slowestName, slowest, bound);
        System.out.printf("[cost] at 24 frames/s a frame must be done in 41.7 ms; the single thread %s%n",
                allTogether <= 41.7 ? "keeps up" : "FALLS BEHIND");

        // Check that running all the effects together takes roughly the same time as adding up
        // their individual times. The range allows for some variation in the timing results.
        assertTrue(allTogether >= 0.5 * sum && allTogether <= 1.6 * sum,
                "expected stacked cost ~ sum of parts, got " + allTogether + " vs " + sum);

        // Check that splitting the effects across threads has some potential to improve performance.
        // If one effect takes almost all the time, adding more threads won't make much difference.
        assertTrue(bound >= 1.2, "pipelining could give at most " + bound + "x, so one effect dominates the stack");
    }
}
