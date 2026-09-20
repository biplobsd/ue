package dev.updateengine.hyperos

import dev.updateengine.hyperos.core.common.LocalStoragePath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalStoragePathTest {

    @Test
    fun testExternalStorageDocumentUriResolvesToARealPath() {
        // The URI MIUI's / AOSP Files picker returns for a zip in Download.
        val uri = "content://com.android.externalstorage.documents/document/primary%3ADownload%2FDLManager%2F" +
            "zorn-ota_full-OS4.0.0.8.XOKCNXM-user-17.0-c5b483f634.zip"

        assertEquals(
            "/storage/emulated/0/Download/DLManager/zorn-ota_full-OS4.0.0.8.XOKCNXM-user-17.0-c5b483f634.zip",
            LocalStoragePath.fromUriString(uri)
        )
    }

    @Test
    fun testFileUriResolvesAndDecodesSpaces() {
        assertEquals(
            "/storage/emulated/0/Download/My ROM/x.zip",
            LocalStoragePath.fromUriString("file:///storage/emulated/0/Download/My%20ROM/x.zip")
        )
    }

    @Test
    fun testPlusIsNotTreatedAsASpace() {
        assertEquals(
            "/storage/emulated/0/Download/a+b/x.zip",
            LocalStoragePath.fromUriString("content://com.android.externalstorage.documents/document/primary%3ADownload%2Fa+b%2Fx.zip")
        )
    }

    @Test
    fun testRemovableVolumeDocIdIsResolved() {
        assertEquals(
            "/storage/1A2B-3C4D/OTA/x.zip",
            LocalStoragePath.fromExternalStorageDocId("1A2B-3C4D:OTA/x.zip")
        )
    }

    @Test
    fun testEscapeAttemptsAreRejected() {
        assertNull(LocalStoragePath.fromExternalStorageDocId("primary:../../data/adb/modules/x.zip"))
        assertNull(LocalStoragePath.fromExternalStorageDocId("primary:/system/x.zip"))
        assertNull(LocalStoragePath.fromExternalStorageDocId("no-colon.zip"))
    }

    @Test
    fun testOtherProvidersFallBackToCopying() {
        // The Downloads provider hands out opaque media ids; those cannot be turned into a path.
        assertNull(LocalStoragePath.fromUriString("content://com.android.providers.downloads.documents/document/msf%3A1000000123"))
        assertNull(LocalStoragePath.fromUriString("content://com.google.android.apps.docs.storage/document/abc"))
        assertNull(LocalStoragePath.fromUriString("https://example.com/x.zip"))
    }

    @Test
    fun testSharedStorageCheck() {
        assertTrue(LocalStoragePath.isSharedStoragePath("/sdcard/Download/x.zip"))
        assertTrue(LocalStoragePath.isSharedStoragePath("/storage/emulated/0/x.zip"))
        assertFalse(LocalStoragePath.isSharedStoragePath("/data/ota_package/x.zip"))
        assertFalse(LocalStoragePath.isSharedStoragePath("/storage/emulated/0/x.zip\nrm -rf /data"))
    }
}
