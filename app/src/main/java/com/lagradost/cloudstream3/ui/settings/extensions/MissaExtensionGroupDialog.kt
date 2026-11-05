package com.lagradost.cloudstream3.ui.settings.extensions

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.R
import com.lagradost.cloudstream3.ui.universe.MissaExtensionGroups

/** EXT-GROUPS : fenêtres de choix du groupe de médias d'une extension / d'un provider. */
object MissaExtensionGroupDialog {

    /**
     * @param pluginPath chemin du fichier .cs3 de l'extension installée
     * @param extensionName nom affiché dans le titre
     * @param onChanged appelé (thread principal) après chaque enregistrement, pour rafraîchir l'affichage
     */
    fun show(context: Context, pluginPath: String, extensionName: String, onChanged: () -> Unit) {
        val providers = MissaExtensionGroups.providersOfExtension(pluginPath)
        if (providers.size <= 1) {
            showPicker(
                context = context,
                title = extensionName,
                current = MissaExtensionGroups.getForExtension(pluginPath)
            ) { chosen ->
                MissaExtensionGroups.setForExtension(pluginPath, chosen)
                onChanged()
            }
        } else {
            showHub(context, pluginPath, extensionName, providers, onChanged)
        }
    }

    private fun showHub(
        context: Context,
        pluginPath: String,
        extensionName: String,
        providers: List<MainAPI>,
        onChanged: () -> Unit
    ) {
        val extGroups = MissaExtensionGroups.getForExtension(pluginPath)
        val extLabel = MissaExtensionGroups.label(context, extGroups)

        val lines = ArrayList<String>(providers.size + 1)
        lines.add(context.getString(R.string.missa_ext_group_whole_extension) + " → " + extLabel)
        providers.forEach { api ->
            val own = MissaExtensionGroups.getForProvider(api.name)
            val shown = if (own != null) {
                MissaExtensionGroups.label(context, own)
            } else {
                context.getString(R.string.missa_ext_group_inherit, extLabel)
            }
            lines.add("${api.name} → $shown")
        }

        AlertDialog.Builder(context, R.style.AlertDialogCustom)
            .setTitle(context.getString(R.string.missa_ext_group_dialog_title, extensionName))
            .setItems(lines.toTypedArray()) { _, which ->
                if (which == 0) {
                    showPicker(context, extensionName, extGroups) { chosen ->
                        MissaExtensionGroups.setForExtension(pluginPath, chosen)
                        onChanged()
                    }
                } else {
                    val api = providers[which - 1]
                    showPicker(context, api.name, MissaExtensionGroups.getForProvider(api.name)) { chosen ->
                        MissaExtensionGroups.setForProvider(api.name, chosen)
                        onChanged()
                    }
                }
            }
            .setNegativeButton(R.string.missa_ext_group_cancel, null)
            .show()
    }

    /** Liste à cocher. onSave(null) = Auto, onSave(emptySet()) = Aucun, sinon les groupes cochés. */
    private fun showPicker(
        context: Context,
        title: String,
        current: Set<String>?,
        onSave: (Set<String>?) -> Unit
    ) {
        val groups = MissaExtensionGroups.ASSIGNABLE_GROUPS
        val labels = groups.map { context.getString(it.labelRes) }.toTypedArray()
        val checked = BooleanArray(groups.size) { i -> current?.contains(groups[i].id) == true }

        AlertDialog.Builder(context, R.style.AlertDialogCustom)
            .setTitle(context.getString(R.string.missa_ext_group_dialog_title, title))
            .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton(R.string.missa_ext_group_save) { _, _ ->
                val selected = groups.filterIndexed { i, _ -> checked[i] }.map { it.id }.toSet()
                if (current == null && selected.isEmpty()) {
                    // Rien coché alors que c'était Auto : on ne change rien (pas de "Aucun" par accident)
                    onSave(null)
                } else {
                    onSave(selected) // emptySet() = Aucun
                }
            }
            .setNeutralButton(R.string.missa_ext_group_reset_auto) { _, _ -> onSave(null) }
            .setNegativeButton(R.string.missa_ext_group_cancel, null)
            .show()
    }
}
