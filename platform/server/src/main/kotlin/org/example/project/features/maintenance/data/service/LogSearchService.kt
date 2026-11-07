package org.example.project.features.maintenance.data.service

import io.ktor.http.HttpStatusCode
import org.example.project.core.error.PosErrorCode
import org.example.project.features.maintenance.data.logs.BoundedLineReader
import org.example.project.features.maintenance.data.logs.ClientErrorLogParser
import org.example.project.features.maintenance.data.logs.ErrorLogParser
import org.example.project.features.maintenance.data.logs.JsonLogParser
import org.example.project.features.maintenance.data.logs.LogEntryMapper
import org.example.project.features.maintenance.data.logs.LogEntryMatcher
import org.example.project.features.maintenance.data.logs.LogFile
import org.example.project.features.maintenance.data.logs.LogFiles
import org.example.project.features.maintenance.data.logs.LogParser
import org.example.project.features.maintenance.data.logs.LogQuery
import org.example.project.features.maintenance.data.logs.LogSourceKind
import org.example.project.features.maintenance.data.logs.PathNormalizer
import org.example.project.features.maintenance.data.logs.ScanBudget
import org.example.project.features.maintenance.domain.dto.MaintenanceDto
import org.example.project.infrastructure.errors.PosException
import org.example.project.infrastructure.security.RouteRuleCatalog
import org.slf4j.LoggerFactory
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.time.Instant
import java.time.ZoneId
import java.util.zip.GZIPInputStream

/**
 * Searches the server's log files for the maintainer.
 *
 * Blocking I/O; the route calls it on `Dispatchers.IO`. The candidate files of ALL selected sources are
 * visited in one newest-first order — every current file first, then rotated files by day and index
 * descending — so a search without a time bound cannot spend the whole budget in one source's old
 * files before another source's current file is even opened. Both the match cap (per source) and the
 * scan budget (shared) surface as `truncated` in the response, so the page can ask the user to narrow
 * the filter instead of silently showing a partial answer.
 *
 * Within a file the newest [matchCap] matches are kept (a ring), so hitting the cap drops the oldest,
 * not the entries the user most probably wants.
 *
 * Memory: lines are read through [BoundedLineReader], at most 64 KiB each, and an entry holds at most
 * [MAX_BLOCK_LINES] lines while it is being collected. The ring is per FILE and the per-source cap is
 * checked between files, so a source can hold up to `2 × matchCap − 1` entries before it stops, i.e. up
 * to `4 × (2 × matchCap − 1)` (~8,000 by default) across the four sources. Each kept entry is bounded
 * by `LogEntryMapper`: message and exception summary at most 4,000 characters, at most 200 frames of at
 * most 4,000 characters.
 */
