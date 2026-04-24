# camel-saludvital

Integración File Transfer entre el **Sistema de Admisiones** y el **Sistema de Facturación** de la Clínica SaludVital, implementada con **Apache Camel 4 + Spring Boot 3**.

## Estructura de carpetas de datos

```
data/
├── input/      (archivos CSV pendientes de procesar)
├── output/     (archivos válidos entregados a Facturación)
├── archive/    (respaldo de todo lo procesado, renombrado con timestamp)
└── error/      (archivos inválidos + reporte .motivo.txt)
```

## Cómo ejecutar

```bash
# 1. Compilar
mvn clean package

# 2. Ejecutar
mvn spring-boot:run

# 3. Copiar un CSV a data/input y observar los logs
cp ../data/input/pre_registros_validos.csv data/input/
```

## Rutas Camel implementadas

| routeId | Propósito |
|---|---|
| `ruta-ingesta-pre-registros` | Detecta CSV en `input`, valida y decide destino |
| `ruta-envio-output`          | Copia el archivo válido a `output` (Facturación) |
| `ruta-envio-error`           | Mueve el archivo inválido a `error` y genera reporte |
| `ruta-archivado`             | Archiva una copia en `archive` con timestamp |

## Validaciones aplicadas (`PreRegistroValidator`)

1. El encabezado debe ser exactamente:
   `patient_id,full_name,appointment_date,insurance_code`
2. Ninguna celda puede estar vacía.
3. `appointment_date` debe cumplir `YYYY-MM-DD` y ser una fecha real.
4. `insurance_code` debe pertenecer a `{IESS, PRIVADO, NINGUNO}`.

## Prevención de reprocesamiento

- Los archivos se **mueven** (no se copian) fuera de `input` mediante la opción `move=.processing/${file:name}`.
- Un `idempotentConsumer` basado en `nombre + tamaño` descarta duplicados incluso si un mismo archivo vuelve a aparecer.
- El archivado en `archive` añade timestamp, por lo que nunca se sobrescribe.
