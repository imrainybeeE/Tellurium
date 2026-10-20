// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.metal;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.StructLayout;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The Objective-C runtime and the few Foundation and Metal entry points the Metal backend needs, through
 * java.lang.foreign.  Objects are bare {@link MemorySegment} pointers.
 *
 * <p>{@code objc_msgSend} is an ordinary (not variadic) function on arm64, so each distinct signature gets a
 * downcall handle of its own, made once.  Arguments are passed as {@code long} (NSUInteger, 8 bytes), {@code int},
 * {@code boolean} (BOOL), {@link MemorySegment} (a pointer) or {@link Size} (MTLSize, three NSUInteger, 24 bytes, passed
 * by value through a struct layout so that the ABI is the compiler's).  Ownership follows Cocoa: what comes from
 * {@code new...}, {@code alloc} or {@code MTLCreate...} is +1 and is {@link #release released} by whoever asked; factory
 * results (an NSString, a command buffer, an encoder) are autoreleased and live until the enclosing {@link Pool} closes.</p>
 */
final class ObjC {
    /** MTLSize / MTLOrigin shape: width, height, depth. */
    record Size(long width, long height, long depth) {}

    static final StructLayout MTL_SIZE = MemoryLayout.structLayout(
            ValueLayout.JAVA_LONG.withName("width"), ValueLayout.JAVA_LONG.withName("height"), ValueLayout.JAVA_LONG.withName("depth"));

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup OBJC = SymbolLookup.libraryLookup("/usr/lib/libobjc.A.dylib", Arena.global());
    // Loading the frameworks registers their classes with the runtime (NSString, MTLCompileOptions, ...).
    private static final SymbolLookup FOUNDATION = SymbolLookup.libraryLookup("/System/Library/Frameworks/Foundation.framework/Foundation", Arena.global());
    private static final SymbolLookup METAL = SymbolLookup.libraryLookup("/System/Library/Frameworks/Metal.framework/Metal", Arena.global());

    private static final MemorySegment MSG_SEND = symbol(OBJC, "objc_msgSend");
    private static final MethodHandle GET_CLASS = downcall(symbol(OBJC, "objc_getClass"), FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle REGISTER_NAME = downcall(symbol(OBJC, "sel_registerName"), FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    private static final MethodHandle POOL_PUSH = downcall(symbol(OBJC, "objc_autoreleasePoolPush"), FunctionDescriptor.of(ValueLayout.ADDRESS));
    private static final MethodHandle POOL_POP = downcall(symbol(OBJC, "objc_autoreleasePoolPop"), FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
    private static final MethodHandle CREATE_DEVICE = downcall(symbol(METAL, "MTLCreateSystemDefaultDevice"), FunctionDescriptor.of(ValueLayout.ADDRESS));

    private static final Map<String, MethodHandle> SEND_HANDLES = new ConcurrentHashMap<>();
    private static final Map<String, MemorySegment> SELECTORS = new ConcurrentHashMap<>();
    private static final Map<String, MemorySegment> CLASSES = new ConcurrentHashMap<>();

    private ObjC() {}

    /** Forces the class to initialise: throws (as a LinkageError) when the Objective-C runtime or the frameworks cannot be loaded. */
    static void require() {}

    private static MemorySegment symbol(SymbolLookup lookup, String name) {
        return lookup.find(name).orElseThrow(() -> new IllegalStateException("Symbol not found: " + name));
    }

    private static MethodHandle downcall(MemorySegment address, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(address, descriptor);
    }

    // ---------------------------------------------------------------- classes, selectors, strings

    static MemorySegment cls(String name) {
        return CLASSES.computeIfAbsent(name, n -> {
            try (Arena arena = Arena.ofConfined()) {
                MemorySegment found = (MemorySegment) invokeHandle(GET_CLASS, arena.allocateFrom(n));
                if (found.address() == 0) throw new IllegalStateException("Objective-C class not found: " + n);
                return found;
            }
        });
    }

    static MemorySegment sel(String name) {
        return SELECTORS.computeIfAbsent(name, n -> {
            try (Arena arena = Arena.ofConfined()) {
                return (MemorySegment) invokeHandle(REGISTER_NAME, arena.allocateFrom(n));
            }
        });
    }

    /** An autoreleased NSString: use it inside a {@link Pool}. */
    static MemorySegment nsString(String text) {
        try (Arena arena = Arena.ofConfined()) {
            return ptr(cls("NSString"), "stringWithUTF8String:", arena.allocateFrom(text));
        }
    }

    /** The text of a NUL-terminated C string. */
    static String cString(MemorySegment pointer) {
        return pointer.address() == 0 ? "" : pointer.reinterpret(Long.MAX_VALUE).getString(0);
    }

    /** The text of an NSString (an empty text for nil). */
    static String string(MemorySegment nsString) {
        return nsString.address() == 0 ? "" : cString(ptr(nsString, "UTF8String"));
    }

    /** The localized description of an NSError (what Metal reports compile and command failures with). */
    static String errorText(MemorySegment nsError) {
        if (nsError.address() == 0) return "no error object";
        try (Pool ignored = pool()) {
            return string(ptr(nsError, "localizedDescription"));
        }
    }

    static boolean isNull(MemorySegment object) { return object == null || object.address() == 0; }

    // ---------------------------------------------------------------- messages

    static MemorySegment ptr(MemorySegment receiver, String selector, Object... args) {
        return (MemorySegment) send(MemorySegment.class, receiver, selector, args);
    }

    static long num(MemorySegment receiver, String selector, Object... args) {
        return (long) send(long.class, receiver, selector, args);
    }

    static boolean flag(MemorySegment receiver, String selector, Object... args) {
        return (boolean) send(boolean.class, receiver, selector, args);
    }

    static void call(MemorySegment receiver, String selector, Object... args) {
        send(void.class, receiver, selector, args);
    }

    static boolean responds(MemorySegment receiver, String selector) {
        return flag(receiver, "respondsToSelector:", sel(selector));
    }

    static MemorySegment retain(MemorySegment object) { return ptr(object, "retain"); }

    static void release(MemorySegment object) { if (!isNull(object)) call(object, "release"); }

    private static Object send(Class<?> returns, MemorySegment receiver, String selector, Object[] args) {
        if (isNull(receiver)) throw new IllegalStateException("Message " + selector + " sent to nil");
        try (Arena arena = Arena.ofConfined()) {
            List<MemoryLayout> layouts = new ArrayList<>(args.length);
            List<Object> values = new ArrayList<>(args.length + 2);
            values.add(receiver);
            values.add(sel(selector));
            StringBuilder key = new StringBuilder(returns.getSimpleName()).append(':');
            for (Object arg : args) {
                if (arg instanceof Long) { layouts.add(ValueLayout.JAVA_LONG); values.add(arg); key.append('J'); }
                else if (arg instanceof Integer) { layouts.add(ValueLayout.JAVA_INT); values.add(arg); key.append('I'); }
                else if (arg instanceof Boolean) { layouts.add(ValueLayout.JAVA_BOOLEAN); values.add(arg); key.append('Z'); }
                else if (arg instanceof MemorySegment) { layouts.add(ValueLayout.ADDRESS); values.add(arg); key.append('P'); }
                else if (arg instanceof Size size) {
                    MemorySegment copy = arena.allocate(MTL_SIZE);
                    copy.set(ValueLayout.JAVA_LONG, 0, size.width());
                    copy.set(ValueLayout.JAVA_LONG, 8, size.height());
                    copy.set(ValueLayout.JAVA_LONG, 16, size.depth());
                    layouts.add(MTL_SIZE); values.add(copy); key.append('S');
                } else throw new IllegalArgumentException("Unsupported message argument " + arg);
            }
            MethodHandle handle = SEND_HANDLES.computeIfAbsent(key.toString(), k -> {
                List<MemoryLayout> all = new ArrayList<>(layouts.size() + 2);
                all.add(ValueLayout.ADDRESS);
                all.add(ValueLayout.ADDRESS);
                all.addAll(layouts);
                MemoryLayout[] parameters = all.toArray(MemoryLayout[]::new);
                FunctionDescriptor descriptor = returns == void.class ? FunctionDescriptor.ofVoid(parameters)
                        : FunctionDescriptor.of(layoutOf(returns), parameters);
                return downcall(MSG_SEND, descriptor);
            });
            try {
                return handle.invokeWithArguments(values);
            } catch (RuntimeException | Error failure) {
                throw failure;
            } catch (Throwable failure) {
                throw new IllegalStateException("Message " + selector + " failed", failure);
            }
        }
    }

    private static MemoryLayout layoutOf(Class<?> type) {
        if (type == MemorySegment.class) return ValueLayout.ADDRESS;
        if (type == long.class) return ValueLayout.JAVA_LONG;
        if (type == int.class) return ValueLayout.JAVA_INT;
        if (type == boolean.class) return ValueLayout.JAVA_BOOLEAN;
        throw new IllegalArgumentException("Unsupported message result " + type);
    }

    private static Object invokeHandle(MethodHandle handle, Object... arguments) {
        try {
            return handle.invokeWithArguments(arguments);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException(failure);
        }
    }

    // ---------------------------------------------------------------- autorelease pools

    /** An autorelease pool for the current thread: close it on the thread that opened it. */
    static final class Pool implements AutoCloseable {
        private final MemorySegment token = (MemorySegment) invokeHandle(POOL_PUSH);

        @Override public void close() { invokeHandle(POOL_POP, token); }
    }

    static Pool pool() { return new Pool(); }

    // ---------------------------------------------------------------- Metal

    /** The system default MTLDevice (+1), or a null pointer when the machine has none. */
    static MemorySegment createSystemDefaultDevice() {
        return (MemorySegment) invokeHandle(CREATE_DEVICE);
    }

    /** A little-endian view over native memory (the contents of a shared MTLBuffer). */
    static ByteBuffer view(MemorySegment address, long length) {
        return address.reinterpret(length).asByteBuffer().order(ByteOrder.LITTLE_ENDIAN);
    }
}
