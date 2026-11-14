# COMP3011 Assignment 2 - Java Local Project

Author: Evie Durdin (a2957600). Built on the single-threaded starter code by Simon Ratcliffe

## Build, run and test

```bash
mvn compile javafx:run '-Djavafx.args=-nsw /path/to/video.mp4'   # play with effects (-h lists every option)
mvn test                                                         # unit tests
mvn verify                                                       # unit tests + effect cost measurement
```

Effect flags are applied in the order typed. They can also be repeated or grouped together. For example, `-nssnf` is the same as entering `-n -s -s -n -f`.

## Adding a new frame processor

1. Create a class that extends `FrameProcessor`. It needs to give the same result for a frame regardless of what it has processed before. `ProcessorBehaviourTest` checks this for the existing effects.
2. Add one entry to `EFFECT_OPTIONS` in `CommandLineController`.

Everything else is handled automatically from that table, including parsing, `--help`, grouped flags, ordering and repeats. Duplicate flags are also checked when the class loads, so any conflicts are picked up straight away.

The model creates one thread for each processor in the list, so no other changes are needed to add another effect.

## Design

Originally, everything for each frame happened on one thread. This included decoding the frame, applying all the effects and converting the image. This meant the time for each frame was the total time taken by all those steps.

I changed this so each part runs as a separate stage in a pipeline, with its own thread. This means different frames can be at different stages at the same time, spreading the work across the available cores. The overall processing speed is mainly limited by whichever stage takes the longest.

```text
decoder -> effect 0 -> effect 1 -> ... -> convert -> output queue -> JavaFX thread shows the frame when due
        (a bounded queue between every pair of stages)
```

The pipeline is handled inside `VideoPlayerModel` (`startPipeline`, `stopPipeline`, `decodeLoop`, `effectLoop`, `convertLoop`). I kept the playback controls and state (clock, seek, pause, audio and callbacks to the view) on the JavaFX thread.

## Why it is correct

I've also included the reasoning behind the pipeline in the `VideoPlayerModel` class comment. The table below explains the main shared resources, what could go wrong and how I prevent it.

| Shared thing | What could go wrong | How I handle it |
|---|---|---|
| Pixels of a frame | Two threads could change the same frame, or one could read it while another is still writing to it. | A frame only has one owner at a time. Ownership is passed between stages using a `BlockingQueue`, which also provides the happens-before relationship. This means the pixels don't need locks. |
| The decoder's reused frame | The decoder could overwrite pixels while an effect is still using them. | `decodeLoop` makes a separate deep copy using `copyImage` before passing the frame on. |
| A processor's fields | Two threads could try to use the same effect at once. | Each processor has its own thread. |
| Frame order | Frames could overtake each other and be processed out of order. | Each queue is FIFO and every stage has one thread. |
| Memory | A faster stage could get too far ahead and use too much memory. | All queues have a size limit, so a producer has to wait when the next queue is full. This is back-pressure. |
| Native frame memory | `Frame` memory isn't automatically freed by the garbage collector. | I call `close()` on every exit path, including normal completion, errors, interrupts and the queue cleanup in `stopPipeline`. |
| The grabber and audio line during seek or close | Pipeline threads could still be using them while the JavaFX thread tries to seek or close them. | `stopPipeline` interrupts and joins all the threads first. The JavaFX thread only seeks or closes them once the workers have stopped. |
| Playback clock and view callbacks | A worker thread could try to update the GUI directly. | These are only accessed from the JavaFX thread. Workers report errors using `Platform.runLater`, and a generation counter ignores errors from a pipeline that has already stopped. |
| `AudioPlayer` | The decoder and JavaFX threads could access it at the same time. | `copySamples` (decoder thread) only uses `outputBuffer`, while `write`, `flush` and `close` (JavaFX thread) only use `line`. The fields are separate. |

I don't expect the pipeline to deadlock because the queues only go in one direction. Workers don't wait for the JavaFX thread because `Platform.runLater` doesn't block. The JavaFX thread only polls the output queue, and blocking calls like `put` and `take` respond to interrupts. This means `stopPipeline` can interrupt and stop the worker threads.

Processors are also created while the arguments are being parsed, on the main thread, before any of the pipeline threads are started.

