package org.example.servicequestion.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LeetCodeArtifactImportServiceTest {

    @Test
    void shouldCreateSafeReadableArtifactBaseName() {
        assertEquals(
                "两数之和-LeetCode",
                LeetCodeArtifactImportService.safeArtifactBaseName("  两数之和 / LeetCode:*?  ")
        );
        assertEquals("CON-problem", LeetCodeArtifactImportService.safeArtifactBaseName("CON"));
        assertEquals("problem", LeetCodeArtifactImportService.safeArtifactBaseName("..."));
    }
}
