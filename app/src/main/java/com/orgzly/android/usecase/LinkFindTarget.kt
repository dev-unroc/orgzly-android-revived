package com.orgzly.android.usecase

import com.orgzly.android.App
import com.orgzly.android.BookName
import com.orgzly.android.data.DataRepository
import com.orgzly.android.db.entity.BookView
import com.orgzly.android.prefs.AppPreferences
import com.orgzly.android.util.AttachmentManager
import java.io.File
import java.net.URLDecoder

class LinkFindTarget(val path: String, val noteId: String? = null, val bookFile: File? = null) : UseCase() {
    val context = App.getAppContext();

    override fun run(dataRepository: DataRepository): UseCaseResult {
        val target = openLink(dataRepository, path)

        return UseCaseResult(
                userData = target
        )
    }

    private fun openLink(dataRepository: DataRepository, path: String): Any {
        // First check if this is an attachment link
        if (path.startsWith("attachment:")) {
            // If we have book and note context, use it
            bookFile?.let { book ->
                val attachmentFile = AttachmentManager.resolveAttachmentLink(book, noteId, path)
                if (attachmentFile != null) {
                    return attachmentFile
                }
            }
            
            // If no context provided, try to find any note containing this attachment link
            val attachmentFile = findAttachmentFromDatabase(dataRepository, path)
            if (attachmentFile != null) {
                return attachmentFile
            }
        }

        // Don't treat attachment links as regular file paths - this prevents
        // creating incorrect paths like "/storage/emulated/0/attachment:filename"
        if (path.startsWith("attachment:")) {
            val filename = path.substring("attachment:".length)
            // Return a File that doesn't exist to trigger a "file not found" error with a more descriptive path
            return File("Attachment not found: $filename (note context missing)")
        }

        return if (isAbsolute(path)) {
            File(AppPreferences.fileAbsoluteRoot(context), path)
        } else {
            isMaybeBook(path)?.let { bookName ->
                dataRepository.getBook(bookName.name)?.let {
                    return it
                }
            }

            File(AppPreferences.fileRelativeRoot(context), path)
        }
    }

    private fun isAbsolute(path: String): Boolean {
        return path.startsWith('/')
    }

    private fun isMaybeBook(path: String): BookName? {
        val file = File(path)

        return if (!hasParent(file) && BookName.isSupportedFormatFileName(file.name)) {
            BookName.fromRepoRelativePath(file.name)
        } else {
            null
        }
    }

    private fun hasParent(file: File): Boolean {
        val parentFile = file.parentFile
        return parentFile != null && parentFile.name != "."
    }
    
    /**
     * Try to find an attachment by searching through all books using AttachmentManager.
     * This is more efficient than filesystem search and respects the attachment system structure.
     */
    private fun findAttachmentFromDatabase(dataRepository: DataRepository, path: String): File? {
        if (!path.startsWith("attachment:")) {
            return null
        }
        
        val filename = path.substring("attachment:".length)
        android.util.Log.d("LinkFindTarget", "findAttachmentFromDatabase: Looking for attachment file: $filename")
        
        // Use efficient search only in known attachment locations
        val result = findAttachmentInKnownLocations(filename)
        android.util.Log.d("LinkFindTarget", "findAttachmentFromDatabase: Found file: ${result?.absolutePath ?: "null"}")
        return result
    }
    
