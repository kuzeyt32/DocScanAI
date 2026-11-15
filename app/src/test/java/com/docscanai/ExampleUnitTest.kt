package com.docscanai

import com.docscanai.core.util.Hashing
import org.junit.Assert.assertEquals
import org.junit.Test

/** Trivial JVM unit test to ensure the `test` source set exists and compiles. */
class ExampleUnitTest {

    @Test
    fun addition_isCorrect() {
        assertEquals(4, 2 + 2)
    }

    @Test
    fun sha256_ofEmptyInput_matchesKnownDigest() {
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            Hashing.sha256Hex(ByteArray(0))
        )
    }
}
