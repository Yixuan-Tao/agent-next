package agent.qualification;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/** Pure request validation for one exact text replacement. */
public final class InputRequest {
    private static final int MAX_ENCODED_LENGTH = 4096;
    private static final int MAX_TEXT_CODE_POINTS = 256;
    public final ProbeRequest probe;
    public final int windowId;
    public final String viewId;
    public final String text;

    private InputRequest(ProbeRequest probe, int windowId, String viewId, String text) {
        this.probe = probe;
        this.windowId = windowId;
        this.viewId = viewId;
        this.text = text;
    }

    public static InputRequest parse(String[] args) {
        if (args == null || args.length != 8) {
            throw new IllegalArgumentException("exactly eight input arguments are required");
        }
        String[] inspection = Arrays.copyOf(args, 6);
        inspection[5] = "inspect";
        ProbeRequest probe = ProbeRequest.parse(inspection);
        if (args[5] == null || !args[5].matches("[0-9]+")) {
            throw new IllegalArgumentException("window ID must be unsigned ASCII decimal digits");
        }
        int windowId;
        try {
            windowId = Integer.parseInt(args[5]);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("window ID exceeds the non-negative integer range", invalid);
        }
        if (args[6] == null || !args[6].matches(
                "[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)*:id/[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("view ID must be an Android namespace:id/name resource ID");
        }
        if (args[7] == null || args[7].length() > MAX_ENCODED_LENGTH) {
            throw new IllegalArgumentException("base64 text must be present and at most 4096 characters");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(args[7]);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("text must use canonical standard base64", invalid);
        }
        if (!Base64.getEncoder().encodeToString(decoded).equals(args[7])) {
            throw new IllegalArgumentException("text must use canonical standard base64");
        }
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(decoded)).toString();
        } catch (CharacterCodingException invalid) {
            throw new IllegalArgumentException("decoded text must be well-formed UTF-8", invalid);
        }
        if (text.indexOf('\u0000') >= 0) {
            throw new IllegalArgumentException("text must not contain NUL");
        }
        if (text.codePointCount(0, text.length()) > MAX_TEXT_CODE_POINTS) {
            throw new IllegalArgumentException("text must contain at most 256 Unicode code points");
        }
        return new InputRequest(probe, windowId, args[6], text);
    }
}
