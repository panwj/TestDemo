package com.example.similarscandemo.contacts

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.TextView
import com.example.similarscandemo.R
import com.example.similarscandemo.contacts.model.ContactCategory

class ContactCategoryAdapter(
    private val context: Context,
    private var categories: List<ContactCategory>
) : BaseAdapter() {
    fun submitList(newCategories: List<ContactCategory>) {
        categories = newCategories
        notifyDataSetChanged()
    }

    override fun getCount(): Int = categories.size
    override fun getItem(position: Int): ContactCategory = categories[position]
    override fun getItemId(position: Int): Long = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_contact_category, parent, false)
        val category = getItem(position)
        view.findViewById<TextView>(R.id.categoryTitle).text = category.title
        view.findViewById<TextView>(R.id.categorySummary).text = category.summary
        view.findViewById<TextView>(R.id.categoryPreview).text = category.contacts
            .take(6)
            .joinToString(separator = "\n") { "${it.name.ifBlank { "No Name" }} · ${it.phone.ifBlank { "No Phone" }}" }
            .ifBlank { "No items need attention" }
        return view
    }
}
