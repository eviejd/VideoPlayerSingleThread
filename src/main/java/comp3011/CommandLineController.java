/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Evie Durdin, a2957600
 *
 * Copyright 2026 Simon Ratcliffe
 * 
 * Modification note: option parsing rewritten to support clustered short flags and the frame processor options (Part 1)
 * 
 *  AI assistance: I used Claude (Anthropic) as a development aid for guidance and problem-solving. 
 *  I substantially modified, refined and integrated the suggested code into my own implementation, 
 *  tested the final result and made sure I understood how everything worked.
 *  
 */
package comp3011;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Created in the VideoPlayerApp's main function to receive and parse the video player's command-line arguments.
 *
 * <p>
 * Extracts the video file and supported launch options, reports invalid input
 * or help text, and exposes the resulting launch configuration via methods to the
 * {@link VideoPlayerApp}. Frame processor options build an ordered (and repeatable) list of
 * {@link FrameProcessor} objects for the {@link VideoPlayerModel} to apply to each frame
 * </p>
 */
public class CommandLineController {
    private static final Map<String, Character> LONG_OPTIONS = Map.ofEntries(
            Map.entry("--help", 'h'),
            Map.entry("--audio", 'a'),
            Map.entry("--maximise", 'x'),
            Map.entry("--monitor-1", '1'),
            Map.entry("--monitor-2", '2'),
            Map.entry("--number-frames", 'n'),
            Map.entry("--scratch-frames", 's'),
            Map.entry("--flicker-frames", 'f'),
            Map.entry("--black-and-white", 'w'),
            Map.entry("--yellow-frames", 'y'),
            Map.entry("--vignette-frame", 'v'),
            Map.entry("--dust-frame", 'd'),
            Map.entry("--jitter-frames", 'j'),
            Map.entry("--mottle-frames", 'm'),
            Map.entry("--bleed-frames", 'b'),
            Map.entry("--pepper-frames", 'p'));

    private static final Map<Character, Supplier<FrameProcessor>> EFFECTS = Map.ofEntries(
            Map.entry('n', FrameNumberer::new),
            Map.entry('s', FrameScratcher::new),
            Map.entry('f', FrameFlickerer::new),
            Map.entry('w', FrameBlackAndWhiter::new),
            Map.entry('y', FrameYellower::new),
            Map.entry('v', FrameVignetter::new),
            Map.entry('d', FrameDuster::new),
            Map.entry('j', FrameJitterer::new),
            Map.entry('m', FrameMottler::new),
            Map.entry('b', FrameBleeder::new),
            Map.entry('p', FramePepperer::new));

    private final String[] args;
    private final List<FrameProcessor> frameProcessors = new ArrayList<>();

    private boolean helpRequested;
    private boolean audioRequested;
    private boolean maximiseRequested;
    private Integer displayId;
    private File videoFile;
    private String errorMessage;

    public CommandLineController(String[] args) {
        this.args = args.clone();
        parse();
        if (errorMessage != null) {
            System.out.println(errorMessage);
        }
        if (helpRequested) {
            printHelp();
        }
    }

    public File getVideoFile() {
        return videoFile;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public int getExitCode() {
        return errorMessage == null ? 0 : 1;
    }

    public Integer getDisplayId() {
        return displayId;
    }

    public boolean isAudioRequested() {
        return audioRequested;
    }

    public boolean isMaximiseRequested() {
        return maximiseRequested;
    }
    
    public List<FrameProcessor> getFrameProcessors() {
        return new ArrayList<>(frameProcessors);
    }

    public boolean shouldLaunchApplication() {
        return errorMessage == null && videoFile != null;
    }

    private void parse() {
        // keep track of the video files passed in
        List<String> videoFiles = new ArrayList<>();
        for (String arg : args) {
            if (arg.startsWith("--")) {
                Character option = LONG_OPTIONS.get(arg);
                if (option == null) {
                    setError("Unknown option: " + arg);
                } else {
                    handleOption(option);
                }
            } else if (arg.startsWith("-") && arg.length() > 1) {
                // loop through each short option in case they're grouped together
                for (char option : arg.substring(1).toCharArray()) {
                    if (!LONG_OPTIONS.containsValue(option)) {
                        setError("Unknown option: -" + option);
                    } else {
                        handleOption(option);
                    }
                }
            } else if (arg.startsWith("-")) {
                setError("Unknown option: " + arg); // just a single dash
            } else {
                videoFiles.add(arg);
            }
        }
        // only one video file should be provided
        if (videoFiles.size() > 1) {
            setError("Usage: VideoPlayer [options] [video-file]");
        } else if (videoFiles.size() == 1) {
            videoFile = new File(videoFiles.get(0));
            // check the file actually exists
            if (!videoFile.isFile()) {
                setError("File not found: " + videoFile.getPath());
                videoFile = null;
            }
        } else {
            // no file needed if help was requested
            if (!helpRequested) {
                setError("No video file specified.");
            }
        }
    }

    private void handleOption(char option) {
        // set the matching option
        switch (option) {
            case 'h' -> helpRequested = true;
            case 'a' -> audioRequested = true;
            case 'x' -> maximiseRequested = true;
            case '1' -> setDisplayId(1);
            case '2' -> setDisplayId(2);
            default -> frameProcessors.add(EFFECTS.get(option).get());
        }
    }

    // don't overwrite the first error
    private void setError(String message) {
        if (errorMessage == null) {
            errorMessage = message;
        }
    }

    private void setDisplayId(int displayId) {
        if (this.displayId != null && this.displayId != displayId) {
            setError("Only one display option can be used");
            return;
        }
        this.displayId = displayId;
    }

    private void printHelp() {
        System.out.println("Usage: VideoPlayer [options] [video-file]");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  -h, --help         Show this help message");
        System.out.println("  -a, --audio        Play audio");
        System.out.println("  -x, --maximise     Open the player maximised");
        System.out.println("  -1, --monitor-1    Open the player on display 1");
        System.out.println("  -2, --monitor-2    Open the player on display 2");
        System.out.println();
        System.out.println("Frame processors:");
        System.out.println("  -n, --number-frames    Render the frame number onto each frame");
        System.out.println("  -s, --scratch-frames   Render vertical film scratches");
        System.out.println("  -f, --flicker-frames   Randomly dim frames");
        System.out.println("  -w, --black-and-white  Convert frames to black and white");
        System.out.println("  -y, --yellow-frames    Apply a warmer colour temperature");
        System.out.println("  -v, --vignette-frame   Darken the frame edges");
        System.out.println("  -d, --dust-frame       Render dust and hair marks");
        System.out.println("  -j, --jitter-frames    Randomly displace frames by a few pixels");
        System.out.println("  -m, --mottle-frames    Add cloudy emulsion mottling");
        System.out.println("  -b, --bleed-frames     Bleed light into frames");
        System.out.println("  -p, --pepper-frames    Pepper frames with dark spots/blotches");
        System.out.println();
        System.out.println("Frame processors are applied in command-line order and may be repeated.");
        System.out.println("Example: -nssnfwyvdjmbp numbers, scratches twice, numbers again, flickers,");
        System.out.println("converts, warms, vignettes, dusts, jitters, mottles, bleeds, then peppers.");
    }
}
