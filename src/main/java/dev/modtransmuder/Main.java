package dev.modtransmuder;

import dev.modtransmuder.cli.RunCommand;
import picocli.CommandLine;

/**
 * Entry point: delegates to the {@code run} command and maps its result onto
 * the process exit code (ARCHITECTURE §6, §7).
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        int exitCode = new CommandLine(new RunCommand()).execute(args);
        System.exit(exitCode);
    }
}
