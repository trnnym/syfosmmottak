package no.nav.syfo

import com.ctc.wstx.exc.WstxException
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlRootElement
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.confluent.kafka.serializers.KafkaAvroSerializer
import io.ktor.application.Application
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.util.KtorExperimentalAPI
import io.prometheus.client.hotspot.DefaultExports
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import net.logstash.logback.argument.StructuredArgument
import net.logstash.logback.argument.StructuredArguments.keyValue
import no.kith.xmlstds.apprec._2004_11_21.XMLAppRec
import no.kith.xmlstds.msghead._2006_05_24.XMLIdent
import no.kith.xmlstds.msghead._2006_05_24.XMLMsgHead
import no.nav.emottak.subscription.StartSubscriptionRequest
import no.nav.emottak.subscription.SubscriptionPort
import no.nav.helse.sm2013.HelseOpplysningerArbeidsuforhet
import no.nav.paop.ws.configureBasicAuthFor
import no.nav.paop.ws.configureSTSFor
import no.nav.syfo.client.Status
import no.nav.syfo.api.registerNaisApi
import no.nav.syfo.apprec.ApprecError
import no.nav.syfo.apprec.ApprecStatus
import no.nav.syfo.apprec.createApprec
import no.nav.syfo.apprec.findApprecError
import no.nav.syfo.apprec.toApprecCV
import no.nav.syfo.client.AktoerIdClient
import no.nav.syfo.client.SamhandlerPraksis
import no.nav.syfo.client.SarClient
import no.nav.syfo.client.StsOidcClient
import no.nav.syfo.client.SyfoSykemeldingRuleClient
import no.nav.syfo.client.ValidationResult
import no.nav.syfo.client.findBestSamhandlerPraksis
import no.nav.syfo.metrics.APPREC_COUNTER
import no.nav.syfo.metrics.INCOMING_MESSAGE_COUNTER
import no.nav.syfo.metrics.REQUEST_TIME
import no.nav.syfo.model.ReceivedSykmelding
import no.nav.syfo.model.toSykmelding
import no.nav.syfo.sak.avro.PrioritetType
import no.nav.syfo.sak.avro.ProduceTask
import no.nav.syfo.util.connectionFactory
import no.nav.syfo.util.loadBaseConfig
import no.nav.syfo.util.toProducerConfig
import no.nav.tjeneste.virksomhet.arbeidsfordeling.v1.binding.ArbeidsfordelingV1
import no.nav.tjeneste.virksomhet.arbeidsfordeling.v1.informasjon.ArbeidsfordelingKriterier
import no.nav.tjeneste.virksomhet.arbeidsfordeling.v1.informasjon.Tema
import no.nav.tjeneste.virksomhet.arbeidsfordeling.v1.meldinger.FinnBehandlendeEnhetListeRequest
import no.nav.tjeneste.virksomhet.arbeidsfordeling.v1.meldinger.FinnBehandlendeEnhetListeResponse
import no.nav.tjeneste.virksomhet.person.v3.binding.PersonV3
import no.nav.tjeneste.virksomhet.person.v3.informasjon.GeografiskTilknytning
import no.nav.tjeneste.virksomhet.person.v3.informasjon.NorskIdent
import no.nav.tjeneste.virksomhet.person.v3.informasjon.PersonIdent
import no.nav.tjeneste.virksomhet.person.v3.informasjon.Personidenter
import no.nav.tjeneste.virksomhet.person.v3.meldinger.HentGeografiskTilknytningRequest
import no.nav.tjeneste.virksomhet.person.v3.meldinger.HentGeografiskTilknytningResponse
import no.trygdeetaten.xml.eiff._1.XMLEIFellesformat
import no.trygdeetaten.xml.eiff._1.XMLMottakenhetBlokk
import org.apache.cxf.jaxws.JaxWsProxyFactoryBean
import org.apache.cxf.ws.addressing.WSAddressingFeature
import org.apache.kafka.clients.producer.KafkaProducer
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit
import javax.jms.Session
import javax.jms.TextMessage
import org.apache.kafka.clients.producer.ProducerRecord
import redis.clients.jedis.Jedis
import redis.clients.jedis.JedisSentinelPool
import redis.clients.jedis.exceptions.JedisConnectionException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringReader
import java.io.StringWriter
import java.nio.file.Paths
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.UUID
import java.util.concurrent.Executors
import javax.jms.MessageConsumer
import javax.jms.MessageProducer
import javax.xml.bind.Marshaller

