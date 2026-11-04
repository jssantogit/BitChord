package com.music.bitchord.acquisition.a0

import android.app.Activity
import android.app.AlertDialog
import com.music.bitchord.download.SoulseekBatchPlan

/**
 * Single-purpose visible list for previously unresolved acquisition slots.
 *
 * Android's AlertController hides a setItems() list if the same dialog also
 * owns a setMessage() body. Keep the guidance on the parent review screen
 * and put ONLY the selectable pending titles in this native dialog.
 * Selection is navigation, never P/F authorization or implicit download.
 */
internal object A0PendingReviewChooserDialog {
    fun create(
        activity:Activity,
        pendingIndices:List<Int>,
        entries:List<SoulseekBatchPlan.Entry>,
        onSelect:(Int)->Unit,
    ):AlertDialog {
        require(pendingIndices.isNotEmpty())
        require(pendingIndices.distinct().size==pendingIndices.size)
        require(pendingIndices.all { it in entries.indices })
        val labels=pendingIndices.map { index ->
            "${index+1}. ${entries[index].query}"
        }.toTypedArray()
        return AlertDialog.Builder(activity)
            .setTitle("${pendingIndices.size} " +
                if(pendingIndices.size==1) "faixa pendente" else "faixas pendentes")
            .setItems(labels) { _,position -> onSelect(pendingIndices[position]) }
            .setNegativeButton("Voltar",null)
            .create()
    }
}
