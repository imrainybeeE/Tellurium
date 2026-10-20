// SPDX-License-Identifier: MIT
package dev.tellurium.runtime.metal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.foreign.MemorySegment;

import static org.junit.jupiter.api.Assertions.*;

/** The Objective-C glue: what it passes to the runtime and what it reads back. */
@EnabledOnOs(OS.MAC)
class ObjCTest {
    @Test void aStringSurvivesTheRoundTripThroughNSString() {
        try (ObjC.Pool ignored = ObjC.pool()) {
            for (String text : new String[]{"", "main0", "kernel é中😀 end"}) {
                assertEquals(text, ObjC.string(ObjC.nsString(text)));
            }
        }
    }

    @Test void anMtlSizeIsThreeEightByteFields() {
        assertEquals(24, ObjC.MTL_SIZE.byteSize());
        assertEquals(8, ObjC.MTL_SIZE.byteAlignment());
    }

    @Test void aMissingClassIsAnErrorNotANullMessage() {
        var error = assertThrows(IllegalStateException.class, () -> ObjC.cls("TelluriumNoSuchClass"));
        assertTrue(error.getMessage().contains("TelluriumNoSuchClass"));
    }

    @Test void aMessageToNilIsAnErrorNamingTheSelector() {
        var error = assertThrows(IllegalStateException.class, () -> ObjC.ptr(MemorySegment.NULL, "name"));
        assertTrue(error.getMessage().contains("name"));
    }

    @Test void respondsToSelectorSeesWhatAClassImplements() {
        try (ObjC.Pool ignored = ObjC.pool()) {
            MemorySegment text = ObjC.nsString("x");
            assertTrue(ObjC.responds(text, "UTF8String"));
            assertFalse(ObjC.responds(text, "telluriumNoSuchSelector:"));
        }
    }
}
