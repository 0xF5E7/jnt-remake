package war.jnt.exhaust;

import com.google.common.io.MoreFiles;
import com.google.common.io.RecursiveDeleteOption;
import war.jnt.dash.Ansi;
import war.jnt.dash.Level;
import war.jnt.dash.Logger;
import war.jnt.dash.Origin;
import war.jnt.utility.timing.Timing;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static war.jnt.dash.Ansi.Color.WHITE;

public class Exhaust {

    private static final Logger logger = Logger.INSTANCE;
    private static final Timing timing = new Timing();

    private String readResource(String resourcePath) throws IOException {
        try (InputStream is = Exhaust.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (is != null) {
                return new String(is.readAllBytes());
            }
        }
        // Fallback: read from filesystem relative to working directory
        return Files.readString(Paths.get(resourcePath));
    }

    public void prepare(String path) {
        timing.begin();

        try {
            Files.createDirectories(Paths.get(path));

            Path build = Paths.get(path + "/build");
            Path classes = Paths.get(path + "/classes");

            if (classes.toFile().exists()) {
                MoreFiles.deleteDirectoryContents(classes, RecursiveDeleteOption.ALLOW_INSECURE);
            }

            if (build.toFile().exists()) {
                MoreFiles.deleteDirectoryContents(build, RecursiveDeleteOption.ALLOW_INSECURE);
            }

            Files.deleteIfExists(classes);
            Files.createDirectories(classes);

            Files.deleteIfExists(build);
            Files.createDirectories(build);

            Files.createDirectories(Paths.get(path + "/lib"));

            // Read from classpath (bundled in JAR), fall back to filesystem
            Files.write(Paths.get(path + "/lib/intrinsics.h"), readResource("intrinsics/intrinsics.h").getBytes());
            Files.write(Paths.get(path + "/lib/intrinsics.c"), readResource("intrinsics/intrinsics.c").getBytes());
            Files.write(Paths.get(path + "/lib/jni.h"),        readResource("jni/jni.h").getBytes());

            for (String helper : new String[]{"boxing.c", "boxing.h", "invokedynamic.c", "invokedynamic.h"}) {
                Files.write(Paths.get(path + "/lib/" + helper), readResource("helpers/" + helper).getBytes());
            }

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        timing.end();

        long elapsed = timing.calc();
        logger.logln(Level.INFO, Origin.EXHAUST, String.format("Prepared output directories in %s.", new Ansi().c(WHITE).s(String.format("%dms", elapsed))));
    }

    public static void write(String fileName, byte[] data, String path) {
        try {
            Files.write(Paths.get(String.format("%s/%s", path, fileName)), data);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
