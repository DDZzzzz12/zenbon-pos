package org.example.project.features.zBon.domain

/**
 * The repository's last guard against a Z-Bon whose period overlaps an existing one of the same
 * shop: some stored closing ends after the start the new closing would get.
 *
 * Plain data-access exception (the repository does not speak HTTP). `ZBonServiceImpl.createReport`
 * translates it into a 409 `ZBON_PERIOD_OVERLAP` problem; before NEW-25 it escaped as a bare
 * [IllegalStateException] and answered 500 INTERNAL_ERROR. Still an [IllegalStateException] so any
 * other caller keeps seeing what it saw before.
 */
class ZBonPeriodOverlapException(
    message: String,
) : IllegalStateException(message)
