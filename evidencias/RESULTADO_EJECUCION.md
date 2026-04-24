# Evidencias de ejecución — Integración File Transfer SaludVital

Ejecución del simulador `simulador/simulador_camel.py`, que replica exactamente
la ruta Camel `ruta-ingesta-pre-registros` del proyecto `camel-saludvital/`.

## Resumen del procesamiento

| Archivo de entrada                | Resultado  | Filas válidas | Motivo de rechazo                                                                 | Archivo archivado (con timestamp)                          |
|-----------------------------------|------------|---------------|----------------------------------------------------------------------------------|------------------------------------------------------------|
| pre_registros_bad_header.csv      | INVALIDO   | 0             | Encabezado incorrecto (id_patient,name,date,insurance)                           | pre_registros_bad_header_2026-04-24_002710.csv             |
| pre_registros_invalidos.csv       | INVALIDO   | 0             | Fila 2: `appointment_date '10/05/2026'` no cumple formato YYYY-MM-DD              | pre_registros_invalidos_2026-04-24_002710.csv              |
| pre_registros_mixtos.csv          | INVALIDO   | 0             | Fila 3: `full_name` vacío                                                         | pre_registros_mixtos_2026-04-24_002710.csv                 |
| pre_registros_validos.csv         | **VALIDO** | **3**         | —                                                                                 | pre_registros_validos_2026-04-24_002710.csv                |

## Ubicaciones resultantes

- `data/output/pre_registros_validos.csv` — entregado a Facturación.
- `data/archive/` — 4 copias con timestamp (una por cada archivo procesado).
- `data/error/` — 3 CSV inválidos + 3 reportes `.motivo.txt`.
- `data/input/` — vacío (los originales se movieron a `data/input/.processing/`).

## Log completo

El archivo `evidencias/ejecucion.log` contiene el trace completo con formato
`timestamp [NIVEL] mensaje`, idéntico al que emitiría `org.apache.camel` en la
ruta Java. Las etiquetas usadas (`[INGESTA]`, `[VALIDACION OK]`,
`[VALIDACION FAIL]`, `[OUTPUT]`, `[ERROR]`, `[ARCHIVE]`, `[FIN]`) corresponden
uno a uno a los `log()` declarados en `PreRegistrosRoute.java` y
`PreRegistroValidator.java`.
