package war.jnt.dash;

import lombok.Setter;
import war.Entrypoint;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.locks.ReentrantLock;

@Setter
public class Logger {

    public static final Logger INSTANCE = new Logger();

    private static final StringBuilder output = new StringBuilder();
    private static final StringBuilder copy   = new StringBuilder();
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    // ── ANSI shorthands ────────────────────────────────────────────────────────
    private static final String RS  = Ansi.esc + "0m";          // reset
    private static final String DIM = Ansi.esc + "2;37m";       // dim gray   (timestamps)
    private static final String SEP = Ansi.esc + "90m" + "│" + RS; // dark-gray separator

    private Level level = Level.DEBUG;
    private ReentrantLock lock = new ReentrantLock();

    public Logger() {}

    public Logger(Level level) {
        this.level = level;
    }

    // ── Banner ─────────────────────────────────────────────────────────────────

    public void ascii() {
        if (level == Level.NONE) return;

        // Four colour stops — cyan → bright-cyan → bright-blue → magenta gradient
        String ca = Ansi.esc + "1;96m";  // bold bright-cyan
        String cb = Ansi.esc + "1;36m";  // bold cyan
        String cc = Ansi.esc + "1;94m";  // bold bright-blue
        String cd = Ansi.esc + "1;95m";  // bold bright-magenta

        System.out.println();

        if (Entrypoint.JNT_DISTRO == 2) {
            System.out.println(ca + "    /$$$$$ /$$   /$$ /$$$$$$$$"          + RS);
            System.out.println(ca + "   |__  $$| $$$ | $$|__  $$__/"          + RS);
            System.out.println(cb + "      | $$| $$$$| $$   | $$   "          + RS);
            System.out.println(cb + "      | $$| $$ $$ $$   | $$   "          + RS);
            System.out.println(cc + " /$$  | $$| $$  $$$$   | $$   "          + RS);
            System.out.println(cc + "| $$  | $$| $$\\  $$$   | $$   "          + RS);
            System.out.println(cd + "|  $$$$$$/| $$ \\  $$   | $$   "          + RS);
            System.out.println(cd + " \\______/ |__/  \\__/   |__/   "         + RS);
        } else if (Entrypoint.JNT_DISTRO == 3) {
            System.out.println(ca + "      _              __        ___   ______   ________  " + RS);
            System.out.println(ca + "     / \\            [  |  _  .'   `.|_   _ \\ |_   __  | " + RS);
            System.out.println(ca + "    / _ \\     _ .--. | | / ]/  .-.  \\ | |_) |  | |_ \\_| " + RS);
            System.out.println(ca + "   / ___ \\   [ `/'\\]| '' < | |   | | |  __'.  |  _|    " + RS);
            System.out.println(cb + " _/ /   \\ \\_  | |    | |`\\ \\\\  `-'  /_| |__) |_| |_     " + RS);
            System.out.println(cb + "|____| |____|[___]  [__|  \\_]`.___.'|_______/|_____|    " + RS);
        }

        // Subtitle
        System.out.println(DIM + "         java native transpiler  ·  v" + Entrypoint.JNT_DISTRO + RS);
        System.out.println();
    }

    // ── Logging ────────────────────────────────────────────────────────────────

