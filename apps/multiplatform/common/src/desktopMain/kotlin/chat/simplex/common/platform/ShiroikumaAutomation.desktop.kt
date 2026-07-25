package chat.simplex.common.platform

// downstream (shiroikuma): the 保存復元 automation contract is an Android-only feature (it is
// driven by 自由作業盤's broadcasts). Desktop keeps the shared token infrastructure compiling
// without any storage-permission concept.

actual fun hasAllFilesAccess(): Boolean? = null

actual fun openAllFilesAccessSettings() {}
