package com.mcmanager.client.update;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UpdateCheckerTest {

    @Test
    void newerMinorVersionIsNewer() {
        assertTrue(UpdateChecker.isNewerVersion("1.1.0", "1.0.0"));
    }

    @Test
    void identicalVersionIsNotNewer() {
        assertFalse(UpdateChecker.isNewerVersion("1.0.0", "1.0.0"));
    }

    @Test
    void newerPatchVersionIsNewer() {
        assertTrue(UpdateChecker.isNewerVersion("1.0.1", "1.0.0"));
    }

    @Test
    void olderPatchVersionIsNotNewer() {
        assertFalse(UpdateChecker.isNewerVersion("1.0.0", "1.0.1"));
    }

    @Test
    void newerMajorVersionIsNewer() {
        assertTrue(UpdateChecker.isNewerVersion("2.0.0", "1.9.9"));
    }

    @Test
    void preReleaseSuffixIsIgnoredForComparison() {
        // Split on "-" and compare only the numeric core, so "1.0.0-beta" == "1.0.0"
        assertFalse(UpdateChecker.isNewerVersion("1.0.0-beta", "1.0.0"));
        assertTrue(UpdateChecker.isNewerVersion("1.0.1-beta", "1.0.0"));
    }

    @Test
    void differingSegmentCountsCompareAsZeroPadded() {
        assertTrue(UpdateChecker.isNewerVersion("1.0.1", "1.0"));
        assertFalse(UpdateChecker.isNewerVersion("1.0", "1.0.0"));
        assertFalse(UpdateChecker.isNewerVersion("1.0", "1.0.1"));
    }

    @Test
    void nullVersionsAreNeverNewer() {
        assertFalse(UpdateChecker.isNewerVersion(null, "1.0.0"));
        assertFalse(UpdateChecker.isNewerVersion("1.0.0", null));
        assertFalse(UpdateChecker.isNewerVersion(null, null));
    }

    @Test
    void nonNumericSegmentsAreTreatedAsZero() {
        // Prerelease suffix is stripped, so 1.0.0-alpha == 1.0.0 — not newer
        assertFalse(UpdateChecker.isNewerVersion("1.0.0", "1.0.0-alpha"));
        // Non-numeric segment "x" parses to 0, so 1.0.5 vs 1.x.5 compare as equal
        assertFalse(UpdateChecker.isNewerVersion("1.0.5", "1.x.5"));
        assertTrue(UpdateChecker.isNewerVersion("1.0.6", "1.x.5"));
    }
}
