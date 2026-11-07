package org.example.project.admin.core.presentation.components

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BucketPanelFilterTest {
    private enum class Bucket { A, B, C }

    @Test
    fun theBucketsAreAPanelFieldOnAPhoneOrOnceTheDraftChangedThem() {
        assertTrue(BucketPanelFilter(Bucket.A).showsBuckets(compact = true, applied = Bucket.A))
        assertFalse(BucketPanelFilter(Bucket.A).showsBuckets(compact = false, applied = Bucket.A))
        assertTrue(BucketPanelFilter(Bucket.B).showsBuckets(compact = false, applied = Bucket.A))
    }

    @Test
    fun applyReportsOnlyAChangedBucket() {
        assertNull(BucketPanelFilter(Bucket.A).toApply(applied = Bucket.A))
        assertEquals(Bucket.C, BucketPanelFilter(Bucket.C).toApply(applied = Bucket.A))
    }

    @Test
    fun resetReturnsToTheScreensOpeningBucket() {
        assertEquals(BucketPanelFilter(Bucket.B), BucketPanelFilter(Bucket.C).cleared(default = Bucket.B))
        assertEquals(BucketPanelFilter(Bucket.B), BucketPanelFilter(Bucket.B).cleared(default = Bucket.B))
    }
}
