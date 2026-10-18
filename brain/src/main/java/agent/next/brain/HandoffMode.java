package agent.next.brain;

/** How the human takes over. Preview is the default; main screen is fallback (D6). */
public enum HandoffMode {
    PREVIEW_TOUCH,
    MAIN_SCREEN,
    CREDENTIAL_ENTRY
}
