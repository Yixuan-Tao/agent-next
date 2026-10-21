package agent.phone.capability;

import agent.next.device.DeviceException;
import agent.next.device.DisplayHandle;
import agent.next.device.TargetHandle;
import android.view.Surface;

/** Android Surface preview/touch port; excluded from the pure JVM device-api. */
public interface AndroidPreviewController {

    int MIRROR_INTERACTIVE = 1;
    int MIRROR_PHONE_READ_ONLY = 2;
    int MIRROR_DESKTOP_READ_ONLY = 3;

    void startPreview(DisplayHandle display, Surface surface) throws DeviceException;

    void stopPreview(DisplayHandle display) throws DeviceException;

    /** Observation-only mirror; it never grants touch ownership or pauses agent input. */
    void startReadOnlyMirror(DisplayHandle display, Surface surface) throws DeviceException;

    void stopReadOnlyMirror(DisplayHandle display) throws DeviceException;

    void startPhoneReadOnlyMirror(DisplayHandle display, Surface surface) throws DeviceException;

    void stopPhoneReadOnlyMirror(DisplayHandle display) throws DeviceException;

    void userTap(TargetHandle target, int x, int y) throws DeviceException;

    void userSwipe(TargetHandle target, int startX, int startY, int endX, int endY,
            int durationMs) throws DeviceException;
}
