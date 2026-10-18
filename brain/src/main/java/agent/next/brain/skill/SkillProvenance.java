package agent.next.brain.skill;

import java.time.Instant;
import java.util.Objects;

/** Where a skill came from and which immutable artifact was validated. */
public record SkillProvenance(String source, String author, String digest, Instant createdAt) {

    public SkillProvenance {
        source = Checks.text(source, "source");
        author = Checks.text(author, "author");
        digest = Checks.text(digest, "digest");
        createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }
}
