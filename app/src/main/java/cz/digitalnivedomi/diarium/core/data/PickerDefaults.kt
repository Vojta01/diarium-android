package cz.digitalnivedomi.diarium.core.data

/**
 * Static fallbacks + group metadata for the pickers.
 *
 * The live data comes from `activity_catalog` / `user_activities`, `habits` /
 * `habit_catalog` and `scales`. These constants only kick in offline or for a
 * brand-new user, and they reproduce the web's own fallbacks and its
 * `CATEGORY_ORDER` / `categoryGroups` translations.
 */
object PickerDefaults {

    /** Group order, verbatim from OnePageCheckIn.tsx `CATEGORY_ORDER`. */
    val CATEGORY_ORDER = listOf(
        "sociální", "práce", "volný čas", "sport", "jídlo", "zdraví",
        "wellness", "domácí práce", "počasí", "vlastní", "obecné",
    )

    /** Czech group titles, verbatim from cs.ts `categoryGroups`. */
    val CATEGORY_LABELS = mapOf(
        "sociální" to "Společenské",
        "volný čas" to "Záliby",
        "jídlo" to "Jídlo",
        "sport" to "Zdraví",
        "zdraví" to "Zdraví",
        "wellness" to "Mé lepší já",
        "práce" to "Práce",
        "počasí" to "Počasí",
        "domácí práce" to "Domácí práce",
        "vlastní" to "Vlastní",
        "obecné" to "Ostatní",
    )

    fun categoryLabel(category: String): String =
        CATEGORY_LABELS[category] ?: category

    /**
     * The `počasí` slice of the catalogue is rendered by the check-in's own
     * weather section, not as an activity group.
     */
    const val WEATHER_CATEGORY = "počasí"

    /**
     * Aliases the stored data drifts into: `user_activities` writes `záliby`
     * while the catalogue writes `volný čas`, and accents/case vary, so the same
     * group would otherwise surface twice under two headers.
     */
    private val CATEGORY_ALIASES = mapOf(
        "záliby" to "volný čas",
        "zaliby" to "volný čas",
        "volny cas" to "volný čas",
    )

    /** Trims + lowercases a raw category and folds known aliases onto one key. */
    fun canonicalCategory(category: String): String {
        val normalized = category.trim().lowercase()
        return CATEGORY_ALIASES[normalized] ?: normalized
    }

    /**
     * Comparison key for a stored entry value or a picker item's label: trimmed
     * and lower-cased. Entries were saved as label strings before the duplicate
     * rows were cleaned up in the database, so a day stored as `hacking` must
     * still select the surviving `Hacking` chip. This never rewrites the value
     * stored in an entry — it is only used to compare.
     *
     * Pure, so it is covered directly by the JVM tests.
     */
    fun normalizeLabel(label: String): String = label.trim().lowercase()

    /**
     * True when two labels are the same ignoring surrounding space and letter
     * case (e.g. `hacking` == `Hacking`). Symmetric, so either side may be the
     * stored value or the picker item.
     *
     * Pure, so it is covered directly by the JVM tests.
     */
    fun labelsMatch(a: String, b: String): Boolean = normalizeLabel(a) == normalizeLabel(b)

    /**
     * True when [label] is already present in a [stored] list of labels,
     * case-insensitively — the check a chip uses to render itself as selected,
     * so the entry `["Rodina", "hacking"]` still ticks the `Hacking` chip.
     *
     * Pure, so it is covered directly by the JVM tests.
     */
    fun isStoredLabel(stored: Collection<String>, label: String): Boolean =
        stored.any { labelsMatch(it, label) }

    /** The eight weather options (the `počasí` slice of the web's catalog). */
    val WEATHER_OPTIONS = listOf(
        ActivityDef(key = "slunecno", label = "Slunečno", icon = "☀️", category = "počasí"),
        ActivityDef(key = "zatazeno", label = "Zataženo", icon = "☁️", category = "počasí"),
        ActivityDef(key = "dest", label = "Déšť", icon = "🌧️", category = "počasí"),
        ActivityDef(key = "snih", label = "Sníh", icon = "❄️", category = "počasí"),
        ActivityDef(key = "mraz", label = "Mráz", icon = "🥶", category = "počasí"),
        ActivityDef(key = "horko", label = "Horko", icon = "🌡️", category = "počasí"),
        ActivityDef(key = "bourka", label = "Bouřka", icon = "🌩️", category = "počasí"),
        ActivityDef(key = "vitr", label = "Vítr", icon = "💨", category = "počasí"),
    )