    /**
     * Limited filesystem search only in known attachment locations.
     */
    private fun findAttachmentInKnownLocations(filename: String): File? {
        android.util.Log.d("LinkFindTarget", "findAttachmentInKnownLocations: Searching for file: $filename")
        
        // Only search in the most likely locations for attachments
        val knownLocations = mutableListOf<String>()
        
        // App's external directory (most common location)
        val appExternalDir = context.getExternalFilesDir("orgzly-books")?.absolutePath
        appExternalDir?.let { knownLocations.add(it) }
        
        // App's internal directory (fallback)
        knownLocations.add(context.filesDir.absolutePath)
        
        // User-configured file roots (only if they exist)
        val relativeRoot = AppPreferences.fileRelativeRoot(context)
        val absoluteRoot = AppPreferences.fileAbsoluteRoot(context)
        if (relativeRoot.isNotEmpty() && File(relativeRoot).exists()) {
            knownLocations.add(relativeRoot)
        }
        if (absoluteRoot.isNotEmpty() && File(absoluteRoot).exists()) {
            knownLocations.add(absoluteRoot)
        }
        
        android.util.Log.d("LinkFindTarget", "findAttachmentInKnownLocations: Will search in ${knownLocations.size} known locations")
        
        for (rootPath in knownLocations) {
            android.util.Log.d("LinkFindTarget", "findAttachmentInKnownLocations: Checking: $rootPath")
            val rootDir = File(rootPath)
            
            // Look for attachments directory directly (no recursive search)
            val attachDir = File(rootDir, "attachments")
            if (attachDir.exists() && attachDir.isDirectory) {
                android.util.Log.d("LinkFindTarget", "findAttachmentInKnownLocations: Found attachments dir: ${attachDir.absolutePath}")
                
                // Search the hierarchical structure more efficiently
                val found = searchAttachmentInHierarchy(attachDir, filename)
                if (found != null) {
                    android.util.Log.d("LinkFindTarget", "findAttachmentInKnownLocations: FOUND: ${found.absolutePath}")
                    return found
                }
            }
        }
        
        android.util.Log.d("LinkFindTarget", "findAttachmentInKnownLocations: File not found: $filename")
        return null
    }
    
    /**
     * Search for attachment file in hierarchical structure within attachments directory.
     */
    private fun searchAttachmentInHierarchy(attachDir: File, filename: String): File? {
        // Look for the file in the hierarchical structure
        // First level: first 2 chars of note ID  
        val firstLevelDirs = attachDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
        android.util.Log.d("LinkFindTarget", "searchAttachmentInHierarchy: Found ${firstLevelDirs.size} first-level dirs in ${attachDir.name}")
        
        for (firstLevelDir in firstLevelDirs) {
            // Second level: full note ID directories
            val noteIdDirs = firstLevelDir.listFiles()?.filter { it.isDirectory } ?: emptyList()
            android.util.Log.d("LinkFindTarget", "searchAttachmentInHierarchy: Found ${noteIdDirs.size} note-ID dirs in ${firstLevelDir.name}")
            
            for (noteIdDir in noteIdDirs) {
                val attachmentFile = File(noteIdDir, filename)
                if (attachmentFile.exists()) {
                    android.util.Log.d("LinkFindTarget", "searchAttachmentInHierarchy: FOUND: ${attachmentFile.absolutePath}")
                    return attachmentFile
                }
            }
        }
        
        return null
    }
    
    /**
     * Get book file for attachments - similar logic to AttachmentSaveFiles.
     * This determines the base directory where attachment directories should be located.
     */
    private fun getBookFileForAttachments(dataRepository: DataRepository, bookView: BookView): File {
        // Try to determine the book file path
        // This is needed for creating attachments directories relative to the org file
        
        // If book has a synced location, try to use repo relative path
        if (bookView.syncedTo != null) {
            val repoRelativePath = BookName.getRepoRelativePath(bookView)
            
            // For local file repositories, construct the full path
            val repo = bookView.linkRepo
            if (repo != null) {
                when {
                    repo.url.startsWith("file:") -> {
                        val repoPath = repo.url.removePrefix("file:")
                        val bookFile = File(repoPath, repoRelativePath)
                        return bookFile
                    }
                    repo.url.startsWith("content://com.android.externalstorage.documents/tree/primary") -> {
                        // Handle document URIs for external storage
                        val path = repo.url.removePrefix("content://com.android.externalstorage.documents/tree/primary")
                        val decodedPath = URLDecoder.decode(path, "UTF-8")
                        val cleanPath = if (decodedPath.startsWith(":")) decodedPath.substring(1) else decodedPath
                        val repoPath = "/storage/emulated/0/$cleanPath"
                        val bookFile = File(repoPath, repoRelativePath)
                        return bookFile
                    }
                }
            }
        }
        
        // Fallback to app external directory
        val appExternalDir = context.getExternalFilesDir("orgzly-books") ?: context.filesDir
        val bookDirName = bookView.book.name.replace("[^a-zA-Z0-9_-]".toRegex(), "_")
        return File(appExternalDir, "$bookDirName.org")
    }
}
