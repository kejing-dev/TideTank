package io.embodit.tidetank.data

import io.embodit.tidetank.domain.Discovery
import io.embodit.tidetank.domain.Food
import io.embodit.tidetank.domain.Kind
import io.embodit.tidetank.domain.Saved
import io.embodit.tidetank.domain.Spot
import io.embodit.tidetank.domain.Tide
import org.junit.Assert.assertEquals
import org.junit.Test

/** files/tank.json round trip (design dataModel); unknown / broken fields fall back instead of crashing. */
class SaveCodecTest {
    @Test fun roundTrip() {
        val s = Saved(Tide.H, Food.SHRIMP, listOf(Discovery(Kind.CLOWN, 123L, Tide.L, Food.PELLETS, Spot.ANEMONE), Discovery(Kind.JELLY, 456L, Tide.H, Food.SHRIMP, Spot.OPEN)))
        assertEquals(s, SaveCodec.decode(SaveCodec.encode(s)))
    }

    @Test fun brokenFieldsFallBack() {
        val s = SaveCodec.decode("""{"v":1,"tide":"Z","food":"?","discovered":[{"id":"shark"},{"id":"seahorse","spot":"MOON"}]}""")
        assertEquals(Tide.M, s.tide); assertEquals(Food.FLAKES, s.food)
        assertEquals(listOf(Kind.SEAHORSE), s.discovered.map { it.kind }); assertEquals(Spot.OPEN, s.discovered[0].spot)
    }
}
