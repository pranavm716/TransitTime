package io.github.pranavm716.transittime.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import io.github.pranavm716.transittime.data.model.Arrival
import io.github.pranavm716.transittime.data.model.WidgetConfig

class Converters {
    @TypeConverter
    fun fromList(value: List<String>): String = value.joinToString(",")

    @TypeConverter
    fun toList(value: String): List<String> =
        if (value.isEmpty()) emptyList() else value.split(",")
}

@Database(entities = [Arrival::class, WidgetConfig::class], version = 1)
@TypeConverters(Converters::class)
abstract class TransitDatabase : RoomDatabase() {
    abstract fun arrivalDao(): ArrivalDao
    abstract fun widgetConfigDao(): WidgetConfigDao

    companion object {
        @Volatile
        private var instance: TransitDatabase? = null

        fun getInstance(context: Context): TransitDatabase {
            return instance ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    TransitDatabase::class.java,
                    "transit_database"
                ).build().also { instance = it }
            }
        }
    }
}