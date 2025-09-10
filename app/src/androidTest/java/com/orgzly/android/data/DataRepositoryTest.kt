package com.orgzly.android.data

import com.google.gson.Gson
import com.orgzly.android.OrgzlyTest
import com.orgzly.android.prefs.AppPreferences
import com.orgzly.android.util.AttachmentManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DataRepositoryTest : OrgzlyTest() {

    /**
     * If the user attempts to export app settings to a note with a non-unique "ID" value, then
     * - an exception should be thrown
     * - no export should happen
     */
    @Test(expected = RuntimeException::class)
    fun testExportSettingsToNonUniqueNoteId() {
        // Given
        testUtils.setupBook(
            "book1",
            """
                * Note 1
                :PROPERTIES:
                :ID: not-unique-value
                :END:

                content

                * Note 2
                :PROPERTIES:
                :ID: not-unique-value
                :END:

                content

           """.trimIndent()
        )
        assertEquals(2, dataRepository.getNotes("book1").size)
        AppPreferences.settingsExportAndImportNoteId(context, "not-unique-value")
        val targetNote = dataRepository.getNotes("book1")[0].note
        
        // Expect
        try {
            dataRepository.exportSettingsAndSearchesToNote(targetNote)
        } catch (e: java.lang.RuntimeException) {
            assertTrue(e.message!!.contains("Found multiple"))
            throw e
        } finally {
            assertEquals("content", dataRepository.getNotes("book1")[0].note.content)
            assertEquals("content", dataRepository.getNotes("book1")[1].note.content)
        }
    }

    /**
     * Unknown keys in the JSON blob must be silently ignored during import
     * without causing issues.
     */
    @Test
    fun testImportSettingsWithInvalidEntries() {
        // Given
        val noteId = "my-export-note"
        testUtils.setupBook(
            "book1",
            """
                * Note 1
                :PROPERTIES:
                :ID: $noteId
                :END:

                {"settings":{"pref_key_states":"NEXT | DONE","invalid_key":"invalid_value"},"saved_searches":{}}

           """.trimIndent()
        )
        val searchesBeforeImport = dataRepository.getSavedSearches()
        // Check that a setting has its default value
        assertEquals("TODO NEXT | DONE", AppPreferences.states(context))
        val sourceNote = dataRepository.getNotes("book1")[0].note

        // When
        dataRepository.importSettingsAndSearchesFromNote(sourceNote)

        // Expect the ssetting to have changed
        assertEquals("NEXT | DONE", AppPreferences.states(context))
        // Expect searches not to have changed
        assertEquals(searchesBeforeImport, dataRepository.getSavedSearches())
    }

    /**
     * An attempt to import completely invalid data must fail gracefully, with no changes.
     */
    @Test(expected = RuntimeException::class)
    fun testImportInvalidSettingsData() {
        // Given
        val noteId = "my-export-note"
        testUtils.setupBook(
            "book1",
            """
                * Note 1
                :PROPERTIES:
                :ID: $noteId
                :END:

                Sorry, I'm just a little note. I may even look a little bit like
                JSON. {"something":"nothing"}

           """.trimIndent()
        )
        val searchesBeforeImport = dataRepository.getSavedSearches()
        val settingsBeforeImport = Gson().toJson(AppPreferences.getDefaultPrefsAsJsonObject(context))
        val sourceNote = dataRepository.getNotes("book1")[0].note

        // Expect
        try {
            dataRepository.importSettingsAndSearchesFromNote(sourceNote)
        } catch (e: java.lang.RuntimeException) {
            assertTrue(e.message!!.contains("valid JSON"))
            throw e
        } finally {
            // Searches have not changed
            assertEquals(searchesBeforeImport, dataRepository.getSavedSearches())
            // Settings have not changed
            assertEquals(settingsBeforeImport, Gson().toJson(AppPreferences.getDefaultPrefsAsJsonObject(context)))
        }
    }

    /**
     * If the "settings" key is missing, no import should happen.
     */
    @Test(expected = RuntimeException::class)
    fun testImportSettingsNoSettingsKey() {
        // Given
        val noteId = "my-export-note"
        testUtils.setupBook(
            "book1",
            """
                * Note 1
                :PROPERTIES:
                :ID: $noteId
                :END:

                {"saved_searches":{"Agenda":".it.done ad.7"}}

           """.trimIndent()
        )
        val searchesBeforeImport = dataRepository.getSavedSearches()
        val settingsBeforeImport = Gson().toJson(AppPreferences.getDefaultPrefsAsJsonObject(context))
        val sourceNote = dataRepository.getNotes("book1")[0].note

        // Expect
        try {
            dataRepository.importSettingsAndSearchesFromNote(sourceNote)
        } catch (e: java.lang.RuntimeException) {
            assertTrue(e.message!!.contains("missing mandatory fields"))
            throw e
        } finally {
            // Searches have not changed
            assertEquals(searchesBeforeImport, dataRepository.getSavedSearches())
            // Settings have not changed
            assertEquals(settingsBeforeImport, Gson().toJson(AppPreferences.getDefaultPrefsAsJsonObject(context)))
        }
    }

    /**
     * If the "searches" key is missing, no import should happen.
     */
    @Test(expected = RuntimeException::class)
    fun testImportSettingsNoSearchesKey() {
        // Given
        val noteId = "my-export-note"
        testUtils.setupBook(
            "book1",
            """
                * Note 1
                :PROPERTIES:
                :ID: $noteId
                :END:

                {"settings":{"pref_key_states":"NEXT | DONE"}}

           """.trimIndent()
        )
        val searchesBeforeImport = dataRepository.getSavedSearches()
        val settingsBeforeImport = Gson().toJson(AppPreferences.getDefaultPrefsAsJsonObject(context))
        val sourceNote = dataRepository.getNotes("book1")[0].note

        // Expect
        try {
            dataRepository.importSettingsAndSearchesFromNote(sourceNote)
        } catch (e: java.lang.RuntimeException) {
            assertTrue(e.message!!.contains("missing mandatory fields"))
            throw e
        } finally {
            // Searches have not changed
            assertEquals(searchesBeforeImport, dataRepository.getSavedSearches())
            // Settings have not changed
            assertEquals(settingsBeforeImport, Gson().toJson(AppPreferences.getDefaultPrefsAsJsonObject(context)))
        }
    }

    /**
     * The "settings" and "saved_searches" keys must be present, but they may be empty.
     */

    @Test
    fun testImportSettingsWithSettingsDataWithoutSearchesData() {
        // Given
        val noteId = "my-export-note"
        testUtils.setupBook(
            "book1", """
                * Note 1
                :PROPERTIES:
                :ID: $noteId
                :END:

                {"settings":{"pref_key_states":"NEXT | DONE"},"saved_searches":{}}

           """.trimIndent()
        )
        val searchesBeforeImport = dataRepository.getSavedSearches()
        // Check that the setting has the default value
        assertEquals("TODO NEXT | DONE", AppPreferences.states(context))
        val sourceNote = dataRepository.getNotes("book1")[0].note

        // When
        dataRepository.importSettingsAndSearchesFromNote(sourceNote)

        // Expect searches not to have changed
        assertEquals(searchesBeforeImport, dataRepository.getSavedSearches())
        // Expect settings to have changed
        assertEquals("NEXT | DONE", AppPreferences.states(context))
    }

    @Test
    fun testImportSettingsWithSearchesDataWithoutSettingsData() {
        // Given
        val noteId = "my-export-note"
        testUtils.setupBook(
            "book1", """
                * Note 1
                :PROPERTIES:
                :ID: $noteId
                :END:

                {"settings":{},"saved_searches":{"Agenda":".it.done ad.7"}}

           """.trimIndent()
        )
        val searchesBeforeImport = dataRepository.getSavedSearches()
        // Assert default number of searches
        assertEquals(4, searchesBeforeImport.size)
        // Store current settings
        val settingsBeforeImport = Gson().toJson(AppPreferences.getDefaultPrefsAsJsonObject(context))
        val sourceNote = dataRepository.getNotes("book1")[0].note

        // When
        dataRepository.importSettingsAndSearchesFromNote(sourceNote)

        // Then
        // Searches have changed
        assertEquals(1, dataRepository.getSavedSearches().size)
        // Settings have not changed
        assertEquals(settingsBeforeImport, Gson().toJson(AppPreferences.getDefaultPrefsAsJsonObject(context)))
    }

    @Test(expected = RuntimeException::class)
    fun testImportSettingsValidJsonButNoData() {
        val noteId = "my-export-note"
        testUtils.setupBook(
            "book1", """
                * Note 1
                :PROPERTIES:
                :ID: $noteId
                :END:

                {"settings":{},"saved_searches":{}}

           """.trimIndent()
        )
        val searchesBeforeImport = dataRepository.getSavedSearches()
        val settingsBeforeImport = Gson().toJson(AppPreferences.getDefaultPrefsAsJsonObject(context))
        val sourceNote = dataRepository.getNotes("book1")[0].note

        // Expect
        try {
            dataRepository.importSettingsAndSearchesFromNote(sourceNote)
        } catch (e: java.lang.RuntimeException) {
            assertTrue(e.message!!.contains("Found no settings or saved searches to import"))
            throw e
        } finally {
            // Searches have not changed
            assertEquals(searchesBeforeImport, dataRepository.getSavedSearches())
            // Settings have not changed
            assertEquals(
                settingsBeforeImport,
                Gson().toJson(AppPreferences.getDefaultPrefsAsJsonObject(context))
            )
        }
    }
    
    /**
     * Test that attachments are cleaned up when a book is deleted.
     */
    @Test
    fun testBookDeletionCleansUpAttachments() {
        // Given - create a book with a note that has an attachment
        val bookName = "test-book-with-attachments"
        val noteId = "caeffd7f-f678-486b-8ffa-7bac167f4e71"
        testUtils.setupBook(
            bookName,
            """
                * Note with attachment   :ATTACH:
                :PROPERTIES:
                :ID: $noteId
                :END:
                
                This note has an attachment: [[attachment:test-file.txt][test-file.txt]]

           """.trimIndent()
        )
        
        val book = dataRepository.getBook(bookName)!!
        val bookView = dataRepository.getBookView(book.id)!!
        
        // Simulate creating attachment files for this note
        val appExternalDir = context.getExternalFilesDir("orgzly-books")!!
        val bookDirName = bookName.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        val bookFile = File(appExternalDir, "$bookDirName.org")
        val attachmentsDir = File(bookFile.parentFile, "attachments")
        val prefixDir = File(attachmentsDir, "ca") // First 2 chars of note ID
        val noteAttachDir = File(prefixDir, "effd7f-f678-486b-8ffa-7bac167f4e71") // Rest of note ID
        noteAttachDir.mkdirs()
        
        val attachmentFile = File(noteAttachDir, "test-file.txt")
        attachmentFile.writeText("This is test attachment content")
        assertTrue("Attachment file should exist before deletion", attachmentFile.exists())
        
        // Verify the query finds notes with attachments
        val notesWithAttachments = dataRepository.getNotesWithAttachments(book.id)
        assertEquals("Should find 1 note with attachments", 1, notesWithAttachments.size)
        
        // When - delete the book with attachment cleanup enabled
        dataRepository.deleteBook(bookView, false, true)
        
        // Then - attachment files should be cleaned up
        assertFalse("Attachment file should be deleted", attachmentFile.exists())
        assertFalse("Note attachment directory should be deleted", noteAttachDir.exists())
        assertFalse("Prefix directory should be deleted if empty", prefixDir.exists())
        
        // Verify book is actually deleted
        val deletedBook = dataRepository.getBook(bookName)
        assertEquals("Book should be deleted", null, deletedBook)
    }
    
    /**
     * Test that attachments are NOT cleaned up when a book is deleted with deleteAttachments=false.
     */
    @Test
    fun testBookDeletionPreservesAttachments() {
        // Given - create a book with a note that has an attachment
        val bookName = "test-book-preserve-attachments"
        val noteId = "caeffd7f-f678-486b-8ffa-7bac167f4e72"
        testUtils.setupBook(
            bookName,
            """
                * Note with attachment   :ATTACH:
                :PROPERTIES:
                :ID: $noteId
                :END:
                
                This note has an attachment: [[attachment:test-file.txt][test-file.txt]]

           """.trimIndent()
        )
        
        val book = dataRepository.getBook(bookName)!!
        val bookView = dataRepository.getBookView(book.id)!!
        
        // Simulate creating attachment files for this note
        val appExternalDir = context.getExternalFilesDir("orgzly-books")!!
        val bookDirName = bookName.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        val bookFile = File(appExternalDir, "$bookDirName.org")
        val attachmentsDir = File(bookFile.parentFile, "attachments")
        val prefixDir = File(attachmentsDir, "ca") // First 2 chars of note ID
        val noteAttachDir = File(prefixDir, "effd7f-f678-486b-8ffa-7bac167f4e72") // Rest of note ID
        noteAttachDir.mkdirs()
        
        val attachmentFile = File(noteAttachDir, "test-file.txt")
        attachmentFile.writeText("This is test attachment content")
        assertTrue("Attachment file should exist before deletion", attachmentFile.exists())
        
        // Verify the query finds notes with attachments
        val notesWithAttachments = dataRepository.getNotesWithAttachments(book.id)
        assertEquals("Should find 1 note with attachments", 1, notesWithAttachments.size)
        
        // When - delete the book with attachment cleanup disabled (default)
        dataRepository.deleteBook(bookView, false, false)
        
        // Then - attachment files should NOT be cleaned up
        assertTrue("Attachment file should still exist", attachmentFile.exists())
        assertTrue("Note attachment directory should still exist", noteAttachDir.exists())
        assertTrue("Prefix directory should still exist", prefixDir.exists())
        
        // Verify book is actually deleted
        val deletedBook = dataRepository.getBook(bookName)
        assertEquals("Book should be deleted", null, deletedBook)
        
        // Clean up the test files
        attachmentsDir.deleteRecursively()
    }
    
    /**
     * Test the booksHaveAttachments method works correctly.
     */
    @Test
    fun testBooksHaveAttachments() {
        // Given - create two books, one with attachments and one without
        val bookWithAttachments = "book-with-attachments"
        val bookWithoutAttachments = "book-without-attachments"
        val noteId = "test-note-id-123"
        
        testUtils.setupBook(
            bookWithAttachments,
            """
                * Note with attachment   :ATTACH:
                :PROPERTIES:
                :ID: $noteId
                :END:
                
                This note has an attachment: [[attachment:test.txt][test.txt]]
            """.trimIndent()
        )
        
        testUtils.setupBook(
            bookWithoutAttachments,
            """
                * Regular note
                
                This note has no attachments.
            """.trimIndent()
        )
        
        val bookWithAttachmentsObj = dataRepository.getBook(bookWithAttachments)!!
        val bookWithoutAttachmentsObj = dataRepository.getBook(bookWithoutAttachments)!!
        
        // When & Then - check individual books
        assertTrue(
            "Book with attachments should return true", 
            dataRepository.booksHaveAttachments(setOf(bookWithAttachmentsObj.id))
        )
        
        assertFalse(
            "Book without attachments should return false", 
            dataRepository.booksHaveAttachments(setOf(bookWithoutAttachmentsObj.id))
        )
        
        // When & Then - check multiple books
        assertTrue(
            "Multiple books where one has attachments should return true",
            dataRepository.booksHaveAttachments(setOf(
                bookWithAttachmentsObj.id, 
                bookWithoutAttachmentsObj.id
            ))
        )
        
        // When & Then - check empty set
        assertFalse(
            "Empty set should return false",
            dataRepository.booksHaveAttachments(emptySet())
        )
    }
}
