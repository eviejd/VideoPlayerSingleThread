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
 * Modification note: video decoding now runs through a multi-threaded pipeline, with a decoder thread, 
 *  a thread for each frame processor and a convert thread connected by bounded blocking queues. 
 *  The JavaFX thread only handles playback timing and displaying frames.
 *  
 *  AI assistance: I used Claude (Anthropic) as a development aid for guidance and problem-solving. 
 *  I substantially modified, refined and integrated the suggested code into my own implementation, 
 *  tested the final result and made sure I understood how everything worked.
 *  
   * */
package comp3011;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.JavaFXFrameConverter;

import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.scene.image.Image;

/**
 * The model part of the video player model-view-controller architecture.
 *
 * <p>
 * Owns the FFmpeg decoder, playback clock, seeking logic, audio scheduling,
 * and frame processing - which it all manages through cooperative multi-tasking
 * on a single thread. It publishes decoded frames and playback state through
 * callbacks supplied by its owning {@link VideoPlayerController}, keeping it
 * decoupled from the JavaFX view.
 * </p>
 */
public class VideoPlayerModel {
    private static final long NO_SEEK_REQUEST = -1;
    private static final long FIVE_SECONDS_US = 5_000_000L;
    private static final long AUDIO_LEAD_NS = 30_000_000L;
    private static final int STAGE_QUEUE_CAPACITY = 2; // Frames waiting between two pipeline stages
    private static final int OUTPUT_QUEUE_CAPACITY = 4; // Finished frames waiting for display (back-pressure)
    private static final int PREROLL_FRAMES = 3; // Frames to have buffered before the playback clock starts
    private static final FrameJob END_OF_STREAM_JOB = new FrameJob(null, null);
    private static final PreparedFrame END_OF_STREAM = new PreparedFrame(null, -1, 0, 0);

    private final List<FrameProcessor> frameProcessors;
    private final Queue<PendingAudio> pendingAudio = new ConcurrentLinkedQueue<>();
    private final BlockingQueue<PreparedFrame> outputQueue = new ArrayBlockingQueue<>(OUTPUT_QUEUE_CAPACITY);
    private final BiConsumer<Integer, Integer> videoSizeChangedHandler;
    private final Consumer<Image> frameReadyHandler;
    private final Consumer<String> statusChangedHandler;
    private final BiConsumer<Boolean, Boolean> playbackStateChangedHandler;
    private final Consumer<Boolean> audioOutputStateChangedHandler;

