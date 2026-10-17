// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class VulkanPortabilityTest {
    @Test void enablesPortabilityEnumerationOnlyWhenOffered() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var enabled = VulkanPortability.instanceExtensionsToEnable(stack,
                    List.of("VK_KHR_surface", VulkanPortability.ENUMERATION_EXTENSION));
            assertNotNull(enabled);
            assertEquals(1, enabled.remaining());
            assertEquals(VulkanPortability.ENUMERATION_EXTENSION, org.lwjgl.system.MemoryUtil.memUTF8(enabled.get(0)));
            assertEquals(VulkanPortability.INSTANCE_ENUMERATE_PORTABILITY_BIT, VulkanPortability.instanceFlags(enabled));
        }
    }

    @Test void changesNothingWhenPortabilityEnumerationIsNotOffered() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var enabled = VulkanPortability.instanceExtensionsToEnable(stack, List.of("VK_KHR_surface"));
            assertNull(enabled);
            assertEquals(0, VulkanPortability.instanceFlags(enabled));
        }
    }

    @Test void enumerationBitMatchesTheSpecification() {
        assertEquals(1, VulkanPortability.INSTANCE_ENUMERATE_PORTABILITY_BIT);
    }
}
