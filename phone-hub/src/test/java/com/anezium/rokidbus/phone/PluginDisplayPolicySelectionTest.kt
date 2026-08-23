package com.anezium.rokidbus.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PluginDisplayPolicySelectionTest {
    @Test
    fun `normal is all rows off`() {
        PluginDisplayPolicySelection.Row.entries.forEach { row ->
            assertFalse(PluginDisplayPolicySelection.isChecked(PluginDisplayPolicy.NORMAL, row))
        }
    }

    @Test
    fun `each restricting policy lights only its own row`() {
        val expected = mapOf(
            PluginDisplayPolicy.MUTE to PluginDisplayPolicySelection.Row.MUTE,
            PluginDisplayPolicy.DEMOTE to PluginDisplayPolicySelection.Row.DEMOTE,
            PluginDisplayPolicy.NOTICES to PluginDisplayPolicySelection.Row.NOTICES,
        )
        expected.forEach { (policy, lit) ->
            PluginDisplayPolicySelection.Row.entries.forEach { row ->
                assertEquals(
                    "$policy $row",
                    row == lit,
                    PluginDisplayPolicySelection.isChecked(policy, row),
                )
            }
        }
    }

    @Test
    fun `turning a row on selects that policy`() {
        assertEquals(
            PluginDisplayPolicy.MUTE,
            PluginDisplayPolicySelection.afterToggle(
                PluginDisplayPolicy.NORMAL,
                PluginDisplayPolicySelection.Row.MUTE,
                checked = true,
            ),
        )
        assertEquals(
            PluginDisplayPolicy.DEMOTE,
            PluginDisplayPolicySelection.afterToggle(
                PluginDisplayPolicy.NORMAL,
                PluginDisplayPolicySelection.Row.DEMOTE,
                checked = true,
            ),
        )
        assertEquals(
            PluginDisplayPolicy.NOTICES,
            PluginDisplayPolicySelection.afterToggle(
                PluginDisplayPolicy.NORMAL,
                PluginDisplayPolicySelection.Row.NOTICES,
                checked = true,
            ),
        )
    }

    @Test
    fun `turning a row on while another is on replaces it`() {
        assertEquals(
            PluginDisplayPolicy.DEMOTE,
            PluginDisplayPolicySelection.afterToggle(
                PluginDisplayPolicy.MUTE,
                PluginDisplayPolicySelection.Row.DEMOTE,
                checked = true,
            ),
        )
        assertEquals(
            PluginDisplayPolicy.NOTICES,
            PluginDisplayPolicySelection.afterToggle(
                PluginDisplayPolicy.DEMOTE,
                PluginDisplayPolicySelection.Row.NOTICES,
                checked = true,
            ),
        )
        assertEquals(
            PluginDisplayPolicy.MUTE,
            PluginDisplayPolicySelection.afterToggle(
                PluginDisplayPolicy.NOTICES,
                PluginDisplayPolicySelection.Row.MUTE,
                checked = true,
            ),
        )
    }

    @Test
    fun `turning the active row off returns normal`() {
        assertEquals(
            PluginDisplayPolicy.NORMAL,
            PluginDisplayPolicySelection.afterToggle(
                PluginDisplayPolicy.MUTE,
                PluginDisplayPolicySelection.Row.MUTE,
                checked = false,
            ),
        )
        assertEquals(
            PluginDisplayPolicy.NORMAL,
            PluginDisplayPolicySelection.afterToggle(
                PluginDisplayPolicy.DEMOTE,
                PluginDisplayPolicySelection.Row.DEMOTE,
                checked = false,
            ),
        )
        assertEquals(
            PluginDisplayPolicy.NORMAL,
            PluginDisplayPolicySelection.afterToggle(
                PluginDisplayPolicy.NOTICES,
                PluginDisplayPolicySelection.Row.NOTICES,
                checked = false,
            ),
        )
    }

    @Test
    fun `turning an inactive row off leaves the current policy`() {
        assertEquals(
            PluginDisplayPolicy.MUTE,
            PluginDisplayPolicySelection.afterToggle(
                PluginDisplayPolicy.MUTE,
                PluginDisplayPolicySelection.Row.DEMOTE,
                checked = false,
            ),
        )
        assertEquals(
            PluginDisplayPolicy.DEMOTE,
            PluginDisplayPolicySelection.afterToggle(
                PluginDisplayPolicy.DEMOTE,
                PluginDisplayPolicySelection.Row.NOTICES,
                checked = false,
            ),
        )
        assertEquals(
            PluginDisplayPolicy.NORMAL,
            PluginDisplayPolicySelection.afterToggle(
                PluginDisplayPolicy.NORMAL,
                PluginDisplayPolicySelection.Row.MUTE,
                checked = false,
            ),
        )
    }
}