## Performance and how to repeat the comparison

`mvn verify` runs `EffectCostIT`, which measures how long each effect takes to process a 1920x1080 frame. It also compares the time taken by all the effects running on one thread with the time taken by the slowest individual effect.

My results were:

```text
[cost] all 11 in one thread: 24.77 ms/frame (parts add up to 30.64 ms)
[cost] slowest effect: bleed (10.99 ms). Best possible pipeline speed-up for this stack: 2.8x
[cost] at 24 frames/s a frame must be done in 41.7 ms; the single thread keeps up
```

The 2.8x figure is an estimate of the best possible speed-up, based on the total individual effect costs divided by the slowest effect's cost. It isn't an actual measured playback speed-up.

With one of each effect at 1080p, a single thread can still keep up with 24 fps. The pipeline becomes more useful when effects are repeated or when processing larger videos.

I also compared the player against my Part 1 commit (single-threaded, using the same flags). I ran both versions on a laptop with 8 cores (4 performance and 4 efficiency cores). The player logs `Frame headroom` once per second of video. A negative value means the frame was late.

| Video | Effects | Single-threaded | This version |
|---|---|---|---|
| 368x368, 30 fps | `-bbbbbbbbbbmmmmvvvv` (18) | 4 of 23 samples late | 0 of 14 late, about 150 ms ahead |
| 3840x2160, 25 fps | `-bbbbbbbbbbmmmmvvvv` (18) | about 1.5 frames/s | about 7.6 frames/s (about 5.2x faster) |
| 3840x2160, 25 fps | none | not run | 0 of 31 late, about 175 ms ahead |

At 4K with 18 effects, neither version can keep up with real-time playback. The pipeline is still about 5.2 times faster, but it also falls behind. The single-threaded version needs about 684 ms per 4K frame (17.1 seconds of real time for each second of video), compared with a 40 ms frame budget. The 8 cores (4 of which are slower efficiency cores) help spread the work out, but there is still a limit to how much can run at once.

With no effects, the pipeline keeps up with 4K playback. This suggests that the effects, rather than decoding, are the main thing slowing it down in this test.

To repeat the comparison, I used a separate worktree for the original Part 1 version. This test is manual rather than part of `mvn verify` because it needs the JavaFX window and a video file.

```bash
git worktree add ../baseline 1afec91        # the single-threaded Part 1 commit
cd ../baseline && mvn -q javafx:run '-Djavafx.args=-bbbbbbbbbbmmmmvvvv /path/to/video.mp4'
# Then run the same command in this project and compare the "Frame headroom" lines.
```

## Tests

- `CommandLineControllerTest`: Checks effect order, repeats, grouped and long flags, invalid options and help text.
- `ProcessorBehaviourTest`: Checks that each effect gives the same result for a frame regardless of what it processed before. This is important because the effects now run on separate threads. It also checks that effects stay within the frame and that changing their order changes the result.
- `EffectCostIT`: Measures the processing time of the effects, as described above.

I haven't automated testing of the whole player because it requires a JavaFX window and an actual video file.

## Limits and trade-offs

- There is one thread per effect, plus the decoder and conversion threads. Having more effects than available cores won't keep increasing performance, and the pipeline is still limited by its slowest stage.
- The queues add about three frames of latency before playback starts, but help keep the playback smooth.
- The player doesn't drop frames. If it can't keep up, it falls further behind instead of skipping frames. The original player behaved the same way.
- I kept the pipeline inside `VideoPlayerModel`. Moving it into its own class would make it easier to test without needing JavaFX.

## Changes and attribution

I changed `CommandLineController` for the Part 1 command-line flags, along with `VideoPlayerApp`, `VideoPlayerController` and `VideoPlayerModel`. In `VideoPlayerModel`, I removed the original single-threaded frame loop and replaced it with the pipeline. I also made comment-only changes to `AudioPlayer` and updated `pom.xml` to include JUnit, Surefire and Failsafe.

I added the test classes listed above. Each file's header explains what I changed.

I used Claude (Anthropic) to help draft parts of the code. I reviewed the implementation, made changes to suit the assignment requirements, tested it and made sure I understood how it works. I can explain the code and the decisions I made.
