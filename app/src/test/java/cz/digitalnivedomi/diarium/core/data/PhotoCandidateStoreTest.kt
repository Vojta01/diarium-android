package cz.digitalnivedomi.diarium.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PhotoCandidateStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(PhotoCandidateStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `distinct staged candidates persist by owner and date across store recreation`() {
        val first = "https://example.supabase.co/storage/v1/object/public/diary-photos/$OWNER_A/$DATE-p1.jpg"
        val second = "https://example.supabase.co/storage/v1/object/public/diary-photos/$OWNER_A/$DATE-p2.jpg"
        val ownerA = PhotoCandidateStore.from(context, OWNER_A)

        assertTrue(ownerA.track(DATE, first))
        assertTrue(ownerA.track(DATE, second))

        val restartedA = PhotoCandidateStore.from(context, OWNER_A)
        assertEquals(setOf(first, second), restartedA.forDate(DATE).toSet())
        assertTrue(PhotoCandidateStore.from(context, OWNER_B).forDate(DATE).isEmpty())
        assertTrue(restartedA.remove(DATE, first))
        assertEquals(listOf(second), restartedA.forDate(DATE))
        assertFalse(restartedA.remove(DATE, first))
    }

    private companion object {
        const val OWNER_A = "5f2c1b7e-9d3a-4c6f-8a10-2b4d6e8f0a12"
        const val OWNER_B = "9e0a6e91-36c0-4e84-a6ed-f05d92e0d521"
        const val DATE = "2026-09-10"
    }
}
