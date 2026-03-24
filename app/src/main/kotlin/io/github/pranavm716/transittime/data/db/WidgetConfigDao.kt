package io.github.pranavm716.transittime.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.pranavm716.transittime.data.model.WidgetConfig

@Dao
interface WidgetConfigDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertConfig(config: WidgetConfig)

    @Query("SELECT * FROM widget_configs WHERE widgetId = :widgetId")
    suspend fun getConfig(widgetId: Int): WidgetConfig?

    @Query("SELECT * FROM widget_configs")
    suspend fun getAllConfigs(): List<WidgetConfig>

    @Query("DELETE FROM widget_configs WHERE widgetId = :widgetId")
    suspend fun deleteConfig(widgetId: Int)

    @Query("SELECT * FROM widget_configs WHERE stopId = :stopId")
    suspend fun getConfigByStopId(stopId: String): WidgetConfig?
}