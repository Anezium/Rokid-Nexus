package com.anezium.rokidbus.plugin.nav

import org.junit.Assert.assertEquals
import org.junit.Test

/** The cut a bounded activity line gets before the wire: at a word, never ending on a separator. */
class NavTextFitTest {
    @Test
    fun `a value within the bound is only trimmed and its runs of spaces folded`() {
        assertEquals("Rue de Rivoli", NavText.fit("  Rue  de   Rivoli ", 28))
    }

    @Test
    fun `a no-break space is not a separator here, it is the caller's to fold with spaces()`() {
        assertEquals("80 m", NavText.fit("80 m", 28))
        assertEquals("80 m", NavText.fit(NavText.spaces("80 m")!!, 28))
    }

    @Test
    fun `a long name is cut at a word and loses the separator the cut leaves`() {
        assertEquals("Gare de Goussainville…", NavText.fit("Gare de Goussainville, Val-d'Oise", 28))
        assertEquals("Place de la République…", NavText.fit("Place de la République - Métro 3", 28))
        assertEquals("Châtelet…", NavText.fit("Châtelet (Les Halles) RER A B D", 12))
    }

    @Test
    fun `a word too far back is not used and the cut falls inside the word`() {
        assertEquals("Aaaaaaaaaaaaaaaaaaaaaaaaaaa…", NavText.fit("Aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa bbb", 28))
    }
}
