package cz.digitalnivedomi.diarium.build

object AppVersionCode {
    /** Upper limit used by Android distribution channels. */
    const val MAX_VERSION_CODE = 2_100_000_000
    private const val DEFAULT_VERSION_CODE = 1

    /*
     * Preserve workflow codes for stable/alpha releases before v2. Legacy beta
     * tags are rejected: their old +100 prerelease offset overlaps the next
     * patch's stable/alpha range. For v2+, every patch owns 200 codes: alpha
     * 1..99, beta 101..199, and stable 200. This retains shipped beta5 code
     * 2,000,105 and orders beta6/stable/later releases above it.
     */
    private val versionPattern = Regex(
        "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-(alpha|beta)(0|[1-9][0-9]*))?$",
    )

    private enum class Channel { STABLE, ALPHA, BETA }

    private data class ParsedVersion(
        val major: Long,
        val minor: Long,
        val patch: Long,
        val channel: Channel,
        val prereleaseNumber: Long,
    )

    /**
     * Resolve the Android version code. Historical pre-v2 release codes continue
     * to come from APP_VERSION_CODE; v2+ codes are generated from versionName so
     * alpha, beta, stable, and patch ordering cannot depend on the old workflow
     * formula (which did not reserve a beta range).
     */
    fun resolve(versionName: String, configuredVersionCode: String?): Int {
        val parsed = parse(versionName)
        val configured = parseConfiguredCode(configuredVersionCode)
        return if (parsed.major >= 2) codeFor(parsed) else configured
    }

    /** Generate the established numeric layout, including v2's beta band. */
    fun fromVersionName(versionName: String): Int = codeFor(parse(versionName))

    private fun parse(versionName: String): ParsedVersion {
        val match = versionPattern.matchEntire(versionName)
            ?: throw IllegalArgumentException("Unsupported app version name: $versionName")
        val major = match.groupValues[1].toLongOrNull()
            ?: throw IllegalArgumentException("App version component is too large: $versionName")
        val minor = match.groupValues[2].toLongOrNull()
            ?: throw IllegalArgumentException("App version component is too large: $versionName")
        val patch = match.groupValues[3].toLongOrNull()
            ?: throw IllegalArgumentException("App version component is too large: $versionName")
        val label = match.groupValues[4]
        val numberText = match.groupValues[5]
        val channel = when (label) {
            "alpha" -> Channel.ALPHA
            "beta" -> Channel.BETA
            else -> Channel.STABLE
        }
        val number = if (numberText.isEmpty()) {
            0L
        } else {
            numberText.toLongOrNull()
                ?: throw IllegalArgumentException("App prerelease number is too large: $versionName")
        }

        val modernLayout = major >= 2
        val minorLimit = if (modernLayout) 49 else 99
        require(major <= 2_100 && minor <= minorLimit && patch <= 99) {
            "App version components exceed the supported version-code layout: $versionName"
        }
        if (channel != Channel.STABLE) {
            require(number in 1L..99L) {
                "App prerelease number must be between 1 and 99: $versionName"
            }
        }
        require(channel != Channel.BETA || modernLayout) {
            "Beta releases before 2.0.0 are unsupported because their version-code slots collide: $versionName"
        }

        return ParsedVersion(major, minor, patch, channel, number)
    }

    private fun codeFor(version: ParsedVersion): Int {
        val modernLayout = version.major >= 2
        val minorWidth = if (modernLayout) 20_000L else 10_000L
        val patchWidth = if (modernLayout) 200L else 100L
        val base = version.major * 1_000_000L + version.minor * minorWidth + version.patch * patchWidth
        val suffix = when (version.channel) {
            Channel.STABLE -> if (modernLayout) 200L else 0L
            Channel.ALPHA -> version.prereleaseNumber
            Channel.BETA -> 100L + version.prereleaseNumber
        }
        val code = base + suffix
        require(code in 1..MAX_VERSION_CODE.toLong()) {
            "Generated Android versionCode is outside 1..$MAX_VERSION_CODE"
        }
        return code.toInt()
    }

    private fun parseConfiguredCode(raw: String?): Int {
        if (raw == null) return DEFAULT_VERSION_CODE
        val code = raw.toIntOrNull()
            ?: throw IllegalArgumentException("APP_VERSION_CODE must be a positive integer")
        require(code in 1..MAX_VERSION_CODE) {
            "APP_VERSION_CODE must be between 1 and $MAX_VERSION_CODE"
        }
        return code
    }
}
