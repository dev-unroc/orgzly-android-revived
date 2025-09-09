package com.orgzly.android.ui.note

import android.os.Bundle
import android.text.TextUtils
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.orgzly.R
import com.orgzly.android.App
import com.orgzly.android.data.DataRepository
import com.orgzly.android.data.mappers.OrgMapper
import com.orgzly.android.db.entity.BookView
import com.orgzly.android.db.entity.Note
import com.orgzly.android.db.entity.NoteView
import com.orgzly.android.prefs.AppPreferences
import com.orgzly.android.ui.CommonViewModel
import com.orgzly.android.ui.NotePlace
import com.orgzly.android.ui.Place
import com.orgzly.android.ui.SingleLiveEvent
import com.orgzly.android.ui.main.MainActivity
import com.orgzly.android.usecase.BookScrollToNote
import com.orgzly.android.usecase.BookSparseTreeForNote
import com.orgzly.android.usecase.NoteCreate
import com.orgzly.android.usecase.NoteDelete
import com.orgzly.android.usecase.NoteUpdate
import com.orgzly.android.usecase.UseCaseRunner
import com.orgzly.android.util.LogUtils
import com.orgzly.android.util.MiscUtils
import com.orgzly.BuildConfig
import com.orgzly.org.OrgProperties
import com.orgzly.org.datetime.OrgRange
import com.orgzly.org.parser.OrgParserWriter

data class NoteInitialData(
    val bookId: Long,
    val noteId: Long, // Could be 0 if new note is being created
    val place: Place? = null, // Relative location, used for new notes
    val title: String? = null, // Initial title, used for when sharing
    val content: String? = null, // Initial content, used for when sharing
    val tags: List<String>? = null, // Initial tags, used for when sharing
    val properties: Map<String, String>? = null // Initial properties, used for when sharing
)

