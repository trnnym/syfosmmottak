import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.github.jengelman.gradle.plugins.shadow.transformers.ServiceFileTransformer
import no.nils.wsdl2java.Wsdl2JavaTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

group = "no.nav.syfo"
version = "1.0.22"

val artemisVersion = "2.6.4"
val coroutinesVersion = "1.0.1"
val fellesformatVersion = "1.0"
val ibmMqVersion = "9.1.0.0"
val javaxActivationVersion = "1.1.1"
val jacksonVersion = "2.9.7"
val jaxbApiVersion = "2.4.0-b180830.0359"
val jaxbVersion = "2.3.0.1"
val jedisVersion = "2.9.0"
val kafkaVersion = "2.0.0"
val kafkaEmbeddedVersion = "1.0.0"
val kithHodemeldingVersion = "1.1"
val kithApprecVersion = "1.1"
val kluentVersion = "1.39"
val ktorVersion = "1.1.3"
val logbackVersion = "1.2.3"
val logstashEncoderVersion = "5.1"
val prometheusVersion = "0.6.0"
val smCommonVersion = "1.0.3"
val spekVersion = "2.0.1"
val sykmeldingVersion = "1.1-SNAPSHOT"
val subscriptionVersion = "1.0.5"
val cxfVersion = "3.2.7"
val jaxwsApiVersion = "2.3.1"
val commonsTextVersion = "1.4"
val navArbeidsfordelingv1Version = "1.1.0"
val syfooppgaveSchemasVersion = "1.2-SNAPSHOT"
val confluentVersion = "4.1.1"
val navPersonv3Version = "3.2.0"
val jaxbBasicAntVersion = "1.11.1"
val javaxAnnotationApiVersion = "1.3.2"
val jaxwsToolsVersion = "2.3.1"
val jaxbRuntimeVersion = "2.4.0-b180830.0438"

tasks.withType<Jar> {
    manifest.attributes["Main-Class"] = "no.nav.syfo.BootstrapKt"
}


plugins {
    java
    id("no.nils.wsdl2java") version "0.10"
    kotlin("jvm") version "1.3.21"
    id("org.jmailen.kotlinter") version "1.21.0"
    id("com.diffplug.gradle.spotless") version "3.14.0"
    id("com.github.johnrengelman.shadow") version "4.0.4"
}


buildscript {
    dependencies {
        classpath("javax.xml.bind:jaxb-api:2.4.0-b180830.0359")
        classpath("org.glassfish.jaxb:jaxb-runtime:2.4.0-b180830.0438")
        classpath("com.sun.activation:javax.activation:1.2.0")
        classpath("com.sun.xml.ws:jaxws-tools:2.3.1") {
            exclude(group = "com.sun.xml.ws", module = "policy")
        }
    }
}

repositories {
    maven(url= "https://repo.adeo.no/repository/maven-snapshots/")
    maven(url= "https://repo.adeo.no/repository/maven-releases/")
    maven(url= "https://dl.bintray.com/kotlin/ktor")
    maven(url= "https://dl.bintray.com/spekframework/spek-dev")
    maven(url= "http://packages.confluent.io/maven/")
    maven(url= "https://kotlin.bintray.com/kotlinx")
    mavenCentral()
    jcenter()
}

val navWsdl= configurations.create("navWsdl") {
    setTransitive(false)
}

