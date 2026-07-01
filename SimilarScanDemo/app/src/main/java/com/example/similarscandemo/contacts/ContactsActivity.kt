package com.example.similarscandemo.contacts

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import com.example.similarscandemo.MainActivity
import com.example.similarscandemo.R
import com.example.similarscandemo.compress.VideoCompressActivity
import com.example.similarscandemo.contacts.repository.ContactsRepository
import java.util.concurrent.Executors

class ContactsActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var permissionButton: Button
    private lateinit var summaryText: TextView
    private lateinit var listView: ListView
    private var adapter: ContactCategoryAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_contacts)
        permissionButton = findViewById(R.id.permissionButton)
        summaryText = findViewById(R.id.summaryText)
        listView = findViewById(R.id.contactList)
        permissionButton.setOnClickListener { ContactPermissionHelper.request(this) }
        findViewById<Button>(R.id.photoTabButton).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }
        findViewById<Button>(R.id.compressTabButton).setOnClickListener {
            startActivity(Intent(this, VideoCompressActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == ContactPermissionHelper.REQUEST_CODE) {
            render()
        }
    }

    private fun render() {
        if (!ContactPermissionHelper.hasPermission(this)) {
            permissionButton.visibility = View.VISIBLE
            summaryText.text = "Allow contacts access to find items that need attention"
            adapter?.submitList(emptyList())
            return
        }
        permissionButton.visibility = View.GONE
        summaryText.text = "Loading contacts..."
        executor.execute {
            val categories = ContactsRepository(applicationContext).loadCategories()
            val total = categories.sumOf { it.contacts.size }
            runOnUiThread {
                summaryText.text = "$total contacts may need cleanup"
                val current = adapter
                if (current == null) {
                    adapter = ContactCategoryAdapter(this, categories)
                    listView.adapter = adapter
                } else {
                    current.submitList(categories)
                }
            }
        }
    }
}
