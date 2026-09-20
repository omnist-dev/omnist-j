package dev.omnist;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * No source file may contain a raw U+FEFF (the bytes EF BB BF). An invisible mark in source is how
 * an open-coded byte-order-mark strip goes unnoticed (found in two sibling ports), and this
 * project's rule is that the mark is written {@code (char) 0xFEFF} or as a Java unicode escape,
 * and stripped in exactly one place, {@code dev.omnist.document.Bom}.
 *
 * <p>The scan covers every text file under the repository root except build output, the generated
 * site and the vendored spec submodule (whose files are not this repository's). It is proven to
 * bite by the tests below that run the same scan over a directory the test builds itself.
 */
class NoRawByteOrderMarkTest {

    private static final Set<String> SKIPPED_DIRECTORIES = Set.of(".git", "target", "site", "vendor", "node_modules");

    private static final Set<String> TEXT_EXTENSIONS = Set.of(
        "java", "md", "xml", "yml", "yaml", "json", "txt", "properties", "html", "css", "js",
        "sh", "toml", "osd", "oml", "cfg", "gitignore", "gitmodules", "gitattributes");

    static boolean containsRawBom(byte[] bytes) {
        for (int i = 0; i + 2 < bytes.length; i++) {
            if ((bytes[i] & 0xFF) == 0xEF && (bytes[i + 1] & 0xFF) == 0xBB && (bytes[i + 2] & 0xFF) == 0xBF) {
                return true;
            }
        }
        return false;
    }

    static boolean isTextFile(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        // Extension-less files (the "omnist" launcher, "run-conformance") and dotfiles are scripts/config.
        String extension = dot < 0 ? "" : name.substring(dot + 1);
        return dot <= 0 || TEXT_EXTENSIONS.contains(extension);
    }

    static List<Path> filesWithRawBom(Path root) throws IOException {
        List<Path> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            List<Path> files = walk
                .filter(Files::isRegularFile)
                .filter(p -> {
                    for (Path part : root.relativize(p)) {
                        if (SKIPPED_DIRECTORIES.contains(part.toString())) {
                            return false;
                        }
                    }
                    return isTextFile(p);
                })
                .toList();
            for (Path file : files) {
                if (containsRawBom(Files.readAllBytes(file))) {
                    offenders.add(root.relativize(file));
                }
            }
        }
        return offenders;
    }

    @Test
    void noTrackedSourceFileContainsARawByteOrderMark() throws IOException {
        Path repoRoot = Paths.get(System.getProperty("user.dir")).toAbsolutePath();
        assertTrue(Files.exists(repoRoot.resolve("pom.xml")), "test must run from the repository root");
        List<Path> offenders = filesWithRawBom(repoRoot);
        assertTrue(offenders.isEmpty(),
            "raw U+FEFF (EF BB BF) found; write (char) 0xFEFF or a unicode escape instead: " + offenders);
    }

    // ------------------------------------------------- prove the scan bites

    @Test
    void scanRecognisesTheThreeBytes() {
        assertTrue(containsRawBom(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}));
        assertTrue(containsRawBom(new byte[]{'a', (byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'b'}));
        assertFalse(containsRawBom(new byte[]{(byte) 0xEF, (byte) 0xBB}));
        assertFalse(containsRawBom(new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBE}));
        assertFalse(containsRawBom("plain text, \\uFEFF spelled as an escape".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertFalse(containsRawBom(new byte[0]));
    }

    @Test
    void scanFindsARawMarkInAJavaFileAndInATestFile(@TempDir Path root) throws IOException {
        Files.createDirectories(root.resolve("src/main/java/x"));
        Files.createDirectories(root.resolve("src/test/java/x"));
        Path main = root.resolve("src/main/java/x/Main.java");
        Path test = root.resolve("src/test/java/x/MainTest.java");
        Files.write(main, new byte[]{'/', '/', ' ', (byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '\n'});
        Files.write(test, "class MainTest {}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path clean = root.resolve("src/main/java/x/Clean.java");
        Files.write(clean, "class Clean {}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertEquals(List.of(root.relativize(main)), filesWithRawBom(root));

        Files.write(test, new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'c', 'l', 'a', 's', 's'});
        List<Path> both = filesWithRawBom(root);
        assertEquals(2, both.size());
        assertTrue(both.contains(root.relativize(test)));
    }

    @Test
    void scanSkipsBuildOutputTheSiteAndTheVendoredSpec(@TempDir Path root) throws IOException {
        for (String skipped : new String[]{"target", "site", "vendor", ".git"}) {
            Files.createDirectories(root.resolve(skipped));
            Files.write(root.resolve(skipped).resolve("Generated.java"), new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        }
        Files.write(root.resolve("logo.png"), new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        assertEquals(List.of(), filesWithRawBom(root));
    }

    @Test
    void scanCoversExtensionlessScripts(@TempDir Path root) throws IOException {
        Path script = root.resolve("run-conformance");
        Files.write(script, new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '#', '!'});
        assertEquals(List.of(root.relativize(script)), filesWithRawBom(root));
    }
}
