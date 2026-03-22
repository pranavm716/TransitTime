package io.github.pranavm716.transittime.data.api.bart

import okhttp3.ResponseBody
import retrofit2.http.GET

interface BartApi {

    @GET("gtfsrt/tripupdate.aspx")
    suspend fun getTripUpdates(): ResponseBody
}