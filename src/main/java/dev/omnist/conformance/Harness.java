package dev.omnist.conformance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class Harness {

    private Harness() {}

    public static void main(String[] args) {
        try {
            System.out.println("Starting Conformance Harness...");

            Path repoDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
            Path fixturesPath = repoDir.resolve("vendor/omnist-spec/conformance/fixtures");
            Path testSuitePath = repoDir.resolve("vendor/omnist-spec/test-suite");

            System.out.println("Fixtures path: " + fixturesPath);
            System.out.println("Test suite path: " + testSuitePath);

            int passCount = 0;
            int failCount = 0;
            int skipCount = 0;

            // 1. Run Track 1: CLI Fixtures
            System.out.println("\n=== Track 1: CLI Fixtures ===");
            if (Files.exists(fixturesPath)) {
                int[] res1 = Track1Runner.runTrack1(fixturesPath, repoDir);
                passCount += res1[0];
                failCount += res1[1];
                skipCount += res1[2];
            } else {
                System.err.println("Fixtures directory not found!");
                failCount++;
            }

            // 2. Run Track 2: JSON Vectors
            System.out.println("\n=== Track 2: JSON Vectors ===");
            if (Files.exists(testSuitePath)) {
                int[] res2 = Track2Runner.runTrack2(testSuitePath);
                passCount += res2[0];
                failCount += res2[1];
                skipCount += res2[2];
            } else {
                System.err.println("Test suite directory not found!");
                failCount++;
            }

            // Print Summary
            System.out.println("\n=============================");
            System.out.println("Conformance Harness Summary:");
            System.out.println("  Pass: " + passCount);
            System.out.println("  Fail: " + failCount);
            System.out.println("  Skip: " + skipCount);
            System.out.println("=============================");

            if (failCount > 0) {
                System.exit(1);
            } else {
                System.exit(0);
            }

        } catch (Throwable t) {
            t.printStackTrace();
            System.exit(1);
        }
    }
}
