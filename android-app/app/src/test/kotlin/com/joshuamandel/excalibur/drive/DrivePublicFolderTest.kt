package com.joshuamandel.excalibur.drive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DrivePublicFolderTest {
    @Test fun parsesFolderUrlWithResourceKey() {
        val ref = DrivePublicFolder.parseFolderUrl(
            "https://drive.google.com/drive/folders/1Abcdefghijklmnopqrstuvwxyz_123?usp=sharing&resourcekey=0-key"
        )
        assertNotNull(ref)
        assertEquals("1Abcdefghijklmnopqrstuvwxyz_123", ref!!.id)
        assertEquals("0-key", ref.resourceKey)
    }

    @Test fun parsesOpenIdUrl() {
        val ref = DrivePublicFolder.parseFolderUrl("https://drive.google.com/open?id=1FolderId_1234567890")
        assertNotNull(ref)
        assertEquals("1FolderId_1234567890", ref!!.id)
    }

    @Test fun rejectsNonDriveFolderShape() {
        assertNull(DrivePublicFolder.parseFolderUrl("https://example.com/nope"))
    }

    @Test fun parsesEmbeddedFolderHtml() {
        val parsed = DrivePublicFolder.parseEmbeddedFolderHtml(
            """
            <html>
              <head><title>Inbox &amp; Books - Google Drive</title></head>
              <body>
                <a href="https://drive.google.com/file/d/1FileId_abcdefghijklmnopqrs/view?usp=drive_link&amp;resourcekey=0-file">Dune &amp; Other.epub</a>
                <a href="https://drive.google.com/drive/folders/1ChildFolder_abcdefghijklmnopq?resourcekey=0-child">Subfolder</a>
              </body>
            </html>
            """.trimIndent()
        )
        assertEquals("Inbox & Books", parsed.folderName)
        assertEquals(1, parsed.files.size)
        assertEquals("1FileId_abcdefghijklmnopqrs", parsed.files.single().id)
        assertEquals("Dune & Other.epub", parsed.files.single().name)
        assertEquals("0-file", parsed.files.single().resourceKey)
        assertEquals(1, parsed.folders.size)
        assertEquals("1ChildFolder_abcdefghijklmnopq", parsed.folders.single().ref.id)
        assertEquals("0-child", parsed.folders.single().ref.resourceKey)
    }
}
