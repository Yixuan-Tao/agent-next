package agent.next.brain.model;

import java.io.IOException;

/** Independent image verifier; it never controls the device. */
public interface VisualVerifier {

    VisualVerification verify(VisualVerificationRequest request) throws IOException;
}
