package io.github.pranavm716.transittime.transit

import io.github.pranavm716.transittime.data.model.Agency
import io.github.pranavm716.transittime.transit.bart.BartAgency
import io.github.pranavm716.transittime.transit.muni.MuniAgency

object AgencyRegistry {
    private val agencies: Map<Agency, TransitAgency> = mapOf(
        Agency.BART to BartAgency,
        Agency.MUNI to MuniAgency,
    )

    fun get(agency: Agency): TransitAgency = agencies.getValue(agency)
    fun all(): Collection<TransitAgency> = agencies.values
}
