package com.macedonga.visor.core.settings

import org.json.JSONArray
import org.json.JSONObject

/** How a setting is shown and what it accepts, from the firmware and Meta app mapping in settings_ui.json. */
data class SettingSpec(
    val key: String,
    val label: String,
    val note: String?,
    val group: String,
    val control: Control,
    // The key says "disabled" and the label is the positive form, so the switch shows the opposite.
    val invert: Boolean,
    // Shown by the Meta app; the rest are internal knobs.
    val userFacing: Boolean,
    val confidence: String,
    // Meta product ids the Meta app shows this on (null: all), and ones it never does.
    val products: Set<Int>? = null,
    val notProducts: Set<Int> = emptySet(),
)

/** Whether the Meta app offers this on [product]; anything goes while the product is unknown. */
fun SettingSpec.offeredOn(product: Int?) = offered(products, notProducts, product)

fun Control.Option.offeredOn(product: Int?) = offered(products, notProducts, product)

private fun offered(only: Set<Int>?, not: Set<Int>, product: Int?) =
    product == null || (product !in not && (only == null || product in only))

/** The Meta app's product ids by the codename a build fingerprint names, "Meta/hammerhead/greathammerhead:14/...". */
private val PRODUCT_IDS = mapOf(
    "stella" to 257, "hammerhead" to 258, "greatwhite" to 259, "zebra" to 262, "lager" to 263, "mako" to 264,
    "pylades" to 265, "greathammerhead" to 266, "topaz" to 267, "orca" to 268, "oatmeal" to 269, "diamond" to 514,
    "colada" to 770, "tiki" to 771, "swiftlet" to 1537, "ithaca" to 1538, "amethyst" to 1793, "solvang" to 2049,
)

fun productId(build: String?): Int? =
    build?.split('/')?.getOrNull(2)?.substringBefore(':')?.lowercase()?.let { PRODUCT_IDS[it] }

sealed interface Control {
    data object Toggle : Control

    /** [detail] is the Meta app's second line, such as "3K at 30fps" under "High resolution". */
    data class Option(
        val value: String,
        val label: String,
        val hidden: Boolean = false,
        val detail: String? = null,
        val products: Set<Int>? = null,
        val notProducts: Set<Int> = emptySet(),
    )

    data class Choice(val options: List<Option>) : Control

    data class Range(val min: Double, val max: Double, val step: Double, val unit: String) : Control

    /** An open count or size: no bounds exist beyond the optional [min] and [max]. */
    data class Number(val unit: String, val min: Long?, val max: Long?) : Control

    /** Stored in [unit] ("ms", "s" or "min"), either one of [options] or anywhere in [min]..[max]. */
    data class Duration(val unit: String, val options: List<Long>, val min: Long?, val max: Long?) : Control

    /** An instant the glasses keep, in [unit] since the epoch. */
    data class Timestamp(val unit: String) : Control

    data class Text(val max: Int, val what: String) : Control

    /** A locale as the phone app writes it, en_GB. */
    data object Locale : Control

    /** An installed app's package, for [role] "sms" or "media", plus fixed [extra] values. */
    data class App(val role: String, val extra: List<Option>) : Control

    /** Phone numbers joined by [separator]. */
    data class PhoneList(val separator: String) : Control

    /** One number packing several values: [width] bits from [shift], named by [options] when wider than one bit. */
    data class BitField(val label: String, val shift: Int, val width: Int, val options: List<Option>)

    data class Bits(val fields: List<BitField>) : Control

    /** The continuous AI camera's JSON calendar: profiles of settings, and weekly windows that pick one. */
    data object Schedule : Control

    /** An encoded value with a known layout; [schema] is the field map an editor is built from. */
    data class Structured(val format: String, val schema: JSONObject) : Control

    data class ReadOnly(val why: String) : Control
}

/** The display order of groups. */
val GROUPS = listOf(
    "Glasses", "Sound", "Touch and gestures", "Camera", "Video and live", "Meta AI", "Live AI and translation",
    "Calls and messages", "Notifications", "Music and media", "Connectivity", "Health and fitness",
    "Accessibility", "Privacy and data", "Updates and system", "Display", "Neural band gestures",
    "Developer and tests",
)

internal fun loadSpecs(): Map<String, SettingSpec> {
    val stream = GlassesSettings::class.java.getResourceAsStream("settings_ui.json") ?: return emptyMap()
    val array = JSONArray(stream.bufferedReader().use { it.readText() })
    return (0 until array.length()).map { parseSpec(array.getJSONObject(it)) }.associateBy { it.key }
}

// The Meta app's own translation where the catalogue has one ("label_it"), else the English.
private val language = java.util.Locale.getDefault().language

private fun JSONObject.text(name: String): String? =
    optString("${name}_$language").ifEmpty { null } ?: optString(name).ifEmpty { null }

private fun JSONObject.ids(name: String): Set<Int>? = optJSONArray(name)?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() }

private fun parseOption(o: JSONObject) = Control.Option(
    o.getString("value"), o.text("label")!!, o.optBoolean("hidden"), o.text("detail"), o.ids("products"), o.ids("not_products").orEmpty(),
)

private fun parseSpec(o: JSONObject): SettingSpec = SettingSpec(
    key = o.getString("key"),
    label = o.text("label")!!,
    note = o.text("note"),
    group = o.getString("group"),
    control = parseControl(o.getJSONObject("control")),
    invert = o.optBoolean("invert"),
    userFacing = o.optBoolean("user_facing"),
    confidence = o.optString("confidence", "low"),
    products = o.ids("products"),
    notProducts = o.ids("not_products").orEmpty(),
)

