package com.melih.altyazi

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class HistoryAdapter(private val items: List<SubtitleEntry>) : RecyclerView.Adapter<HistoryAdapter.VH>() {

    var showNotes = true

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val who: TextView = v.findViewById(R.id.hWho)
        val time: TextView = v.findViewById(R.id.hTime)
        val tr: TextView = v.findViewById(R.id.hTr)
        val en: TextView = v.findViewById(R.id.hEn)
        val notes: TextView = v.findViewById(R.id.hNotes)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false))

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val e = items[position]
        h.who.text = e.who
        h.time.text = e.time
        h.tr.text = e.tr
        h.en.text = e.en
        val n = showNotes && e.notes.isNotEmpty()
        h.notes.visibility = if (n) View.VISIBLE else View.GONE
        h.notes.text = e.notes.joinToString("\n")
    }
}
