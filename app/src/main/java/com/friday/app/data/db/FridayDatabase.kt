package com.friday.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import java.time.LocalDate
import java.time.LocalTime

class Converters {
    @TypeConverter fun dateToLong(d: LocalDate?): Long? = d?.toEpochDay()
    @TypeConverter fun longToDate(v: Long?): LocalDate? = v?.let(LocalDate::ofEpochDay)
    @TypeConverter fun timeToInt(t: LocalTime?): Int? = t?.let { it.hour * 60 + it.minute }
    @TypeConverter fun intToTime(v: Int?): LocalTime? = v?.let { LocalTime.of(it / 60, it % 60) }
}

@Database(
    entities = [
        TaskEntity::class, SubtaskEntity::class, CueEntity::class, NoteEntity::class,
        FocusSessionEntity::class, MessageEntity::class, PlaceEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class FridayDatabase : RoomDatabase() {
    abstract fun tasks(): TaskDao
    abstract fun notes(): NoteDao
    abstract fun focus(): FocusDao
    abstract fun messages(): MessageDao
    abstract fun places(): PlaceDao

    companion object {
        // Future schema changes must add a Migration here; never fall back to wiping user data.
        fun build(context: Context, inMemory: Boolean = false): FridayDatabase =
            (if (inMemory) Room.inMemoryDatabaseBuilder(context, FridayDatabase::class.java)
            else Room.databaseBuilder(context, FridayDatabase::class.java, "friday.db"))
                .build()
    }
}
