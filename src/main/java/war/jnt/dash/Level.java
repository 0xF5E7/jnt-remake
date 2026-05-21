package war.jnt.dash;

import lombok.Getter;

@Getter
public enum Level {

    DEBUG   ("DEBUG",   "·", "90m"),   // dim gray dot
    INFO    ("INFO",    "›", "97m"),   // bright white chevron
    MEMORY  ("MEMORY",  "◈", "94m"),   // bright blue diamond
    WARNING ("WARNING", "▲", "93m"),   // bright yellow triangle
    ERROR   ("ERROR",   "✖", "91m"),   // bright red cross
    FATAL   ("FATAL",   "✖", "1;91m"), // bold bright red cross
    NONE    ("NONE",    " ", "0m");

    private final String level;
    private final String icon;

    /** Raw ANSI suffix after ESC[ — e.g. "93m" → \e[93m  */
    private final String ansiColor;

    Level(String level, String icon, String ansiColor) {
        this.level     = level;
        this.icon      = icon;
        this.ansiColor = ansiColor;
    }
}
