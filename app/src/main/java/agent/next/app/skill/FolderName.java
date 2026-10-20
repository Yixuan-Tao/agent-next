package agent.next.app.skill;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts a Unicode folder name; returns empty instead of inventing a default. */
public final class FolderName {

    private static final Pattern PATTERN = Pattern.compile(
            "(?:名为|文件夹名[为是:：]?|文件夹\\s*[「\"']?)([\\p{L}\\p{N}_-]{1,40})");

    private FolderName() {
    }

    public static Optional<String> parse(String goal) {
        if (goal == null || goal.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = PATTERN.matcher(goal);
        if (!matcher.find()) {
            return Optional.empty();
        }
        String name = matcher.group(1);
        if (name.endsWith("的文件夹")) {
            name = name.substring(0, name.length() - "的文件夹".length());
        } else if (name.endsWith("文件夹")) {
            name = name.substring(0, name.length() - "文件夹".length());
        }
        return name.isBlank() ? Optional.empty() : Optional.of(name);
    }
}
