package chat.simplex.common.platform

import chat.simplex.common.model.ChatController.appPrefs
import java.security.MessageDigest
import java.security.SecureRandom

// downstream (shiroikuma): the automation gate of the 保存復元 contract — the same infrastructure
// the sister apps use (renrakusaki's Config, 自由作業盤's AutomationAuth).
//
// CONTRACT v2 (2026-09-04) changed what this gate is. v1 shipped the app closed: the switch was
// off and a caller also had to present a 48-character secret 白い熊 had pasted from this app's
// settings into the caller's. That is wrong for where this is going — a pasted secret cannot
// survive a wipe, and the case the family now exists to serve is 応用管理 restoring apps *and
// their data* onto a clean phone, where nothing has been configured and nobody has pasted
// anything. So the switch ships ON, and the token became an extra a caller may be asked for.
//
// Who may drive the DATA door (export/import through a caller-supplied descriptor) is a separate
// and much stronger question, answered by identity rather than by a shared secret — see
// AutomationCallers in the android module.
//
// All three preferences live in this app's ordinary preferences, and are excluded from the export
// by construction rather than by a filter: UiEximCategory.specs() is a whitelist naming every
// pref that travels, and none of these three is in it. A require-token flag restored onto another
// device is exactly the failure this keeps out of the ZIP.

object AutomationAuth {
  private const val TOKEN_BYTES = 24

  fun enabled(): Boolean = appPrefs.automationEnabled.get()

  /** Whether a caller must also present [token]. Default off — see the file comment. */
  fun requireToken(): Boolean = appPrefs.automationRequireToken.get()

  /**
   * The stored token, generated lazily on first read so the settings row always shows a value
   * even before the token is being asked for.
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
   * The whole gate, in ONE function — both doors call this and nothing else.
   *
   * Returns null to proceed, or the exact `ERROR:` line to answer with. Two checks written out
   * at each entry point is how "disabled" and "bad token" drift apart across forty-two apps, so
   * there is deliberately no second way to ask.
   *
   * **A token sent to an app that does not require one is IGNORED, never an error.** Tokens live
   * in task arguments and workspace variables that outlive the setting they were pasted for: a
   * caller still sending one — because it was configured last year, or because another app on the
   * batch does want one — must be served. Refusing it would turn "白い熊 turned a switch off" into
   * "half the batch mysteriously fails", which is the friction the switch exists to remove.
   *
   * The two failures stay distinct because they debug differently.
   */
  fun refuse(candidate: String?): String? = when {
    !enabled() -> "ERROR:automation disabled"
    requireToken() && !isTokenValid(candidate) -> "ERROR:bad token"
    else -> null
  }

  /** Constant-time, for the case where the token really is required. */
  private fun isTokenValid(candidate: String?): Boolean =
    candidate != null && MessageDigest.isEqual(candidate.toByteArray(), token().toByteArray())
}

/**
 * Whether this app holds All-Files-Access (needed to honour the contract's absolute `path`
 * extra). Null on platforms where the concept doesn't apply.
 */
expect fun hasAllFilesAccess(): Boolean?

/** Opens the system screen where All-Files-Access is granted. No-op where not applicable. */
expect fun openAllFilesAccessSettings()
