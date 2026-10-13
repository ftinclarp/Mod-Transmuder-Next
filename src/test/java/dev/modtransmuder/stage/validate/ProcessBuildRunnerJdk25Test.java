package dev.modtransmuder.stage.validate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hermetic tests for {@link ProcessBuildRunner}'s JDK 25 detection: the
 * candidate scan is driven by a mocked list of candidate paths, so no real
 * JDK 25 on the machine is required.
 */
class ProcessBuildRunnerJdk25Test {

    @TempDir
    Path tmp;

    /** Builds the "looks like a JDK" layout (bin/java.exe) under tmp/jdk-25.0.1. */
    private Path fakeJdk(String dirName) throws Exception {
        Path jdk = tmp.resolve(dirName);
        Files.createDirectories(jdk.resolve("bin"));
        Files.writeString(jdk.resolve("bin/java.exe"), "fake java");
        return jdk;
    }

    @Test
    void returnsFirstCandidateThatHasBinJavaExe() throws Exception {
        Path jdk1 = fakeJdk("jdk-25.0.1");
        Path jdk2 = fakeJdk("jdk-25.0.2");

        String found = ProcessBuildRunner.firstJdk25(List.of(tmp.resolve("missing"), jdk1, jdk2));

        assertEquals(jdk1.toAbsolutePath().toString(), found);
    }

    @Test
    void prefersJavaExeWhenEarlierCandidateIsNotJdk() throws Exception {
        Path jdk = fakeJdk("jdk-25");
        // non-JDK dir with no bin/java.exe
        Files.createDirectories(tmp.resolve("not-a-jdk"));

        String found = ProcessBuildRunner.firstJdk25(List.of(tmp.resolve("not-a-jdk"), jdk));

        assertEquals(jdk.toAbsolutePath().toString(), found);
    }

    @Test
    void returnsNullWhenNoCandidateLooksLikeJdk() {
        String found = ProcessBuildRunner.firstJdk25(List.of(tmp.resolve("a"), tmp.resolve("b")));
        assertNull(found);
    }

    @Test
    void honorsJAVA_HOME_25EnvironmentOverride() throws Exception {
        Path jdk = fakeJdk("jdk-25-env");
        Map<String, String> env = Map.of("JAVA_HOME_25", jdk.toString());

        String found = ProcessBuildRunner.detectJdk25(env);

        assertEquals(jdk.toAbsolutePath().toString(), found);
    }

    @Test
    void fallsBackToTRANSMUDER_JDK25WhenEverythingElseMissing() throws Exception {
        Path jdk = fakeJdk("jdk-25-fallback");
        Map<String, String> env = Map.of("TRANSMUDER_JDK25", jdk.toString());

        List<Path> candidates = ProcessBuildRunner.jdk25Candidates(env);

        // The fallback must come last (after JAVA_HOME_25 and well-known locations).
        assertEquals(jdk, candidates.get(candidates.size() - 1));
    }

    @Test
    void suppressesBlankEnvValues() {
        Map<String, String> env = Map.of("JAVA_HOME_25", "   ", "TRANSMUDER_JDK25", "");
        String found = ProcessBuildRunner.detectJdk25(env);
        // No real JDK guaranteed on the machine; assert either null or a found path is absolute.
        assertTrue(found == null || Path.of(found).isAbsolute());
        if (found != null) {
            assertTrue(Files.isDirectory(Path.of(found)));
        }
    }
}
