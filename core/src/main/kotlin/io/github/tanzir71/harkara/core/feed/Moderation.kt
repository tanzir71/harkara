package io.github.tanzir71.harkara.core.feed

import io.github.tanzir71.harkara.core.Category
import io.github.tanzir71.harkara.core.nostr.Event
import io.github.tanzir71.harkara.core.nostr.KeyPair
import io.github.tanzir71.harkara.core.nostr.Kinds
import io.github.tanzir71.harkara.core.nostr.signEvent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.text.Normalizer

@Serializable
data class FilterRules(
    val maxTextLength: Int = 400,
    val maxLinks: Int = 1,
    val noPhoneNumbersIn: List<String> = listOf("chatter", "alert"),
    val phoneDigitsMin: Int = 10,
    val phoneDigitsMax: Int = 15,
)

@Serializable
data class ModerationRules(val hideAfterDistinctReports: Int = 3, val hideAtScore: Int = -5)

/** `data/word-filters/filters.json` (from loka `contentFilter.ts`). */
@Serializable
data class WordFilters(
    val englishBlockedWords: List<String> = emptyList(),
    val banglaBlockedFragments: List<String> = emptyList(),
    val spamPhrases: List<String> = emptyList(),
    val rules: FilterRules = FilterRules(),
    val moderation: ModerationRules = ModerationRules(),
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun parse(text: String): WordFilters = json.decodeFromString(serializer(), text)
    }
}

/** Bangla + English word filter and composer rules, applied on the phone only. */
class ContentFilter(private val filters: WordFilters, private val muteWords: Set<String> = emptySet()) {
    private val urlPattern = Regex(
        """\b(?:(?:https?://|www\.)[^\s<]+|[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.(?:com|net|org|io|co|bd|app)(?:/[^\s<]*)?)""",
        RegexOption.IGNORE_CASE,
    )
    private val phoneCandidate = Regex("""\+?\d[\d\s().-]{7,}\d""")

    fun withMuteWords(words: Set<String>) = ContentFilter(filters, words)

    /** Composer check for our own draft. */
    fun checkDraft(text: String, category: Category?): ComposeVerdict {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ComposeVerdict.EMPTY
        if (trimmed.length > filters.rules.maxTextLength) return ComposeVerdict.TOO_LONG
        val n = normalize(trimmed)
        if (containsBlocked(n)) return ComposeVerdict.BLOCKED_WORDS
        if (filters.spamPhrases.any { n.contains(it) }) return ComposeVerdict.SPAM
        if (urlPattern.findAll(n).count() > filters.rules.maxLinks) return ComposeVerdict.TOO_MANY_LINKS
        val cat = (category ?: Category.CHATTER).tag
        if (cat in filters.rules.noPhoneNumbersIn && containsPhoneNumber(n)) return ComposeVerdict.PHONE_NUMBER
        return ComposeVerdict.OK
    }

    /** True when someone else's post should be hidden locally (blocked words, spam or the user's mute words). */
    fun hidesIncoming(text: String): Boolean {
        val n = normalize(text)
        return containsBlocked(n) || filters.spamPhrases.any { n.contains(it) } || muteWords.any { it.isNotBlank() && n.contains(normalize(it)) }
    }

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC).replace(Regex("[\u200B-\u200D\u2060\uFEFF]"), "").lowercase()

    private fun containsBlocked(n: String): Boolean {
        val tokens = n.split(Regex("""[^\p{L}\p{M}\p{N}]+""")).filter { it.isNotEmpty() }
        if (tokens.any { it in filters.englishBlockedWords }) return true
        val compact = n.replace(Regex("""[^\p{L}\p{M}\p{N}]+"""), "")
        return filters.banglaBlockedFragments.any { compact.contains(it) }
    }

    private fun containsPhoneNumber(n: String): Boolean {
        val ascii = n.map { c -> if (c in '০'..'৯') '0' + (c - '০') else c }.joinToString("")
        return phoneCandidate.findAll(ascii).any { m ->
            val digits = m.value.count(Char::isDigit)
            digits in filters.rules.phoneDigitsMin..filters.rules.phoneDigitsMax
        }
    }
}

/** NIP-56 report reasons offered on every card. */
enum class ReportReason(val wire: String) { SPAM("spam"), ABUSE("profanity"), ILLEGAL("illegal"), FALSE("other") }

/** Votes (NIP-25 kind 7) and reports (NIP-56 kind 1984) on feed posts. */
object Reactions {
    fun vote(keys: KeyPair, post: Event, up: Boolean, createdAt: Long): Event = keys.signEvent(
        Kinds.REACTION,
        listOf(listOf("e", post.id), listOf("p", post.pubkey), listOf("k", post.kind.toString())),
        if (up) "+" else "-",
        createdAt,
    )

    fun report(keys: KeyPair, post: Event, reason: ReportReason, createdAt: Long): Event = keys.signEvent(
        Kinds.REPORT,
        listOf(listOf("e", post.id, reason.wire), listOf("p", post.pubkey)),
        "",
        createdAt,
    )

    /** The post id a vote or report targets, or null. */
    fun target(event: Event): String? = event.tagValue("e")
}

/** Local moderation verdict for one post. */
data class ModerationState(val score: Int, val reporters: Int, val hidden: Boolean, val myVote: Int = 0)

/**
 * The local moderation ladder: a post hides when 3 distinct keys report it, when its score drops to −5,
 * when a moderator the user opted into reports it, or when the user blocked the author or muted its words.
 * Each key counts once (its latest vote wins). Nothing here is enforced on anyone else's phone.
 */
class ModerationLadder(
    private val rules: ModerationRules = ModerationRules(),
    private val moderators: Set<String> = emptySet(),
) {
    fun evaluate(
        post: Event,
        reactions: List<Event>,
        blockedAuthors: Set<String> = emptySet(),
        contentFilter: ContentFilter? = null,
        me: Set<String> = emptySet(),
    ): ModerationState {
        val forPost = reactions.filter { Reactions.target(it) == post.id }
        val latestVotes = forPost.filter { it.kind == Kinds.REACTION && (it.content == "+" || it.content == "-") }
            .groupBy { it.pubkey }
            .mapValues { (_, votes) -> votes.maxBy { it.createdAt } }
        val score = latestVotes.values.sumOf { if (it.content == "+") 1 else -1 }.toInt()
        val reporters = forPost.filter { it.kind == Kinds.REPORT }.map { it.pubkey }.toSet()
        val myVote = latestVotes.filterKeys { it in me }.values.maxByOrNull { it.createdAt }?.let { if (it.content == "+") 1 else -1 } ?: 0
        val hidden = reporters.size >= rules.hideAfterDistinctReports ||
            score <= rules.hideAtScore ||
            reporters.any { it in moderators } ||
            post.pubkey in blockedAuthors ||
            (contentFilter?.hidesIncoming(post.content) == true && post.pubkey !in me)
        return ModerationState(score, reporters.size, hidden, myVote)
    }
}
