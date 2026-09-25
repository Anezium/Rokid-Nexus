package com.anezium.rokidbus.glasses

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteInputPolicyTest {
    @Test
    fun acceptsBoundedRealtimeTextAndRejectsEmptyOrOversizedPayloads() {
        assertTrue(RemoteInputPolicy.acceptsText("a", 1))
        assertTrue(
            RemoteInputPolicy.acceptsText(
                "x".repeat(RemoteInputPolicy.MAX_TEXT_CODE_UNITS),
                1,
            ),
        )
        assertFalse(RemoteInputPolicy.acceptsText("", 1))
        assertFalse(
            RemoteInputPolicy.acceptsText(
                "x".repeat(RemoteInputPolicy.MAX_TEXT_CODE_UNITS + 1),
                1,
            ),
        )
        assertFalse(RemoteInputPolicy.acceptsText("a", Int.MAX_VALUE))
        assertTrue(RemoteInputPolicy.acceptsComposingText("", 1))
        assertFalse(
            RemoteInputPolicy.acceptsComposingText(
                "x".repeat(RemoteInputPolicy.MAX_TEXT_CODE_UNITS + 1),
                1,
            ),
        )
    }

    @Test
    fun validatesDeleteAndEditorActions() {
        assertTrue(RemoteInputPolicy.acceptsDelete(beforeLength = 1, afterLength = 0))
        assertTrue(RemoteInputPolicy.acceptsDelete(beforeLength = 0, afterLength = 1))
        assertFalse(RemoteInputPolicy.acceptsDelete(beforeLength = 0, afterLength = 0))
        assertFalse(RemoteInputPolicy.acceptsDelete(beforeLength = -1, afterLength = 0))
        assertFalse(
            RemoteInputPolicy.acceptsDelete(
                beforeLength = RemoteInputPolicy.MAX_DELETE_CODE_UNITS + 1,
                afterLength = 0,
            ),
        )

        assertTrue(RemoteInputPolicy.acceptsEditorAction(EditorInfo.IME_ACTION_DONE))
        assertTrue(RemoteInputPolicy.acceptsEditorAction(EditorInfo.IME_ACTION_PREVIOUS))
        assertFalse(RemoteInputPolicy.acceptsEditorAction(EditorInfo.IME_ACTION_PREVIOUS + 1))
    }

    @Test
    fun sessionMetadataOnlyAllowsPackageShapedValues() {
        assertEquals(
            "com.example.video",
            RemoteInputMetadataPolicy.sanitizePackageName("com.example.video"),
        )
        assertEquals("", RemoteInputMetadataPolicy.sanitizePackageName("user@example.com"))
        assertEquals("", RemoteInputMetadataPolicy.sanitizePackageName("field hint"))
        assertEquals("", RemoteInputMetadataPolicy.sanitizePackageName(null))
    }

    @Test
    fun keyboardRequestIsTrustedOnlyFromTheHubsOwnEditableField() {
        val hub = "com.anezium.rokidbus.glasses"
        val marker = RemoteInputMetadataPolicy.EDITABLE_SURFACE_IME_OPTION

        assertTrue(RemoteInputMetadataPolicy.keyboardRequested(hub, marker, hub))
        assertFalse(RemoteInputMetadataPolicy.keyboardRequested("com.example.other", marker, hub))
        assertFalse(RemoteInputMetadataPolicy.keyboardRequested(hub, null, hub))
        assertFalse(RemoteInputMetadataPolicy.keyboardRequested(null, marker, hub))
    }
}
