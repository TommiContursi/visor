package com.macedonga.visor.core.identity

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the app knows about one identity. */
class IdentityBundle(
    /** The owner key, PEM encoded, exactly as the desktop wrote it. */
    val privateKeyPem: ByteArray,
    val glassesAddress: String,
    val serial: String,
    val createdAt: String,
    /** How this identity was obtained: `change-owner`, `extracted` or `imported`. */
    val provenance: String,
    val state: String,
) {

    fun withState(state: String): IdentityBundle =
        IdentityBundle(privateKeyPem, glassesAddress, serial, createdAt, provenance, state)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IdentityBundle) return false
        return privateKeyPem.contentEquals(other.privateKeyPem) &&
            glassesAddress == other.glassesAddress &&
            serial == other.serial &&
            createdAt == other.createdAt &&
            provenance == other.provenance &&
            state == other.state
    }

    override fun hashCode(): Int {
        var result = privateKeyPem.contentHashCode()
        result = 31 * result + glassesAddress.hashCode()
        result = 31 * result + serial.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + provenance.hashCode()
        result = 31 * result + state.hashCode()
        return result
    }

    /** Deliberately says nothing about the key. */
    override fun toString(): String =
        "IdentityBundle(serial=$serial, address=$glassesAddress, state=$state, " +
            "provenance=$provenance, key=${privateKeyPem.size}B)"
}

/** The recorded state of a bundle, explicit rather than inferred. */
object BundleState {
    const val UNPROVISIONED = "unprovisioned"
    const val UNVERIFIED = "unverified"
    const val VERIFIED = "verified"
    const val EVICTED = "evicted"
}

/** The timestamp format the store records, from `bundle.py`'s `now_iso`. */
fun nowIso(instant: Instant = Instant.now()): String =
    ISO_SECONDS_Z.format(instant.atOffset(ZoneOffset.UTC))

private val ISO_SECONDS_Z: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")

/** The JSON shape, which is a wire format rather than an internal detail. */
@Serializable
internal class BundleJson(
    @SerialName("private_key_pem") val privateKeyPem: String,
    @SerialName("glasses_address") val glassesAddress: String,
    @SerialName("serial") val serial: String,
    @SerialName("created_at") val createdAt: String,
    @SerialName("provenance") val provenance: String,
    @SerialName("state") val state: String,
)

/** Lenient about what it reads and strict about what it writes. */
internal val BundleFormat: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

internal fun IdentityBundle.toJsonModel(): BundleJson = BundleJson(
    privateKeyPem = Base64.getEncoder().encodeToString(privateKeyPem),
    glassesAddress = glassesAddress,
    serial = serial,
    createdAt = createdAt,
    provenance = provenance,
    state = state,
)

internal fun BundleJson.toBundle(): IdentityBundle = IdentityBundle(
    privateKeyPem = Base64.getDecoder().decode(privateKeyPem),
    glassesAddress = glassesAddress,
    serial = serial,
    createdAt = createdAt,
    provenance = provenance,
    state = state,
)

/** One bundle as the JSON text `bundle_to_json` plus `json.dumps` would produce. */
fun bundleToJson(bundle: IdentityBundle): String =
    BundleFormat.encodeToString(BundleJson.serializer(), bundle.toJsonModel())

fun bundleFromJson(text: String): IdentityBundle =
    BundleFormat.decodeFromString(BundleJson.serializer(), text).toBundle()

/** A whole store or transfer payload: a JSON array of bundles. */
fun bundlesToJson(bundles: List<IdentityBundle>): String =
    BundleFormat.encodeToString(
        kotlinx.serialization.builtins.ListSerializer(BundleJson.serializer()),
        bundles.map { it.toJsonModel() },
    )

fun bundlesFromJson(text: String): List<IdentityBundle> =
    BundleFormat.decodeFromString(
        kotlinx.serialization.builtins.ListSerializer(BundleJson.serializer()),
        text,
    ).map { it.toBundle() }
