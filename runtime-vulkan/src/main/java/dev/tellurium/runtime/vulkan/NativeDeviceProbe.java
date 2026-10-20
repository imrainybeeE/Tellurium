// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK11;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceDriverProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceLimits;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkCreateInstance;
import static org.lwjgl.vulkan.VK10.vkDestroyInstance;
import static org.lwjgl.vulkan.VK10.vkEnumeratePhysicalDevices;
import static org.lwjgl.vulkan.VK10.vkGetPhysicalDeviceFeatures;

/**
 * G0 device probe: lists every Vulkan physical device with the properties, features, limits and
 * extensions that decide whether the integer-carrier IEEE profile can run, and writes JSON.
 * Exit status 0 means at least one device passes the integer-profile gate; 1 means none does;
 * 2 means the instance could not be created or no device exists.
 */
public final class NativeDeviceProbe {
    static final int MIN_STORAGE_BUFFERS = 17;
    static final int MIN_PUSH_CONSTANTS = 8;
    static final int MIN_WORKGROUP_INVOCATIONS = 64;

    private NativeDeviceProbe() {}

    public static void main(String[] args) throws IOException {
        Path output = Path.of("build/reports/native-device-probe.json");
        for (String arg : args) if (arg.startsWith("--output=")) output = Path.of(arg.substring("--output=".length()));
        String json;
        int exit;
        try {
            Result result = probe();
            json = result.json;
            exit = result.devices == 0 ? 2 : result.passing > 0 ? 0 : 1;
            System.out.println(result.summary);
        } catch (Throwable failure) {
            json = "{\n  \"instanceCreated\": false,\n  \"error\": " + quote(failure.toString()) + "\n}\n";
            System.out.println("G0 FAIL: Vulkan probe failed: " + failure);
            exit = 2;
        }
        if (output.getParent() != null) Files.createDirectories(output.getParent());
        Files.writeString(output, json);
        System.out.println("report: " + output.toAbsolutePath());
        System.exit(exit);
    }

    private record Result(String json, String summary, int devices, int passing) {}

