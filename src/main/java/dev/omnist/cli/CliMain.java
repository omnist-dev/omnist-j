package dev.omnist.cli;

/**
 * Process entry point. Kept as a separate, trivially-thin class so it can be
 * excluded from the coverage gate wholesale (JaCoCo excludes at class
 * granularity, not line granularity) without hiding any real logic --
 * {@link Cli#run} carries all testable behavior and is fully covered.
 */
public final class CliMain {
    private CliMain() {}

    public static void main(String[] args) {
        int code = Cli.run(args, System.out, System.err, System.in);
        System.exit(code);
    }
}
