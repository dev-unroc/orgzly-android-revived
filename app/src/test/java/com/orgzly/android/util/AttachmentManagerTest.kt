package com.orgzly.android.util

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.orgzly.android.OrgzlyTest
import com.orgzly.android.testutils.MiscTestUtils
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class AttachmentManagerTest : OrgzlyTest() {

    private lateinit var context: Context
    private lateinit var testBookFile: File
    private lateinit var testAttachmentDir: File

    @Before
    override fun setUp() {
        super.setUp()
        context = ApplicationProvider.getApplicationContext()
        
        // Create test book file
        testBookFile = File(dataRepository.getStorage().getAbsolutePath(), "test-book.org")
        testBookFile.writeText("* Test Book")
        
        // Test attachment directory will be created as needed
        testAttachmentDir = File(testBookFile.parent, "attachments")
    }

    @After
    override fun tearDown() {
        super.tearDown()
        
        // Clean up test files
        testAttachmentDir.deleteRecursively()
        testBookFile.delete()
    }

    @Test
    fun testGenerateNoteId() {
        val noteId = AttachmentManager.generateNoteId()
        
        assertNotNull(noteId)
        assertTrue("Note ID should be non-empty", noteId.isNotEmpty())
        assertTrue("Note ID should be uppercase UUID format", 
            noteId.matches(Regex("[A-F0-9]{8}-[A-F0-9]{4}-[A-F0-9]{4}-[A-F0-9]{4}-[A-F0-9]{12}")))
    }

    @Test
    fun testGetBookAttachmentBaseDir() {
        val baseDir = AttachmentManager.getBookAttachmentBaseDir(testBookFile)
        
        assertEquals(testAttachmentDir, baseDir)
        assertEquals("attachments", baseDir.name)
    }

    @Test
    fun testGetNoteAttachmentDir() {
        val noteId = "12345678-1234-1234-1234-123456789012"
        val noteDir = AttachmentManager.getNoteAttachmentDir(testBookFile, noteId)
        
        assertEquals(File(testAttachmentDir, noteId), noteDir)
    }

    @Test
    fun testGetOrCreateNoteAttachmentDir() {
        val noteId = "12345678-1234-1234-1234-123456789012"
        
        // Should create the directory if it doesn't exist
        assertFalse("Attachment dir should not exist initially", testAttachmentDir.exists())
        
        val noteDir = AttachmentManager.getOrCreateNoteAttachmentDir(testBookFile, noteId)
        
        assertTrue("Note attachment dir should be created", noteDir.exists())
        assertTrue("Note attachment dir should be a directory", noteDir.isDirectory())
        assertEquals(File(testAttachmentDir, noteId), noteDir)
    }

    @Test
    fun testCreateAttachmentLink() {
        val attachmentFile = File("test-image.jpg")
        
        // Test simple link
        val simpleLink = AttachmentManager.createAttachmentLink(attachmentFile)
        assertEquals("[[attachment:test-image.jpg]]", simpleLink)
        
        // Test link with description
        val linkWithDescription = AttachmentManager.createAttachmentLink(attachmentFile, "My Test Image")
        assertEquals("[[attachment:test-image.jpg][My Test Image]]", linkWithDescription)
    }

    @Test
    fun testResolveAttachmentLink() {
        val noteId = "12345678-1234-1234-1234-123456789012"
        val noteDir = AttachmentManager.getOrCreateNoteAttachmentDir(testBookFile, noteId)
        
        // Create a test attachment file
        val testFile = File(noteDir, "test-image.jpg")
        testFile.writeText("fake image content")
        
        // Test attachment: format
        val resolvedFile = AttachmentManager.resolveAttachmentLink(
            testBookFile, noteId, "attachment:test-image.jpg")
        
        assertNotNull("Should resolve attachment link", resolvedFile)
        assertEquals(testFile, resolvedFile)
        
        // Test direct attachments/ path
        val directPath = "./attachments/$noteId/test-image.jpg"
        val resolvedDirect = AttachmentManager.resolveAttachmentLink(
            testBookFile, noteId, directPath)
            
        assertNotNull("Should resolve direct attach path", resolvedDirect)
        assertEquals(testFile, resolvedDirect)
    }

    @Test
    fun testResolveAttachmentLinkNonExistent() {
        val noteId = "12345678-1234-1234-1234-123456789012"
        
        // Test non-existent file
        val resolvedFile = AttachmentManager.resolveAttachmentLink(
            testBookFile, noteId, "attachment:nonexistent.jpg")
        
        assertNull("Should return null for non-existent attachment", resolvedFile)
    }

    @Test
    fun testGetFileExtension() {
        assertEquals("jpg", AttachmentManager.getFileExtension("image.jpg"))
        assertEquals("png", AttachmentManager.getFileExtension("screenshot.png"))
        assertEquals("pdf", AttachmentManager.getFileExtension("document.pdf"))
        assertEquals("", AttachmentManager.getFileExtension("noextension"))
        assertEquals("", AttachmentManager.getFileExtension(""))
    }

    @Test
    fun testIsImageFile() {
        assertTrue("JPG should be image", AttachmentManager.isImageFile("photo.jpg"))
        assertTrue("PNG should be image", AttachmentManager.isImageFile("screenshot.png"))
        assertTrue("GIF should be image", AttachmentManager.isImageFile("animation.gif"))
        assertTrue("WebP should be image", AttachmentManager.isImageFile("modern.webp"))
        assertTrue("SVG should be image", AttachmentManager.isImageFile("vector.svg"))
        
        assertFalse("PDF should not be image", AttachmentManager.isImageFile("document.pdf"))
        assertFalse("TXT should not be image", AttachmentManager.isImageFile("notes.txt"))
        assertFalse("No extension should not be image", AttachmentManager.isImageFile("filename"))
    }

    @Test
    fun testGetNoteAttachments() {
        val noteId = "12345678-1234-1234-1234-123456789012"
        val noteDir = AttachmentManager.getOrCreateNoteAttachmentDir(testBookFile, noteId)
        
        // Initially should be empty
        val emptyAttachments = AttachmentManager.getNoteAttachments(testBookFile, noteId)
        assertTrue("Should initially have no attachments", emptyAttachments.isEmpty())
        
        // Create some test files
        val file1 = File(noteDir, "image1.jpg")
        val file2 = File(noteDir, "document.pdf")
        file1.writeText("fake image")
        file2.writeText("fake pdf")
        
        val attachments = AttachmentManager.getNoteAttachments(testBookFile, noteId)
        assertEquals("Should have 2 attachments", 2, attachments.size)
        assertTrue("Should contain image1.jpg", attachments.any { it.name == "image1.jpg" })
        assertTrue("Should contain document.pdf", attachments.any { it.name == "document.pdf" })
    }

    @Test
    fun testDeleteAttachment() {
        val noteId = "12345678-1234-1234-1234-123456789012"
        val noteDir = AttachmentManager.getOrCreateNoteAttachmentDir(testBookFile, noteId)
        
        // Create test file
        val testFile = File(noteDir, "test.jpg")
        testFile.writeText("test content")
        
        assertTrue("File should exist before deletion", testFile.exists())
        
        val deleted = AttachmentManager.deleteAttachment(testFile)
        
        assertTrue("Delete operation should succeed", deleted)
        assertFalse("File should not exist after deletion", testFile.exists())
    }
}