class NoteViewModel(
    private val dataRepository: DataRepository,
    private val initialData: NoteInitialData) : CommonViewModel() {

    var bookId = initialData.bookId
    var noteId = initialData.noteId
    private var place = initialData.place
    private val title = initialData.title
    private val content = initialData.content
    private val initialTags = initialData.tags
    private val initialProperties = initialData.properties

    val bookView: MutableLiveData<BookView?> = MutableLiveData()

    val tags: LiveData<List<String>> by lazy {
        dataRepository.selectAllTagsLiveData()
    }

    data class NoteDetailsData(val book: BookView?, val note: NoteView?, val ancestors: List<Note>)

    val noteDetailsDataEvent: SingleLiveEvent<NoteDetailsData> = SingleLiveEvent()

    val noteCreatedEvent: SingleLiveEvent<Note> = SingleLiveEvent()
    val noteUpdatedEvent: SingleLiveEvent<Note> = SingleLiveEvent()
    val noteDeletedEvent: SingleLiveEvent<Int> = SingleLiveEvent()

    val noteDeleteRequest: SingleLiveEvent<Int> = SingleLiveEvent()
    val bookChangeRequestEvent: SingleLiveEvent<List<BookView>> = SingleLiveEvent()

    var notePayload: NotePayload? = null

    private var originalHash: Long = 0L

    fun loadData() {
        App.EXECUTORS.diskIO().execute {
            if (BuildConfig.LOG_DEBUG) LogUtils.d(TAG, "loadData: noteId=$noteId, place=$place, isNew()=${isNew()}")
            
            val book = dataRepository.getBookView(bookId)

            val note = dataRepository.getNoteView(noteId)

            // If creating a new note under specific one include that note too
            val ancestors = if (place == Place.UNDER) {
                dataRepository.getNoteAndAncestors(noteId)
            } else {
                dataRepository.getNoteAncestors(noteId)
            }

            notePayload = if (isNew()) {
                if (BuildConfig.LOG_DEBUG) LogUtils.d(TAG, "loadData: Creating new payload with title='${title.orEmpty()}', content='$content', tags=$initialTags, properties=$initialProperties")
                NoteBuilder.newPayload(App.getAppContext(), title.orEmpty(), content, initialTags, initialProperties)
            } else {
                if (BuildConfig.LOG_DEBUG) LogUtils.d(TAG, "loadData: Loading existing payload from database for noteId=$noteId")
                dataRepository.getNotePayload(noteId)
            }

            // Calculate payload's hash once for the original note
            notePayload?.let { payload ->
                App.EXECUTORS.mainThread().execute {
                    if (originalHash == 0L) {
                        originalHash = notePayloadHash(payload)
                    }
                }
            }

            bookView.postValue(book)

            noteDetailsDataEvent.postValue(NoteDetailsData(book, note, ancestors))
        }
    }

    fun requestNoteDelete() {
        App.EXECUTORS.diskIO().execute {
            val count = dataRepository.getNotesAndSubtreesCount(setOf(noteId))
            noteDeleteRequest.postValue(count)
        }
    }

    fun deleteNote() {
        App.EXECUTORS.diskIO().execute {
            val useCase = NoteDelete(bookId, setOf(noteId))
            catchAndPostError {
                val result = UseCaseRunner.run(useCase)
                noteDeletedEvent.postValue(result.userData as Int)
            }
        }
    }

    fun requestNoteBookChange() {
        App.EXECUTORS.diskIO().execute {
            bookChangeRequestEvent.postValue(dataRepository.getBooks())
        }
    }

    fun savePayloadToBundle(outState: Bundle) {
        notePayload?.let {
            outState.putParcelable("payload", it)
        }
    }

    fun restorePayloadFromBundle(savedInstanceState: Bundle) {
        notePayload = savedInstanceState.getParcelable("payload") as? NotePayload
    }

    fun updatePayload(
        title: String,
        content: String,
        state: String?,
        priority: String?,
        tags: List<String>,
        properties: OrgProperties) {

        notePayload = notePayload?.copy(
            title = title,
            content = content,
            state = state,
            priority = priority,
            tags = tags,
            properties = properties)
    }

    fun updatePayloadState(state: String?) {
        notePayload?.let {
            notePayload = NoteBuilder.changeState(App.getAppContext(), it, state)
        }
    }

    fun updatePayloadScheduledTime(range: OrgRange?) {
        notePayload = notePayload?.copy(scheduled = range?.toString())
    }

    fun updatePayloadDeadlineTime(range: OrgRange?) {
        notePayload = notePayload?.copy(deadline = range?.toString())
    }

    fun updatePayloadClosedTime(range: OrgRange?) {
        notePayload = notePayload?.copy(closed = range?.toString())
    }

    private fun createNote(postSave: ((note: Note) -> Unit)?) {
        val notePlace = if (place != Place.UNSPECIFIED)
            NotePlace(bookId, noteId, place)
        else
            NotePlace(bookId)

        notePayload?.let { payload ->
            App.EXECUTORS.diskIO().execute {
                catchAndPostError {
                    val result = UseCaseRunner.run(NoteCreate(payload, notePlace))
                    val note = result.userData as Note

                    // Update note ID after creating note
                    noteId = note.id
                    
                    // Clear place to transition from "new note" to "existing note" mode
                    place = null

                    if (postSave != null) {
                        postSave(note)
                    } else {
                        noteCreatedEvent.postValue(note)
                    }
                }
            }
        }
    }

    private fun updateNote(postSave: ((note: Note) -> Unit)?) {
        notePayload?.let { payload ->
            App.EXECUTORS.diskIO().execute {
                catchAndPostError {
                    val result = UseCaseRunner.run(NoteUpdate(noteId, payload))
                    val note = result.userData as Note

                    if (postSave != null) {
                        postSave(note)
                    } else {
                        noteUpdatedEvent.postValue(note)
                    }
                }
            }
        }
    }

    /**
     * Hash used to detect note modifications.
     * TODO: Avoid generating org
     */
    private fun notePayloadHash(payload: NotePayload): Long {
        val head = OrgMapper.toOrgHead(payload)

        val parserWriter = OrgParserWriter()
        val str = parserWriter.whiteSpacedHead(head, 1, false)

        return MiscUtils.sha1(str)
    }

    fun isNoteModified(): Boolean {
        val payload = notePayload

        return if (payload != null) {
            notePayloadHash(payload) != originalHash
        } else {
            false
        }
    }

    fun isNew(): Boolean {
        return place != null
    }

    fun hasInitialTitleData(): Boolean {
        return !TextUtils.isEmpty(initialData.title)
    }

    fun setBook(b: BookView) {
        bookId = b.book.id
        bookView.value = b
    }

    fun followBookBreadcrumb() {
        App.EXECUTORS.diskIO().execute {
            catchAndPostError {
                UseCaseRunner.run(BookScrollToNote(noteId))
            }
        }
    }

    fun followNoteBreadcrumb(ancestor: Note) {
        when (AppPreferences.breadcrumbsTarget(App.getAppContext())) {
            "note_details" ->
                MainActivity.openSpecificNote(bookId, ancestor.id)

            "book_and_sparse_tree" ->
                App.EXECUTORS.diskIO().execute {
                    UseCaseRunner.run(BookSparseTreeForNote(ancestor.id))
                }

            "book_and_scroll" ->
                App.EXECUTORS.diskIO().execute {
                    UseCaseRunner.run(BookScrollToNote(ancestor.id))
                }

        }

    }

    fun saveNote(postSave: ((note: Note) -> Unit)? = null) {
        if (isBookSet() && isTitleValid()) {
            if (isNew()) {
                createNote(postSave)
            } else {
                updateNote(postSave)
            }
        }
    }

    private fun isTitleValid(): Boolean {
        return if (TextUtils.isEmpty(notePayload?.title)) {
            snackBarMessage.postValue(R.string.title_can_not_be_empty)
            false
        } else {
            true
        }
    }

    private fun isBookSet(): Boolean {
        return if (bookId == 0L) {
            snackBarMessage.postValue(R.string.note_book_not_set)
            false
        } else {
            true
        }
    }

    companion object {
        private val TAG = NoteViewModel::class.java.name
    }
}