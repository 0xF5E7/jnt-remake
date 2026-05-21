package war.jnt.dash;

import lombok.Getter;

@Getter
public enum Origin {

    CORE     ("jnt::core",             "core",    "97m"),   // bright white
    DASH     ("jnt::dash",             "dash",    "35m"),   // magenta
    EXHAUST  ("jnt::exhaust",          "exhaust", "94m"),   // bright blue
    INTAKE   ("jnt::intake",           "intake",  "92m"),   // bright green
    FUSEBOX  ("jnt::fusebox",          "fusebox", "95m"),   // bright magenta
    TIMING   ("jnt::timing",           "timing",  "32m"),   // green
    STACKMAN ("jnt::stack_management", "stack",   "34m"),   // blue
    ARGS     ("jnt::args",             "args",    "33m"),   // yellow
    METAPHOR ("jnt::metaphor",         "metaphor","96m"),   // bright cyan
    WORKER   ("jnt::worker",           "worker",  "36m");   // cyan

    /** Full origin string used in legacy contexts (unchanged). */
    private final String origin;

    /** Short display name, max 8 chars, used in formatted log lines. */
    private final String shortName;

    /** Raw ANSI suffix after ESC[ — e.g. "96m" → \e[96m */
    private final String ansiColor;

    Origin(String origin, String shortName, String ansiColor) {
        this.origin    = origin;
        this.shortName = shortName;
        this.ansiColor = ansiColor;
    }
}
