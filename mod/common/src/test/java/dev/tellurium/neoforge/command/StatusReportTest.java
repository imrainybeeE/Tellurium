// SPDX-License-Identifier: MIT
package dev.tellurium.neoforge.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StatusReportTest {
    @Test void theGpuLineSaysHowTheGpuDoesArithmetic() {
        assertEquals("GPU: Apple M3 (mode auto, arithmetic: emulated in integers, no fp64 on this GPU)",
                StatusReport.gpuLine("Apple M3", "auto", "emulated in integers, no fp64 on this GPU"));
        assertEquals("GPU: NVIDIA GeForce RTX 5070 Ti (mode check, arithmetic: native fp64)",
                StatusReport.gpuLine("NVIDIA GeForce RTX 5070 Ti", "check", "native fp64"));
    }

    @Test void theGpuLineWithoutKnownArithmeticIsTheOldOne() {
        assertEquals("GPU: Apple M3 (mode force)", StatusReport.gpuLine("Apple M3", "force", null));
    }
}
