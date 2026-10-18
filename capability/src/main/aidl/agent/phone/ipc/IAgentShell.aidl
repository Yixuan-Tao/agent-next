package agent.phone.ipc;

import android.os.Bundle;
import android.view.Surface;

interface IAgentShell {
    Bundle getCapabilities(int clientProtocolVersion) = 0;
    Bundle createDisplay() = 1;
    Bundle checkDisplay(String sessionToken, long generation) = 2;
    Bundle releaseDisplay(String sessionToken, long generation) = 3;
    Bundle launchTarget(String targetToken, long targetGeneration) = 4;
    Bundle observeTarget(String targetToken, long targetGeneration) = 5;
    Bundle bindTarget(String taskId, String displayToken, long displayGeneration, in String[] allowedPackages, String selectedPackage) = 6;
    Bundle captureTarget(String targetToken, long targetGeneration) = 7;
    Bundle tapTarget(String targetToken, long targetGeneration, String observationId, int x, int y) = 8;
    Bundle setTextTarget(String targetToken, long targetGeneration, String observationId, String ref, String text) = 9;
    Bundle swipeTarget(String targetToken, long targetGeneration, String observationId, int startX, int startY, int endX, int endY, int durationMs) = 10;
    Bundle backTarget(String targetToken, long targetGeneration, String observationId) = 11;
    Bundle moveTargetToDisplay(String targetToken, long targetGeneration, int displayId) = 12;
    Bundle startPreviewMirror(String displayToken, long displayGeneration, in Surface surface) = 13;
    void stopPreviewMirror(String displayToken, long displayGeneration) = 14;
    void userTap(String targetToken, long targetGeneration, int x, int y) = 15;
    void userSwipe(String targetToken, long targetGeneration, int x1, int y1, int x2, int y2, int durationMs) = 16;
    // Shizuku requires Binder transaction 16777115 (AIDL method ID + 1).
    oneway void destroy() = 16777114;
}
