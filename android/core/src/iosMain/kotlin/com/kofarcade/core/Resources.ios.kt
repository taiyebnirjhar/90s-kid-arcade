package com.kofarcade.core

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSBundle
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.stringWithContentsOfFile

// The iPhone app bundles core's resources/ as is, in its own resources.
@OptIn(ExperimentalForeignApi::class)
internal actual fun resourceText(path: String): String? {
    val dir = NSBundle.mainBundle.resourcePath ?: return null
    return NSString.stringWithContentsOfFile("$dir/$path", NSUTF8StringEncoding, null)
}