fun doReadynessCheck(): Boolean {
    return true
}

val objectMapper: ObjectMapper = ObjectMapper()
        .registerModule(JavaTimeModule())
        .registerKotlinModule()
        .configure(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS, false)

val redisMasterName = "mymaster"
val redisHost = "rfs-redis-syfosmmottak" // TODO: Do this properly with naiserator

data class ApplicationState(var running: Boolean = true, var initialized: Boolean = false)

val log = LoggerFactory.getLogger("nav.syfosmmottak-application")!!

@KtorExperimentalAPI
fun main(args: Array<String>) = runBlocking(Executors.newFixedThreadPool(2).asCoroutineDispatcher()) {
    val env = Environment()
    val credentials = objectMapper.readValue<VaultCredentials>(Paths.get("/var/run/secrets/nais.io/vault/credentials.json").toFile())
    val applicationState = ApplicationState()

    val applicationServer = embeddedServer(Netty, env.applicationPort) {
        initRouting(applicationState)
    }.start(wait = false)

    DefaultExports.initialize()

    connectionFactory(env).createConnection(credentials.mqUsername, credentials.mqPassword).use { connection ->
        connection.start()

        try {
            val listeners = (1..env.applicationThreads).map {
                launch {
                    JedisSentinelPool(redisMasterName, setOf("$redisHost:26379")).resource.use { jedis ->

                        val session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE)
                        val inputQueue = session.createQueue(env.inputQueueName)
                        val receiptQueue = session.createQueue(env.apprecQueueName)
                        val syfoserviceQueue = session.createQueue(env.syfoserviceQueueName)
                        val backoutQueue = session.createQueue(env.inputBackoutQueueName)

                        val inputconsumer = session.createConsumer(inputQueue)
                        val receiptProducer = session.createProducer(receiptQueue)
                        val syfoserviceProducer = session.createProducer(syfoserviceQueue)
                        val backoutProducer = session.createProducer(backoutQueue)

                        val kafkaBaseConfig = loadBaseConfig(env, credentials)

                        val producerProperties = kafkaBaseConfig.toProducerConfig(env.applicationName, valueSerializer = JacksonKafkaSerializer::class)

                        val kafkaproducer = KafkaProducer<String, ReceivedSykmelding>(producerProperties)

                        val manualTaskproducerProperties = kafkaBaseConfig.toProducerConfig(env.applicationName, valueSerializer = KafkaAvroSerializer::class)
                        val manualTaskkafkaproducer = KafkaProducer<String, ProduceTask>(manualTaskproducerProperties)

                        val syfoSykemeldingRuleClient = SyfoSykemeldingRuleClient(env.syfosmreglerApiUrl, credentials)

                        val sarClient = SarClient(env.kuhrSarApiUrl, credentials)

                        val oidcClient = StsOidcClient(credentials.serviceuserUsername, credentials.serviceuserPassword)
                        val aktoerIdClient = AktoerIdClient(env.aktoerregisterV1Url, oidcClient)

                        val subscriptionEmottak = JaxWsProxyFactoryBean().apply {
                            address = env.subscriptionEndpointURL
                            features.add(WSAddressingFeature())
                            serviceClass = SubscriptionPort::class.java
                        }.create() as SubscriptionPort
                        configureBasicAuthFor(subscriptionEmottak, credentials.serviceuserUsername, credentials.serviceuserPassword)

                        val arbeidsfordelingV1 = JaxWsProxyFactoryBean().apply {
                            address = env.arbeidsfordelingV1EndpointURL
                            serviceClass = ArbeidsfordelingV1::class.java
                        }.create() as ArbeidsfordelingV1
                        configureSTSFor(arbeidsfordelingV1, credentials.serviceuserUsername,
                                credentials.serviceuserPassword, env.securityTokenServiceUrl)

                        val personV3 = JaxWsProxyFactoryBean().apply {
                            address = env.personV3EndpointURL
                            serviceClass = PersonV3::class.java
                        }.create() as PersonV3
                        configureSTSFor(personV3, credentials.serviceuserUsername,
                                credentials.serviceuserPassword, env.securityTokenServiceUrl)

                        blockingApplicationLogic(inputconsumer, receiptProducer, syfoserviceProducer, backoutProducer,
                                subscriptionEmottak, kafkaproducer,
                                syfoSykemeldingRuleClient, sarClient, aktoerIdClient,
                                env, credentials, applicationState, jedis, manualTaskkafkaproducer, personV3, session, arbeidsfordelingV1)
                    }
                }
            }.toList()

            applicationState.initialized = true

            Runtime.getRuntime().addShutdownHook(Thread {
                applicationServer.stop(10, 10, TimeUnit.SECONDS)
            })
            runBlocking { listeners.forEach { it.join() } }
        } finally {
            applicationState.running = false
        }
    }
}

