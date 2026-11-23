package com.geekinasuit.waxnotes.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class NoteIdTest {
  private val valid = "0123456789abcdef".repeat(4)

  @Test
  fun acceptsThirtyTwoBytesOfLowercaseHex() {
    assertEquals(valid, NoteId(valid).hex)
  }

  @Test
  fun rejectsTheWrongLength() {
    for (hex in listOf("", valid.dropLast(1), valid + "0")) {
      assertFailsWith<IllegalArgumentException>(hex) { NoteId(hex) }
    }
  }

  @Test
  fun rejectsAnythingButLowercaseHex() {
    for (bad in listOf('A', 'g', ' ', '/', ':', '`')) {
      assertFailsWith<IllegalArgumentException>("$bad") { NoteId(valid.dropLast(1) + bad) }
    }
  }

  @Test
  fun doesNotEchoTheRejectedValue() {
    val secret = "deadbeef"
    val error = assertFailsWith<IllegalArgumentException> { NoteId(secret) }
    assertEquals(false, error.message.orEmpty().contains(secret))
  }
}
