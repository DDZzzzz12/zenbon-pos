package org.example.project.tse.utils

import org.example.project.core.domain.dto.tse.TseDto

/**
 * Parses a TSE export TAR into its log-message records, reusing the archive reader and
 * [Tr03153LogMessageParser], the TR-03151 log-message parser this module already owns. Members
 * that are not log messages (info.csv, certificates) do not parse and are simply dropped.
 *
 * Pure and device-free, so callers (e.g. pos-server verification) run it in-process regardless
 * of connector mode.
 */
object TarLogMessageReader {
    fun read(tar: ByteArray): List<TseDto.ParsedLogMessageDto> =
        UstarArchive.read(tar).mapNotNull { Tr03153LogMessageParser.parse(it.content) }
}
