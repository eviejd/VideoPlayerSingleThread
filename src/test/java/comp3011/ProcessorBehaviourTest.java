
/*
 * Authors:
 *   1. Evie Durdin, a2957600
 *
 * Tests that check the frame processors behave correctly when used in the multi-threaded pipeline.
 * Each processor runs on its own thread, so it needs to give the same result for a particular frame
 * regardless of which frames it has processed before. These tests also check that the order of effects
 * matters and matches the order entered on the command line.
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.CRC32;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessorBehaviourTest {
    private static final int WIDTH = 160;
    private static final int HEIGHT = 90;
    private static final int STRIDE = WIDTH * 3;
    private static final String ALL_EFFECT_FLAGS = "-nsfwyvdjmbp";

    @TempDir
    Path tempDir;

    // Create the processors using the actual command-line flags and initialise them
    // in the same way they are initialised in VideoPlayerModel.
    private List<FrameProcessor> effects(String flags) throws Exception {
        Path video = Files.createTempFile(tempDir, "clip", ".mp4");
        List<FrameProcessor> processors = new CommandLineController(new String[] {flags, video.toString()})
                .getFrameProcessors();
        for (FrameProcessor processor : processors) {
            processor.initialise(new InfoVideo("test", 100, WIDTH, HEIGHT, Frame.DEPTH_UBYTE, 3, STRIDE,
                    24.0, 24, 41_667, 0));
        }
        return processors;
    }

    private static Frame inputFrame(int number) {
        Frame frame = new Frame(WIDTH, HEIGHT, Frame.DEPTH_UBYTE, 3, STRIDE);
        ByteBuffer pixels = (ByteBuffer) frame.image[0];
        for (int i = 0; i < pixels.capacity(); i++) {
            pixels.put(i, (byte) (i * 7 + number * 13 + (i >> 6)));
        }
        return frame;
    }

    // Run a single processor on a frame and calculate a checksum of the result.
    // This makes it easier to compare whether two processed frames are identical.
    private static long run(FrameProcessor processor, int frameNumber) throws Exception {
        Frame frame = inputFrame(frameNumber);
        processor.process(frame, new InfoFrame(frameNumber, frameNumber * 41_667L));
        return fingerprint(frame);
    }

    private static long fingerprint(Frame frame) {
        ByteBuffer pixels = ((ByteBuffer) frame.image[0]).duplicate();
        pixels.clear();
        byte[] bytes = new byte[pixels.remaining()];
        pixels.get(bytes);
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, bytes.length);
        return crc.getValue();
    }

    @Test
    void everyEffectGivesTheSameResultForAFrameWhateverItProcessedBefore() throws Exception {
        // Process frames in their normal order first, then in a different order using the same processor.
        // If a processor was keeping track of previous frames, the result could change depending on the order.
        // Each frame should produce exactly the same result both times.
        List<FrameProcessor> processors = effects(ALL_EFFECT_FLAGS);
        for (FrameProcessor processor : processors) {
            long[] inOrder = new long[3];
            for (int n = 0; n < 3; n++) {
                inOrder[n] = run(processor, n);
            }
            int[] shuffled = {2, 0, 1};
            for (int n : shuffled) {
                assertEquals(inOrder[n], run(processor, n),
                        processor.getClass().getSimpleName() + " gave a different picture for frame " + n
                                + " after processing other frames, so it keeps hidden state between frames");
            }
        }
    }

    @Test
    void everyEffectKeepsTheFrameTheSameSizeAndStaysInsideItsBuffer() throws Exception {
        // Check that processing a frame doesn't change its dimensions or the size of its pixel buffer.
        for (FrameProcessor processor : effects(ALL_EFFECT_FLAGS)) {
            Frame frame = inputFrame(4);
            int capacity = ((ByteBuffer) frame.image[0]).capacity();
            processor.process(frame, new InfoFrame(4, 4 * 41_667L));
            assertEquals(WIDTH, frame.imageWidth, processor.getClass().getSimpleName());
            assertEquals(HEIGHT, frame.imageHeight, processor.getClass().getSimpleName());
            assertEquals(capacity, ((ByteBuffer) frame.image[0]).capacity(), processor.getClass().getSimpleName());
        }
    }

    @Test
    void theOrderOfEffectsChangesThePicture() throws Exception {
        // Applying yellowing before black and white should give a different result from doing it the other way
        // around. This checks that the effects are applied in the order they were entered.
        long yellowThenGrey = runStack("-yw", 5);
        long greyThenYellow = runStack("-wy", 5);
        assertTrue(yellowThenGrey != greyThenYellow, "-yw and -wy gave identical pictures");
    }

    @Test
    void aStackGivesTheSameResultWhenRunTwiceOverTheSameInstances() throws Exception {
        // Run the same group of processors twice on the same frame using the same instances.
        // The result should be identical each time so the original player and the multi-threaded
        // pipeline can produce matching output.
        List<FrameProcessor> stack = effects("-ssnfwdjp");
        assertEquals(runStack(stack, 7), runStack(stack, 7));
    }

    private long runStack(String flags, int frameNumber) throws Exception {
        return runStack(effects(flags), frameNumber);
    }

    private static long runStack(List<FrameProcessor> stack, int frameNumber) throws Exception {
        Frame frame = inputFrame(frameNumber);
        InfoFrame info = new InfoFrame(frameNumber, frameNumber * 41_667L);
        for (FrameProcessor processor : stack) {
            processor.process(frame, info);
        }
        return fingerprint(frame);
    }
}
