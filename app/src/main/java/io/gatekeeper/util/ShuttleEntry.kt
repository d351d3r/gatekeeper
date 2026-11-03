package io.gatekeeper.util

import android.os.Parcel
import android.os.Parcelable
import android.provider.DocumentsContract.Document

/**
 * Запись листинга File Shuttle: что профиль с файлами сообщает другому о файле.
 *
 * Раньше запись ходила как `Map<String, Serializable>` внутри нетипизированных
 * `List`/`Map` AIDL. Такой ответ разбирается устаревшими `Parcel.readArrayList` и
 * `readHashMap`, которые десериализуют любые Serializable, положенные другой стороной.
 * Parcelable читает только свои поля и ничего не десериализует.
 *
 * null в полях -- колонка не известна (нейтральный отказ по непринятому пути).
 */
data class ShuttleEntry(
    val documentId: String,
    val displayName: String? = null,
    val mimeType: String? = null,
    val size: Long? = null,
    val lastModified: Long? = null,
    val flags: Int = 0,
) : Parcelable {
    /** Значение колонки [Document] для курсора провайдера; чужая колонка -- null. */
    fun column(name: String): Any? = when (name) {
        Document.COLUMN_DOCUMENT_ID -> documentId
        Document.COLUMN_DISPLAY_NAME -> displayName
        Document.COLUMN_MIME_TYPE -> mimeType
        Document.COLUMN_SIZE -> size
        Document.COLUMN_LAST_MODIFIED -> lastModified
        Document.COLUMN_FLAGS -> flags
        else -> null
    }

    override fun writeToParcel(dest: Parcel, parcelFlags: Int) {
        dest.writeString(documentId)
        dest.writeString(displayName)
        dest.writeString(mimeType)
        writeOptionalLong(dest, size)
        writeOptionalLong(dest, lastModified)
        dest.writeInt(flags)
    }

    override fun describeContents(): Int = 0

    companion object {
        @JvmField
        val CREATOR: Parcelable.Creator<ShuttleEntry> = object : Parcelable.Creator<ShuttleEntry> {
            override fun createFromParcel(source: Parcel): ShuttleEntry = ShuttleEntry(
                documentId = source.readString().orEmpty(),
                displayName = source.readString(),
                mimeType = source.readString(),
                size = readOptionalLong(source),
                lastModified = readOptionalLong(source),
                flags = source.readInt(),
            )

            override fun newArray(size: Int): Array<ShuttleEntry?> = arrayOfNulls(size)
        }

        private fun writeOptionalLong(dest: Parcel, value: Long?) {
            dest.writeInt(if (value == null) 0 else 1)
            if (value != null) dest.writeLong(value)
        }

        private fun readOptionalLong(source: Parcel): Long? =
            if (source.readInt() != 0) source.readLong() else null
    }
}