private fun parseControl(c: JSONObject): Control = when (c.getString("type")) {
    "toggle" -> Control.Toggle
    "choice" -> c.getJSONArray("options").let { a ->
        Control.Choice((0 until a.length()).map { parseOption(a.getJSONObject(it)) })
    }
    "range" -> Control.Range(c.getDouble("min"), c.getDouble("max"), c.optDouble("step", 1.0), c.optString("unit"))
    "duration" -> Control.Duration(
        unit = c.optString("unit", "ms"),
        options = c.optJSONArray("options")?.let { a -> (0 until a.length()).map { a.getLong(it) } }.orEmpty(),
        min = if (c.has("min")) c.getLong("min") else null,
        max = if (c.has("max")) c.getLong("max") else null,
    )
    "number" -> Control.Number(
        unit = c.optString("unit"),
        min = if (c.has("min")) c.getLong("min") else null,
        max = if (c.has("max")) c.getLong("max") else null,
    )
    "timestamp" -> Control.Timestamp(c.optString("unit", "ms"))
    "text" -> Control.Text(c.optInt("max", 100), c.optString("what"))
    "locale" -> Control.Locale
    "app" -> Control.App(
        role = c.optString("role"),
        extra = c.optJSONArray("extra")?.let { a ->
            (0 until a.length()).map { parseOption(a.getJSONObject(it)) }
        }.orEmpty(),
    )
    "phone_list" -> Control.PhoneList(c.optString("separator", ","))
    "bits" -> c.getJSONArray("fields").let { a ->
        Control.Bits(
            (0 until a.length()).map { a.getJSONObject(it) }.map { f ->
                Control.BitField(
                    label = f.text("label")!!,
                    shift = f.getInt("shift"),
                    width = f.optInt("width", 1),
                    options = f.optJSONArray("options")?.let { o ->
                        (0 until o.length()).map { parseOption(o.getJSONObject(it)) }
                    }.orEmpty(),
                )
            },
        )
    }
    "schedule" -> Control.Schedule
    "structured" -> Control.Structured(c.getString("format"), c.optJSONObject("schema") ?: JSONObject())
    else -> Control.ReadOnly(c.optString("why"))
}

/** The value as a person reads it: the option's name, a number with its unit, a duration, a date. */
fun SettingSpec.display(value: String?): String {
    if (value == null) return "Unknown"
    val v = value.trim()
    return when (val c = control) {
        Control.Toggle -> if (asBool(v) != invert) "On" else "Off"
        // A short spec line ("3K at 30fps") says more than its title ("High resolution").
        is Control.Choice -> c.options.firstOrNull { it.value == v }?.let { o -> o.detail?.takeIf { it.length <= 24 } ?: o.label } ?: v.ifEmpty { "Not set" }
        is Control.Range -> v.toDoubleOrNull()?.let { number(it) + unitSuffix(c.unit) } ?: v
        is Control.Number -> v.toDoubleOrNull()?.let { number(it) + unitSuffix(c.unit) } ?: v
        is Control.Duration -> v.toLongOrNull()?.let { duration(it, c.unit) } ?: v
        is Control.Timestamp -> v.toLongOrNull()?.let { timestamp(it, c.unit) } ?: v
        Control.Locale -> v.takeIf { it.isNotEmpty() }?.let { localeOf(it).displayName } ?: "Not set"
        is Control.App -> c.extra.firstOrNull { it.value == v }?.label ?: v.ifEmpty { "Not set" }
        is Control.PhoneList -> v.split(c.separator).map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ").ifEmpty { "None" }
        else -> v.ifEmpty { "Not set" }
    }
}

fun number(x: Double): String = if (x == Math.floor(x) && Math.abs(x) < 1e15) x.toLong().toString() else "%.2f".format(x).trimEnd('0').trimEnd('.')

fun unitSuffix(unit: String): String = when (unit) {
    "" -> ""
    "%" -> "%"
    "count" -> ""
    else -> " $unit"
}

/** [amount] in [unit] ("ms", "s", "min" or "h") as "1 h 30 min", "15 s", "250 ms". */
fun duration(amount: Long, unit: String): String {
    val ms = amount * when (unit) {
        "s" -> 1_000L
        "min" -> 60_000L
        "h" -> 3_600_000L
        else -> 1L
    }
    if (ms == 0L) return "0"
    if (ms < 1_000) return "$ms ms"
    val parts = listOf(86_400_000L to "d", 3_600_000L to "h", 60_000L to "min", 1_000L to "s")
    var rest = ms
    val words = buildList {
        for ((size, name) in parts) {
            if (rest >= size) {
                add("${rest / size} $name")
                rest %= size
            }
        }
    }
    return words.take(2).joinToString(" ")
}

fun timestamp(amount: Long, unit: String): String {
    if (amount <= 0) return "Not set"
    val ms = if (unit == "s") amount * 1_000 else amount
    return java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT).format(java.util.Date(ms))
}

/** "en_GB" or "en-GB" as a Locale. */
fun localeOf(tag: String): java.util.Locale = java.util.Locale.forLanguageTag(tag.trim().replace('_', '-'))

/** A Locale the way the phone app writes it: language, underscore, country. */
fun localeValue(locale: java.util.Locale): String = if (locale.country.isEmpty()) locale.language else "${locale.language}_${locale.country}"