    private static Result probe() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            List<String> instanceExtensions = VulkanPortability.instanceExtensionNames(stack);
            PointerBuffer enable = VulkanPortability.instanceExtensionsToEnable(stack, instanceExtensions);
            var application = VkApplicationInfo.calloc(stack).sType$Default()
                    .pApplicationName(stack.UTF8("Tellurium device probe"))
                    .applicationVersion(VK10.VK_MAKE_VERSION(0, 1, 0))
                    .apiVersion(VK10.VK_MAKE_VERSION(1, 2, 0));
            var create = VkInstanceCreateInfo.calloc(stack).sType$Default().pApplicationInfo(application)
                    .flags(VulkanPortability.instanceFlags(enable)).ppEnabledExtensionNames(enable);
            PointerBuffer out = stack.mallocPointer(1);
            int status = vkCreateInstance(create, null, out);
            if (status != VK_SUCCESS) throw new IllegalStateException("vkCreateInstance failed with VkResult=" + status);
            VkInstance instance = new VkInstance(out.get(0), create);
            try {
                var count = stack.ints(0);
                check(vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices/count");
                PointerBuffer handles = stack.mallocPointer(Math.max(count.get(0), 1));
                if (count.get(0) > 0) check(vkEnumeratePhysicalDevices(instance, count, handles), "vkEnumeratePhysicalDevices");
                int devices = count.get(0), passing = 0;
                var json = new StringBuilder("{\n");
                json.append("  \"instanceCreated\": true,\n");
                json.append("  \"portabilityEnumerationEnabled\": ").append(enable != null).append(",\n");
                json.append("  \"instanceExtensions\": ").append(array(instanceExtensions)).append(",\n");
                json.append("  \"devices\": [");
                var summary = new StringBuilder();
                for (int i = 0; i < devices; i++) {
                    VkPhysicalDevice physical = new VkPhysicalDevice(handles.get(i), instance);
                    var driver = VkPhysicalDeviceDriverProperties.calloc(stack).sType$Default();
                    var props = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(driver.address());
                    VK11.vkGetPhysicalDeviceProperties2(physical, props);
                    var features = VkPhysicalDeviceFeatures.calloc(stack);
                    vkGetPhysicalDeviceFeatures(physical, features);
                    var properties = props.properties();
                    VkPhysicalDeviceLimits limits = properties.limits();
                    List<String> extensions = VulkanPortability.deviceExtensionNames(physical, stack);
                    int storage = limits.maxPerStageDescriptorStorageBuffers();
                    int push = limits.maxPushConstantsSize();
                    int invocations = limits.maxComputeWorkGroupInvocations();
                    boolean pass = features.shaderInt64() && storage >= MIN_STORAGE_BUFFERS
                            && push >= MIN_PUSH_CONSTANTS && invocations >= MIN_WORKGROUP_INVOCATIONS;
                    if (pass) passing++;
                    if (i > 0) json.append(',');
                    json.append("\n    {\n");
                    json.append("      \"name\": ").append(quote(properties.deviceNameString())).append(",\n");
                    json.append("      \"type\": ").append(quote(type(properties.deviceType()))).append(",\n");
                    json.append("      \"apiVersion\": ").append(quote(version(properties.apiVersion()))).append(",\n");
                    json.append("      \"driverVersionRaw\": ").append(Integer.toUnsignedString(properties.driverVersion())).append(",\n");
                    json.append("      \"driverId\": ").append(driver.driverID()).append(",\n");
                    json.append("      \"driverName\": ").append(quote(driver.driverNameString())).append(",\n");
                    json.append("      \"driverInfo\": ").append(quote(driver.driverInfoString())).append(",\n");
                    json.append("      \"features\": {\"shaderInt64\": ").append(features.shaderInt64())
                            .append(", \"shaderFloat64\": ").append(features.shaderFloat64())
                            .append(", \"shaderInt16\": ").append(features.shaderInt16()).append("},\n");
                    json.append("      \"limits\": {\n");
                    json.append("        \"maxPerStageDescriptorStorageBuffers\": ").append(storage).append(",\n");
                    json.append("        \"maxPushConstantsSize\": ").append(push).append(",\n");
                    json.append("        \"maxComputeWorkGroupInvocations\": ").append(invocations).append(",\n");
                    json.append("        \"maxComputeWorkGroupSize\": [").append(limits.maxComputeWorkGroupSize(0)).append(", ")
                            .append(limits.maxComputeWorkGroupSize(1)).append(", ").append(limits.maxComputeWorkGroupSize(2)).append("],\n");
                    json.append("        \"maxComputeSharedMemorySize\": ").append(limits.maxComputeSharedMemorySize()).append(",\n");
                    json.append("        \"maxStorageBufferRange\": ").append(Integer.toUnsignedString(limits.maxStorageBufferRange())).append("\n");
                    json.append("      },\n");
                    json.append("      \"integerProfileGate\": ").append(pass).append(",\n");
                    json.append("      \"extensions\": ").append(array(extensions)).append("\n    }");
                    summary.append(String.format(Locale.ROOT,
                            "device %d: %s (%s) api=%s driver=%s/%s %s%n  shaderInt64=%b shaderFloat64=%b shaderInt16=%b%n"
                                    + "  maxPerStageDescriptorStorageBuffers=%d maxPushConstantsSize=%d maxComputeWorkGroupInvocations=%d%n"
                                    + "  maxComputeWorkGroupSize=%d,%d,%d maxComputeSharedMemorySize=%d maxStorageBufferRange=%s%n"
                                    + "  portability extensions: %s%n  gate: %s%n",
                            i, properties.deviceNameString(), type(properties.deviceType()), version(properties.apiVersion()),
                            Integer.toUnsignedString(properties.driverVersion()), driver.driverNameString(), driver.driverInfoString(),
                            features.shaderInt64(), features.shaderFloat64(), features.shaderInt16(),
                            storage, push, invocations,
                            limits.maxComputeWorkGroupSize(0), limits.maxComputeWorkGroupSize(1), limits.maxComputeWorkGroupSize(2),
                            limits.maxComputeSharedMemorySize(), Integer.toUnsignedString(limits.maxStorageBufferRange()),
                            extensions.stream().filter(name -> name.contains("portability")).toList(), pass ? "PASS" : "FAIL"));
                }
                json.append(devices == 0 ? "]\n" : "\n  ]\n").append("}\n");
                String verdict = devices == 0 ? "G0 FAIL: no Vulkan physical devices"
                        : passing > 0 ? "G0 PASS: " + passing + " of " + devices + " device(s) satisfy the integer profile"
                        : "G0 FAIL: no device satisfies the integer profile";
                return new Result(json.toString(), "portability enumeration enabled: " + (enable != null) + "\n" + summary + verdict, devices, passing);
            } finally {
                vkDestroyInstance(instance, null);
            }
        }
    }

    private static void check(int status, String operation) {
        if (status != VK_SUCCESS) throw new IllegalStateException(operation + " failed with VkResult=" + status);
    }

    private static String type(int type) {
        return switch (type) {
            case VK10.VK_PHYSICAL_DEVICE_TYPE_OTHER -> "OTHER";
            case VK10.VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> "INTEGRATED_GPU";
            case VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> "DISCRETE_GPU";
            case VK10.VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> "VIRTUAL_GPU";
            case VK10.VK_PHYSICAL_DEVICE_TYPE_CPU -> "CPU";
            default -> "UNKNOWN(" + type + ")";
        };
    }

    private static String version(int version) {
        return VK10.VK_VERSION_MAJOR(version) + "." + VK10.VK_VERSION_MINOR(version) + "." + VK10.VK_VERSION_PATCH(version);
    }

    private static String array(List<String> values) {
        var parts = new ArrayList<String>(values.size());
        for (String value : values) parts.add(quote(value));
        return "[" + String.join(", ", parts) + "]";
    }

    private static String quote(String value) {
        var out = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> { if (c < 0x20) out.append(String.format("\\u%04x", (int) c)); else out.append(c); }
            }
        }
        return out.append('"').toString();
    }
}
