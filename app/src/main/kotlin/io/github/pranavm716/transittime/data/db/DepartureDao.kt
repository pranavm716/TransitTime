package io.github.pranavm716.transittime.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.github.pranavm716.transittime.data.model.Departure

@Dao
interface DepartureDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDepartures(departures: List<Departure>)

    @Query("SELECT * FROM departures WHERE stopId = :stopId ORDER BY departureTimestamp ASC")
    suspend fun getDeparturesForStop(stopId: String): List<Departure>

    @Query("DELETE FROM departures WHERE stopId = :stopId")
    suspend fun deleteDeparturesForStop(stopId: String)
}
