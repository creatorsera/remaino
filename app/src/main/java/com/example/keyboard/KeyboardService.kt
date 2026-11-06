package com.example.keyboard

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Toast

class KeyboardService : InputMethodService(), KeyboardView.Listener {

    private lateinit var keyboardView: KeyboardView
    private lateinit var stats: TypingStats
    private lateinit var wordFreq: WordFrequencies
    private val engine = SuggestionEngine()
    private val composing = StringBuilder()

    private var sensitive = false
    private var useComposing = true
    private var enterAction = EditorInfo.IME_ACTION_NONE
    private var enterIsAction = false

    override fun onCreate() {
        super.onCreate()
        stats = TypingStats(this)
        wordFreq = WordFrequencies.get(this)
    }

    override fun onCreateInputView(): View {
        // Transparent window so the rounded top corners show the app behind.
        window?.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        keyboardView = KeyboardView(this).also { it.listener = this }
        return keyboardView
    }

    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (info == null || !::keyboardView.isInitialized) return
        composing.clear()

        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val noLearning = (info.imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
        sensitive = isPasswordField(cls, variation) || noLearning
        useComposing = !sensitive && !isEmailOrUri(cls, variation)

        val action = info.imeOptions and EditorInfo.IME_MASK_ACTION
        val multiLine = (info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
        val noEnterAction = (info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        enterAction = action
        enterIsAction = !multiLine && !noEnterAction &&
            action != EditorInfo.IME_ACTION_NONE &&
            action != EditorInfo.IME_ACTION_UNSPECIFIED

        val options = KeyboardPrefs(this)
        keyboardView.applySettings(options.showNumberRow, options.keyPreview)
        keyboardView.closeTray()
        keyboardView.setSensitive(sensitive)
        keyboardView.setSymbols(
            cls == InputType.TYPE_CLASS_NUMBER ||
                cls == InputType.TYPE_CLASS_PHONE ||
                cls == InputType.TYPE_CLASS_DATETIME,
        )
        keyboardView.setShift(if (shouldAutoCapitalize(info, cls)) ShiftState.ONE_SHOT else ShiftState.OFF)
        refreshSuggestions()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        finishWord()
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
        val caps = currentInputConnection?.getCursorCapsMode(info.inputType) ?: 0
        return caps != 0
    }

    // ---------- KeyboardView.Listener ----------

    override fun onText(text: String) {
        val ic = currentInputConnection ?: return
        val ch = text.firstOrNull() ?: return
        if (useComposing && (ch.isLetter() || (ch == '\'' && composing.isNotEmpty()))) {
            composing.append(text)
            ic.setComposingText(composing.toString(), 1)
        } else {
            finishWord()
            ic.commitText(text, 1)
        }
        refreshSuggestions()
    }

    override fun onBackspace() {
        val ic = currentInputConnection ?: return
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
    }

    override fun onSpace() {
        val ic = currentInputConnection ?: return
        finishWord()
        val before = ic.getTextBeforeCursor(2, 0)
        val doubleSpace = useComposing && before != null && before.length == 2 &&
            before[1] == ' ' && before[0].isLetterOrDigit()
        if (doubleSpace) {
            ic.deleteSurroundingText(1, 0)
            ic.commitText(". ", 1)
            keyboardView.setShift(ShiftState.ONE_SHOT)
        } else {
            ic.commitText(" ", 1)
            val after = ic.getTextBeforeCursor(2, 0)
            if (useComposing && after != null && after.length == 2 && after[1] == ' ' &&
                (after[0] == '.' || after[0] == '!' || after[0] == '?')
            ) {
                keyboardView.setShift(ShiftState.ONE_SHOT)
            }
        }
        refreshSuggestions()
    }

    override fun onEnter() {
        val ic = currentInputConnection ?: return
        finishWord()
        if (enterIsAction) {
            ic.performEditorAction(enterAction)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            if (useComposing) keyboardView.setShift(ShiftState.ONE_SHOT)
        }
        refreshSuggestions()
    }

    override fun onSuggestion(word: String) {
        val ic = currentInputConnection ?: return
        val typed = composing.toString()
        composing.setLength(0)
        val out = if (typed.isNotEmpty() && typed[0].isUpperCase()) {
            word.replaceFirstChar { it.uppercase() }
        } else {
            word
        }
        ic.commitText("$out ", 1)
        countWord(word)
        refreshSuggestions()
    }

    override fun onEmoji(emoji: String) {
        val ic = currentInputConnection ?: return
        finishWord()
        ic.commitText(emoji, 1)
        refreshSuggestions()
    }

    override fun onGlobe() {
        finishWord()
        if (!switchToNextInputMethod(false)) {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }
    }

    override fun onToolbarAction(action: ToolbarAction) {
        when (action) {
            ToolbarAction.SETTINGS -> {
                val intent = Intent(this, SettingsActivity::class.java)
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            }
            ToolbarAction.LANGUAGE -> onGlobe()
            ToolbarAction.MENU, ToolbarAction.CLOSE -> Unit
            else -> Toast.makeText(this, "Coming soon", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- helpers ----------

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
    }

    private fun refreshSuggestions() {
        if (!::keyboardView.isInitialized) return
        if (!useComposing) {
            keyboardView.setSuggestions(emptyList())
            return
        }
        val typed = composing.toString()
        val list = if (typed.isEmpty()) {
            SuggestionEngine.DEFAULTS
        } else {
            listOf(typed) + engine.suggest(typed, wordFreq.snapshot(), 2)
        }
        keyboardView.setSuggestions(list)
    }
}
