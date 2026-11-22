package com.example

import java.util.Locale

/**
 * Local Long-Term Memory Vault Manager for JARVIS (Developed by Mozahid).
 * - Stores personal facts, routines, goals, and preferences 100% locally in Room DB (`user_memories`).
 * - Captures explicit ("Yaad rakhna...", "Remember...") and personal routine/preference statements automatically.
 * - Deduplicates memories and injects ONLY the top 6 most relevant/recent facts (capped <= 100 tokens / ~360 chars)
 *   into AI prompts so JARVIS remembers past conversations permanently without wasting daily API quota.
 */
class MemoryManager(private val repository: JarvisRepository) {

    /**
     * Checks if the user explicitly asked JARVIS to remember something ("Yaad rakhna...", "Remember...").
     * Returns the saved clean fact if matched, or null otherwise.
     */
    suspend fun tryCaptureExplicitRememberCommand(rawQuery: String): String? {
        val trimmed = rawQuery.trim()
        val match = EXPLICIT_REMEMBER_REGEX.find(trimmed) ?: return null
        val fact = match.groupValues.getOrNull(1)?.trim()?.trimEnd('.', '!', '?').orEmpty()
        if (fact.length < 4) return null

        val category = inferCategory(fact)
        saveMemory(factText = fact, category = category)
        return fact
    }

    /**
     * Passively detects personal facts, preferences, or routines shared in normal conversation
     * (e.g., "Mera naam...", "Main roz 6 baje uthta hu", "I love coding", "My exam is in May")
     * and stores them silently in the local Room Memory Vault.
     */
    suspend fun detectAndSaveImplicitMemory(rawQuery: String): Boolean {
        val trimmed = rawQuery.trim()
        if (trimmed.length < 8 || trimmed.endsWith("?")) return false
        val lower = trimmed.lowercase(Locale.ROOT)
        if (IMPLICIT_PERSONAL_PREFIXES.none { lower.contains(it) }) return false
        // Skip direct device commands so commands are never mistaken for memories
        if (COMMAND_EXCLUSION_TOKENS.any { lower.contains(it) }) return false

        val cleanFact = trimmed.take(160).trim()
        return saveMemory(cleanFact, inferCategory(cleanFact))
    }

    /**
     * Persists a fact (from user voice, manual Settings entry, or AI `"save_memory"` JSON field)
     * while deduplicating against existing saved memories.
     */
    suspend fun saveMemory(
        factText: String,
        category: String = MEMORY_CAT_GENERAL
    ): Boolean {
        val clean = JarvisResponseSanitizer.sanitizePlainSpeechText(factText)
            .take(180)
            .trim()
        if (clean.length < 4) return false

        val normalizedNew = normalizeForDedup(clean)
        val existing = repository.getRecentMemories(limit = 80)
        val duplicate = existing.firstOrNull { mem ->
            val normOld = normalizeForDedup(mem.factText)
            normOld == normalizedNew ||
                (normOld.length > 10 && normalizedNew.contains(normOld)) ||
                (normalizedNew.length > 10 && normOld.contains(normalizedNew))
        }
        if (duplicate != null) {
            // Refresh timestamp if already known
            repository.saveMemory(duplicate.copy(factText = clean, timestamp = System.currentTimeMillis()))
            return true
        }

        repository.saveMemory(
            UserMemoryEntity(
                factText = clean,
                category = category.ifBlank { inferCategory(clean) },
                timestamp = System.currentTimeMillis()
            )
        )
        return true
    }

