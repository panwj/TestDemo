package com.example.similarscandemo.util

import android.database.AbstractCursor
import android.database.Cursor

fun <T> Cursor?.useCursor(block: (Cursor) -> T): T {
    val cursor = this ?: return block(EmptyCursor)
    return cursor.use(block)
}

fun Cursor.string(column: String): String {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getString(index) else ""
}

fun Cursor.int(column: String): Int {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getInt(index) else 0
}

fun Cursor.long(column: String): Long {
    val index = getColumnIndex(column)
    return if (index >= 0 && !isNull(index)) getLong(index) else 0L
}

private object EmptyCursor : Cursor by MatrixEmptyCursor()

private class MatrixEmptyCursor : AbstractCursor() {
    override fun getCount(): Int = 0
    override fun getColumnNames(): Array<String> = emptyArray()
    override fun getString(column: Int): String = ""
    override fun getShort(column: Int): Short = 0
    override fun getInt(column: Int): Int = 0
    override fun getLong(column: Int): Long = 0L
    override fun getFloat(column: Int): Float = 0f
    override fun getDouble(column: Int): Double = 0.0
    override fun isNull(column: Int): Boolean = true
}