fun Application.initRouting(applicationState: ApplicationState) {
    routing {
        registerNaisApi(readynessCheck = ::doReadynessCheck, livenessCheck = { applicationState.running })
    }
}

@KtorExperimentalAPI
suspend fun CoroutineScope.blockingApplicationLogic(
    inputconsumer: MessageConsumer,
    receiptProducer: MessageProducer,
    syfoserviceProducer: MessageProducer,
    backoutProducer: MessageProducer,
    subscriptionEmottak: SubscriptionPort,
    kafkaproducer: KafkaProducer<String, ReceivedSykmelding>,
    syfoSykemeldingRuleClient: SyfoSykemeldingRuleClient,
    kuhrSarClient: SarClient,
    aktoerIdClient: AktoerIdClient,
    env: Environment,
    credentials: VaultCredentials,
    applicationState: ApplicationState,
    jedis: Jedis,
    kafkaManuelTaskProducer: KafkaProducer<String, ProduceTask>,
    personV3: PersonV3,
    session: Session,
    arbeidsfordelingV1: ArbeidsfordelingV1
) {
    loop@ while (applicationState.running) {
        val message = inputconsumer.receiveNoWait()
        if (message == null) {
            delay(100)
            continue
        }

        var logValues = arrayOf(
                keyValue("mottakId", "missing"),
                keyValue("sykmeldingId", "missing"),
                keyValue("organizationNumber", "missing"),
                keyValue("msgId", "missing")
        )

        val logKeys = logValues.joinToString(prefix = "(", postfix = ")", separator = ",") {
            "{}"
        }

        try {
            val inputMessageText = when (message) {
                is TextMessage -> message.text
                else -> throw RuntimeException("Incoming message needs to be a byte message or text message")
            }
            INCOMING_MESSAGE_COUNTER.inc()
            val requestLatency = REQUEST_TIME.startTimer()
            val fellesformat = fellesformatUnmarshaller.unmarshal(StringReader(inputMessageText)) as XMLEIFellesformat
            val receiverBlock = fellesformat.get<XMLMottakenhetBlokk>()
            val healthInformation = extractHelseOpplysningerArbeidsuforhet(fellesformat)
            val msgHead = fellesformat.get<XMLMsgHead>()
            val ediLoggId = receiverBlock.ediLoggId
            val sha256String = sha256hashstring(healthInformation)
            val msgId = msgHead.msgInfo.msgId

            val legekontorHerId = extractOrganisationHerNumberFromSender(fellesformat)?.id
            val legekontorReshId = extractOrganisationRashNumberFromSender(fellesformat)?.id
            val legekontorOrgNr = extractOrganisationNumberFromSender(fellesformat)?.id
            val legekontorOrgName = msgHead.msgInfo.sender.organisation.organisationName

            val personNumberPatient = healthInformation.pasient.fodselsnummer.id
            val personNumberDoctor = receiverBlock.avsenderFnrFraDigSignatur

            logValues = arrayOf(
                    keyValue("mottakId", ediLoggId),
                    keyValue("organizationNumber", legekontorOrgNr),
                    keyValue("msgId", msgId)
            )

            log.info("Received message, $logKeys", *logValues)

            val aktoerIds = aktoerIdClient.getAktoerIds(listOf(personNumberDoctor, personNumberPatient), msgId, credentials.serviceuserUsername).await()
            val samhandlerInfo = kuhrSarClient.getSamhandler(personNumberDoctor).await()

            val samhandlerPraksis = findBestSamhandlerPraksis(samhandlerInfo, legekontorOrgName)?.samhandlerPraksis

            // TODO comment out this when syfosmemottakmock is ready and up i prod
            /*
            when(samhandlerPraksis){
                null -> log.info("None samhandlerPraksis is found")
                else -> startSubscription(subscriptionEmottak,samhandlerPraksis,msgHead,receiverBlock)
            }
            */

            try {
                val redisEdiLoggId = jedis.get(sha256String)

                if (redisEdiLoggId != null) {
                    log.warn("Message with {} marked as duplicate $logKeys", keyValue("originalEdiLoggId", redisEdiLoggId), *logValues)
                    sendReceipt(session, receiptProducer, fellesformat, ApprecStatus.avvist, listOf(ApprecError.DUPLICATE))
                    log.info("Apprec Receipt sent to {} $logKeys", env.apprecQueueName, *logValues)
                    continue
                } else {
                    jedis.setex(sha256String, TimeUnit.DAYS.toSeconds(7).toInt(), ediLoggId)
                }
            } catch (connectionException: JedisConnectionException) {
                log.warn("Unable to contact redis, will allow possible duplicates.", connectionException)
            }

            val patientIdents = aktoerIds[personNumberPatient]
            val doctorIdents = aktoerIds[personNumberDoctor]

            if (patientIdents == null || patientIdents.feilmelding != null) {
                log.info("Patient not found i aktorRegister $logKeys, {}", *logValues,
                        keyValue("errorMessage", patientIdents?.feilmelding ?: "No response for FNR"))
                sendReceipt(session, receiptProducer, fellesformat, ApprecStatus.avvist, listOf(ApprecError.PATIENT_NOT_IN_FOLKEREGISTERET))
                log.info("Apprec Receipt sent to {} $logKeys", env.apprecQueueName, *logValues)
                continue@loop
            }
            if (doctorIdents == null || doctorIdents.feilmelding != null) {
                log.info("Doctor not found i aktorRegister $logKeys, {}", *logValues,
                        keyValue("errorMessage", doctorIdents?.feilmelding ?: "No response for FNR"))
                sendReceipt(session, receiptProducer, fellesformat, ApprecStatus.avvist, listOf(ApprecError.BEHANDLER_NOT_IN_FOLKEREGISTERET))
                log.info("Apprec Receipt sent to {} $logKeys", env.apprecQueueName, *logValues)
                continue@loop
            }

            val sykmelding = healthInformation.toSykmelding(
                    sykmeldingId = UUID.randomUUID().toString(),
                    pasientAktoerId = patientIdents.identer!!.first().ident,
                    legeAktoerId = doctorIdents.identer!!.first().ident,
                    msgId = msgId
            )
            val receivedSykmelding = ReceivedSykmelding(
                    sykmelding = sykmelding,
                    personNrPasient = personNumberPatient,
                    personNrLege = personNumberDoctor,
                    navLogId = ediLoggId,
                    msgId = msgId,
                    legekontorOrgNr = legekontorOrgNr,
                    legekontorOrgName = legekontorOrgName,
                    legekontorHerId = legekontorHerId,
                    legekontorReshId = legekontorReshId,
                    mottattDato = receiverBlock.mottattDatotid.toGregorianCalendar().toZonedDateTime().toLocalDateTime(),
                    signaturDato = msgHead.msgInfo.genDate,
                    rulesetVersion = healthInformation.regelSettVersjon,
                    fellesformat = inputMessageText
            )

            log.info("Validating against rules, $logKeys", *logValues)
            val validationResult = syfoSykemeldingRuleClient.executeRuleValidation(receivedSykmelding).await()

            if (validationResult.status in arrayOf(Status.OK, Status.MANUAL_PROCESSING)) {
                sendReceipt(session, receiptProducer, fellesformat, ApprecStatus.ok)
                log.info("Apprec Receipt sent to {} $logKeys", env.apprecQueueName, *logValues)

                notifySyfoService(session, syfoserviceProducer, ediLoggId, msgId, healthInformation)
                log.info("Message send to syfoService {} $logKeys", env.syfoserviceQueueName, *logValues)
            } else {
                sendReceipt(session, receiptProducer, fellesformat, ApprecStatus.avvist, findApprecError(validationResult.ruleHits))
                log.info("Apprec Receipt sent to {} $logKeys", env.apprecQueueName, *logValues)
            }

            val topicName = when (validationResult.status) {
                Status.OK -> env.sm2013AutomaticHandlingTopic
                Status.MANUAL_PROCESSING -> env.sm2013ManualHandlingTopic
                Status.INVALID -> env.sm2013InvalidHandlingTopic
            }

            if (validationResult.status == Status.MANUAL_PROCESSING) {
                val geografiskTilknytning = fetchGeografiskTilknytningAsync(personV3, receivedSykmelding)
                val finnBehandlendeEnhetListeResponse = fetchBehandlendeEnhetAsync(arbeidsfordelingV1, geografiskTilknytning.await().geografiskTilknytning)
                createTask(kafkaManuelTaskProducer, receivedSykmelding, validationResult, findNavOffice(finnBehandlendeEnhetListeResponse.await()), logKeys, logValues)
            }

            kafkaproducer.send(ProducerRecord(topicName, receivedSykmelding.sykmelding.id, receivedSykmelding))
            log.info("Message send to kafka {} $logKeys", topicName, *logValues)

            val currentRequestLatency = requestLatency.observeDuration()

            log.info("Message($logKeys) got outcome {}, {}, processing took {}s",
                    *logValues,
                    keyValue("status", validationResult.status),
                    keyValue("ruleHits", validationResult.ruleHits.joinToString(", ", "(", ")") { it.ruleMessage }),
                    keyValue("latency", currentRequestLatency))
        } catch (e: Exception) {
            log.error("Exception caught while handling message, sending to backout $logKeys", *logValues, e)
            backoutProducer.send(message)
        }
        delay(100)
    }
}

