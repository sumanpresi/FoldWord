package com.example.foldword

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/** One line in a document or folder list. */
data class Row(
    val title: String,
    val subtitle: String,
    val badge: String? = null,
    val enabled: Boolean = true,
    val onClick: () -> Unit = {},
    val onLongClick: (() -> Unit)? = null
)

/** Fills a row view (layout row_doc) from a [Row]. */
fun bindRow(v: View, row: Row) {
    v.findViewById<TextView>(R.id.rowTitle).text = row.title
    v.findViewById<TextView>(R.id.rowSubtitle).apply {
        text = row.subtitle
        visibility = if (row.subtitle.isEmpty()) View.GONE else View.VISIBLE
    }
    v.findViewById<TextView>(R.id.rowBadge).apply {
        text = row.badge ?: ""
        visibility = if (row.badge == null) View.GONE else View.VISIBLE
    }
    v.alpha = if (row.enabled) 1f else 0.45f
    v.setOnClickListener { if (row.enabled) row.onClick() }
    val long = row.onLongClick
    if (long != null) v.setOnLongClickListener { long(); true } else v.setOnLongClickListener(null)
}

/** Short label shown in the coloured square next to a file name. */
fun badgeFor(extension: String): String = when (extension.lowercase()) {
    "docx" -> "DOCX"
    "txt" -> "TXT"
    "md" -> "MD"
    "pdf" -> "PDF"
    "folder" -> "▤"
    else -> extension.uppercase().take(4)
}

class RowAdapter : RecyclerView.Adapter<RowAdapter.Holder>() {
    class Holder(v: View) : RecyclerView.ViewHolder(v)

    var rows: List<Row> = emptyList()
        set(value) { field = value; notifyDataSetChanged() }

    override fun getItemCount() = rows.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.row_doc, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = bindRow(holder.itemView, rows[position])
}
