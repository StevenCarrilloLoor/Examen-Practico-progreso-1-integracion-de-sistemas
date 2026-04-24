package com.saludvital.integration;

import org.apache.camel.Body;
import org.apache.camel.Exchange;
import org.apache.camel.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/**
 * Valida el contenido de un archivo CSV de pre-registros de pacientes.
 *
 * Reglas (segun especificacion del caso SaludVital):
 *   1. Encabezado obligatorio: patient_id, full_name, appointment_date, insurance_code
 *   2. Ninguna fila puede tener campos vacios.
 *   3. appointment_date debe estar en formato YYYY-MM-DD y ser una fecha real.
 *   4. insurance_code debe pertenecer al conjunto: IESS, PRIVADO, NINGUNO.
 *
 * El resultado se escribe en la propiedad "validationResult" del Exchange
 * para que el Choice de la ruta enrute el archivo a output o a error.
 */
@Component("preRegistroValidator")
public class PreRegistroValidator {

    private static final Logger log = LoggerFactory.getLogger(PreRegistroValidator.class);

    private static final List<String> EXPECTED_HEADER = Arrays.asList(
            "patient_id", "full_name", "appointment_date", "insurance_code"
    );

    private static final Set<String> ALLOWED_INSURANCE = Set.of("IESS", "PRIVADO", "NINGUNO");

    private static final DateTimeFormatter DATE_FMT =
            DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    /**
     * Metodo invocado desde la ruta Camel mediante .bean(preRegistroValidator).
     *
     * @param body     contenido del CSV como String
     * @param fileName nombre del archivo (header CamelFileName)
     * @param exchange Exchange de Camel (para establecer propiedades)
     */
    public void validate(@Body String body,
                         @Header("CamelFileName") String fileName,
                         Exchange exchange) {

        if (body == null || body.isBlank()) {
            reject(exchange, fileName, "Archivo vacio");
            return;
        }

        String[] lines = body.split("\\r?\\n");

        // 1. Encabezado
        String[] headerCols = lines[0].split(",", -1);
        if (headerCols.length != EXPECTED_HEADER.size()) {
            reject(exchange, fileName,
                    "Numero de columnas invalido: se esperaban " + EXPECTED_HEADER.size()
                            + " y se encontraron " + headerCols.length);
            return;
        }
        for (int i = 0; i < EXPECTED_HEADER.size(); i++) {
            if (!EXPECTED_HEADER.get(i).equalsIgnoreCase(headerCols[i].trim())) {
                reject(exchange, fileName,
                        "Encabezado invalido. Esperado: " + EXPECTED_HEADER
                                + " | Recibido: " + Arrays.toString(headerCols));
                return;
            }
        }

        // 2-4. Validacion fila a fila
        int rowNumber = 1;
        int validRows = 0;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            rowNumber++;
            if (line.trim().isEmpty()) {
                continue;
            }
            String[] cols = line.split(",", -1);

            if (cols.length != EXPECTED_HEADER.size()) {
                reject(exchange, fileName,
                        "Fila " + rowNumber + ": numero de columnas invalido.");
                return;
            }
            for (int c = 0; c < cols.length; c++) {
                if (cols[c] == null || cols[c].trim().isEmpty()) {
                    reject(exchange, fileName,
                            "Fila " + rowNumber + ": campo vacio en columna '"
                                    + EXPECTED_HEADER.get(c) + "'.");
                    return;
                }
            }

            // Formato fecha
            String date = cols[2].trim();
            try {
                LocalDate.parse(date, DATE_FMT);
            } catch (Exception e) {
                reject(exchange, fileName,
                        "Fila " + rowNumber + ": appointment_date '" + date
                                + "' no cumple formato YYYY-MM-DD.");
                return;
            }

            // insurance_code
            String insurance = cols[3].trim().toUpperCase();
            if (!ALLOWED_INSURANCE.contains(insurance)) {
                reject(exchange, fileName,
                        "Fila " + rowNumber + ": insurance_code '" + insurance
                                + "' no permitido. Valores validos: " + ALLOWED_INSURANCE);
                return;
            }
            validRows++;
        }

        if (validRows == 0) {
            reject(exchange, fileName, "El archivo no contiene filas con datos.");
            return;
        }

        // Archivo valido
        exchange.setProperty("validationResult", "VALID");
        exchange.setProperty("validRows", validRows);
        log.info("[VALIDACION OK] archivo='{}' filas_validas={}", fileName, validRows);
    }

    private void reject(Exchange exchange, String fileName, String reason) {
        exchange.setProperty("validationResult", "INVALID");
        exchange.setProperty("validationError", reason);
        log.warn("[VALIDACION FAIL] archivo='{}' motivo='{}'", fileName, reason);
    }
}
