package io.github.pranavm716.transittime.data.api.muni

import okhttp3.ResponseBody
import retrofit2.http.GET
import retrofit2.http.Query

interface MuniApi {

    @GET("StopMonitoring")
    suspend fun getStopMonitoring(
        @Query("api_key") apiKey: String,
        @Query("agency") agency: String = "SF",
        @Query("stopcode") stopCode: String,
        @Query("format") format: String = "json"
    ): ResponseBody
}