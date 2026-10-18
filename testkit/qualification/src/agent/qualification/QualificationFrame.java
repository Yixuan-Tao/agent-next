package agent.qualification;

import android.graphics.Bitmap;
import android.os.SystemClock;

import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import org.json.JSONArray;
import org.json.JSONObject;

/** One local PNG from an explicitly selected, identity-checked API 36 display. */
public final class QualificationFrame {
    private final FrameRequest request;
    private final QualificationProbe probe;

    private QualificationFrame(FrameRequest request) {
        this.request = request;
        probe = new QualificationProbe(request.probe);
    }

    private JSONObject capture() throws Exception {
        Path path = Paths.get("/data/local/tmp/agent-phone-frame-" + request.nonce + ".png");
        Bitmap bitmap = null;
        boolean created = false;
        boolean complete = false;
        try {
            probe.checkDisplay();
            JSONObject result;
            try (QualificationProbe.ObservationSession session =
                    new QualificationProbe.ObservationSession(request.probe.displayId)) {
                ObservationBudget budget = new ObservationBudget();
                probe.checkDisplay();
                session.clearCache();
                Set<Integer> focusedIds = probe.focusedTargetWindows(session, budget);
                if (focusedIds.isEmpty()) {
                    throw new IllegalStateException("no focused target-package window on the selected display");
                }
                probe.checkDisplay();
                long captureStarted = SystemClock.elapsedRealtimeNanos();
                // The shared session always uses the display-ID constructor; there is no default-display fallback.
                bitmap = session.automation().takeScreenshot();
                long captureEnded = SystemClock.elapsedRealtimeNanos();
                if (bitmap == null || bitmap.isRecycled() || bitmap.getWidth() <= 0 || bitmap.getHeight() <= 0) {
                    throw new IllegalStateException("selected display did not return a valid screenshot bitmap");
                }
                checkIdentity(session, budget, focusedIds);
                SeekableByteChannel channel = Files.newByteChannel(path,
                        EnumSet.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
                created = true;
                try (SeekableByteChannel outputChannel = channel;
                        OutputStream output = Channels.newOutputStream(outputChannel)) {
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                        throw new IllegalStateException("screenshot PNG encoding failed");
                    }
                }
                checkIdentity(session, budget, focusedIds);
                result = new JSONObject();
                result.put("status", "ok");
                result.put("mode", request.probe.mode == SessionPolicy.Mode.FOREGROUND ? "foreground" : "background");
                result.put("displayId", request.probe.displayId);
                result.put("uniqueId", request.probe.uniqueId);
                result.put("packageName", request.probe.packageName);
                result.put("nonce", request.nonce);
                result.put("width", bitmap.getWidth());
                result.put("height", bitmap.getHeight());
                result.put("captureStartElapsedRealtimeNanos", captureStarted);
                result.put("captureEndElapsedRealtimeNanos", captureEnded);
                List<Integer> sortedIds = new ArrayList<Integer>(focusedIds);
                Collections.sort(sortedIds);
                JSONArray focused = new JSONArray();
                for (Integer id : sortedIds) {
                    focused.put(id);
                }
                result.put("focusedWindowIds", focused);
                result.put("path", path.toString());
            }
            bitmap.recycle();
            bitmap = null;
            complete = true;
            return result;
        } finally {
            try {
                if (bitmap != null) {
                    bitmap.recycle();
                }
            } finally {
                if (!complete && created) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    private void checkIdentity(QualificationProbe.ObservationSession session, ObservationBudget budget,
            Set<Integer> expectedFocusedIds) throws ReflectiveOperationException {
        probe.checkDisplay();
        session.clearCache();
        if (!expectedFocusedIds.equals(probe.focusedTargetWindows(session, budget))) {
            throw new IllegalStateException("focused target window changed during screenshot capture");
        }
        probe.checkDisplay();
    }

    public static void main(String[] args) {
        final FrameRequest request;
        try {
            // Parse first: invalid requests must not touch Android bootstrap.
            request = FrameRequest.parse(args);
        } catch (Throwable failure) {
            QualificationCommand.reportFailure(failure);
            System.exit(1);
            return;
        }
        QualificationCommand.start(new QualificationCommand.Operation() {
            @Override
            public JSONObject run() throws Exception {
                return new QualificationFrame(request).capture();
            }
        });
    }
}
