package com.willyshare.willykez.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "transfers")
data class TransferEntity(
    @PrimaryKey val id: String,
    val fileName: String,
    val category: String, // PHOTO, VIDEO, DOC, APP, AUDIO, ARCHIVE
    val sizeBytes: Long,
    val timestamp: Long,
    val deviceName: String,
    val isSend: Boolean,
    val status: String, // COMPLETED, FAILED, IN_PROGRESS
    val savedPath: String? = null, // Where a received file was written on disk
    /** Where a SENT file was read from - a content:// Uri (MediaStore pick, or a share-intent
     *  from another app) or a file:// Uri (folder browser pick), always as a Uri string so
     *  the history thumbnail loader can Uri.parse() it the same way regardless of source.
     *  Null for older rows recorded before this field existed, and for received files (which
     *  use [savedPath] instead - the file physically exists on THIS device either way, so
     *  there's no separate "source" to track). */
    val sourceUri: String? = null
)
