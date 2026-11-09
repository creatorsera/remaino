package com.example.keyboard

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import java.io.File

class KeyboardService : InputMethodService(), KeyboardView.Listener {

    private lateinit var keyboardView: KeyboardView
    private lateinit var stats: TypingStats
    private lateinit var wordFreq: WordFrequencies
    private lateinit var bigrams: Bigrams
    private lateinit var hiddenWords: HiddenWords
    private val engine by lazy { SuggestionEngine(EnglishWords.load(this)) }
    private val composing = StringBuilder()

    /** Word-level undo/redo. Each entry is a committed word (with its trailing space). */
    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()
    private var lastWord = ""

    private var clipManager: ClipboardManager? = null
    private val clipListener = ClipboardManager.OnPrimaryClipChangedListener { captureClip() }
    private var lastClipText: String? = null
    private var lastClipUri: String? = null
    private var pendingClipText: String? = null
    private var pendingClipAt = 0L
    private lateinit var prefs: KeyboardPrefs

    private lateinit var floatingHost: FloatingHost
    private lateinit var mascotOverlay: MascotOverlay
    private var inputRoot: FrameLayout? = null
    private var hasCaret = false
    private var caretScreenX = -1f
    private var caretScreenTop = Float.NaN
    private var mascotVisibleNow = false
    private var mascotDeletingNow = false

    private val mainHandler = Handler(Looper.getMainLooper())

    private var sensitive = false
    private var useComposing = true
    private var enterAction = EditorInfo.IME_ACTION_NONE
    private var enterIsAction = false
    private var lastShiftTap = 0L

    override fun onCreate() {
        super.onCreate()
        stats = TypingStats(this)
        wordFreq = WordFrequencies.get(this)
        bigrams = Bigrams(this)
        hiddenWords = HiddenWords(this)
        prefs = KeyboardPrefs(this)
        floatingHost = FloatingHost(this, prefs)
        mascotOverlay = MascotOverlay(this)
        clipManager = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipManager?.addPrimaryClipChangedListener(clipListener)
    }

