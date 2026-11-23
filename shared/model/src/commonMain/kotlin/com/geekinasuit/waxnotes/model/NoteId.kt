package com.geekinasuit.waxnotes.model

/**
 * A note's identity: 32 random bytes, as 64 lowercase hex digits.
 *
 * Never sent to a relay in plaintext (brief §5).
 */
data class NoteId(val hex: String) {
  init {
    require(hex.length == HEX_LENGTH && hex.all { it in '0'..'9' || it in 'a'..'f' }) {
      "a note id is $HEX_LENGTH lowercase hex digits"
    }
  }

  private companion object {
    const val HEX_LENGTH = 64
  }
}