    // JavaFX calls this on its heart beat, on the JavaFX thread. It no longer does heavy work: it only moves audio
    // along and displays the next finished frame if it is due
    private final AnimationTimer playbackTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            pumpPlayback(now);
        }
    };

    private File videoFile; // Path to the media that comes from the command line
    private FFmpegFrameGrabber grabber; // This is the 3rd party video media decoder. It deals in JavaCV Frame objects.
    private JavaFXFrameConverter converter; // Takes Frame objects to JavaFX Image objects, which can be put on screen.
    private AudioPlayer audioPlayer; // Our audio output. The JavaFX thread owns the line, the decoder only calls copySamples.
    private final List<Thread> pipelineThreads = new ArrayList<>(); // Only touched from the JavaFX thread
    private final List<BlockingQueue<FrameJob>> stageQueues = new ArrayList<>(); // JavaFX thread only (see stopPipeline)
    private int pipelineGeneration; // Bumped on every start/stop so late errors from old threads can be ignored
    private volatile boolean endOfStreamQueued; // Set by convert thread once END_OF_STREAM is on the output queue  
    private boolean audioAvailable; // Set before the decoder starts, so the decoder can read it safely
    private boolean displayOneFrameWhilePaused; // Lets a seek/start show one frame even while paused
    private boolean playbackOpen; // True when playback is happening
    private boolean pauseRequested;
    private volatile boolean audioOutputEnabled; // Written by JavaFX thread, read by decoder thread
    private boolean frameProcessorsInitialised;
    private long currentTimestampUs;
    private long videoDurationUs = NO_SEEK_REQUEST;
    private int videoFrameDurationUs;
    private double frameRate;
    private int intFrameRate;
    private int totalVideoFrames;
    private long firstTimestampUs = NO_SEEK_REQUEST;
    private long logicalPlaybackBaseUs;
    private long playbackStartNs;
    private long pauseStartedNs;
    private long relativeSeekBaseUs = NO_SEEK_REQUEST;

    public VideoPlayerModel(
            boolean audioEnabled,
            List<FrameProcessor> frameProcessors,
            BiConsumer<Integer, Integer> videoSizeChangedHandler,
            Consumer<Image> frameReadyHandler,
            Consumer<String> statusChangedHandler,
            BiConsumer<Boolean, Boolean> playbackStateChangedHandler,
            Consumer<Boolean> audioOutputStateChangedHandler) {
        audioOutputEnabled = audioEnabled;
        this.videoSizeChangedHandler = videoSizeChangedHandler;
        this.frameReadyHandler = frameReadyHandler;
        this.statusChangedHandler = statusChangedHandler;
        this.playbackStateChangedHandler = playbackStateChangedHandler;
        this.audioOutputStateChangedHandler = audioOutputStateChangedHandler;
        //effects now come from CL instead of being hard coded
        this.frameProcessors = new ArrayList<>(frameProcessors);
    }

    public void play(File file) {
        videoFile = file;
        videoDurationUs = NO_SEEK_REQUEST;
        videoFrameDurationUs = 0;
        startPlayback(0, false, NO_SEEK_REQUEST);
    }

    public void startOver() {
        if (videoFile == null) {
            return;
        }

        seekTo(0);
    }

    public void backFiveSeconds() {
        seekRelative(-FIVE_SECONDS_US);
    }

    public void forwardFiveSeconds() {
        seekRelative(FIVE_SECONDS_US);
    }

    public void togglePause() {
        if (!playbackOpen) {
            if (videoFile != null) {
                startPlayback(displayableSeekTimestamp(currentTimestampUs), false, currentTimestampUs);
            }
            return;
        }

        pauseRequested = !pauseRequested;
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        if (pauseRequested) {
            pauseStartedNs = System.nanoTime();
            flushAudioOutput();
        } else {
            resumePlaybackClock(System.nanoTime());
        }

        notifyPlaybackStateChanged();
    }

    public void toggleAudioOutput() {
        audioOutputEnabled = !audioOutputEnabled;
        pendingAudio.clear();
        flushAudioOutput();
        notifyAudioOutputStateChanged();
    }

    public boolean isAudioOutputEnabled() {
        return audioOutputEnabled;
    }

    public void stopPlayback() {
        closePlaybackResources();
        currentTimestampUs = 0;
        pauseRequested = false;
        notifyStatusChanged("Stopped");
        notifyFrameReady(null);
        notifyPlaybackStateChanged();
    }

    public void shutdown() {
        closePlaybackResources();
    }

    private void seekRelative(long offsetUs) {
        if (videoFile == null) {
            return;
        }

        long baseTimestampUs = relativeSeekBaseUs != NO_SEEK_REQUEST
                ? relativeSeekBaseUs
                : currentTimestampUs;
        seekTo(baseTimestampUs + offsetUs);
    }

    private void seekTo(long timestampUs) {
        long logicalTimestampUs = clampSeekTimestamp(timestampUs);
        long grabTimestampUs = displayableSeekTimestamp(logicalTimestampUs);

        if (!playbackOpen) {
            startPlayback(grabTimestampUs, pauseRequested, logicalTimestampUs);
            return;
        }

        try {
            stopPipeline();
            grabber.setTimestamp(grabTimestampUs);
            resetPlaybackClock(logicalTimestampUs);
            flushAudioOutput();
            startPipeline();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void startPlayback(long startTimestampUs, boolean initiallyPaused, long initialRelativeSeekBaseUs) {
        closePlaybackResources();

        pauseRequested = initiallyPaused;
        currentTimestampUs = initialRelativeSeekBaseUs != NO_SEEK_REQUEST
                ? initialRelativeSeekBaseUs
                : startTimestampUs;
        relativeSeekBaseUs = initialRelativeSeekBaseUs;

        notifyStatusChanged(videoFile.getName());

        try {
            openPlaybackResources(startTimestampUs);
            resetPlaybackClock(currentTimestampUs);
            playbackOpen = true;
            playbackTimer.start();
            startPipeline();
            notifyPlaybackStateChanged();
        } catch (Exception e) {
            handlePlaybackError(e);
        }
    }

    private void openPlaybackResources(long startTimestampUs) throws Exception {
        converter = new JavaFXFrameConverter();
        grabber = new FFmpegFrameGrabber(videoFile);
        grabber.setImageMode(FrameGrabber.ImageMode.COLOR);
        grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
        grabber.start();

        notifyVideoSizeChanged(grabber.getImageWidth(), grabber.getImageHeight());

        frameRate = grabber.getFrameRate();
        intFrameRate = (int) Math.round(frameRate);
        videoFrameDurationUs = frameRate > 0
                ? (int) Math.round(1_000_000.0 / frameRate)
                : 0;
        totalVideoFrames = grabber.getLengthInVideoFrames();

        long durationUs = grabber.getLengthInTime();
        videoDurationUs = durationUs > 0
                ? durationUs
                : NO_SEEK_REQUEST;

        audioAvailable = grabber.hasAudio();
        audioPlayer = new AudioPlayer();
        if (audioAvailable) {
            audioPlayer.open(grabber.getSampleRate(), grabber.getAudioChannels());
        }

        if (startTimestampUs > 0) {
            grabber.setTimestamp(startTimestampUs);
        }
    }

    private void resetPlaybackClock(long logicalTimestampUs) {
        pendingAudio.clear();
        outputQueue.clear();
        displayOneFrameWhilePaused = true;
        currentTimestampUs = logicalTimestampUs;
        relativeSeekBaseUs = logicalTimestampUs;
        firstTimestampUs = NO_SEEK_REQUEST;
        logicalPlaybackBaseUs = logicalTimestampUs;
        playbackStartNs = 0;
        pauseStartedNs = pauseRequested ? System.nanoTime() : 0;
    }

    private void resumePlaybackClock(long now) {
        if (pauseStartedNs > 0 && playbackStartNs > 0) {
            playbackStartNs += now - pauseStartedNs;
        }
        pauseStartedNs = 0;
    }



    // start the pipeline and create the queues
    private void startPipeline() {
        endOfStreamQueued = false;
        outputQueue.clear();
        final int generation = ++pipelineGeneration;

        int stageCount = frameProcessors.size();
        stageQueues.clear();
        for (int i = 0; i <= stageCount; i++) {
            stageQueues.add(new ArrayBlockingQueue<>(STAGE_QUEUE_CAPACITY));
        }

        // get the queues now so the worker threads don't need to access the list
        BlockingQueue<FrameJob> firstQueue = stageQueues.get(0);
        BlockingQueue<FrameJob> lastQueue = stageQueues.get(stageCount);
        launch("video-decoder", generation, () -> decodeLoop(firstQueue));

        for (int i = 0; i < stageCount; i++) {
            FrameProcessor processor = frameProcessors.get(i);
            BlockingQueue<FrameJob> in = stageQueues.get(i);
            BlockingQueue<FrameJob> out = stageQueues.get(i + 1);
            String name = "effect-" + i + "-" + processor.getClass().getSimpleName();
            launch(name, generation, () -> effectLoop(processor, in, out));
        }

        launch("video-convert", generation, () -> convertLoop(lastQueue));
    }

    private void launch(String name, int generation, StageBody body) {
        Thread thread = new Thread(() -> runStage(generation, body), name);
        thread.setDaemon(true);
        pipelineThreads.add(thread);
        thread.start();
    }

    // stop all pipeline threads before accessing or closing their resources
    private void stopPipeline() {
        pipelineGeneration++;
        for (Thread thread : pipelineThreads) {
            thread.interrupt();
        }
        for (Thread thread : pipelineThreads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        pipelineThreads.clear();

        // free any frames left in the queues
        for (BlockingQueue<FrameJob> queue : stageQueues) {
            FrameJob stranded;
            while ((stranded = queue.poll()) != null) {
                closeJob(stranded);
            }
        }
        stageQueues.clear();
        outputQueue.clear();
    }

    // free the frame's native memory
    private static void closeJob(FrameJob job) {
        if (job != null && job.frame() != null) {
            job.frame().close();
        }
    }

    // close the frame if it can't be added to the queue
    private static void putJob(BlockingQueue<FrameJob> queue, FrameJob job) throws InterruptedException {
        try {
            queue.put(job);
        } catch (InterruptedException e) {
            closeJob(job);
            throw e;
        }
    }

    @FunctionalInterface
    private interface StageBody {
        void run() throws Exception;
    }

    // handle errors from pipeline threads on the javafx thread
    private void runStage(int generation, StageBody body) {
        try {
            body.run();
        } catch (InterruptedException e) {
            // expected when the pipeline is stopped
        } catch (Exception e) {
            Platform.runLater(() -> {
                if (generation == pipelineGeneration && playbackOpen) {
                    handlePlaybackError(e);
                }
            });
        }
    }

    // decode frames, handle audio and pass a copy to the next stage
    private void decodeLoop(BlockingQueue<FrameJob> out) throws Exception {
        while (!Thread.currentThread().isInterrupted()) {
            Frame frame = grabFrame();
            if (frame == null) {
                out.put(END_OF_STREAM_JOB);
                return;
            }

            long timestampUs = grabber.getTimestamp();
            if (audioAvailable && frame.samples != null) {
                queueAudio(timestampUs, frame);
            }

            if (frame.image == null) {
                continue;
            }

            if (!frameProcessorsInitialised) {
                // initialise processors before passing the first frame along
                initialiseFrameProcessors(new InfoVideo(
                        mediaName(videoFile),
                        totalVideoFrames,
                        frame.imageWidth,
                        frame.imageHeight,
                        frame.imageDepth,
                        frame.imageChannels,
                        frame.imageStride,
                        frameRate,
                        intFrameRate,
                        videoFrameDurationUs,
                        grabber.getPixelFormat()));
                frameProcessorsInitialised = true;
            }

            int frameNumber = grabber.getFrameNumber();
            InfoFrame info = new InfoFrame(frameNumber, timestampUs);

            // copy the frame because the grabber reuses its image buffer
            putJob(out, new FrameJob(copyImage(frame), info));
        }
    }

    // run each frame through its processor
    private void effectLoop(FrameProcessor processor, BlockingQueue<FrameJob> in, BlockingQueue<FrameJob> out)
            throws Exception {
        while (true) {
            FrameJob job = in.take();
            if (job == END_OF_STREAM_JOB) {
                out.put(job);
                return;
            }

            // make sure the frame is freed if processing fails
            boolean passedOn = false;
            try {
                processor.process(job.frame(), job.info());
                putJob(out, job);
                passedOn = true;
            } finally {
                if (!passedOn) {
                    closeJob(job);
                }
            }
        }
    }

    // convert the processed frames into javafx images
    private void convertLoop(BlockingQueue<FrameJob> in) throws Exception {
        while (true) {
            FrameJob job = in.take();
            if (job == END_OF_STREAM_JOB) {
                endOfStreamQueued = true;
                outputQueue.put(END_OF_STREAM);
                return;
            }

            Image image;
            try {
                image = converter.convert(job.frame());
            } finally {
                job.frame().close();
            }
            outputQueue.put(new PreparedFrame(
                    image,
                    job.info().frameNumber(),
                    job.info().timestampUs(),
                    System.nanoTime()));
        }
    }

    // copy only the image data, not the audio samples
    private Frame copyImage(Frame source) throws Exception {
        if (!(source.image[0] instanceof ByteBuffer sourcePixels)) {
            throw new Exception("Only byte-backed image frames are supported");
        }

        Frame copy = new Frame(
                source.imageWidth,
                source.imageHeight,
                source.imageDepth,
                source.imageChannels,
                source.imageStride);

        // use duplicate buffers so the original buffer positions aren't changed
        ByteBuffer from = sourcePixels.duplicate();
        ByteBuffer to = ((ByteBuffer) copy.image[0]).duplicate();
        from.clear();
        to.clear();
        int byteCount = Math.min(from.remaining(), to.remaining());
        from.limit(byteCount);
        to.limit(byteCount);
        to.put(from);
        return copy;
    }


    // update playback regularly on the javafx thread
    private void pumpPlayback(long now) {
        if (!playbackOpen) {
            return;
        }

        writeDueAudio(now);

        // check the next frame without removing it from the queue
        PreparedFrame next = outputQueue.peek();
        if (next == null) {
            return;
        }

        if (next == END_OF_STREAM) {
            if (!pauseRequested) {
                outputQueue.poll();
                finishPlayback();
            }
            return;
        }

        if (firstTimestampUs == NO_SEEK_REQUEST) {
            // wait for a few frames before starting playback
            boolean readyToStart = pauseRequested
                    || endOfStreamQueued
                    || outputQueue.size() >= PREROLL_FRAMES;
            if (!readyToStart) {
                return;
            }
            firstTimestampUs = next.mediaTimestampUs();
            playbackStartNs = System.nanoTime();
            if (pauseRequested) {
                pauseStartedNs = playbackStartNs;
            }
        }

        if (pauseRequested && !displayOneFrameWhilePaused) {
            return;
        }

        long relativeTimestampUs = Math.max(0, next.mediaTimestampUs() - firstTimestampUs);
        long targetTimeNs = playbackStartNs + relativeTimestampUs * 1_000L;
        if (targetTimeNs > now) {
            return;
        }

        outputQueue.poll();
        displayOneFrameWhilePaused = false;
        displayFrame(next, relativeTimestampUs, targetTimeNs);
    }

    private void displayFrame(PreparedFrame frame, long relativeTimestampUs, long targetTimeNs) {
        currentTimestampUs = logicalPlaybackBaseUs + relativeTimestampUs;
        relativeSeekBaseUs = NO_SEEK_REQUEST;

        // log frame timing once per second
        if (intFrameRate > 0 && frame.frameNumber() % intFrameRate == 0) {
            long remainingUs = (targetTimeNs - frame.preparedAtNs()) / 1_000L;
            if (remainingUs < 0) {
                System.out.printf("Frame headroom is \u001B[31m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            } else {
                System.out.printf("Frame headroom is \u001B[32m%5dus\u001B[0m out of %dus spare.%n",
                        remainingUs,
                        videoFrameDurationUs);
            }
        }

        notifyFrameReady(frame.image());
    }


    private void queueAudio(long timestampUs, Frame frame) {
        if (!audioOutputEnabled || audioPlayer == null) {
            return;
        }

        byte[] samples = audioPlayer.copySamples(frame);
        if (samples.length > 0) {
            pendingAudio.add(new PendingAudio(timestampUs, samples));
        }
    }

    private void writeDueAudio(long now) {
        // Handle some obvious early exits
        if (!audioOutputEnabled || audioPlayer == null) {
            pendingAudio.clear();
            return;
        }
        if (pauseRequested || firstTimestampUs == NO_SEEK_REQUEST || playbackStartNs <= 0) {
            return;
        }

        // Here is the real time dependent logic
        long dueTimestampUs = firstTimestampUs + (now + AUDIO_LEAD_NS - playbackStartNs) / 1_000L;
        while (!pendingAudio.isEmpty()) {
            PendingAudio audio = pendingAudio.peek();
            if (audio.timestampUs() > dueTimestampUs) {
                return;
            }

            int written = audioPlayer.write(audio.samples(), audio.offset(), audio.remaining());
            if (written <= 0) {
                return;
            }

            audio.advance(written);
            if (!audio.finished()) {
                return;
            }
            pendingAudio.remove();
        }
    }

    private void finishPlayback() {
        long endTimestampUs = videoDurationUs != NO_SEEK_REQUEST
                ? videoDurationUs
                : currentTimestampUs;

        closePlaybackResources();
        pauseRequested = true;
        currentTimestampUs = endTimestampUs;
        relativeSeekBaseUs = endTimestampUs;
        notifyStatusChanged("Playback finished");
        notifyPlaybackStateChanged();
    }

    private void handlePlaybackError(Exception e) {
        e.printStackTrace();
        closePlaybackResources();
        pauseRequested = false;
        notifyStatusChanged("Error: " + e.getMessage());
        notifyPlaybackStateChanged();
    }

    private void closePlaybackResources() {
        playbackTimer.stop();
        playbackOpen = false;
        stopPipeline();
        pendingAudio.clear();
        firstTimestampUs = NO_SEEK_REQUEST;
        playbackStartNs = 0;
        pauseStartedNs = 0;
        audioAvailable = false;
        frameProcessorsInitialised = false;

        if (audioPlayer != null) {
            audioPlayer.close();
            audioPlayer = null;
        }

        if (grabber != null) {
            try {
                grabber.stop();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            try {
                grabber.close();
            } catch (Exception e) {
                // The resource is being closed; there is no useful recovery action.
            }
            grabber = null;
        }

        if (converter != null) {
            converter.close();
            converter = null;
        }
    }

    private String mediaName(File file) {
        String fileName = file.getName();
        int extensionStart = fileName.lastIndexOf('.');
        if (extensionStart <= 0) {
            return fileName;
        }
        return fileName.substring(0, extensionStart);
    }

    private long clampSeekTimestamp(long timestampUs) {
        long clampedTimestampUs = Math.max(0, timestampUs);
        long durationUs = videoDurationUs;
        if (durationUs != NO_SEEK_REQUEST) {
            clampedTimestampUs = Math.min(clampedTimestampUs, durationUs);
        }
        return clampedTimestampUs;
    }

    private long displayableSeekTimestamp(long logicalTimestampUs) {
        long durationUs = videoDurationUs;
        if (durationUs == NO_SEEK_REQUEST || logicalTimestampUs < durationUs) {
            return logicalTimestampUs;
        }

        int frameDurationUs = videoFrameDurationUs;
        if (frameDurationUs <= 0) {
            return Math.max(0, durationUs - 1);
        }

        return Math.max(0, durationUs - frameDurationUs);
    }

    private void initialiseFrameProcessors(InfoVideo videoInfo) throws Exception {
        for (FrameProcessor processor : frameProcessors) {
            processor.initialise(videoInfo);
        }
    }

    private Frame grabFrame() throws Exception {
        if (audioAvailable) {
            return grabber.grab();
        }
        return grabber.grabImage();
    }

    private void flushAudioOutput() {
        if (audioPlayer != null) {
            audioPlayer.flush();
        }
    }

    private void notifyVideoSizeChanged(int width, int height) {
        videoSizeChangedHandler.accept(width, height);
    }

    private void notifyFrameReady(Image image) {
        frameReadyHandler.accept(image);
    }

    private void notifyStatusChanged(String status) {
        statusChangedHandler.accept(status);
    }

    private void notifyPlaybackStateChanged() {
        playbackStateChangedHandler.accept(playbackOpen, pauseRequested);
    }

    private void notifyAudioOutputStateChanged() {
        audioOutputStateChangedHandler.accept(audioOutputEnabled);
    }
    
    private record FrameJob(
            Frame frame,
            InfoFrame info) {
    }

    private record PreparedFrame(
            Image image,
            int frameNumber,
            long mediaTimestampUs,
            long preparedAtNs) {
    }

    private static class PendingAudio {
        private final long timestampUs;
        private final byte[] samples;
        private int offset;

        PendingAudio(long timestampUs, byte[] samples) {
            this.timestampUs = timestampUs;
            this.samples = samples;
        }

        long timestampUs() {
            return timestampUs;
        }

        byte[] samples() {
            return samples;
        }

        int offset() {
            return offset;
        }

        int remaining() {
            return samples.length - offset;
        }

        void advance(int byteCount) {
            offset += byteCount;
        }

        boolean finished() {
            return offset >= samples.length;
        }
    }
}
