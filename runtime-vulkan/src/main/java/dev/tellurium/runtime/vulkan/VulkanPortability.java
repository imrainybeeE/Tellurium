// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkEnumerateDeviceExtensionProperties;
import static org.lwjgl.vulkan.VK10.vkEnumerateInstanceExtensionProperties;

/**
 * Vulkan portability handling (VK_KHR_portability_enumeration / VK_KHR_portability_subset), needed
 * on layered implementations such as MoltenVK. Both steps are no-ops when the extension is not offered.
 */
public final class VulkanPortability {
    public static final String ENUMERATION_EXTENSION = "VK_KHR_portability_enumeration";
    public static final String SUBSET_EXTENSION = "VK_KHR_portability_subset";
    /** VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR. */
    public static final int INSTANCE_ENUMERATE_PORTABILITY_BIT = 0x00000001;

    private VulkanPortability() {}

    /** Names of all instance extensions the loader offers (no layer). */
    public static List<String> instanceExtensionNames(MemoryStack stack) {
        IntBuffer count = stack.ints(0);
        if (vkEnumerateInstanceExtensionProperties((ByteBuffer) null, count, null) != VK_SUCCESS || count.get(0) == 0) return List.of();
        var properties = VkExtensionProperties.malloc(count.get(0), stack);
        if (vkEnumerateInstanceExtensionProperties((ByteBuffer) null, count, properties) != VK_SUCCESS) return List.of();
        return names(properties, count.get(0));
    }

    /** Names of all device extensions of a physical device. */
    public static List<String> deviceExtensionNames(VkPhysicalDevice device, MemoryStack stack) {
        IntBuffer count = stack.ints(0);
        if (vkEnumerateDeviceExtensionProperties(device, (ByteBuffer) null, count, null) != VK_SUCCESS || count.get(0) == 0) return List.of();
        var properties = VkExtensionProperties.malloc(count.get(0), stack);
        if (vkEnumerateDeviceExtensionProperties(device, (ByteBuffer) null, count, properties) != VK_SUCCESS) return List.of();
        return names(properties, count.get(0));
    }

    /** Instance extensions to enable (null when none), allocated on {@code stack}. */
    public static PointerBuffer instanceExtensionsToEnable(MemoryStack stack) {
        return instanceExtensionsToEnable(stack, instanceExtensionNames(stack));
    }

    public static PointerBuffer instanceExtensionsToEnable(MemoryStack stack, List<String> offered) {
        if (!offered.contains(ENUMERATION_EXTENSION)) return null;
        return stack.pointers(stack.UTF8(ENUMERATION_EXTENSION));
    }

    /** Instance create flags matching {@link #instanceExtensionsToEnable}: the portability bit only when enabled. */
    public static int instanceFlags(PointerBuffer enabledExtensions) {
        return enabledExtensions == null ? 0 : INSTANCE_ENUMERATE_PORTABILITY_BIT;
    }

    /** Device extensions to enable (null when none): portability_subset when the device lists it. */
    public static PointerBuffer deviceExtensionsToEnable(VkPhysicalDevice device, MemoryStack stack) {
        if (!deviceExtensionNames(device, stack).contains(SUBSET_EXTENSION)) return null;
        return stack.pointers(stack.UTF8(SUBSET_EXTENSION));
    }

    private static List<String> names(VkExtensionProperties.Buffer properties, int count) {
        var names = new ArrayList<String>(count);
        for (int i = 0; i < count; i++) names.add(properties.get(i).extensionNameString());
        return List.copyOf(names);
    }
}
