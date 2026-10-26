package io.quarkmind.sc2.replay;

import io.quarkmind.domain.UpgradeType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpgradeMappingGeneratorTest {

    @Test
    void upgradeMappingMatchesEnum() throws IOException {
        var expected = Arrays.stream(UpgradeType.values())
                             .map(UpgradeType::pythonName)
                             .collect(Collectors.toList());

        var outputPath = Path.of("../quarkmind-classifier/src/upgrade_mapping.py");
        assertTrue(Files.exists(outputPath),
                   "upgrade_mapping.py must exist (generated at build time by UpgradeMappingGenerator)");

        var lines = Files.readAllLines(outputPath);
        var actual = lines.stream()
                          .map(String::strip)
                          .filter(l -> l.startsWith("\"") && l.endsWith("\","))
                          .map(l -> l.substring(1, l.length() - 2))
                          .collect(Collectors.toList());

        assertEquals(expected.size(), actual.size(),
                     "Upgrade count mismatch — regenerate with mvn compile -pl quarkmind-sc2");
        assertEquals(expected, actual,
                     "Upgrade names mismatch — regenerate with mvn compile -pl quarkmind-sc2");
    }
}
