import cz.digitalnivedomi.diarium.build.AppVersionCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionCodeTest {
    @Test
    fun `beta5 keeps its shipped Android version code and beta6 advances it`() {
        val beta5 = AppVersionCode.fromVersionName("2.0.0-beta5")
        val beta6 = AppVersionCode.fromVersionName("2.0.0-beta6")

        assertEquals(2_000_105, beta5)
        assertEquals(2_000_106, beta6)
        assertTrue("beta6 must be installable over beta5", beta6 > beta5)
        assertEquals(2_000_105, AppVersionCode.resolve("2.0.0-beta5", "2000105"))
    }

    @Test
    fun `v2 alpha and stable version names retain ordered codes`() {
        assertEquals(2_000_011, AppVersionCode.fromVersionName("2.0.0-alpha11"))
        assertEquals(2_000_200, AppVersionCode.fromVersionName("2.0.0"))
    }

    @Test
    fun `v2 alpha beta stable and later patch releases stay upgradeable`() {
        val beta6 = AppVersionCode.resolve("2.0.0-beta6", "2000006")
        val stable = AppVersionCode.resolve("2.0.0", "2000000")
        val nextPatchAlpha = AppVersionCode.resolve("2.0.1-alpha1", "2000101")
        val nextPatchBeta = AppVersionCode.resolve("2.0.1-beta1", "2000101")
        val nextPatchStable = AppVersionCode.resolve("2.0.1", "2000100")

        assertEquals(2_000_200, stable)
        assertEquals(2_000_201, nextPatchAlpha)
        assertEquals(2_000_301, nextPatchBeta)
        assertEquals(2_000_400, nextPatchStable)
        assertTrue(beta6 < stable && stable < nextPatchAlpha)
        assertTrue(nextPatchAlpha < nextPatchBeta && nextPatchBeta < nextPatchStable)

        assertTrue(AppVersionCode.fromVersionName("2.0.99") < AppVersionCode.fromVersionName("2.1.0-alpha1"))
        assertTrue(AppVersionCode.fromVersionName("2.49.99") < AppVersionCode.fromVersionName("3.0.0-alpha1"))
    }

    @Test
    fun `modern prerelease slots are unique and monotonic across every patch in a minor`() {
        val codes = buildList {
            for (patch in 0..99) {
                for (sequence in 1..99) add(AppVersionCode.fromVersionName("2.0.$patch-alpha$sequence"))
                for (sequence in 1..99) add(AppVersionCode.fromVersionName("2.0.$patch-beta$sequence"))
                add(AppVersionCode.fromVersionName("2.0.$patch"))
            }
        }

        assertEquals(codes.size, codes.toSet().size)
        assertEquals(codes.sorted(), codes)
    }

    @Test
    fun `legacy stable version codes preserve configured workflow values`() {
        assertEquals(1_020_300, AppVersionCode.resolve("1.2.3", "1020300"))
        assertEquals(10_000, AppVersionCode.resolve("1.0.0", "10000"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `legacy beta versions are rejected because their slots collide`() {
        AppVersionCode.resolve("1.0.0-beta1", "1000001")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `malformed version names are rejected`() {
        AppVersionCode.fromVersionName("2.0-beta6")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero prerelease sequence is rejected`() {
        AppVersionCode.fromVersionName("2.0.0-beta0")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `prerelease sequence outside its reserved range is rejected`() {
        AppVersionCode.fromVersionName("2.0.0-beta100")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unsupported prerelease labels are rejected`() {
        AppVersionCode.fromVersionName("2.0.0-rc1")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `malformed configured version codes are rejected`() {
        AppVersionCode.resolve("2.0.0-beta6", "not-a-number")
    }
}
