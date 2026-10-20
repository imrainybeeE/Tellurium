// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
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
    /** How MoltenVK takes configuration per instance (and the name of the "layer" it answers to). */
    public static final String LAYER_SETTINGS_EXTENSION = "VK_EXT_layer_settings";
    public static final String MOLTENVK_LAYER = "MoltenVK";
    private static final int STRUCTURE_TYPE_LAYER_SETTINGS_CREATE_INFO = 1000496000;
    private static final int LAYER_SETTING_TYPE_INT32 = 1;

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

    /**
     * A VkLayerSettingsCreateInfoEXT (for an instance's pNext) giving MoltenVK one integer setting.  Built in
     * place because the LWJGL the older builds bundle (3.3.3) predates VK_EXT_layer_settings; the layout is the
     * specification's: the create info is {sType, pNext, settingCount, pSettings} and each setting
     * {pLayerName, pSettingName, type, valueCount, pValues}, 32 bytes each on a 64-bit platform.
     */
    public static long moltenVkSetting(MemoryStack stack, String name, int value) {
        long values = stack.nmalloc(4, 4);
        MemoryUtil.memPutInt(values, value);
        long setting = stack.ncalloc(8, 1, 32);
        MemoryUtil.memPutAddress(setting, MemoryUtil.memAddress(stack.UTF8(MOLTENVK_LAYER)));
        MemoryUtil.memPutAddress(setting + 8, MemoryUtil.memAddress(stack.UTF8(name)));
        MemoryUtil.memPutInt(setting + 16, LAYER_SETTING_TYPE_INT32);
        MemoryUtil.memPutInt(setting + 20, 1);
        MemoryUtil.memPutAddress(setting + 24, values);
        long info = stack.ncalloc(8, 1, 32);
        MemoryUtil.memPutInt(info, STRUCTURE_TYPE_LAYER_SETTINGS_CREATE_INFO);
        MemoryUtil.memPutInt(info + 16, 1);
        MemoryUtil.memPutAddress(info + 24, setting);
        return info;
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