    /**
     * Offline activity catalog, grouped per the web's categories. Used only when
     * `activity_catalog` cannot be read, so the form is never empty.
     */
    val ACTIVITY_FALLBACK: List<ActivityDef> = listOf(
        // sociální
        ActivityDef("rodina", "Rodina", "👨‍👩‍👧", "sociální"),
        ActivityDef("pratele", "Přátelé", "👥", "sociální"),
        ActivityDef("rande", "Rande", "💑", "sociální"),
        ActivityDef("party", "Párty", "🎉", "sociální"),
        // práce
        ActivityDef("office", "Office", "🏢", "práce"),
        // volný čas
        ActivityDef("filmy_a_tv", "Filmy a TV", "🎬", "volný čas"),
        ActivityDef("cteni", "Čtení", "📖", "volný čas"),
        ActivityDef("hrani_her", "Hraní her", "🎮", "volný čas"),
        ActivityDef("hudba", "Hudba", "🎵", "volný čas"),
        ActivityDef("relax", "Relax", "😌", "volný čas"),
        // sport
        ActivityDef("sport", "Sport", "🏃", "sport"),
        ActivityDef("trenink", "Trénink", "🏋️", "sport"),
        ActivityDef("chuze", "Chůze", "🚶", "sport"),
        ActivityDef("kolo", "Kolo", "🚴", "sport"),
        ActivityDef("plavani", "Plavání", "🏊", "sport"),
        ActivityDef("paddleboard", "Paddleboard", "🏄", "sport"),
        ActivityDef("snooker", "Snooker", "🎱", "sport"),
        // jídlo
        ActivityDef("jist_zdrave", "Jíst zdravě", "🥗", "jídlo"),
        ActivityDef("rychle_obcerstveni", "Rychlé občerstvení", "🍔", "jídlo"),
        ActivityDef("domaci_vyroba", "Domácí výroba", "🍳", "jídlo"),
        ActivityDef("restaurace", "Restaurace", "🍽️", "jídlo"),
        ActivityDef("donaska", "Donáška", "📦", "jídlo"),
        // zdraví
        ActivityDef("den_bez_masa", "Den bez masa", "🥬", "zdraví"),
        ActivityDef("zadne_sladkosti", "Žádné sladkosti", "🚫🍰", "zdraví"),
        ActivityDef("zadne_limonady", "Žádné limonády", "🚫🥤", "zdraví"),
        ActivityDef("pit_vody", "Pít vodu", "💧", "zdraví"),
        // wellness
        ActivityDef("meditovat", "Meditovat", "🧘", "wellness"),
        ActivityDef("laskavost", "Laskavost", "💝", "wellness"),
        ActivityDef("naslouchani", "Naslouchání", "👂", "wellness"),
        ActivityDef("darcovstvi", "Dárcovství", "💰", "wellness"),
        ActivityDef("dej_darek", "Dej dárek", "🎁", "wellness"),
        ActivityDef("terapie", "Terapie", "🛋️", "wellness"),
        ActivityDef("integrita", "Integrita", "⚖️", "wellness"),
        // domácí práce
        ActivityDef("nakupovani", "Nakupování", "🛒", "domácí práce"),
        ActivityDef("uklizeni", "Uklízení", "🧹", "domácí práce"),
        ActivityDef("vareni", "Vaření", "🍲", "domácí práce"),
        ActivityDef("prani", "Praní", "🧺", "domácí práce"),
        ActivityDef("zehleni", "Žehlení", "👕", "domácí práce"),
    )

    /** Mirrors the web's `FALLBACK_HABIT_DEFS` — the DB normally supplies these. */
    val HABIT_FALLBACK: List<HabitDef> = listOf(
        HabitDef(
            key = "alkohol",
            label = "Alkohol",
            icon = "🍺",
            category = "zdraví",
            color = "#ef4444",
            isNegative = true,
            source = "default",
        ),
    )

    /** Mirrors `DEFAULT_TEMPLATES` in `src/lib/templates.ts`. */
    val DEFAULT_TEMPLATES: List<NoteTemplate> = listOf(
        NoteTemplate(
            id = "default-1",
            name = "🌅 Ranní reflexe",
            content = "Dnes ráno se cítím...\n\n3 věci, na které se těším:\n1. \n2. \n3. \n\nCo dnes chci dokázat:",
            sortOrder = 1,
        ),
        NoteTemplate(
            id = "default-2",
            name = "🌙 Večerní shrnutí",
            content = "Dnešek byl...\n\nNejlepší moment dne:\n\nCo bych zlepšil/a:\n\nZa co jsem vděčný/á:",
            sortOrder = 2,
        ),
        NoteTemplate(
            id = "default-3",
            name = "💪 Těžký den",
            content = "Dnes to bylo těžké, protože...\n\nCo mě drží při životě:\n\nZítra bude líp, protože:",
            sortOrder = 3,
        ),
    )
}
