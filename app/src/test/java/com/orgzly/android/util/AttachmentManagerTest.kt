package com.orgzly.android.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.orgzly.android.db.entity.Note
import com.orgzly.android.db.entity.NotePosition
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.io.path.createTempDirectory

@RunWith(AndroidJUnit4::class)
class AttachmentManagerTest {

    private lateinit var context: Context
    private lateinit var testBookFile: File
    private lateinit var testAttachmentDir: File
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        
        // Create temporary directory for tests
        tempDir = createTempDirectory().toFile()
        
        // Create test book file
        testBookFile = File(tempDir, "test-book.org")
        testBookFile.writeText("* Test Book")
        
        // Test attachment directory will be created as needed
        testAttachmentDir = File(testBookFile.parent, "attachments")
    }

    @After
    fun tearDown() {
        // Clean up test files
        tempDir.deleteRecursively()
    }

    @Test
    fun testGenerateNoteId() {
        val noteId = AttachmentManager.generateNoteId()
        
        assertNotNull(noteId)
        assertTrue("Note ID should be non-empty", noteId.isNotEmpty())
        assertTrue("Note ID should be lowercase UUID format", 
            noteId.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")))
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
        
        // Should use hierarchical structure (first 2 chars of first segment as subdirectory)
        // For "12345678-1234-1234-1234-123456789012", use "12" and "345678-1234-1234-1234-123456789012"
        val expectedDir = File(File(testAttachmentDir, "12"), "345678-1234-1234-1234-123456789012")
        assertEquals(expectedDir, noteDir)
    }
    

    @Test
    fun testGetOrCreateNoteAttachmentDir() {
        val noteId = "12345678-1234-1234-1234-123456789012"
        
        // Should create the hierarchical directory structure if it doesn't exist
        assertFalse("Attachment dir should not exist initially", testAttachmentDir.exists())
        
        val noteDir = AttachmentManager.getOrCreateNoteAttachmentDir(testBookFile, noteId)
        
        assertTrue("Note attachment dir should be created", noteDir.exists())
        assertTrue("Note attachment dir should be a directory", noteDir.isDirectory())
        // Should use hierarchical structure (first 2 chars of first segment as subdirectory)
        // For "12345678-1234-1234-1234-123456789012", use "12" and "345678-1234-1234-1234-123456789012"
        val expectedDir = File(File(testAttachmentDir, "12"), "345678-1234-1234-1234-123456789012")
        assertEquals(expectedDir, noteDir)
    }

    @Test
    fun testCreateAttachmentLink() {
        val attachmentFile = File("test-image.jpg")
        
        // Test simple link (uses filename as link text)
        val simpleLink = AttachmentManager.createAttachmentLink(attachmentFile)
        assertEquals("[[attachment:test-image.jpg][test-image.jpg]]", simpleLink)
        
        // Test link with description
        val linkWithDescription = AttachmentManager.createAttachmentLink(attachmentFile, "My Test Image")
        assertEquals("[[attachment:test-image.jpg][My Test Image]]", linkWithDescription)
    }

    @Test
    fun testResolveAttachmentLink() {
        val noteId = "12345678-1234-1234-1234-123456789012"
        val noteDir = AttachmentManager.getOrCreateNoteAttachmentDir(testBookFile, noteId)
        
        // Create a test attachment file in hierarchical structure
        val testFile = File(noteDir, "test-image.jpg")
        testFile.writeText("fake image content")
        
        // Test attachment: format should find file in hierarchical structure
        val resolvedFile = AttachmentManager.resolveAttachmentLink(
            testBookFile, noteId, "attachment:test-image.jpg")
        
        assertNotNull("Should resolve attachment link", resolvedFile)
        assertEquals(testFile, resolvedFile)
        
        // Test direct hierarchical attachments/ path
        val directPath = "./attachments/12/345678-1234-1234-1234-123456789012/test-image.jpg"
        val resolvedDirect = AttachmentManager.resolveAttachmentLink(
            testBookFile, noteId, directPath)
            
        assertNotNull("Should resolve direct hierarchical attach path", resolvedDirect)
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
        
        // Initially should be empty
        val emptyAttachments = AttachmentManager.getNoteAttachments(testBookFile, noteId)
        assertTrue("Should initially have no attachments", emptyAttachments.isEmpty())
        
        // Create files in hierarchical structure
        val hierarchicalDir = AttachmentManager.getOrCreateNoteAttachmentDir(testBookFile, noteId)
        val file1 = File(hierarchicalDir, "image1.jpg")
        val file2 = File(hierarchicalDir, "document.pdf")
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
    
    @Test
    fun testCleanupNoteAttachments() {
        val noteId = "12345678-1234-1234-1234-123456789012"
        
        // Create files in hierarchical structure
        val hierarchicalDir = AttachmentManager.getOrCreateNoteAttachmentDir(testBookFile, noteId)
        val file1 = File(hierarchicalDir, "image1.jpg")
        val file2 = File(hierarchicalDir, "document.pdf")
        file1.writeText("fake image")
        file2.writeText("fake pdf")
        
        // Verify files exist before cleanup
        assertTrue("Hierarchical file 1 should exist", file1.exists())
        assertTrue("Hierarchical file 2 should exist", file2.exists())
        
        // Clean up attachments
        val deletedCount = AttachmentManager.cleanupNoteAttachments(testBookFile, noteId)
        
        // Verify cleanup results
        assertEquals("Should have deleted 2 files", 2, deletedCount)
        assertFalse("Hierarchical file 1 should be deleted", file1.exists())
        assertFalse("Hierarchical file 2 should be deleted", file2.exists())
        
        // Verify directories are cleaned up too
        assertFalse("Hierarchical note directory should be removed", hierarchicalDir.exists())
    }
    
    @Test
    fun testNoteHasAttachments() {
        // Test note with ATTACH tag
        val noteWithTag = Note(
            id = 1,
            position = NotePosition(
                bookId = 1,
                lft = 1,
                rgt = 2,
                level = 0,
                parentId = 0,
                foldedUnderId = 0,
                isFolded = false,
                descendantsCount = 0
            ),
            title = "Test Note with Attachments",
            content = "Some content",
            tags = "ATTACH work", // Note.dbSerializeTags format
            state = null,
            priority = null,
            createdAt = 0L,
            scheduledRangeId = null,
            deadlineRangeId = null,
            closedRangeId = null,
            clockRangeId = null,
            contentLineCount = 1
        )
        
        assertTrue("Note with ATTACH tag should be detected as having attachments", 
            AttachmentManager.noteHasAttachments(noteWithTag))
        
        // Test note with attachment link
        val noteWithLink = noteWithTag.copy(
            tags = "", // No tags
            content = "Check out this image: [[attachment:photo.jpg]]"
        )
        
        assertTrue("Note with attachment link should be detected as having attachments", 
            AttachmentManager.noteHasAttachments(noteWithLink))
        
        // Test note without attachments
        val noteWithoutAttachments = noteWithTag.copy(
            tags = "work urgent", // No ATTACH tag
            content = "Just regular content with no attachments"
        )
        
        assertFalse("Note without attachments should not be detected as having attachments", 
            AttachmentManager.noteHasAttachments(noteWithoutAttachments))
    }
}
