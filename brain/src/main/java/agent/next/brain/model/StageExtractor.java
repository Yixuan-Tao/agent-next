package agent.next.brain.model;

import java.io.IOException;

/** Extracts stage data without controlling the device. */
public interface StageExtractor {

    StageData extract(StageExtractRequest request) throws IOException;
}