    override fun onDestroy() {
        clipManager?.removePrimaryClipChangedListener(clipListener)
        mascotOverlay.hide()
        if (::keyboardView.isInitialized) floatingHost.hide(keyboardView)
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        // Transparent window so the rounded top corners show the app behind.
        window?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        if (!::keyboardView.isInitialized) {
            keyboardView = KeyboardView(this).also { it.listener = this }
        }
        if (floatingHost.isShowing) floatingHost.hide(keyboardView)
        (keyboardView.parent as? ViewGroup)?.removeView(keyboardView)
        val root = FrameLayout(this)
        root.addView(
            keyboardView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        inputRoot = root
        return root
    }

    /**
     * Floating mode draws the keyboard in an overlay window and leaves a one-pixel strip as the
     * real keyboard window. Normal mode puts the keyboard back in the real window.
     */
    private fun applyHostMode() {
        val root = inputRoot ?: return
        val wantFloating = prefs.floatingKeyboard && floatingHost.canUse()
        if (wantFloating) {
            if (!floatingHost.isShowing) {
                (keyboardView.parent as? ViewGroup)?.removeView(keyboardView)
                root.removeAllViews()
                root.addView(View(this), FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1))
                if (!floatingHost.show(keyboardView)) {
                    root.removeAllViews()
                    root.addView(
                        keyboardView,
                        FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                    )
                    keyboardView.setFloating(false)
                    return
                }
            }
            keyboardView.dragListener = { dx, dy -> floatingHost.move(dx, dy) }
            keyboardView.dragEndListener = { floatingHost.savePosition() }
            keyboardView.setFloating(true)
        } else {
            if (floatingHost.isShowing) floatingHost.hide(keyboardView)
            if (keyboardView.parent !== root) {
                (keyboardView.parent as? ViewGroup)?.removeView(keyboardView)
                root.removeAllViews()
                root.addView(
                    keyboardView,
                    FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                )
            }
            keyboardView.setFloating(false)
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (info == null || !::keyboardView.isInitialized) return
        composing.clear()
        undoStack.clear()
        redoStack.clear()
        lastWord = ""

        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val noLearning = (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        sensitive = isPasswordField(cls, variation) || noLearning ||
            prefs.isSensitivePackage(info.packageName)
        useComposing = !sensitive && !isEmailOrUri(cls, variation)

        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val multiLine = (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        val noEnterAction = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        enterAction = action
        enterIsAction = !multiLine && !noEnterAction &&
            action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED

        val options = KeyboardPrefs(this)
        applyHostMode()
        hasCaret = false
        caretScreenTop = Float.NaN
        keyboardView.setOverlayMascot(false)
        currentInputConnection?.requestCursorUpdates(InputConnection.CURSOR_UPDATE_MONITOR)
        keyboardView.applySettings(options)
        updateMascotOverlay()
        keyboardView.closeTray()
        keyboardView.setSensitive(sensitive)
        keyboardView.setFieldEmpty(isFieldEmpty())
        keyboardView.setSymbols(
            cls == InputType.TYPE_CLASS_NUMBER ||
                cls == InputType.TYPE_CLASS_PHONE ||
                cls == InputType.TYPE_CLASS_DATETIME,
        )
        keyboardView.setShift(if (shouldAutoCapitalize(info, cls)) ShiftState.ONE_SHOT else ShiftState.OFF)
        refreshSuggestions()
        syncClipboardOnOpen()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        finishWord()
        mascotOverlay.hide()
        mascotVisibleNow = false
        currentInputConnection?.requestCursorUpdates(0)
        if (::keyboardView.isInitialized && floatingHost.isShowing) floatingHost.hide(keyboardView)
        super.onFinishInputView(finishingInput)
    }

    private fun isPasswordField(cls: Int, variation: Int): Boolean {
        if (cls == InputType.TYPE_CLASS_TEXT) {
            return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        }
        if (cls == InputType.TYPE_CLASS_NUMBER) {
            return variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        return false
    }

    private fun isEmailOrUri(cls: Int, variation: Int): Boolean =
        cls == InputType.TYPE_CLASS_TEXT && (
            variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_URI
            )

    private fun shouldAutoCapitalize(info: EditorInfo, cls: Int): Boolean {
        if (cls != InputType.TYPE_CLASS_TEXT || !useComposing) return false
        val ic = currentInputConnection ?: return false
        val caps = ic.getCursorCapsMode(info.inputType) ?: 0
        if (caps == 0) return false
        val before = ic.getTextBeforeCursor(1, 0)
        val atStart = before.isNullOrEmpty()
        return if (atStart) prefs.capitalizeFirstLetter else prefs.autoCapitalize
    }

    private fun isFieldEmpty(): Boolean {
        val ic = currentInputConnection ?: return true
        return ic.getTextBeforeCursor(1, 0).isNullOrEmpty()
    }

    private fun updateFieldEmpty() {
        if (::keyboardView.isInitialized) keyboardView.setFieldEmpty(isFieldEmpty())
    }

    private fun nextAutoShift(): ShiftState =
        if (prefs.autoCapitalize) ShiftState.ONE_SHOT else ShiftState.OFF

    private fun titleCase(text: String): String {
        val lower = text.lowercase()
        if (lower.isEmpty()) return lower
        return lower.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
    }

    // ---------- KeyboardView.Listener ----------

    override fun onText(text: String) {
        val ic = currentInputConnection ?: return
        val ch = text.firstOrNull() ?: return
        // Typing always hides Reo and any "paste this" pill.
        keyboardView.dismissCopiedChip()
        keyboardView.setFieldEmpty(false)
        if (useComposing && (ch.isLetter() || (ch == '\'' && composing.isNotEmpty()))) {
            composing.append(text)
            ic.setComposingText(composing.toString(), 1)
        } else {
            finishWord()
            ic.commitText(text, 1)
        }
        refreshSuggestions()
        updateFieldEmpty()
    }

    /**
     * Shift with a selection cycles the case of the selected text:
     * lowercase -> Title -> UPPERCASE -> lowercase. A double tap jumps to UPPERCASE.
     * With nothing selected: one tap capitalises the next letter, a quick second tap
     * locks caps, and another tap turns shift off.
     */
    override fun onShift() {
        val ic = currentInputConnection
        val selected = try {
            ic?.getSelectedText(0)?.toString()
        } catch (e: Exception) {
            null
        }
        val now = SystemClock.uptimeMillis()
        val doubleTap = now - lastShiftTap < 350L
        lastShiftTap = now
        if (!selected.isNullOrEmpty()) {
            val lower = selected.lowercase()
            val upper = selected.uppercase()
            val title = titleCase(selected)
            val next = when {
                doubleTap -> upper
                selected == upper && upper != lower -> lower
                selected == title -> upper
                else -> title
            }
            ic?.commitText(next, 1)
            keyboardView.setShift(ShiftState.OFF)
            return
        }
        val next = when (keyboardView.shiftState) {
            ShiftState.OFF -> ShiftState.ONE_SHOT
            ShiftState.ONE_SHOT -> if (doubleTap) ShiftState.CAPS else ShiftState.OFF
            ShiftState.CAPS -> ShiftState.OFF
        }
        keyboardView.setShift(next)
    }

    override fun onBackspace() {
        val ic = currentInputConnection ?: return
        keyboardView.dismissCopiedChip()
        if (composing.isNotEmpty()) {
            composing.setLength(composing.length - 1)
            if (composing.isEmpty()) {
                ic.commitText("", 1)
            } else {
                ic.setComposingText(composing.toString(), 1)
            }
            refreshSuggestions()
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }
        updateFieldEmpty()
    }

    override fun onSpace() {
        val ic = currentInputConnection ?: return
        keyboardView.dismissCopiedChip()
        finishWord()
        val before = ic.getTextBeforeCursor(2, 0)
        val doubleSpace = useComposing && before != null && before.length == 2 &&
            before[1] == ' ' && before[0].isLetterOrDigit()
        if (doubleSpace) {
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
            keyboardView.setShift(nextAutoShift())
        } else {
            ic.commitText(" ", 1)
            val after = ic.getTextBeforeCursor(2, 0)
            if (useComposing && after != null && after.length == 2 && after[1] == ' ' &&
                (after[0] == '.' || after[0] == '!' || after[0] == '?')
            ) {
                keyboardView.setShift(nextAutoShift())
            }
        }
        keyboardView.setFieldEmpty(false)
        refreshSuggestions()
    }

    override fun onEnter() {
        val ic = currentInputConnection ?: return
        keyboardView.dismissCopiedChip()
        finishWord()
        if (enterIsAction) {
            ic.performEditorAction(enterAction)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            if (useComposing) keyboardView.setShift(nextAutoShift())
        }
        keyboardView.setFieldEmpty(false)
        refreshSuggestions()
    }

    override fun onSuggestion(word: String) {
        val ic = currentInputConnection ?: return
        keyboardView.dismissCopiedChip()
        if (word.isNotEmpty() && word.none { it.isLetterOrDigit() }) {
            // Emoji chip: insert as an emoji, not as a word with a trailing space.
            composing.setLength(0)
            ic.finishComposingText()
            ic.commitText(EmojiData.withTone(word, prefs.emojiTone), 1)
            keyboardView.setFieldEmpty(false)
            refreshSuggestions()
            return
        }
        val typed = composing.toString()
        composing.setLength(0)
        val out = if (typed.isNotEmpty() && typed[0].isUpperCase()) {
            word.replaceFirstChar { it.uppercase() }
        } else {
            word
        }
        ic.commitText("$out ", 1)
        countWord(word)
        keyboardView.setFieldEmpty(false)
        refreshSuggestions()
    }

    override fun onEmoji(emoji: String) {
        val ic = currentInputConnection ?: return
        keyboardView.dismissCopiedChip()
        finishWord()
        ic.commitText(EmojiData.withTone(emoji, prefs.emojiTone), 1)
        keyboardView.setFieldEmpty(false)
        refreshSuggestions()
    }

    /** Long-press on a suggestion chip: remove it and remember that choice. */
    override fun onSuggestionLongPress(word: String) {
        if (sensitive) return
        val clean = word.trim()
        if (clean.length < 1 || clean.none { it.isLetter() }) return
        if (!hiddenWords.hide(clean)) return
        wordFreq.forget(clean)
        Toast.makeText(
            this,
            "Removed \"$clean\" from suggestions",
            Toast.LENGTH_SHORT,
        ).show()
        refreshSuggestions()
    }

    override fun onPaste(text: String) {
        val ic = currentInputConnection ?: return
        keyboardView.dismissCopiedChip()
        finishWord()
        ic.commitText(text, 1)
        keyboardView.setFieldEmpty(false)
        refreshSuggestions()
    }

    override fun onGlobe() {
        finishWord()
        if (!switchToNextInputMethod(false)) {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }
    }

    override fun onDeleteWord() {
        val ic = currentInputConnection ?: return
        keyboardView.dismissCopiedChip()
        finishWord()
        val before = ic.getTextBeforeCursor(60, 0)?.toString() ?: return
        if (before.isEmpty()) return
        // If cursor is in the middle of a word (no space in text before cursor),
        // delete from the start of that word to the cursor position
        val lastSpaceIdx = before.lastIndexOf(' ')
        if (lastSpaceIdx < 0) {
            // No space found - cursor is in middle of a word or at text start
            // Delete from beginning of word (or start) to cursor
            val wordStart = if (lastSpaceIdx < 0) 0 else lastSpaceIdx + 1
            ic.deleteSurroundingText(before.length - wordStart, 0)
        } else {
            // Cursor is after a space - delete the word at cursor position
            val trimmed = before.trimEnd()
            if (trimmed.isEmpty()) {
                ic.deleteSurroundingText(before.length, 0)
            } else {
                val lastSpace = trimmed.lastIndexOf(' ')
                val count = before.length - (lastSpace + 1)
                if (count > 0) ic.deleteSurroundingText(count, 0)
                else ic.deleteSurroundingText(before.length - (lastSpace + 1), 0)
            }
        }
        lastWord = ""
        keyboardView.setFieldEmpty(isFieldEmpty())
        refreshSuggestions()
    }

    override fun onToolbarAction(action: ToolbarAction) {
        when (action) {
            ToolbarAction.SETTINGS -> {
                val intent = Intent(this, SettingsActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
            ToolbarAction.LANGUAGE -> onGlobe()
            ToolbarAction.UNDO -> undoWord()
            ToolbarAction.REDO -> redoWord()
            ToolbarAction.MENU, ToolbarAction.CLOSE -> Unit
            else -> Toast.makeText(this, "Coming soon", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- stickers, pasted images, cursor and mascot ----------

    /** Sends an image file to the app through the keyboard image API. Returns true if it was accepted. */
    private fun commitImage(file: File, mime: String): Boolean {
        val info = currentInputEditorInfo
        val ic = currentInputConnection
        if (info == null || ic == null) return false
        val accepted = EditorInfoCompat.getContentMimeTypes(info).any { ClipDescription.compareMimeTypes(mime, it) }
        if (!accepted) {
            Toast.makeText(this, "This app does not accept images from the keyboard.", Toast.LENGTH_SHORT).show()
            return false
        }
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val content = InputContentInfoCompat(uri, ClipDescription("image", arrayOf(mime)), null)
        return InputConnectionCompat.commitContent(
            ic, info, content, InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION, null,
        )
    }

    override fun onStickerSend(text: String, palette: Int, style: Int): Boolean {
        return try {
            val file = StickerMaker.save(this, StickerMaker.render(text, palette, style))
            commitImage(file, "image/png")
        } catch (e: Exception) {
            Toast.makeText(this, "Could not make the sticker.", Toast.LENGTH_SHORT).show()
            false
        }
    }

    override fun onPasteImage(item: ClipItem) {
        val encoded = item.image ?: return
        Thread {
            try {
                val bytes = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP)
                val mime = sniffMime(bytes)
                val ext = mime.substringAfter('/')
                val dir = File(cacheDir, "pasted")
                dir.mkdirs()
                dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(10)?.forEach { it.delete() }
                val file = File(dir, "clip_${item.time}.$ext")
                file.writeBytes(bytes)
                mainHandler.post { commitImage(file, mime) }
            } catch (e: Exception) {
                mainHandler.post {
                    Toast.makeText(this, "Could not paste that image.", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun sniffMime(b: ByteArray): String = when {
        b.size > 3 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() -> "image/png"
        b.size > 2 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "image/jpeg"
        b.size > 3 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() -> "image/gif"
        b.size > 11 && b[8] == 'W'.code.toByte() && b[9] == 'E'.code.toByte() -> "image/webp"
        else -> "image/png"
    }

    override fun onUpdateCursorAnchorInfo(cursorAnchorInfo: CursorAnchorInfo?) {
        super.onUpdateCursorAnchorInfo(cursorAnchorInfo)
        val info = cursorAnchorInfo ?: return
        if (info.insertionMarkerTop.isNaN()) return
        // Some apps omit the horizontal marker; keep the last known x in that case.
        val horizontal = if (info.insertionMarkerHorizontal.isNaN()) caretScreenX else info.insertionMarkerHorizontal
        val points = floatArrayOf(horizontal, info.insertionMarkerTop)
        info.matrix.mapPoints(points)
        caretScreenX = points[0]
        caretScreenTop = points[1]
        hasCaret = true
        if (::keyboardView.isInitialized) keyboardView.setCaretX(caretScreenX - floatingHost.offsetX)
        updateMascotOverlay()
    }

    override fun onMascotState(visible: Boolean, deleting: Boolean) {
        mascotVisibleNow = visible
        mascotDeletingNow = deleting
        updateMascotOverlay()
    }

    /** Draws the mascot on the real caret when the user chose "At cursor" and allowed overlays. */
    private fun updateMascotOverlay() {
        if (!::keyboardView.isInitialized) return
        // Decide this before layout so the band is never reserved (and then removed) on CURSOR.
        val wanted = prefs.mascot && prefs.mascotPlacement == MascotPlacement.CURSOR &&
            mascotOverlay.canShow()
        keyboardView.setOverlayMascot(wanted)
        if (!wanted || !mascotVisibleNow || !hasCaret || caretScreenTop.isNaN()) {
            mascotOverlay.hide()
            return
        }
        val dm = resources.displayMetrics
        val size = mascotOverlay.sizePx
        val offsetX = prefs.mascotOffsetX * dm.density
        val offsetY = prefs.mascotOffsetY * dm.density
        val x = (caretScreenX + 16 * dm.density - size / 2f + offsetX).toInt()
        val y = (caretScreenTop + 12 * dm.density - size / 2f + offsetY).toInt()
        mascotOverlay.show(x, y, mascotDeletingNow)
    }

    // ---------- helpers ----------

    private fun currentClip(): android.content.ClipData? = try {
        clipManager?.primaryClip
    } catch (e: SecurityException) {
        null
    }

    private fun isSensitiveClip(clip: android.content.ClipData): Boolean =
        clip.description?.extras
            ?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true

    /** Saves a live copy to encrypted history. Skips sensitive and private content. */
    private fun captureClip() {
        if (sensitive || !prefs.clipboardEnabled || !isInputViewShown) return
        val clip = currentClip() ?: return
        if (clip.itemCount == 0 || isSensitiveClip(clip)) return
        val item = clip.getItemAt(0)
        val uri = item.uri
        if (uri != null && prefs.clipboardImages) {
            captureImage(uri)
            return
        }
        val text = item.coerceToText(this)?.toString() ?: return
        if (text.isBlank() || text.length > 5000 || text == lastClipText) return
        lastClipText = text
        ClipboardStore.get(this).add(text)
        rememberClip(text)
    }

    /**
     * When a field opens, pick up whatever was copied while the keyboard was closed.
     * This is also what makes the "COPIED / Paste" pill reliable: it is queued here
     * instead of only being shown at the exact moment of the copy.
     */
    private fun syncClipboardOnOpen() {
        if (!::keyboardView.isInitialized) return
        if (sensitive || !prefs.clipboardEnabled) {
            pendingClipText = null
            return
        }
        val clip = currentClip()
        if (clip != null && clip.itemCount > 0 && !isSensitiveClip(clip)) {
            val item = clip.getItemAt(0)
            val uri = item.uri
            if (uri != null && prefs.clipboardImages) {
                if (uri.toString() != lastClipUri) captureImage(uri)
            } else {
                val text = item.coerceToText(this)?.toString()?.takeIf {
                    it.isNotBlank() && it.length <= 5000
                }
                if (text != null && text != lastClipText) {
                    lastClipText = text
                    ClipboardStore.get(this).add(text)
                    rememberClip(text)
                }
            }
        }
        val pending = pendingClipText ?: return
        val fresh = System.currentTimeMillis() - pendingClipAt < 10 * 60 * 1000L
        if (fresh) {
            keyboardView.showCopiedChip(pending)
        } else {
            pendingClipText = null
        }
    }

    private fun rememberClip(text: String) {
        pendingClipText = text
        pendingClipAt = System.currentTimeMillis()
        lastClipText = text
        if (::keyboardView.isInitialized) {
            keyboardView.refreshClipboard()
            if (isInputViewShown) keyboardView.showCopiedChip(text)
        }
    }

    /** Reads an image clip (max 10 MB) and stores it encrypted in the clipboard history. */
    private fun captureImage(uri: android.net.Uri) {
        if (uri.toString() == lastClipUri) return
        lastClipUri = uri.toString()
        Thread {
            try {
                val bytes = contentResolver.openInputStream(uri)?.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val read = input.read(buf)
                        if (read < 0) break
                        total += read
                        if (total > 10 * 1024 * 1024) return@Thread
                        out.write(buf, 0, read)
                    }
                    out.toByteArray()
                } ?: return@Thread
                if (bytes.isEmpty()) return@Thread
                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                ClipboardStore.get(this).addImage(base64)
                mainHandler.post {
                    rememberClip("Image copied")
                }
            } catch (e: Exception) {
                // Ignore unreadable images.
            }
        }.start()
    }

    private fun finishWord() {
        if (composing.isEmpty()) return
        val word = composing.toString()
        composing.setLength(0)
        currentInputConnection?.finishComposingText()
        countWord(word)
    }

    /** Counts only numbers. Nothing is counted in password or private fields. */
    private fun countWord(word: String) {
        if (sensitive) return
        stats.addWord()
        wordFreq.increment(word)
        val clean = word.trim().lowercase()
        if (clean.isNotEmpty() && !SuggestionFilter.isBlocked(clean)) {
            if (lastWord.isNotEmpty()) bigrams.add(lastWord, clean)
            lastWord = clean
            undoStack.addLast(word)
            if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
            redoStack.clear()
        }
    }

    /**
     * Deletes the last committed word (and any trailing space) and remembers the
     * exact removed text for redo. If the history was lost, it still removes the
     * word before the cursor instead of doing nothing.
     */
    private fun undoWord() {
        val ic = currentInputConnection ?: return
        keyboardView.dismissCopiedChip()
        if (composing.isNotEmpty()) {
            composing.setLength(0)
            ic.finishComposingText()
        }
        val before = ic.getTextBeforeCursor(160, 0)?.toString().orEmpty()
        if (before.isEmpty()) return
        val word = undoStack.removeLastOrNull()
        val delete = when {
            word != null && before.endsWith("$word ", ignoreCase = true) -> word.length + 1
            word != null && before.endsWith(word, ignoreCase = true) -> word.length
            else -> Editing.trailingTokenLength(before)
        }
        if (delete <= 0) return
        val removed = before.takeLast(delete)
        ic.deleteSurroundingText(delete, 0)
        redoStack.addLast(removed)
        lastWord = undoStack.lastOrNull()?.trim()?.lowercase().orEmpty()
        keyboardView.setFieldEmpty(isFieldEmpty())
        refreshSuggestions()
    }

    private fun redoWord() {
        val ic = currentInputConnection ?: return
        val text = redoStack.removeLastOrNull() ?: return
        ic.commitText(text, 1)
        val word = text.trim().lowercase()
        if (word.isNotEmpty()) {
            undoStack.addLast(word)
            if (undoStack.size > MAX_UNDO) undoStack.removeFirst()
        }
        lastWord = word
        keyboardView.dismissCopiedChip()
        keyboardView.setFieldEmpty(false)
        refreshSuggestions()
    }

    private fun refreshSuggestions() {
        if (!::keyboardView.isInitialized) return
        if (!useComposing) {
            keyboardView.setSuggestions(emptyList())
            return
        }
        val typed = composing.toString()
        keyboardView.setComposing(typed.isNotEmpty())
        val hidden = hiddenWords.snapshot()
        val list = if (typed.isEmpty()) {
            val next = if (prefs.nextWordSuggestions && lastWord.isNotEmpty()) {
                bigrams.followers(lastWord, 2).filterNot { it in hidden }
            } else {
                emptyList()
            }
            val emoji = if (prefs.emojiPrediction && lastWord.isNotEmpty()) {
                EmojiPredict.forWord(lastWord)
            } else {
                null
            }
            val base = SuggestionEngine.DEFAULTS.filterNot { it.lowercase() in hidden }
            val merged = (next + listOfNotNull(emoji) + base)
                .distinct()
            if (prefs.blockInappropriate) SuggestionFilter.clean(merged) else merged
        } else {
            val english = engine.suggest(typed, wordFreq.snapshot(), 2, hidden)
            val completions = (english + RomanUrdu.complete(typed, 2))
                .filterNot { it in hidden }
                .distinct()
                .take(2)
            val urdu = RomanUrdu.lookup(typed)
            val raw = if (urdu != null) {
                listOf(typed) + completions.take(1) + urdu
            } else {
                listOf(typed) + completions
            }
            if (prefs.blockInappropriate) SuggestionFilter.clean(raw) else raw
        }
        keyboardView.setSuggestions(list)
    }

    private companion object {
        const val MAX_UNDO = 40
    }
}
