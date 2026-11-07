package org.example.project.features.appupdate

import org.example.project.features.appupdate.domain.AppUpdateDecision
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppUpdateDecisionTest {
    @Test fun `a higher versionCode is an update`() = assertTrue(AppUpdateDecision.isNewer(installed = 12, offered = 13))

    /** The server-only-release case: new release, same client code. Must not prompt. */
    @Test fun `the same versionCode is not an update`() = assertFalse(AppUpdateDecision.isNewer(installed = 13, offered = 13))

    /** A server rolled back to an older release must never downgrade a till. */
    @Test fun `a lower versionCode is not an update`() = assertFalse(AppUpdateDecision.isNewer(installed = 14, offered = 13))
}