inline fun <reified T> XMLEIFellesformat.get() = this.any.find { it is T } as T

fun extractOrganisationNumberFromSender(fellesformat: XMLEIFellesformat): XMLIdent? =
        fellesformat.get<XMLMsgHead>().msgInfo.sender.organisation.ident.find {
            it.typeId.v == "ENH"
        }

fun extractOrganisationRashNumberFromSender(fellesformat: XMLEIFellesformat): XMLIdent? =
        fellesformat.get<XMLMsgHead>().msgInfo.sender.organisation.ident.find {
            it.typeId.v == "RSH"
        }

fun extractOrganisationHerNumberFromSender(fellesformat: XMLEIFellesformat): XMLIdent? =
        fellesformat.get<XMLMsgHead>().msgInfo.sender.organisation.ident.find {
            it.typeId.v == "HER"
        }

fun sendReceipt(
    session: Session,
    receiptProducer: MessageProducer,
    fellesformat: XMLEIFellesformat,
    apprecStatus: ApprecStatus,
    apprecErrors: List<ApprecError> = listOf()
) {
    APPREC_COUNTER.inc()
    receiptProducer.send(session.createTextMessage().apply {
        val apprec = createApprec(fellesformat, apprecStatus)
        apprec.get<XMLAppRec>().error.addAll(apprecErrors.map { it.toApprecCV() })
        text = apprecMarshaller.toString(apprec)
    })
}

