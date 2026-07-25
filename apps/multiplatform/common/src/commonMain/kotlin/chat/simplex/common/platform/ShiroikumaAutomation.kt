package chat.simplex.common.platform

import chat.simplex.common.model.ChatController.appPrefs
import java.security.MessageDigest
import java.security.SecureRandom

// downstream (shiroikuma): the automation gate of the 保存復元 state-export contract — the same
// token infrastructure the sister apps use (renrakusaki's Config, 自由作業盤's AutomationAuth).
// A sister app may trigger this app's export headlessly by broadcasting an intent carrying the
// token; nothing is reachable until 白い熊 turns the switch on.
//
// Both preferences live in the app's device-local prefs file and are listed in no export
// category, so the token can never travel inside a backup ZIP.

object AutomationAuth {
  private const val TOKEN_BYTES = 24

  enum class Result { OK, DISABLED, BAD_TOKEN }

  fun enabled(): Boolean = appPrefs.automationEnabled.get()

  /**
   * The stored token, generated lazily on first read so the settings row always shows a value
   * even before automation is switched on.
   */
  fun token(): String {
    val existing = appPrefs.automationToken.get()
    if (!existing.isNullOrBlank()) return existing
    return regenerate()
  }

  fun regenerate(): String {
    val bytes = ByteArray(TOKEN_BYTES)
    SecureRandom().nextBytes(bytes)
    val t = bytes.joinToString("") { "%02x".format(it) }
    appPrefs.automationToken.set(t)
    return t
  }

  /** Abbreviated for display: first and last 8 hex characters. */
  fun abbreviated(t: String): String = if (t.length <= 20) t else "${t.take(8)}…${t.takeLast(8)}"

  /**
   * Checks the switch first, then the token in constant time. The two failures are reported
   * separately on purpose — they debug differently.
   */
  fun check(candidate: String?): Result = when {
    !enabled() -> Result.DISABLED
    candidate == null -> Result.BAD_TOKEN
    MessageDigest.isEqual(candidate.toByteArray(), token().toByteArray()) -> Result.OK
    else -> Result.BAD_TOKEN
  }
}

/**
 * Whether this app holds All-Files-Access (needed to honour the contract's absolute `path`
 * extra). Null on platforms where the concept doesn't apply.
 */
expect fun hasAllFilesAccess(): Boolean?

/** Opens the system screen where All-Files-Access is granted. No-op where not applicable. */
expect fun openAllFilesAccessSettings()