dependencies {
    wsdl2java("javax.annotation:javax.annotation-api:$javaxAnnotationApiVersion")
    wsdl2java("javax.activation:activation:$javaxActivationVersion")
    wsdl2java("org.glassfish.jaxb:jaxb-runtime:$jaxbRuntimeVersion")
    wsdl2java("javax.xml.bind:jaxb-api:$jaxbApiVersion")
    wsdl2java ("javax.xml.ws:jaxws-api:$jaxwsApiVersion")
    wsdl2java ("com.sun.xml.ws:jaxws-tools:$jaxwsToolsVersion") {
        exclude(group = "com.sun.xml.ws", module = "policy")
    }

    navWsdl("no.nav.tjenester:subscription-nav-emottak-eletter-web:$subscriptionVersion@zip")


    implementation(kotlin("stdlib"))

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:$coroutinesVersion")
    implementation("io.prometheus:simpleclient_hotspot:$prometheusVersion")
    implementation("io.prometheus:simpleclient_common:$prometheusVersion")

    implementation("io.ktor:ktor-server-netty:$ktorVersion")
    implementation("io.ktor:ktor-client-apache:$ktorVersion")
    implementation("io.ktor:ktor-client-cio:$ktorVersion")
    implementation("io.ktor:ktor-client-auth-basic:$ktorVersion")
    implementation("io.ktor:ktor-client-jackson:$ktorVersion")

    implementation("ch.qos.logback:logback-classic:$logbackVersion")
    implementation("net.logstash.logback:logstash-logback-encoder:$logstashEncoderVersion")

    implementation("org.apache.kafka:kafka_2.12:$kafkaVersion")
    implementation("io.confluent:kafka-avro-serializer:$confluentVersion")

    implementation("com.fasterxml.jackson.module:jackson-module-jaxb-annotations:$jacksonVersion")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jacksonVersion")
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml:$jacksonVersion")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jacksonVersion")
    implementation("no.nav.helse.xml:sm2013:$sykmeldingVersion")
    implementation("no.nav.syfo.tjenester:fellesformat:$fellesformatVersion")
    implementation("no.nav.syfo.tjenester:kith-hodemelding:$kithHodemeldingVersion")
    implementation("no.nav.syfo.tjenester:kith-apprec:$kithApprecVersion")
    implementation("no.nav.tjenester:nav-arbeidsfordeling-v1-tjenestespesifikasjon:$navArbeidsfordelingv1Version:jaxws")
    implementation("no.nav.syfo:syfooppgave-schemas:$syfooppgaveSchemasVersion")
    implementation("no.nav.tjenester:nav-person-v3-tjenestespesifikasjon:$navPersonv3Version")

    implementation("no.nav.syfo.sm:syfosm-common-models:$smCommonVersion")
    implementation("no.nav.syfo.sm:syfosm-common-networking:$smCommonVersion")
    implementation("no.nav.syfo.sm:syfosm-common-rest-sts:$smCommonVersion")
    implementation("no.nav.syfo.sm:syfosm-common-kafka:$smCommonVersion")
    implementation("no.nav.syfo.sm:syfosm-common-mq:$smCommonVersion")
    implementation("no.nav.syfo.sm:syfosm-common-ws:$smCommonVersion")

    implementation("redis.clients:jedis:$jedisVersion")

    implementation("org.apache.commons:commons-text:$commonsTextVersion")

    implementation("javax.xml.ws:jaxws-api:$jaxwsApiVersion")
    implementation("javax.annotation:javax.annotation-api:$javaxAnnotationApiVersion")
    implementation("javax.xml.bind:jaxb-api:$jaxbApiVersion")
    implementation("org.glassfish.jaxb:jaxb-runtime:$jaxbRuntimeVersion")
    implementation("javax.activation:activation:$javaxActivationVersion")
    implementation("com.sun.xml.ws:jaxws-tools:$jaxwsToolsVersion") {
        exclude(group = "com.sun.xml.ws", module = "policy")
    }

    testImplementation("org.amshove.kluent:kluent:$kluentVersion")
    testImplementation("org.spekframework.spek2:spek-dsl-jvm:$spekVersion")
    testImplementation("io.ktor:ktor-server-test-host:$ktorVersion") {
        exclude(group = "org.eclipse.jetty")
    }
    testImplementation("no.nav:kafka-embedded-env:$kafkaEmbeddedVersion")
    testImplementation("org.apache.activemq:artemis-server:$artemisVersion")
    testImplementation("org.apache.activemq:artemis-jms-client:$artemisVersion")

    testImplementation("org.spekframework.spek2:spek-dsl-jvm:$spekVersion") {
        exclude(group = "org.jetbrains.kotlin")
    }
    testRuntimeOnly ("org.spekframework.spek2:spek-runner-junit5:$spekVersion") {
        exclude(group = "org.jetbrains.kotlin")
    }

}


tasks {
    create("printVersion") {

        doLast {
            println(project.version)
        }
    }
    withType<KotlinCompile> {
        dependsOn("wsdl2java")

        kotlinOptions.jvmTarget = "1.8"
    }

    val copyWsdlFromArtifacts =  create("copyWsdlFromArtifacts", Copy::class) {
        includeEmptyDirs = false

        navWsdl.asFileTree.forEach { artifact ->
            from(zipTree(artifact))
        }
        into("$buildDir/schema/")
        include("**/*.xsd", "**/*.wsdl")
    }

    withType<Wsdl2JavaTask> {
        dependsOn(copyWsdlFromArtifacts)
        wsdlDir = file("$buildDir/schema")
        wsdlsToGenerate = listOf(
                mutableListOf("-xjc", "-b", "$projectDir/src/main/xjb/binding.xml", "$buildDir/schema/subscription.wsdl")
        )
    }

    withType<ShadowJar> {
        transform(ServiceFileTransformer::class.java) {
            setPath("META-INF/cxf")
            include("bus-extensions.txt")
        }
    }

    withType<Test> {
        useJUnitPlatform {
            includeEngines("spek2")
        }
        testLogging {
            showStandardStreams = true
        }
    }
}
