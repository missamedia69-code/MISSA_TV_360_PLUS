package com.lagradost.cloudstream3.ui.universe

import android.content.Context
import androidx.annotation.StringRes
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.removeKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.R
import java.io.File
import java.util.Locale

/** Un groupe de médias (= un univers) que l'utilisateur peut attribuer à une extension ou à un provider. */
data class AssignableGroup(
    val id: String,
    @StringRes val labelRes: Int
)

/**
 * EXT-GROUPS : choix par l'utilisateur du groupe de médias où chaque extension (ou provider) charge son contenu.
 *
 * Valeur d'un choix :
 *  - null            -> Auto (heuristique MediaUniverseClassifier, comportement historique)
 *  - emptySet()      -> "Aucun" : ne jamais charger ce contenu dans les univers
 *  - setOf("films")  -> uniquement ces groupes
 *
 * Priorité : provider > extension > Auto.
 * "music" et "kids" ne sont JAMAIS proposés : ces univers sont 100 % YouTube.
 */
object MissaExtensionGroups {
    private const val FOLDER = "missa_ext_groups_v1"
    private const val EXT_PREFIX = "ext:"
    private const val PROV_PREFIX = "prov:"

    /** Valeur stockée pour "Aucun". */
    const val GROUP_NONE = "none"

    val ASSIGNABLE_GROUPS: List<AssignableGroup> = listOf(
        AssignableGroup("films", R.string.universe_films_title),
        AssignableGroup("series", R.string.universe_series_title),
        AssignableGroup("documentaries", R.string.universe_documentaries_title),
        AssignableGroup("shorts", R.string.universe_shorts_title),
        AssignableGroup("live_tv", R.string.universe_live_tv_title),
        AssignableGroup("sports", R.string.universe_sports_title),
        AssignableGroup("podcasts", R.string.universe_podcasts_title),
        AssignableGroup("radio", R.string.universe_radio_title)
    )

    /** Incrémenté à chaque modification : force le rechargement du catalogue (voir computeProviderSignature). */
    @Volatile
    var revision: Int = 0
        private set

    fun signature(): String = "grp$revision"

    // ---------------------------------------------------------------- clés de stockage

    /** Clé stable d'une extension : nom du fichier .cs3 sans extension (identique pour la liste locale et pour sourcePlugin). */
    fun extensionKey(pluginPath: String): String =
        EXT_PREFIX + File(pluginPath).nameWithoutExtension

    fun providerKey(apiName: String): String = PROV_PREFIX + apiName

    // ---------------------------------------------------------------- lecture

    /** Nettoie une valeur lue. null = Auto, emptySet() = Aucun, sinon groupes valides. */
    internal fun normalize(raw: Collection<String>?): Set<String>? {
        if (raw == null) return null
        val ids = raw.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }
        if (ids.isEmpty()) return null
        if (GROUP_NONE in ids) return emptySet()
        val valid = ids
            .filter { id -> ASSIGNABLE_GROUPS.any { it.id == id } }
            .toCollection(LinkedHashSet())
        return if (valid.isEmpty()) null else valid
    }

    private fun read(key: String): Set<String>? =
        normalize(runCatching { getKey<Array<String>>(FOLDER, key) }.getOrNull()?.toList())

    fun getForExtension(pluginPath: String): Set<String>? = read(extensionKey(pluginPath))

    fun getForProvider(apiName: String): Set<String>? = read(providerKey(apiName))

    /** Choix effectif d'un provider : provider > extension > null (Auto). */
    fun resolveForApi(api: MainAPI): Set<String>? {
        getForProvider(api.name)?.let { return it }
        val path = api.sourcePlugin ?: return null
        return getForExtension(path)
    }

    fun resolveForApiName(apiName: String): Set<String>? {
        val api = APIHolder.getApiFromNameNull(apiName) ?: return null
        return resolveForApi(api)
    }

    /** true si l'utilisateur a mis ce provider sur "Aucun". */
    fun isDisabled(api: MainAPI): Boolean = resolveForApi(api)?.isEmpty() == true

    /** true si l'utilisateur a fait un choix (groupes ou Aucun) pour ce provider. */
    fun hasAssignment(api: MainAPI): Boolean = resolveForApi(api) != null

    /** Tous les providers enregistrés par une extension donnée (par son chemin de fichier). */
    fun providersOfExtension(pluginPath: String): List<MainAPI> {
        val key = extensionKey(pluginPath)
        return APIHolder.apis.filter { api ->
            api.sourcePlugin?.let { extensionKey(it) } == key
        }
    }

    // ---------------------------------------------------------------- écriture

    /** groups == null -> retour en Auto ; emptySet() -> Aucun. */
    fun setForExtension(pluginPath: String, groups: Set<String>?) =
        write(extensionKey(pluginPath), groups)

    fun setForProvider(apiName: String, groups: Set<String>?) =
        write(providerKey(apiName), groups)

    private fun write(key: String, groups: Set<String>?) {
        if (groups == null) {
            removeKey(FOLDER, key)
        } else {
            val stored = if (groups.isEmpty()) listOf(GROUP_NONE) else groups.sorted()
            setKey(FOLDER, key, stored.toTypedArray())
        }
        revision++
    }

    // ---------------------------------------------------------------- application sur les univers

    /**
     * Combine la structure du contenu ([auto], déjà passée au crible par l'appelant) avec les
     * choix de l'utilisateur pour les sources d'une entrée.
     *  - aucune source réglée           -> auto (inchangé)
     *  - toutes les sources réglées     -> les groupes choisis **que le crible retient**
     *  - mélange (réglées + Auto)       -> auto + groupes retenus
     *
     * Chaque ensemble « choisi » passe par [MissaContentSieve.sift] : une extension réglée sur
     * Films + Séries verse ses films dans Films et ses séries dans Séries, au lieu de verser
     * les deux dans les deux. Fonction PURE (testable sans Android).
     */
    internal fun mergeUniverses(
        auto: Set<String>,
        forcedPerSource: Collection<Set<String>?>
    ): Set<String> {
        if (forcedPerSource.isEmpty() || forcedPerSource.all { it == null }) return auto
        val forcedUnion = LinkedHashSet<String>()
        forcedPerSource.forEach { forced ->
            if (forced != null) forcedUnion.addAll(MissaContentSieve.sift(forced, auto))
        }
        val hasAutoSource = forcedPerSource.any { it == null }
        return if (hasAutoSource) {
            LinkedHashSet(auto).apply { addAll(forcedUnion) }
        } else {
            forcedUnion
        }
    }

    fun mergeUniversesForSources(auto: Set<String>, sourceApiNames: Collection<String>): Set<String> =
        mergeUniverses(auto, sourceApiNames.map { resolveForApiName(it) })

    // ---------------------------------------------------------------- affichage

    fun label(context: Context, groups: Set<String>?): String = when {
        groups == null -> context.getString(R.string.missa_ext_group_auto)
        groups.isEmpty() -> context.getString(R.string.missa_ext_group_none)
        else -> ASSIGNABLE_GROUPS
            .filter { it.id in groups }
            .joinToString(" • ") { context.getString(it.labelRes) }
    }
}
