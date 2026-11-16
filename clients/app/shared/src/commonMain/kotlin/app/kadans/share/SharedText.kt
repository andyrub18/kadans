package app.kadans.share

import app.kadans.api.TodosApi
import io.ktor.http.Url

/**
 * What a new todo starts from when another app shared a text (ARCHITECTURE → "Share to Kadans"): a title and a
 * description, both the person's to change before anything is saved.
 */
data class TodoDraft(val title: String, val description: String)

/**
 * A text another app shared, read as the start of a todo. Pure: the platform hands over what it was given (Android: a
 * share's subject and text, or a selection), and nothing here trusts it. It is cut to what a todo holds, and cleared of
 * what is not text.
 */
object SharedText {
    /** A title made from a shared text stops here, at a word: a title is a line, not the message. The rest is in the description. */
    const val SUGGESTED_TITLE = 120

    /** What is read of a shared text at all: far more than a todo holds, far less than would slow the phone down. */
    const val READ = 20_000

    /**
     * The draft for [subject] (a page's title, an email's subject; often absent) and [text] (a message, a selection, a
     * link); null when there is nothing in either.
     * - The title is the subject; without one, the text's first line; when that line is only a link, the site's name.
     * - The description is the text, unless the title already says all of it.
     */
    fun draft(subject: String?, text: String?): TodoDraft? {
        val said = clean(subject.orEmpty()).replace('\n', ' ').trim()
        val body = clean(text.orEmpty())
        if (said.isEmpty() && body.isEmpty()) return null

        val firstLine = body.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val site = siteOf(firstLine)
        val whole = said.ifEmpty { site ?: firstLine }
        val title = shortened(whole, SUGGESTED_TITLE)
        // Said in full by the title (a one-line text, or a subject that is the text): a description repeating it would
        // only be noise.
        val titleSaysAll = title == whole && body == whole
        return TodoDraft(within(title, TodosApi.TITLE_LENGTH), if (titleSaysAll) "" else shortened(body, TodosApi.DESCRIPTION_LENGTH))
    }

    /** [text], at most [max] characters long, never cut in the middle of a character that takes two (an emoji). */
    fun within(text: String, max: Int): String {
        if (text.length <= max) return text
        val end = if (text[max - 1].isHighSurrogate()) max - 1 else max
        return text.substring(0, end)
    }

    /** As [within], with "…" for what is left out, and cut at a word when one ends near the limit. */
    private fun shortened(text: String, max: Int): String {
        if (text.length <= max) return text
        val cut = within(text, max - 1)
        val word = cut.lastIndexOfAny(charArrayOf(' ', '\n'))
        return (if (word >= cut.length - WORD_SEARCH && word > 0) cut.substring(0, word) else cut).trimEnd() + "…"
    }

    /**
     * Text only: line ends as "\n", tabs as spaces, no control or invisible formatting characters (a shared text can
     * carry them; a title must not), no trailing spaces, at most one empty line in a row.
     */
    private fun clean(raw: String): String =
        within(raw, READ)
            .replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ')
            .filter { it == '\n' || !(it.isISOControl() || it in INVISIBLE) }
            .lineSequence().map { it.trimEnd() }.joinToString("\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    /** "nytimes.com" for a line that is nothing but an http(s) link; null for any other line. */
    private fun siteOf(line: String): String? {
        if (!LINK.matches(line)) return null
        return runCatching { Url(line).host.removePrefix("www.") }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    private const val WORD_SEARCH = 30
    private val LINK = Regex("https?://\\S+", RegexOption.IGNORE_CASE)

    /** Zero-width and direction marks: they show nothing and can make a text read as another. */
    private val INVISIBLE = setOf('​', '‌', '‍', '‎', '‏', '‪', '‫', '‬', '‭', '‮', '⁠', '⁦', '⁧', '⁨', '⁩', '﻿')
}
