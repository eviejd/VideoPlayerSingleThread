
/*
 * Authors:
 *   1. Evie Durdin, a2957600
 *
 * Tests for Part 1 (command-line frame processor options). These check that effects are applied in the order
 * entered, repeated effects create separate instances, short and long flags work the same way, and invalid
 * options are handled correctly instead of being ignored.
 */
package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandLineControllerTest {
    // The controller checks that the video file exists, so create an empty one for testing.
    // It doesn't actually need to contain a video because it is never decoded.
    @TempDir
    Path tempDir;

    private String video() throws IOException {
        return Files.createFile(tempDir.resolve("clip.mp4")).toString();
    }

    // Get the class names of the processors so they are easier to compare in the tests.
    private static List<String> names(CommandLineController controller) {
        return controller.getFrameProcessors().stream().map(p -> p.getClass().getSimpleName()).toList();
    }

    private record Run(CommandLineController controller, String output) {
    }

    // Capture System.out because the controller prints errors and help messages there.
    // This lets us check what would actually be displayed to the user.
    private static Run run(String... args) {
        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true));
        try {
            return new Run(new CommandLineController(args), buffer.toString());
        } finally {
            System.setOut(original);
        }
    }

    @Test
    void effectsAreAppliedInCommandLineOrder() throws IOException {
        CommandLineController controller = new CommandLineController(new String[] {"-nsw", video()});
        assertEquals(List.of("FrameNumberer", "FrameScratcher", "FrameBlackAndWhiter"), names(controller));
        assertTrue(controller.shouldLaunchApplication());
        assertEquals(0, controller.getExitCode());
    }

    @Test
    void sameEffectCanBeRepeatedAndEachUseIsANewInstance() throws IOException {
        CommandLineController controller = new CommandLineController(new String[] {"-ss", video()});
        List<FrameProcessor> processors = controller.getFrameProcessors();
        assertEquals(2, processors.size());
        assertNotSame(processors.get(0), processors.get(1));
    }

    @Test
    void clusteredSeparateAndLongSpellingsAreEquivalent() throws IOException {
        String file = video();
        List<String> clustered = names(new CommandLineController(new String[] {"-nssnf", file}));
        List<String> separate = names(new CommandLineController(new String[] {"-n", "-s", "-s", "-n", "-f", file}));
        List<String> longForm = names(new CommandLineController(new String[] {
            "--number-frames", "--scratch-frames", "--scratch-frames", "--number-frames", "--flicker-frames", file}));
        assertEquals(5, clustered.size());
        assertEquals(clustered, separate);
        assertEquals(clustered, longForm);
    }

    @Test
    void optionsMayComeAfterTheVideoFileAndMixWithOtherOptions() throws IOException {
        CommandLineController controller = new CommandLineController(new String[] {"-a", video(), "-w", "-x"});
        assertEquals(List.of("FrameBlackAndWhiter"), names(controller));
        assertTrue(controller.isAudioRequested());
        assertTrue(controller.isMaximiseRequested());
    }

    @Test
    void noEffectOptionsMeansNoProcessors() throws IOException {
        assertTrue(new CommandLineController(new String[] {video()}).getFrameProcessors().isEmpty());
    }

    @Test
    void everyEffectOptionBuildsADistinctProcessor() throws IOException {
        // One of each effect, in the same order as the help text.
        // This checks that every flag creates the correct processor.
        CommandLineController controller = new CommandLineController(new String[] {"-nsfwyvdjmbp", video()});
        assertEquals(List.of("FrameNumberer", "FrameScratcher", "FrameFlickerer", "FrameBlackAndWhiter",
                "FrameYellower", "FrameVignetter", "FrameDuster", "FrameJitterer", "FrameMottler",
                "FrameBleeder", "FramePepperer"), names(controller));
    }

    @Test
    void unknownShortOptionIsAnErrorAndStopsLaunch() throws IOException {
        Run run = run("-z", video());
        assertEquals("Unknown option: -z", run.controller().getErrorMessage());
        assertTrue(run.output().contains("Unknown option: -z")); // Check what the user actually sees.
        assertFalse(run.controller().shouldLaunchApplication());
        assertEquals(1, run.controller().getExitCode());
    }

    @Test
    void unknownLongOptionIsAnError() throws IOException {
        CommandLineController controller = new CommandLineController(new String[] {"--no-such-effect", video()});
        assertEquals("Unknown option: --no-such-effect", controller.getErrorMessage());
        assertFalse(controller.shouldLaunchApplication());
    }

    @Test
    void firstErrorIsKept() throws IOException {
        CommandLineController controller = new CommandLineController(new String[] {"-q", "-z", video()});
        assertEquals("Unknown option: -q", controller.getErrorMessage());
    }

    @Test
    void missingFileIsReported() {
        CommandLineController controller = new CommandLineController(new String[] {"-n", "no-such-file.mp4"});
        assertEquals("File not found: no-such-file.mp4", controller.getErrorMessage());
        assertNull(controller.getVideoFile());
    }

    @Test
    void helpListsEveryFrameProcessorOption() {
        Run run = run("--help");
        String help = run.output();
        // Asking for help shouldn't count as an error, even if no video file was provided.
        assertNull(run.controller().getErrorMessage());
        assertTrue(help.contains("Frame processors:"));
        for (String flag : List.of("-n, --number-frames", "-s, --scratch-frames", "-f, --flicker-frames",
                "-w, --black-and-white", "-y, --yellow-frames", "-v, --vignette-frames", "-d, --dust-frames",
                "-j, --jitter-frames", "-m, --mottle-frames", "-b, --bleed-frames", "-p, --pepper-frames")) {
            assertTrue(help.contains(flag), "help is missing " + flag);
        }
        assertTrue(help.contains("applied in command-line order and may be repeated"));
    }

    @Test
    void helpColumnsLineUp() {
        String help = run("-h").output();
        // Check the spacing between flags and descriptions so they line up in the help output.
        assertTrue(help.contains("  -w, --black-and-white  Convert frames to black and white"));
        assertTrue(help.contains("  -n, --number-frames    Render the frame number onto each frame"));
    }

    @Test
    void conflictingDisplayOptionsAreRejected() throws IOException {
        CommandLineController controller = new CommandLineController(new String[] {"-1", "-2", video()});
        assertEquals("Only one display option can be used", controller.getErrorMessage());
    }

    @Test
    void returnedProcessorListIsACopy() throws IOException {
        CommandLineController controller = new CommandLineController(new String[] {"-n", video()});
        // Clearing the returned list shouldn't affect the original list stored by the controller.
        controller.getFrameProcessors().clear();
        assertEquals(1, controller.getFrameProcessors().size());
    }
}