    /**
     * Builds an ultra-compact memory snippet containing ONLY the top 6 most relevant/recent facts,
     * capped strictly to ~100 tokens (~360 characters) to preserve daily API quota.
     */
    suspend fun buildCompactMemoryContext(userQuery: String): String {
        val allMemories = repository.getRecentMemories(limit = 50)
        if (allMemories.isEmpty()) return ""

        val queryTokens = extractKeywords(userQuery)
        val scored = allMemories.mapIndexed { idx, memory ->
            val memTokens = extractKeywords(memory.factText)
            val overlap = if (queryTokens.isEmpty()) 0 else queryTokens.count { it in memTokens }
            // Recency bonus ensures recent preferences remain top-of-mind even for general questions
            val recencyBonus = (50 - idx).coerceAtLeast(0)
            val priorityBonus = when (memory.category) {
                MEMORY_CAT_PROFILE -> 25
                MEMORY_CAT_ROUTINE, MEMORY_CAT_GOAL -> 15
                else -> 5
            }
            val score = (overlap * 80) + recencyBonus + priorityBonus
            memory to score
        }

        val topMemories = scored
            .sortedByDescending { it.second }
            .take(MAX_INJECTED_MEMORIES)
            .map { it.first }

        val sb = StringBuilder()
        for (mem in topMemories) {
            val line = "- ${mem.factText}"
            if (sb.length + line.length + 1 > MAX_MEMORY_CONTEXT_CHARS) break
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(line)
        }
        return sb.toString()
    }

    fun inferCategory(fact: String): String {
        val lower = fact.lowercase(Locale.ROOT)
        return when {
            lower.contains("naam") || lower.contains("name") || lower.contains("live in") ||
                lower.contains("rehta") || lower.contains("student") || lower.contains("developer") ->
                MEMORY_CAT_PROFILE
            lower.contains("subah") || lower.contains("morning") || lower.contains("daily") ||
                lower.contains("roz") || lower.contains("baje") || lower.contains("routine") ||
                lower.contains("wake") || lower.contains("sleep") ->
                MEMORY_CAT_ROUTINE
            lower.contains("exam") || lower.contains("goal") || lower.contains("target") ||
                lower.contains("padh") || lower.contains("study") || lower.contains("youtube") ||
                lower.contains("channel") || lower.contains("project") ->
                MEMORY_CAT_GOAL
            lower.contains("pasand") || lower.contains("like") || lower.contains("love") ||
                lower.contains("favorite") || lower.contains("prefer") || lower.contains("hate") ->
                MEMORY_CAT_PREFERENCE
            else -> MEMORY_CAT_GENERAL
        }
    }

    private fun extractKeywords(text: String): Set<String> {
        return text.lowercase(Locale.ROOT)
            .split(Regex("[^a-z0-9\\u0900-\\u097F]+"))
            .filter { it.length >= 3 && it !in STOP_WORDS }
            .toSet()
    }

    private fun normalizeForDedup(text: String): String {
        return text.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\u0900-\\u097F]+"), " ")
            .trim()
    }

    companion object {
        const val MAX_INJECTED_MEMORIES = 6
        const val MAX_MEMORY_CONTEXT_CHARS = 360 // ~90-100 tokens hard ceiling

        private val EXPLICIT_REMEMBER_REGEX = Regex(
            "(?:yaad\\s+rakhna(?:\\s+ki)?|yaad\\s+rakh\\s+lo(?:\\s+ki)?|remember\\s+that|remember\\s+this|remember)\\s+(.+)",
            RegexOption.IGNORE_CASE
        )

        private val IMPLICIT_PERSONAL_PREFIXES = listOf(
            "mera naam", "my name is", "main roz", "mai roz", "i wake up",
            "i study", "mujhe pasand", "i prefer", "my favorite", "my exam",
            "mera exam", "mera goal", "my goal is", "i live in", "main rehta",
            "mera channel", "my youtube", "my instagram"
        )

        private val COMMAND_EXCLUSION_TOKENS = listOf(
            "open ", "kholo", "call karna", "call cut", "scroll", "back karo",
            "home screen", "flashlight", "torch", "volume", "tap ", "click "
        )

        private val STOP_WORDS = setOf(
            "the", "and", "for", "that", "this", "with", "from", "have", "hai",
            "hain", "karo", "karna", "kya", "mein", "mera", "meri", "sir", "jarvis"
        )
    }
}
