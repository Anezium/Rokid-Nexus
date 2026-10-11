package com.anezium.rokidbus.plugin.assistant

import java.text.Normalizer
import java.util.Locale

internal enum class WorkspaceResolutionState { RESOLVED, AMBIGUOUS, MISSING, UNAVAILABLE, NONE }

/**
 * The file a question names, resolved before the first model request. [nameTerms] are the
 * tokenizer terms of the words that named it, removed from ranking inside that file. Choices are
 * metadata only and never grant access.
 */
internal data class WorkspaceResolution(
    val state: WorkspaceResolutionState,
    val document: WorkspaceDocument? = null,
    val nameTerms: Set<String> = emptySet(),
    val choices: List<WorkspaceDocument> = emptyList(),
    val moreChoices: Int = 0,
) {
    companion object {
        val NONE = WorkspaceResolution(WorkspaceResolutionState.NONE)
    }
}

/**
 * Names are compared on their own normalization, independent of the search tokenizer: every word
 * is kept, case and accents fold deliberately, and folding that makes two names equal leaves them
 * ambiguous. Nothing is fuzzy: a misspelt or partial name, or a name inside a longer file name, does
 * not resolve, and person names are never inferred from a file name.
 */
internal object WorkspaceFileResolver {
    fun words(text: String): List<String> = WORDS.findAll(
        Normalizer.normalize(text, Normalizer.Form.NFKD).replace(MARKS, "").lowercase(Locale.ROOT)
            .replace("œ", "oe").replace("æ", "ae"),
    ).map { it.value }.toList()

    private data class Match(val document: WorkspaceDocument, val strength: Int, val start: Int, val end: Int)

    fun resolve(question: String, documents: List<WorkspaceDocument>): WorkspaceResolution {
        val asked = words(question.take(MAX_QUESTION_CHARS))
        if (asked.isEmpty()) return WorkspaceResolution.NONE
        val named = documents.flatMap { document -> matches(asked, document) }
        // A missing explicit name must not resolve an incidental bare name such as "code".
        val matches = if (namesMissingFile(question, asked)) named.filter { match ->
            match.strength >= 2 || asked.getOrNull(match.start - 1) in FILE_CUES ||
                asked.getOrNull(match.end) in FILE_CUES
        } else named
        val strongest = matches.maxOfOrNull { it.strength }
            ?: return if (namesMissingFile(question, asked)) WorkspaceResolution(WorkspaceResolutionState.MISSING)
            else WorkspaceResolution.NONE
        val best = matches.filter { it.strength == strongest }
        val candidates = best.map { it.document }.distinctBy { it.entry.documentId }
        if (candidates.size > 1) {
            return WorkspaceResolution(WorkspaceResolutionState.AMBIGUOUS,
                choices = candidates.take(WorkspaceLimits.MAX_CHOICES),
                moreChoices = (candidates.size - WorkspaceLimits.MAX_CHOICES).coerceAtLeast(0))
        }
        val document = candidates.single()
        val match = best.first()
        // A shortened, disambiguated, or not yet reconciled label cannot prove which file was meant.
        if (!document.lookupSafe) return WorkspaceResolution(WorkspaceResolutionState.UNAVAILABLE, document)
        val nameTerms = WorkspaceTokenizer.tokens(asked.subList(match.start, match.end).joinToString(" ")).toSet()
        return WorkspaceResolution(WorkspaceResolutionState.RESOLVED, document, nameTerms)
    }

    /**
     * Resolves a model-supplied file reference among [permitted] documents only: an exact turn
     * reference or path label, then a unique file name, then a unique normalized path, name, or
     * base name. Anything else, including a choice the turn never permitted, resolves to null.
     */
    fun reference(file: String, permitted: Map<String, WorkspaceDocument>): WorkspaceDocument? {
        val decorated = file.trim()
        val unmarked = decorated.removeSuffix(WorkspaceRetriever.VISUAL_MARK).trim()
        val candidates = listOf(decorated, unmarked, unmarked.replace(CITED_PAGE, "").trim()).distinct()
        val documents = permitted.values.distinctBy { it.entry.documentId }
        for (cited in candidates) {
            permitted[cited]?.let { return it }
            documents.filter { it.entry.relativePath == cited }.singleOrNull()?.let { return it }
            documents.filter { it.entry.name.equals(cited, ignoreCase = true) }.singleOrNull()?.let { return it }
        }
        val asked = words(unmarked.replace(CITED_PAGE, ""))
        if (asked.isEmpty()) return null
        return documents.filter { document ->
            asked == words(document.entry.relativePath) || asked == words(document.entry.name) ||
                asked == words(baseName(document.entry.name))
        }.singleOrNull()
    }

    private fun matches(asked: List<String>, document: WorkspaceDocument): List<Match> {
        val entry = document.entry
        val name = words(entry.name)
        val base = words(baseName(entry.name))
        val path = words(entry.relativePath)
        val found = mutableListOf<Match>()
        if (path.size > name.size) occurrences(asked, path).forEach { found += Match(document, 3, it, it + path.size) }
        if (name.size > base.size) occurrences(asked, name).forEach { found += Match(document, 2, it, it + name.size) }
        // Match the whole base name, including bare names, never a fragment of a longer name.
        if (base.isNotEmpty()) occurrences(asked, base).forEach { start ->
            val end = start + base.size
            found += Match(document, 1, start, end)
        }
        return found
    }

    private fun occurrences(asked: List<String>, name: List<String>): List<Int> {
        if (name.isEmpty() || name.size > asked.size) return emptyList()
        return (0..asked.size - name.size).filter { start -> name.indices.all { asked[start + it] == name[it] } }
    }

    /** Written names and bounded spoken forms, while leaving discovery questions unscoped. */
    private fun namesMissingFile(question: String, asked: List<String>): Boolean =
        FILE_NAME.containsMatchIn(question) || asked.withIndex().any { (index, word) ->
            word in EXTENSIONS && index > 0 && WorkspaceTokenizer.tokens(asked[index - 1]).isNotEmpty() &&
                asked[index - 1] !in FILE_CUES
        } || asked.withIndex().any { (index, word) ->
            word in FILE_CUES && asked.getOrNull(index + 1)?.let { next ->
                next !in FILE_CUES && next !in DISCOVERY_WORDS && WorkspaceTokenizer.tokens(next).isNotEmpty()
            } == true
        }

    fun baseName(name: String): String = name.substringBeforeLast('.', name).ifEmpty { name }

    private const val MAX_QUESTION_CHARS = 1_000
    private val MARKS = Regex("\\p{M}+")
    private val WORDS = Regex("[\\p{L}\\p{N}]+")
    private val CITED_PAGE = Regex(" › page \\d+$")
    private val EXTENSIONS = setOf("pdf", "txt", "md", "docx", "jpg", "jpeg", "png", "webp", "heic", "heif")
    private val FILE_CUES = EXTENSIONS + setOf("file", "files", "fichier", "fichiers", "document", "documents",
        "doc", "docs", "image", "photo", "scan")
    private val DISCOVERY_WORDS = setOf("parle", "parlent", "contient", "contiennent", "mentionne", "mentionnent",
        "explique", "expliquent", "decrit", "decrivent", "dit", "disent", "about", "contains", "contain",
        "mentions", "mention", "describes", "describe", "says", "say", "talks", "talk")
    private val FILE_NAME = Regex("[\\p{L}\\p{N}_-]+\\.(?:pdf|txt|md|docx|jpe?g|png|webp|heic|heif)\\b",
        RegexOption.IGNORE_CASE)
}
