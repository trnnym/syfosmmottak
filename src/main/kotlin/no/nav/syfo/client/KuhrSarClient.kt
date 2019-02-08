package no.nav.syfo.client

import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.config
import io.ktor.client.features.auth.basic.BasicAuth
import io.ktor.client.features.json.JacksonSerializer
import io.ktor.client.features.json.JsonFeature
import io.ktor.client.features.logging.DEFAULT
import io.ktor.client.features.logging.LogLevel
import io.ktor.client.features.logging.Logger
import io.ktor.client.features.logging.Logging
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.http.ContentType
import io.ktor.util.KtorExperimentalAPI
import no.nav.syfo.VaultCredentials
import org.apache.commons.text.similarity.LevenshteinDistance
import java.util.Date
import kotlin.math.max

@KtorExperimentalAPI
class SarClient(private val endpointUrl: String, private val credentials: VaultCredentials) {

    private val kuhrSarClient = HttpClient(CIO.config {
        maxConnectionsCount = 1000 // Maximum number of socket connections.
        endpoint.apply {
            maxConnectionsPerRoute = 100
            pipelineMaxSize = 20
            keepAliveTime = 5000
            connectTimeout = 5000
            connectRetryAttempts = 5
        }
    }) {
        install(JsonFeature) {
            serializer = JacksonSerializer {
                registerKotlinModule()
                registerModule(JavaTimeModule())
                configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)
            }
        }
        install(BasicAuth) {
            this.username = credentials.serviceuserUsername
            this.password = credentials.serviceuserPassword
        }
        install(Logging) {
            logger = Logger.DEFAULT
            level = LogLevel.ALL
        }
    }

    suspend fun getSamhandler(ident: String): List<Samhandler> =
            kuhrSarClient.get("$endpointUrl/rest/sar/samh") {
            accept(ContentType.Application.Json)
            parameter("ident", ident)
    }
}

data class Samhandler(
    val samh_id: String,
    val navn: String,
    val samh_type_kode: String,
    val behandling_utfall_kode: String,
    val unntatt_veiledning: String,
    val godkjent_manuell_krav: String,
    val ikke_godkjent_for_refusjon: String,
    val godkjent_egenandel_refusjon: String,
    val godkjent_for_fil: String,
    val breg_hovedenhet: SamhandlerBregHovedenhet?,
    val endringslogg_tidspunkt_siste: Date?,
    val samh_ident: List<SamhandlerIdent>,
    val samh_praksis: List<SamhandlerPraksis>,
    val samh_avtale: List<SamhandlerAvtale>,
    val samh_direkte_oppgjor_avtale: List<SamhandlerDirekteOppgjoerAvtale>,
    val samh_email: List<SamhEmail>?
)

data class SamhandlerBregHovedenhet(
    val organisasjonsnummer: String,
    val organisasjonsform: String,
    val institusjonellsektorkodekode: String,
    val naeringskode1kode: String,
    val naeringskode2kode: String?
)

data class SamhandlerIdent(
    val samh_id: String,
    val samh_ident_id: String,
    val ident: String,
    val ident_type_kode: String,
    val aktiv_ident: String
)

data class SamhandlerPraksis(
    val refusjon_type_kode: String,
    val laerer: String,
    val lege_i_spesialisering: String,
    val tidspunkt_resync_periode: Date?,
    val tidspunkt_registrert: Date?,
    val samh_praksis_status_kode: String,
    val telefonnr: String?,
    val arbeids_kommune_nr: String,
    val arbeids_postnr: String,
    val arbeids_adresse_linje_1: String?,
    val arbeids_adresse_linje_2: String?,
    val arbeids_adresse_linje_3: String?,
    val arbeids_adresse_linje_4: String?,
    val arbeids_adresse_linje_5: String?,
    val her_id: String?,
    val post_adresse_linje_1: String?,
    val post_adresse_linje_2: String?,
    val post_adresse_linje_3: String?,
    val post_adresse_linje_4: String?,
    val post_adresse_linje_5: String?,
    val post_kommune_nr: String?,
    val post_postnr: String?,
    val resh_id: String?,
    val tss_ident: String,
    val navn: String?,
    val ident: String,
    val samh_praksis_type_kode: String?,
    val samh_id: String,
    val samh_praksis_id: String,
    val samh_praksis_konto: List<SamhandlerPraksisKonto>,
    val samh_praksis_periode: List<SamhandlerPeriode>,
    val samh_praksis_email: List<SamhandlerPraksisEmail>?
)

data class SamhandlerPraksisKonto(
    val tidspunkt_registrert: Date,
    val registrert_av_id: String,
    val konto: String,
    val samh_praksis_id: String,
    val samh_praksis_konto_id: String
)

data class SamhandlerPeriode(
    val endret_ved_import: String,
    val sist_endret: Date,
    val slettet: String,
    val gyldig_fra: Date,
    val gyldig_til: Date?,
    val samh_praksis_id: String,
    val samh_praksis_periode_id: String
)

data class SamhandlerAvtale(
    val gyldig_fra: Date,
    val gyldig_til: Date?,
    val prosentandel: String,
    val avtale_type_kode: String,
    val samh_id: String,
    val samh_avtale_id: String
)

data class SamhandlerDirekteOppgjoerAvtale(
    val gyldig_fra: Date,
    val koll_avtale_mottatt_dato: Date?,
    val monster_avtale_mottatt_dato: Date?,
    val samh_id: String,
    val samh_direkte_oppgjor_avtale_id: String
)

data class SamhandlerPraksisEmail(
    val samh_praksis_email_id: String,
    val samh_praksis_id: String,
    val email: String,
    val primaer_email: String?
)

data class SamhEmail(
    val samh_email_id: String,
    val samh_id: String,
    val email: String,
    val primaer_email: String,
    val email_type_kode: String
)

data class SamhandlerPraksisMatch(val samhandlerPraksis: SamhandlerPraksis, val percentageMatch: Double)

fun calculatePercentageStringMatch(str1: String?, str2: String): Double {
    val maxDistance = max(str1?.length!!, str2.length).toDouble()
    val distance = LevenshteinDistance().apply(str2, str1).toDouble()
    return (maxDistance - distance) / maxDistance
}

fun findBestSamhandlerPraksis(samhandlers: List<Samhandler>, orgName: String): SamhandlerPraksisMatch? {

    val aktiveSamhandlere = samhandlers.flatMap { it.samh_praksis }
            .filter {
                it.samh_praksis_status_kode == "aktiv"
            }
            .filter {
                it.samh_praksis_periode
                        .filter { it.gyldig_fra <= Date() }
                        .filter { it.gyldig_til == null || it.gyldig_til >= Date() }
                        .any()
            }
            .filter { !it.navn.isNullOrEmpty() }
            .toList()

    return aktiveSamhandlere
            .map {
                SamhandlerPraksisMatch(it, calculatePercentageStringMatch(it.navn, orgName))
            }.sortedBy { it.percentageMatch }
            .firstOrNull()
}