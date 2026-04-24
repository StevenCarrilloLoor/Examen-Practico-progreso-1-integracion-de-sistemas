package com.saludvital.integration;

import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.support.processor.idempotent.MemoryIdempotentRepository;
import org.apache.camel.spi.IdempotentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

/**
 * Ruta principal de integracion entre el Sistema de Admisiones y el Sistema de
 * Facturacion de la clinica SaludVital, usando el estilo File Transfer.
 *
 *     Flujo:
 *     1. Se detectan archivos CSV en {saludvital.paths.input}.
 *     2. El archivo original se mueve (NO se copia) a .processing para evitar
 *        que otro proceso lo lea al mismo tiempo.
 *     3. Se invoca el bean "preRegistroValidator" que valida encabezado,
 *        filas, formato de fecha e insurance_code.
 *     4. Choice:
 *            - VALID    -> copia al directorio de output (facturacion)
 *            - INVALID  -> copia al directorio de error con reporte .txt
 *     5. En ambos casos el archivo se archiva en archive con timestamp
 *        y el original queda fuera de input (moveFailed / move), lo que evita
 *        reprocesamiento.
 *     6. Un idempotentConsumer sobre el nombre + tamaño refuerza la prevencion
 *        de duplicados.
 */
@Component
public class PreRegistrosRoute extends RouteBuilder {

    @Value("${saludvital.paths.input}")
    private String inputPath;

    @Value("${saludvital.paths.output}")
    private String outputPath;

    @Value("${saludvital.paths.archive}")
    private String archivePath;

    @Value("${saludvital.paths.error}")
    private String errorPath;

    @Bean
    public IdempotentRepository idempotentRepo() {
        // Repositorio en memoria; en produccion se usaria JPA o Redis.
        return MemoryIdempotentRepository.memoryIdempotentRepository(2000);
    }

    @Override
    public void configure() {

        // Manejo global de excepciones no controladas
        onException(Exception.class)
                .handled(true)
                .log(LoggingLevel.ERROR,
                        "[ERROR INESPERADO] archivo='${header.CamelFileName}' mensaje='${exception.message}'")
                .to("file://" + errorPath + "?fileName=${header.CamelFileName}.err.txt");

        // =================================================================
        // RUTA 1: Consumo del archivo desde input
        // =================================================================
        from("file://" + inputPath
                + "?include=.*\\.csv"                   // solo CSV
                + "&moveFailed=" + errorPath            // si falla el consumo
                + "&move=.processing/${file:name}"     // mover a subcarpeta temporal -> NO reprocesa
                + "&readLock=changed"                   // no leer si aun se esta escribiendo
                + "&delay=5000")                        // poll cada 5 segundos
                .routeId("ruta-ingesta-pre-registros")
                .idempotentConsumer(simple("${file:name}-${file:size}"), idempotentRepo())
                  .skipDuplicate(true)
                .log(LoggingLevel.INFO,
                        "[INGESTA] Archivo detectado: '${header.CamelFileName}' tamaño=${header.CamelFileLength} bytes")
                .convertBodyTo(String.class)
                .bean("preRegistroValidator", "validate")
                .choice()
                    .when(exchangeProperty("validationResult").isEqualTo("VALID"))
                        .log(LoggingLevel.INFO,
                                "[RUTA] Archivo VALIDO -> output. filas_validas=${exchangeProperty.validRows}")
                        .to("direct:enviarAOutput")
                    .otherwise()
                        .log(LoggingLevel.WARN,
                                "[RUTA] Archivo INVALIDO -> error. motivo='${exchangeProperty.validationError}'")
                        .to("direct:enviarAError")
                .end()
                // Archivado obligatorio con timestamp (siempre, haya sido valido o invalido)
                .to("direct:archivarConTimestamp")
                .log(LoggingLevel.INFO,
                        "[FIN] Procesamiento completo de '${header.CamelFileName}'");

        // =================================================================
        // RUTA 2: Envio a sistema de Facturacion (output)
        // =================================================================
        from("direct:enviarAOutput")
                .routeId("ruta-envio-output")
                .log(LoggingLevel.INFO,
                        "[OUTPUT] Entregando '${header.CamelFileName}' a Facturacion")
                .to("file://" + outputPath
                        + "?fileName=${header.CamelFileName}");

        // =================================================================
        // RUTA 3: Envio a carpeta de error + reporte
        // =================================================================
        from("direct:enviarAError")
                .routeId("ruta-envio-error")
                .log(LoggingLevel.WARN,
                        "[ERROR] Moviendo '${header.CamelFileName}' a carpeta de error")
                .to("file://" + errorPath
                        + "?fileName=${header.CamelFileName}")
                // Reporte texto con el motivo del rechazo
                .setBody(simple(
                        "Archivo: ${header.CamelFileName}\n" +
                        "Motivo : ${exchangeProperty.validationError}\n" +
                        "Fecha  : ${date:now:yyyy-MM-dd HH:mm:ss}\n"))
                .to("file://" + errorPath
                        + "?fileName=${header.CamelFileName}.motivo.txt");

        // =================================================================
        // RUTA 4: Archivado con timestamp
        //         Ejemplo: pre_registros_2026-04-22_103015.csv
        // =================================================================
        from("direct:archivarConTimestamp")
                .routeId("ruta-archivado")
                .setHeader("baseName",
                        simple("${file:name.noext}"))
                .setHeader("CamelFileName",
                        simple("${header.baseName}_${date:now:yyyy-MM-dd_HHmmss}.csv"))
                .log(LoggingLevel.INFO,
                        "[ARCHIVE] Archivando como '${header.CamelFileName}'")
                .to("file://" + archivePath);
    }
}