fun Marshaller.toString(input: Any): String = StringWriter().use {
    marshal(input, it)
    it.toString()
}

fun sha256hashstring(helseOpplysningerArbeidsuforhet: HelseOpplysningerArbeidsuforhet): String =
        MessageDigest.getInstance("SHA-256")
                .digest(objectMapper.writeValueAsBytes(helseOpplysningerArbeidsuforhet))
                .fold("") { str, it -> str + "%02x".format(it) }

fun extractHelseOpplysningerArbeidsuforhet(fellesformat: XMLEIFellesformat): HelseOpplysningerArbeidsuforhet =
        fellesformat.get<XMLMsgHead>().document[0].refDoc.content.any[0] as HelseOpplysningerArbeidsuforhet

fun notifySyfoService(
    session: Session,
    receiptProducer: MessageProducer,
    ediLoggId: String,
    msgId: String,
    healthInformation: HelseOpplysningerArbeidsuforhet

) {
    receiptProducer.send(session.createTextMessage().apply {
        val syketilfelleStartDato = extractSyketilfelleStartDato(healthInformation)
        val sykmelding = convertSykemeldingToBase64(healthInformation)
        val syfo = Syfo(
                tilleggsdata = Tilleggsdata(ediLoggId = ediLoggId, msgId = msgId, syketilfelleStartDato = syketilfelleStartDato),
                sykmelding = Base64.getEncoder().encodeToString(sykmelding))
        text = xmlObjectWriter.writeValueAsString(syfo)
    })
}

@JacksonXmlRootElement(localName = "syfo")
data class Syfo(
    val tilleggsdata: Tilleggsdata,
    val sykmelding: String
)

data class Tilleggsdata(
    val ediLoggId: String,
    val msgId: String,
    val syketilfelleStartDato: LocalDate
)

