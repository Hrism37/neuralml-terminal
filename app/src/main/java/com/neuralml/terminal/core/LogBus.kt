package com.neuralml.terminal.core

/** Log severity levels shown in the bottom SYSTEM & ML LOG bar. */
enum class LogLevel { INFO, WARN, ERROR }

/** One line of the rolling system / model log. */
data class LogEntry(
    val time: String,
    val level: LogLevel,
    val source: String,
    val text: String,
)

/**
 * Fixed-capacity rolling log. The engine appends on the main thread, the log
 * panel reads a copy inside `onDraw`.
 */
class LogBus(private val capacity: Int = 160) {

    private val entries = ArrayDeque<LogEntry>(capacity)
    private val lock = Any()
    private var revision: Long = 0L

    /** Bumped on every append so views can cheaply detect new content. */
    fun revision(): Long = synchronized(lock) { revision }

    fun post(entry: LogEntry) {
        synchronized(lock) {
            entries.addLast(entry)
            while (entries.size > capacity) entries.removeFirst()
            revision++
        }
    }

    fun post(level: LogLevel, source: String, text: String) {
        post(LogEntry("--:--:--", level, source, text))
    }

    /** Newest-last snapshot, at most [limit] entries. */
    fun snapshot(limit: Int = capacity): List<LogEntry> {
        synchronized(lock) {
            if (entries.size <= limit) return ArrayList(entries)
            return entries.takeLast(limit)
        }
    }

    fun clear() {
        synchronized(lock) {
            entries.clear()
            revision++
        }
    }
}
