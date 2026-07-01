package com.example.similarscandemo.contacts.model

data class ContactItem(
    val id: Long,
    val name: String,
    val phone: String
)

data class ContactCategory(
    val title: String,
    val summary: String,
    val contacts: List<ContactItem>
)
