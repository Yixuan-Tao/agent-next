package agent.next.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.Base64;
import android.util.Log;

import agent.next.device.DeviceException;
import agent.next.device.DisplaySpec;
import agent.next.device.DisplayHandle;
import agent.phone.connection.FrameEvidence;
import agent.phone.capability.BlockingDeviceApi;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Opt-in debug mirror. It never observes the target or takes input ownership. */
final class DemoFramePublisher implements AutoCloseable {
    private static final String TAG = "AgentNextMonitor";
    private final AtomicFile output;
    private final HandlerThread thread = new HandlerThread("demo-mirror");
    private final Handler handler;
    private final ImageReader reader;
    private final BlockingDeviceApi api;
    private final DisplayHandle display;
    private static long latestGeneration;
    private final long generation;
    private boolean running;
    private boolean accepting = true;
    private long sequence;
    private long capturedAt;
    private long lastEncoded;
    private Image pendingImage;
    private boolean flushScheduled;
    private byte[] png;
    private String state = "STARTING";
    private String detail = "";

    DemoFramePublisher(Context context, BlockingDeviceApi api, DisplayHandle display)
            throws DeviceException {
        this.api = api;
        this.display = display;
        File directory = new File(context.getCacheDir(), "demo-monitor");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IllegalStateException("cannot create debug monitor cache");
        }
        output = new AtomicFile(new File(directory, "latest.json"));
        synchronized (DemoFramePublisher.class) {
            generation = ++latestGeneration;
            output.delete();
        }
        ImageReader createdReader = null;
        try {
            thread.start();
            handler = new Handler(thread.getLooper());
            // One pending frame plus the two slots acquireLatestImage needs to drop older buffers.
            DisplaySpec spec = DisplaySpec.standard();
            createdReader = ImageReader.newInstance(spec.width(), spec.height(),
                    PixelFormat.RGBA_8888, 3);
            reader = createdReader;
            running = true;
            reader.setOnImageAvailableListener(this::onImage, handler);
            api.startReadOnlyMirror(display, reader.getSurface());
            handler.post(this::writeSnapshot);
        } catch (DeviceException | RuntimeException failure) {
            running = false;
            if (createdReader != null) createdReader.close();
            thread.quitSafely();
            throw failure;
        }
    }

    void update(String nextState, String nextDetail) {
        synchronized (this) {
            state = nextState;
            detail = nextDetail;
        }
        handler.post(this::writeSnapshot);
    }

    private void onImage(ImageReader source) {
        try {
            Image image;
            synchronized (this) {
                // Leave any queued final image for close's flush on this handler.
                if (!accepting) return;
                image = source.acquireLatestImage();
            }
            if (image == null) return;
            if (pendingImage != null) pendingImage.close();
            pendingImage = image;
            if (!flushScheduled) {
                flushScheduled = true;
                handler.postDelayed(this::flushFrame, Math.max(0,
                        500 - (SystemClock.elapsedRealtime() - lastEncoded)));
            }
        } catch (RuntimeException failure) {
            Log.w(TAG, "mirror frame unavailable", failure);
        }
    }

    private void flushFrame() {
        flushScheduled = false;
        Image latest = pendingImage;
        pendingImage = null;
        try (Image image = latest) {
            synchronized (this) {
                if (!running || image == null) return;
                lastEncoded = SystemClock.elapsedRealtime();
            }
            Image.Plane plane = image.getPlanes()[0];
            if (plane.getPixelStride() != 4 || plane.getRowStride() % 4 != 0) {
                throw new IllegalStateException("unsupported mirror pixel layout");
            }
            Bitmap padded = Bitmap.createBitmap(plane.getRowStride() / 4,
                    image.getHeight(), Bitmap.Config.ARGB_8888);
            Bitmap cropped = null;
            byte[] encoded;
            try {
                padded.copyPixelsFromBuffer(plane.getBuffer());
                cropped = Bitmap.createBitmap(padded, 0, 0, image.getWidth(), image.getHeight());
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                if (!cropped.compress(Bitmap.CompressFormat.PNG, 100, bytes)) {
                    throw new IllegalStateException("mirror encoding failed");
                }
                encoded = bytes.toByteArray();
                if (encoded.length > FrameEvidence.MAX_PNG_BYTES) {
                    throw new IllegalStateException("mirror PNG exceeds frame budget");
                }
            } finally {
                if (cropped != null && cropped != padded) {
                    cropped.recycle();
                }
                padded.recycle();
            }
            synchronized (this) {
                if (!running) {
                    return;
                }
                png = encoded;
                capturedAt = System.currentTimeMillis();
                sequence++;
            }
            writeSnapshot();
        } catch (RuntimeException failure) {
            Log.w(TAG, "mirror frame unavailable", failure);
        }
    }

    private synchronized void writeSnapshot() {
        synchronized (DemoFramePublisher.class) {
            if (generation != latestGeneration) return;
            FileOutputStream stream = null;
            try {
                JSONObject packet = new JSONObject();
                packet.put("sequence", sequence);
                packet.put("captured_at_ms", capturedAt);
                packet.put("published_at_ms", System.currentTimeMillis());
                packet.put("active", running);
                packet.put("state", state);
                packet.put("detail", detail);
                packet.put("png", png == null ? "" : Base64.encodeToString(png, Base64.NO_WRAP));
                stream = output.startWrite();
                stream.write(packet.toString().getBytes(StandardCharsets.UTF_8));
                output.finishWrite(stream);
            } catch (Exception failure) {
                if (stream != null) {
                    output.failWrite(stream);
                }
                Log.w(TAG, "monitor output unavailable", failure);
            }
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (!accepting) return;
            accepting = false;
        }
        handler.post(() -> {
            try {
                Image finalImage = reader.acquireLatestImage();
                if (finalImage != null) {
                    if (pendingImage != null) pendingImage.close();
                    pendingImage = finalImage;
                }
                flushFrame();
            } catch (RuntimeException failure) {
                Log.w(TAG, "final mirror frame unavailable", failure);
            }
            synchronized (this) {
                running = false;
            }
            writeSnapshot();
        });
        try {
            api.stopReadOnlyMirror(display);
        } catch (DeviceException failure) {
            Log.w(TAG, "mirror stop unavailable", failure);
        } finally {
            handler.post(() -> {
                reader.setOnImageAvailableListener(null, null);
                if (pendingImage != null) {
                    pendingImage.close();
                    pendingImage = null;
                }
                reader.close();
                writeSnapshot();
            });
            thread.quitSafely();
            try {
                thread.join(3000);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            if (thread.isAlive()) {
                synchronized (this) {
                    running = false;
                }
                writeSnapshot();
                Log.w(TAG, "mirror cleanup exceeded its deadline");
            }
        }
    }
}
