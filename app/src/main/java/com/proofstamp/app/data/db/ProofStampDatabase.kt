package com.proofstamp.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [PhotoEntity::class, SessionEntity::class, PresetEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class ProofStampDatabase : RoomDatabase() {
    abstract fun photoDao(): PhotoDao
    abstract fun sessionDao(): SessionDao
    abstract fun presetDao(): PresetDao

    companion object {
        fun build(context: Context): ProofStampDatabase =
            Room.databaseBuilder(context, ProofStampDatabase::class.java, "proofstamp.db")
                .addCallback(SeedPresets)
                .build()
    }

    private object SeedPresets : Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            val presets = listOf(
                Triple("Project A", "Project A", "Site documentation"),
                Triple("Survey", "Survey", "Field survey"),
                Triple("Delivery", "Delivery", "Proof of delivery"),
                Triple("Inspection", "Inspection", "Inspection record"),
            )
            presets.forEachIndexed { i, (name, project, note) ->
                db.execSQL(
                    "INSERT INTO presets (name, project, operator, locationLabel, defaultNote, sortOrder) VALUES (?, ?, '', '', ?, ?)",
                    arrayOf(name, project, note, i),
                )
            }
        }
    }
}
