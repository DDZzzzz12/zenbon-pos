package org.example.project.features.issuereport.domain.tracker

import org.example.project.features.issuereport.domain.models.IssueCreateResult
import org.example.project.features.update.data.remote.GithubReleaseClient

/** What the report service needs from an issue tracker; GitHub is the only implementation. */
interface IssueTracker {
    suspend fun create(
        repo: GithubReleaseClient.Repo,
        token: String,
        title: String,
        body: String,
    ): IssueCreateResult
}
