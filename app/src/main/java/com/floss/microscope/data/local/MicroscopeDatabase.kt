package com.floss.microscope.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ProjectEntity::class, SessionEntity::class, FrameEntity::class],
    version = 1,
    exportSchema = false
)
abstract class MicroscopeDatabase : RoomDatabase() {
    abstract fun dao(): MicroscopeDao
}
