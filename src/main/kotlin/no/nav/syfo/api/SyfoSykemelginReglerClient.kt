package no.nav.syfo.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.config
import io.ktor.client.features.auth.basic.BasicAuth
import io.ktor.client.features.json.GsonSerializer
import io.ktor.client.features.json.JsonFeature
import io.ktor.client.request.post
import io.ktor.util.url
import no.nav.syfo.Environment
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("no.nav.syfo.http")

fun createHttpClient(env: Environment) = HttpClient(CIO.config {
    maxConnectionsCount = 1000 // Maximum number of socket connections.
    endpoint.apply {
        maxConnectionsPerRoute = 100
        pipelineMaxSize = 20
        keepAliveTime = 5000
        connectTimeout = 5000
        connectRetryAttempts = 5
        url {
            host = "syfosykemeldingrules"
            port = 80
        }
    }
}) {
    install(BasicAuth) {
        username = env.srvSyfoMottakUsername
        password = env.srvSyfoMottakPassword
    }
    install(JsonFeature) {
        serializer = GsonSerializer()
    }
}

suspend fun HttpClient.executeRuleValidation(payload: String): ValidationResult = post {
    url { path("v1", "rules", "validate") }
    body = payload
}

data class ValidationResult(
    val status: Status,
    val ruleHits: List<RuleInfo>
)

data class RuleInfo(
    val ruleMessage: String
)

enum class Status {
    OK,
    MANUAL_PROCESSING,
    INVALID
}
