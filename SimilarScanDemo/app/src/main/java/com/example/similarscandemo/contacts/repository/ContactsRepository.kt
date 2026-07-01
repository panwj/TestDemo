package com.example.similarscandemo.contacts.repository

import android.content.Context
import android.provider.ContactsContract
import com.example.similarscandemo.contacts.model.ContactCategory
import com.example.similarscandemo.contacts.model.ContactItem

class ContactsRepository(private val context: Context) {
    fun loadCategories(): List<ContactCategory> {
        val contacts = loadContacts()
        val duplicatePhone = contacts
            .filter { it.phone.isNotBlank() }
            .groupBy { normalizePhone(it.phone) }
            .values
            .filter { it.size > 1 }
            .flatten()
        val sameName = contacts
            .filter { it.name.isNotBlank() }
            .groupBy { it.name.trim().lowercase() }
            .values
            .filter { it.size > 1 }
            .flatten()
        val incomplete = contacts.filter { it.name.isBlank() || it.phone.isBlank() }
        return listOf(
            ContactCategory(
                title = "Duplicate Contacts",
                summary = "${duplicatePhone.size} contacts share the same phone number",
                contacts = duplicatePhone
            ),
            ContactCategory(
                title = "Same Name",
                summary = "${sameName.size} contacts may belong together",
                contacts = sameName
            ),
            ContactCategory(
                title = "Incomplete Contacts",
                summary = "${incomplete.size} contacts miss a name or phone number",
                contacts = incomplete
            )
        )
    }

    private fun loadContacts(): List<ContactItem> {
        val contacts = mutableListOf<ContactItem>()
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            projection,
            null,
            null,
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC"
        )?.use { cursor ->
            val idIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
            val nameIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val phoneIndex = cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (cursor.moveToNext()) {
                contacts += ContactItem(
                    id = cursor.getLong(idIndex),
                    name = cursor.getString(nameIndex).orEmpty(),
                    phone = cursor.getString(phoneIndex).orEmpty()
                )
            }
        }
        return contacts.distinctBy { it.id to normalizePhone(it.phone) }
    }

    private fun normalizePhone(phone: String): String {
        return phone.filter { it.isDigit() }
    }
}