    /**
     * Carriage-return (in-place) log — used for progress updates that overwrite
     * the current line. Same visual format as {@link #log} but prefixed with \r.
     */
    public synchronized void rlog(Level level, Origin origin, Object... objects) {
        if (level.ordinal() < this.level.ordinal()) return;
        lock.lock();
        try {
            String prefix = buildColorPrefix(level, origin);
            String plain  = buildPlainPrefix(level, origin);
            String msgC   = Ansi.esc + level.getAnsiColor();

            for (var obj : objects) {
                System.out.printf("\r%s%s%s%s", prefix, msgC, obj, RS);
                append(plain + obj + "\n", plain + obj + "\n");
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Inline log (no trailing newline). Pair with {@link #logln} or follow with
     * {@link System#out#println()} when the line is complete.
     */
    public synchronized void log(Level level, Origin origin, Object... objects) {
        if (level.ordinal() < this.level.ordinal()) return;
        lock.lock();
        try {
            PrintStream out  = System.out;
            String prefix    = buildColorPrefix(level, origin);
            String plain     = buildPlainPrefix(level, origin);
            String msgC      = Ansi.esc + level.getAnsiColor();

            for (var obj : objects) {
                if (obj instanceof Throwable) {
                    out.println();
                    append("\n");
                    out.printf("%s%s%s%s", prefix, msgC, obj, RS);
                    append(plain + obj, plain + String.valueOf(obj));

                    Throwable throwable = (Throwable) obj;
                    StackTraceElement[] trace = throwable.getStackTrace();
                    for (int i = 0; i < trace.length; i++) {
                        StackTraceElement ste = trace[i];
                        // First frame in bold, rest dimmed
                        String frameColor = (i == 0)
                                ? Ansi.esc + "1;" + level.getAnsiColor()
                                : Ansi.esc + "2;37m";
                        String traceLine = String.format(
                                "%n        %s  at %s.%s(%s:%d)%s",
                                frameColor,
                                ste.getClassName(), ste.getMethodName(),
                                ste.getFileName(), ste.getLineNumber(),
                                RS);
                        String plainTrace = String.format(
                                "%n        at %s.%s(%s:%d)",
                                ste.getClassName(), ste.getMethodName(),
                                ste.getFileName(), ste.getLineNumber());
                        out.print(traceLine);
                        append(traceLine, plainTrace);
                    }
                } else {
                    out.printf("%s%s%s%s", prefix, msgC, obj, RS);
                    append(prefix + msgC + obj + RS, plain + String.valueOf(obj));
                }
            }
        } finally {
            lock.unlock();
        }
    }

    /** Log with a trailing newline (most common call site). */
    public synchronized void logln(Level level, Origin origin, Object... objects) {
        if (level.ordinal() < this.level.ordinal()) return;
        lock.lock();
        try {
            log(level, origin, objects);
            System.out.println();
            append("\n");
        } finally {
            lock.unlock();
        }
    }

    // ── Prefix builders ────────────────────────────────────────────────────────

    /**
     * Builds the fully-colored prefix for a log line:
     *
     *   [dim]  HH:mm:ss.SSS  [reset] [dark-gray] │ [reset]
     *   [bold+originColor] originName  [reset]
     *   [levelColor] icon [reset]  [levelColor]
     *
     * Example (rendered):
     *   12:34:56.261  │  metaphor   ›  (then message follows in level color)
     */
    private String buildColorPrefix(Level level, Origin origin) {
        String timeStr    = LocalTime.now().format(TIME_FMT);
        String originBold = Ansi.esc + "1;" + origin.getAnsiColor();
        String levelC     = Ansi.esc + level.getAnsiColor();

        return String.format(
                "  %s%s%s  %s  %s%-8s%s  %s%s%s  ",
                DIM, timeStr, RS,          // dim timestamp
                SEP,                        // │ separator
                originBold, origin.getShortName(), RS,   // bold-colored origin, padded to 8
                levelC, level.getIcon(), RS              // colored level icon
        );
    }

    /**
     * Builds a plain (no ANSI) prefix for the log file dump:
     *
     *   HH:mm:ss.SSS  │  originName   icon  (then message follows)
     */
    private String buildPlainPrefix(Level level, Origin origin) {
        String timeStr = LocalTime.now().format(TIME_FMT);
        return String.format("  %s  │  %-8s  %s  ", timeStr, origin.getShortName(), level.getIcon());
    }

    // ── Internal append ────────────────────────────────────────────────────────

    private void append(String colored, String raw) {
        output.append(raw);
        copy.append(colored);
    }

    private void append(String both) {
        output.append(both);
        copy.append(both);
    }

    public void append(Object... objects) {
        for (var obj : objects) {
            copy.append(obj);
        }
    }

    // ── Public utilities ───────────────────────────────────────────────────────

    public String getLog() {
        String log = copy.toString();
        copy.setLength(0);
        return log;
    }

    public void clear() {
        copy.setLength(0);
    }

    public void dump() {
        dump("latest-jnt.txt");
    }

    public void dump(String path) {
        try {
            Files.write(Paths.get(path), output.toString().getBytes());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
