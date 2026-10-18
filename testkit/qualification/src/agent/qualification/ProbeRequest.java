package agent.qualification;

import agent.qualification.SessionPolicy.Mode;

/** Pure validation of the shell diagnostic request; no Android classes are referenced. */
public final class ProbeRequest {
    public final Mode mode;
    public final int displayId;
    public final String uniqueId;
    public final String packageName;
    public final boolean foregroundSelected;
    public final String command;

    private ProbeRequest(Mode mode, int displayId, String uniqueId, String packageName,
            boolean foregroundSelected, String command) {
        this.mode = mode;
        this.displayId = displayId;
        this.uniqueId = uniqueId;
        this.packageName = packageName;
        this.foregroundSelected = foregroundSelected;
        this.command = command;
    }

    public static ProbeRequest parse(String[] args) {
        if (args == null || args.length != 6) {
            throw new IllegalArgumentException("exactly six arguments are required");
        }
        for (String argument : args) {
            if (argument == null) {
                throw new IllegalArgumentException("arguments must not be null");
            }
        }
        Mode mode;
        if ("foreground".equals(args[0])) {
            mode = Mode.FOREGROUND;
        } else if ("background".equals(args[0])) {
            mode = Mode.BACKGROUND;
        } else {
            throw new IllegalArgumentException("mode must be foreground or background");
        }
        if (!args[1].matches("[0-9]+")) {
            throw new IllegalArgumentException("display ID must be unsigned ASCII decimal digits");
        }
        int displayId;
        try {
            displayId = Integer.parseInt(args[1]);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("display ID exceeds the non-negative integer range", invalid);
        }
        if (isBlank(args[2])) {
            throw new IllegalArgumentException("display unique ID must not be blank");
        }
        if (!args[3].matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")) {
            throw new IllegalArgumentException("package must contain valid dotted Android package segments");
        }
        boolean foregroundSelected;
        if ("foreground-selected=yes".equals(args[4])) {
            foregroundSelected = true;
        } else if ("foreground-selected=no".equals(args[4])) {
            foregroundSelected = false;
        } else {
            throw new IllegalArgumentException("foreground-selected must be exactly yes or no");
        }
        if (!"inspect".equals(args[5])) {
            throw new IllegalArgumentException("only the inspect command is supported");
        }
        if (mode == Mode.BACKGROUND && displayId == 0) {
            throw new IllegalArgumentException("background requires a non-main display");
        }
        if (mode == Mode.FOREGROUND && (displayId != 0 || !foregroundSelected)) {
            throw new IllegalArgumentException("foreground requires explicit selection of display zero");
        }
        return new ProbeRequest(mode, displayId, args[2], args[3], foregroundSelected, args[5]);
    }

    private static boolean isBlank(String value) {
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) {
                return false;
            }
            offset += Character.charCount(codePoint);
        }
        return true;
    }
}
