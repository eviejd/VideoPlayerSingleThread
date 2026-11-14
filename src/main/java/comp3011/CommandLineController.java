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
import java.util.HashMap;
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
 * {@link FrameProcessor} objects for the {@link VideoPlayerModel} to apply to each frame.
 * </p>
 *
 * <p>
 * Adding a new frame processor: create a new subclass of {@link FrameProcessor} and add one entry to
 * {@link #EFFECT_OPTIONS}. This is all that is needed because the short flag, long flag, help text and argument
 * parsing are handled automatically from this list. It also supports combining short flags (e.g. -nsw)
 * and applying effects in the order they were entered. Duplicate flags are checked when the class loads, so any
 * conflicts are caught straight away.
 * </p>
 */
public class CommandLineController {
    /**
     * Stores the details for one frame processor option. The factory creates a new processor each time rather than
     * reusing the same one, since each processor has its own state and its position in the effects list is used
     * when generating its pseudo-random seed.
     */
    private record EffectOption(
            char shortFlag, String longFlag, String description, Supplier<FrameProcessor> factory) {
    }

    // Add new frame processor options here. This order is only used for displaying the help text.
    // When running the program, effects are applied in the order the user enters them.
    private static final List<EffectOption> EFFECT_OPTIONS = List.of(
            new EffectOption('n', "--number-frames", "Render the frame number onto each frame", FrameNumberer::new),
            new EffectOption('s', "--scratch-frames", "Render vertical film scratches", FrameScratcher::new),
            new EffectOption('f', "--flicker-frames", "Randomly dim frames", FrameFlickerer::new),
            new EffectOption('w', "--black-and-white", "Convert frames to black and white", FrameBlackAndWhiter::new),
            new EffectOption('y', "--yellow-frames", "Apply a warmer colour temperature", FrameYellower::new),
            new EffectOption('v', "--vignette-frames", "Darken the frame edges", FrameVignetter::new),
            new EffectOption('d', "--dust-frames", "Render dust and hair marks", FrameDuster::new),
            new EffectOption('j', "--jitter-frames", "Randomly displace frames by a few pixels", FrameJitterer::new),
            new EffectOption('m', "--mottle-frames", "Add cloudy emulsion mottling", FrameMottler::new),
            new EffectOption('b', "--bleed-frames", "Bleed light into frames", FrameBleeder::new),
            new EffectOption('p', "--pepper-frames", "Pepper frames with dark spots/blotches", FramePepperer::new));

    // These are the built-in options that control how the program starts, rather than changing the frames.
    private static final Map<String, Character> CORE_LONG_OPTIONS = Map.of(
            "--help", 'h',
            "--audio", 'a',
            "--maximise", 'x',
            "--monitor-1", '1',
            "--monitor-2", '2');

    // These maps are built from the options above so short and long flags stay in sync.
    private static final Map<String, Character> LONG_TO_SHORT = new HashMap<>(CORE_LONG_OPTIONS);
    private static final Map<Character, EffectOption> EFFECTS_BY_SHORT = new HashMap<>();

    static {
        for (EffectOption option : EFFECT_OPTIONS) {
            boolean duplicate = EFFECTS_BY_SHORT.put(option.shortFlag(), option) != null
                    || LONG_TO_SHORT.put(option.longFlag(), option.shortFlag()) != null
                    || CORE_LONG_OPTIONS.containsValue(option.shortFlag());
            if (duplicate) {
                throw new IllegalStateException("Duplicate command-line option: " + option.longFlag());
            }
        }

        // The written specification spells these two without the final "s", so keep accepting that too.
        LONG_TO_SHORT.put("--vignette-frame", 'v');
        LONG_TO_SHORT.put("--dust-frame", 'd');
    }

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
    
	// The processors in the order they were entered on the command line. A copy is returned so callers
	// can't change the original list. The VideoPlayerModel takes ownership of the processors and gives
	// each one to its own pipeline thread.
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
                Character option = LONG_TO_SHORT.get(arg);
                if (option == null) {
                    setError("Unknown option: " + arg);
                } else {
                    handleOption(option);
                }
            } else if (arg.startsWith("-") && arg.length() > 1) {
                // loop through each short option in case they're grouped together
                for (char option : arg.substring(1).toCharArray()) {
                    if (!isKnownShortOption(option)) {
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
    
    private boolean isKnownShortOption(char option) {
        return CORE_LONG_OPTIONS.containsValue(option) || EFFECTS_BY_SHORT.containsKey(option);
    }

    private void handleOption(char option) {
        // set the matching option
        switch (option) {
            case 'h' -> helpRequested = true;
            case 'a' -> audioRequested = true;
            case 'x' -> maximiseRequested = true;
            case '1' -> setDisplayId(1);
            case '2' -> setDisplayId(2);
            default -> frameProcessors.add(EFFECTS_BY_SHORT.get(option).factory().get());
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
        // Find the length of the longest flag so the descriptions all start in the same column.
        int width = EFFECT_OPTIONS.stream().mapToInt(o -> o.longFlag().length()).max().orElse(0) + 2;
        for (EffectOption option : EFFECT_OPTIONS) {
            // Print each option with its short flag, long flag and description.
            System.out.printf("  -%c, %-" + width + "s%s%n", option.shortFlag(), option.longFlag(), option.description());
        }
        System.out.println();
        System.out.println("Frame processors are applied in command-line order and may be repeated.");
        System.out.println("Example: -nssnfwyvdjmbp numbers, scratches twice, numbers again, flickers,");
        System.out.println("converts, warms, vignettes, dusts, jitters, mottles, bleeds, then peppers.");
    }
}