class LogSearchService(
    private val logDir: () -> File,
    private val normalizer: PathNormalizer,
    private val matchCap: Int = DEFAULT_MATCH_CAP,
    private val maxScanChars: Long = DEFAULT_MAX_SCAN_CHARS,
    private val maxScanNanos: Long = DEFAULT_MAX_SCAN_NANOS,
    private val nanoTime: () -> Long = System::nanoTime,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {
    private data class Hit(
        val at: Instant,
        val entry: MaintenanceDto.LogEntryDto,
    )

    private class FileScan(
        val hits: List<Hit>,
        val overflowed: Boolean,
    )

    /** What one file scan needs besides the file: shared by every file of one search. */
    private class ScanContext(
        val matcher: LogEntryMatcher,
        val budget: ScanBudget,
        /** The trace id to look for verbatim in a raw line, or null when a raw line may not contain it verbatim. */
        val lineNeedle: String?,
    )

    fun search(query: LogQuery): MaintenanceDto.LogSearchResponseDto {
        val dir = requireReadable(logDir())
        val context =
            ScanContext(
                matcher = LogEntryMatcher(query),
                budget = ScanBudget(maxScanChars, maxScanNanos, nanoTime),
                lineNeedle = query.traceId?.takeIf { PREFILTERABLE_TRACE_ID.matches(it) },
            )
        val parsers = query.sources.associateWith(::parserFor)
        val files = query.sources.flatMap { LogFiles.list(dir, it) }
        val hits = mutableListOf<Hit>()
        val sourceHits = mutableMapOf<LogSourceKind, Int>()
        var capHit = false
        var budgetHit = false
        var scannedFiles = 0

        for (file in files.sortedWith(NEWEST_FIRST)) {
            if (!file.mayContain(query.from, query.to, zone)) continue
            if (sourceHits.getOrDefault(file.kind, 0) >= matchCap) continue
            if (context.budget.exhausted()) {
                budgetHit = true
                break
            }
            scannedFiles++
            val scan = scanFile(file, parsers.getValue(file.kind), context)
            hits += scan.hits
            val total = sourceHits.getOrDefault(file.kind, 0) + scan.hits.size
            sourceHits[file.kind] = total
            if (scan.overflowed || total >= matchCap) capHit = true
            if (context.budget.exhausted()) {
                budgetHit = true
                break
            }
        }

        return MaintenanceDto.LogSearchResponseDto(
            entries = hits.sortedByDescending { it.at }.take(query.limit).map { it.entry },
            matched = hits.size,
            scannedFiles = scannedFiles,
            truncated =
                when {
                    budgetHit -> SCAN_BUDGET
                    capHit -> MATCH_CAP
                    else -> null
                },
            oldestAvailable = files.mapNotNull { it.date }.minOrNull()?.toString(),
            from = query.from?.toString(),
        )
    }

    private fun scanFile(
        file: LogFile,
        parser: LogParser,
        context: ScanContext,
    ): FileScan {
        val ring = ArrayDeque<Hit>()
        var overflowed = false
        var block = ArrayList<String>()

        fun flush() {
            if (block.isEmpty()) return
            val record = parser.parse(block)
            block = ArrayList()
            if (record == null || !context.matcher.matchesStructure(record)) return
            val entry = LogEntryMapper.toDto(record, normalizer)
            if (!context.matcher.matchesText(entry)) return
            ring.addLast(Hit(record.timestamp, entry))
            if (ring.size > matchCap) {
                ring.removeFirst()
                overflowed = true
            }
        }

        val needle = context.lineNeedle
        // True while the lines being read belong to an entry the trace-id prefilter already rejected.
        var skipping = false
        try {
            BoundedLineReader(open(file.file), context.budget).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (context.budget.exhausted()) break
                    // Every entry's first line carries its trace id (the text formats' header, the JSON
                    // object). Inside a rejected entry, a line without it is dropped unparsed: it is either
                    // that entry's continuation or the start of another entry that cannot match either.
                    if (skipping && needle != null && !line.contains(needle)) continue
                    when {
                        parser.isEntryStart(line) -> {
                            flush()
                            skipping = needle != null && !line.contains(needle)
                            if (!skipping) block.add(line)
                        }
                        !skipping && block.isNotEmpty() && block.size < MAX_BLOCK_LINES -> block.add(line)
                    }
                }
            }
        } catch (e: IOException) {
            // A file being rotated, or a truncated .gz: keep what was read, move on.
            logger.warn("Could not read all of log file {}: {}", file.file.name, e.message)
        }
        flush()
        return FileScan(ring.toList(), overflowed)
    }

    private fun open(file: File): InputStreamReader {
        val raw = file.inputStream().buffered()
        val stream: InputStream =
            if (file.name.endsWith(".gz")) {
                try {
                    GZIPInputStream(raw)
                } catch (e: IOException) {
                    raw.close()
                    throw e
                }
            } else {
                raw
            }
        return InputStreamReader(stream, Charsets.UTF_8)
    }

    private fun parserFor(kind: LogSourceKind): LogParser =
        when (kind) {
            LogSourceKind.REJECTED, LogSourceKind.APPLICATION -> JsonLogParser(kind)
            LogSourceKind.ERROR -> ErrorLogParser(zone)
            LogSourceKind.CLIENT_ERROR -> ClientErrorLogParser(zone)
        }

    private fun requireReadable(dir: File): File {
        if (!dir.isDirectory || !dir.canRead()) {
            throw PosException(
                PosErrorCode.MAINTENANCE_LOG_DIR_UNAVAILABLE,
                HttpStatusCode.ServiceUnavailable,
                "The server's log directory cannot be read",
            )
        }
        return dir
    }

    companion object {
        const val MATCH_CAP = "MATCH_CAP"
        const val SCAN_BUDGET = "SCAN_BUDGET"
        const val DEFAULT_MATCH_CAP = 1_000
        const val DEFAULT_MAX_SCAN_CHARS = 256L * 1024 * 1024
        const val DEFAULT_MAX_SCAN_NANOS = 8_000_000_000L

        /** A runaway stack trace must not grow one entry without bound. */
        private const val MAX_BLOCK_LINES = 400

        /**
         * A trace id made only of these characters appears verbatim in the raw line — JSON escapes none of
         * them — so a line that does not contain it cannot match. Any other trace id is matched after parsing.
         */
        private val PREFILTERABLE_TRACE_ID = Regex("[A-Za-z0-9._-]+")

        /**
         * Every current file first (stable: in the order of the selected sources), then rotated files by
         * day descending and index descending, across all sources.
         */
        private val NEWEST_FIRST =
            compareBy<LogFile> { it.date != null }
                .thenByDescending { it.date }
                .thenByDescending { it.index }

        private val logger = LoggerFactory.getLogger(LogSearchService::class.java)

        /** The production wiring: `LOG_DIR` is set by `LogDirectoryInitializer` before logback loads. */
        fun default(routeRules: RouteRuleCatalog): LogSearchService =
            LogSearchService(
                logDir = { File(System.getProperty("LOG_DIR") ?: "logs") },
                normalizer = PathNormalizer.fromRouteRules(routeRules),
            )
    }
}