fun extractSyketilfelleStartDato(helseOpplysningerArbeidsuforhet: HelseOpplysningerArbeidsuforhet): LocalDate =
        helseOpplysningerArbeidsuforhet.syketilfelleStartDato

fun convertSykemeldingToBase64(helseOpplysningerArbeidsuforhet: HelseOpplysningerArbeidsuforhet): ByteArray =
        ByteArrayOutputStream().use {
            sykmeldingMarshaller.marshal(helseOpplysningerArbeidsuforhet, it)
            it
        }.toByteArray()

fun CoroutineScope.fetchGeografiskTilknytningAsync(personV3: PersonV3, receivedSykmelding: ReceivedSykmelding): Deferred<HentGeografiskTilknytningResponse> =
        retryAsync("tps_hent_geografisktilknytning", IOException::class, WstxException::class) {
            personV3.hentGeografiskTilknytning(HentGeografiskTilknytningRequest().withAktoer(PersonIdent().withIdent(
                    NorskIdent()
                            .withIdent(receivedSykmelding.personNrPasient)
                            .withType(Personidenter().withValue("FNR")))))
        }

fun CoroutineScope.fetchBehandlendeEnhetAsync(arbeidsfordelingV1: ArbeidsfordelingV1, geografiskTilknytning: GeografiskTilknytning?): Deferred<FinnBehandlendeEnhetListeResponse?> =
        retryAsync("finn_nav_kontor", IOException::class, WstxException::class) {
            arbeidsfordelingV1.finnBehandlendeEnhetListe(FinnBehandlendeEnhetListeRequest().apply {
                val afk = ArbeidsfordelingKriterier()
                if (geografiskTilknytning?.geografiskTilknytning != null) {
                    afk.geografiskTilknytning = no.nav.tjeneste.virksomhet.arbeidsfordeling.v1.informasjon.Geografi().apply {
                        value = geografiskTilknytning.geografiskTilknytning
                    }
                }
                afk.tema = Tema().apply {
                    value = "SYM"
                }
                arbeidsfordelingKriterier = afk
            })
        }

fun createTask(kafkaProducer: KafkaProducer<String, ProduceTask>, receivedSykmelding: ReceivedSykmelding, results: ValidationResult, navKontor: String, logKeys: String, logValues: Array<StructuredArgument>) {
    kafkaProducer.send(ProducerRecord("aapen-syfo-oppgave-produserOppgave", receivedSykmelding.sykmelding.id, ProduceTask().apply {
        messageId = receivedSykmelding.msgId
        aktoerId = receivedSykmelding.sykmelding.pasientAktoerId
        tildeltEnhetsnr = navKontor
        opprettetAvEnhetsnr = "9999"
        behandlesAvApplikasjon = "FS22" // Gosys
        orgnr = receivedSykmelding.legekontorOrgNr ?: ""
        beskrivelse = "Manuell behandling sykmelding: ${results.ruleHits}"
        temagruppe = "SYM"
        tema = ""
        behandlingstema = "BEH_EL_SYM"
        oppgavetype = ""
        behandlingstype = ""
        mappeId = 1
        aktivDato = DateTimeFormatter.ISO_DATE.format(LocalDate.now())
        fristFerdigstillelse = DateTimeFormatter.ISO_DATE.format(LocalDate.now())
        prioritet = PrioritetType.NORM
        metadata = mapOf()
    }))

    log.info("Message sendt to topic: aapen-syfo-oppgave-produserOppgave $logKeys", *logValues)
}

fun findNavOffice(finnBehandlendeEnhetListeResponse: FinnBehandlendeEnhetListeResponse?): String =
        if (finnBehandlendeEnhetListeResponse?.behandlendeEnhetListe?.firstOrNull()?.enhetId == null) {
            "0393"
        } else {
            finnBehandlendeEnhetListeResponse.behandlendeEnhetListe.first().enhetId
        }

// TODO This functionality is only necessary due to sending out dialogMelding and oppfølginsplan to doctor
fun startSubscription(
    subscriptionEmottak: SubscriptionPort,
    samhandlerPraksis: SamhandlerPraksis,
    msgHead: XMLMsgHead,
    receiverBlock: XMLMottakenhetBlokk
) {
    subscriptionEmottak.startSubscription(StartSubscriptionRequest().apply {
        key = samhandlerPraksis.tss_ident
        data = msgHead.msgInfo.sender.toString().toByteArray()
        partnerid = receiverBlock.partnerReferanse.toInt()
    })
}
