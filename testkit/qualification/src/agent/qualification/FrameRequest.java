package agent.qualification;

import java.util.Arrays;

/** Pure request validation for one display-bound screenshot. */
public final class FrameRequest {
    public final ProbeRequest probe;
    public final String nonce;

    private FrameRequest(ProbeRequest probe, String nonce) {
        this.probe = probe;
        this.nonce = nonce;
    }

    public static FrameRequest parse(String[] args) {
        if (args == null || args.length != 6) {
            throw new IllegalArgumentException("exactly six frame arguments are required");
        }
        String[] inspection = Arrays.copyOf(args, 6);
        inspection[5] = "inspect";
        ProbeRequest probe = ProbeRequest.parse(inspection);
        if (args[5] == null || !args[5].matches("[0-9a-f]{32}")) {
            throw new IllegalArgumentException("frame nonce must be exactly 32 lowercase hexadecimal characters");
        }
        return new FrameRequest(probe, args[5]);
    }
}
