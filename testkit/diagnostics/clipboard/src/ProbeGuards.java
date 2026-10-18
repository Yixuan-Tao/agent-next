package agent.probe;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

final class ProbeGuards {
    static boolean fixtureFieldId(String id) {
        return "probe-text".equals(id);
    }

    static void display(int id, String expected, String actual, String name, int ownerUid) {
        if (id <= 0) throw new IllegalArgumentException("Physical/invalid display is forbidden");
        if (expected == null || expected.isEmpty() || !expected.equals(actual))
            throw new IllegalArgumentException("Display identity mismatch");
        if (!"scrcpy".equals(name) || ownerUid != 2000)
            throw new IllegalArgumentException("Not a shell-owned test display");
    }

    static void target(String pkg, boolean marker, int fields, boolean enabled, boolean password) {
        if (!"com.android.browser".equals(pkg) || !marker)
            throw new IllegalArgumentException("Not the browser test fixture");
        if (fields != 1 || !enabled || password)
            throw new IllegalArgumentException("Unsafe or ambiguous editable field");
    }

    static String decodeText(String value) {
        if (value == null) throw new IllegalArgumentException("Missing text payload");
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            String text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
            if (text.length() > 4096 || text.indexOf('\0') >= 0)
                throw new IllegalArgumentException("Invalid test text length or null character");
            return text;
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("Invalid UTF-8", e);
        }
    }
}
