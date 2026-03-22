package io.github.pranavm716.transittime.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.pranavm716.transittime.data.model.Arrival

@Dao
interface ArrivalDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertArrivals(arrivals: List<Arrival>)

    @Query("SELECT * FROM arrivals WHERE stopId = :stopId ORDER BY arrivalTimestamp ASC")
    suspend fun getArrivalsForStop(stopId: String): List<Arrival>

    @Query("DELETE FROM arrivals WHERE fetchedAt < :cutoff")
    suspend fun deleteStaleArrivals(cutoff: Long)
}