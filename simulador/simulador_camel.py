"""
Simulador de la ruta Apache Camel `ruta-ingesta-pre-registros`.

Replica, en Python, el mismo flujo que el proyecto `camel-saludvital`:
  1. Lee los CSV de data/input/
  2. Aplica las mismas validaciones que PreRegistroValidator.java
  3. Copia los validos a data/output/
  4. Copia los invalidos a data/error/ con reporte .motivo.txt
  5. Archiva todos los procesados en data/archive/ con timestamp
  6. Mueve el original a data/input/.processing/ para evitar reprocesamiento
  7. Emite log estructurado a stdout y a evidencias/ejecucion.log
"""

from __future__ import annotations

import csv
import datetime as dt
import logging
import shutil
from pathlib import Path
from typing import List, Tuple

BASE_DIR    = Path(__file__).resolve().parents[1]
INPUT_DIR   = BASE_DIR / "data" / "input"
OUTPUT_DIR  = BASE_DIR / "data" / "output"
ARCHIVE_DIR = BASE_DIR / "data" / "archive"
ERROR_DIR   = BASE_DIR / "data" / "error"
EVIDENCIAS  = BASE_DIR / "evidencias"

EXPECTED_HEADER   = ["patient_id", "full_name", "appointment_date", "insurance_code"]
ALLOWED_INSURANCE = {"IESS", "PRIVADO", "NINGUNO"}


EVIDENCIAS.mkdir(parents=True, exist_ok=True)
LOG_FILE = EVIDENCIAS / "ejecucion.log"

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    handlers=[
        logging.FileHandler(LOG_FILE, mode="w", encoding="utf-8"),
        logging.StreamHandler(),
    ],
)
log = logging.getLogger("camel-sim")


def validar_csv(path: Path) -> Tuple[bool, str, int]:
    """Devuelve (es_valido, motivo, filas_validas)."""
    try:
        with path.open(encoding="utf-8") as f:
            reader = list(csv.reader(f))
    except Exception as e:
        return False, f"No se pudo leer el archivo: {e}", 0

    if not reader:
        return False, "Archivo vacio", 0

    header = [c.strip() for c in reader[0]]
    if header != EXPECTED_HEADER:
        return False, (
            f"Encabezado invalido. Esperado: {EXPECTED_HEADER} | "
            f"Recibido: {header}"
        ), 0

    filas_validas = 0
    for i, row in enumerate(reader[1:], start=2):
        if not row or all(not c.strip() for c in row):
            continue

        if len(row) != len(EXPECTED_HEADER):
            return False, f"Fila {i}: numero de columnas invalido.", 0

        for idx, val in enumerate(row):
            if not val or not val.strip():
                return False, (
                    f"Fila {i}: campo vacio en columna "
                    f"'{EXPECTED_HEADER[idx]}'."
                ), 0

        try:
            dt.datetime.strptime(row[2].strip(), "%Y-%m-%d")
        except ValueError:
            return False, (
                f"Fila {i}: appointment_date '{row[2]}' no cumple "
                f"formato YYYY-MM-DD."
            ), 0

        ins = row[3].strip().upper()
        if ins not in ALLOWED_INSURANCE:
            return False, (
                f"Fila {i}: insurance_code '{ins}' no permitido. "
                f"Valores validos: {sorted(ALLOWED_INSURANCE)}"
            ), 0

        filas_validas += 1

    if filas_validas == 0:
        return False, "El archivo no contiene filas con datos.", 0

    return True, "OK", filas_validas


def procesar(path: Path) -> dict:
    log.info("[INGESTA] Archivo detectado: '%s' tamanio=%d bytes",
             path.name, path.stat().st_size)

    es_valido, motivo, filas = validar_csv(path)
    timestamp = dt.datetime.now().strftime("%Y-%m-%d_%H%M%S")
    base, ext = path.stem, path.suffix
    archive_name = f"{base}_{timestamp}{ext}"

    result = {
        "archivo": path.name,
        "tamanio_bytes": path.stat().st_size,
        "valido": es_valido,
        "motivo": motivo,
        "filas_validas": filas,
        "archive_name": archive_name,
        "timestamp": timestamp,
    }

    if es_valido:
        log.info("[VALIDACION OK] archivo='%s' filas_validas=%d",
                 path.name, filas)
        destino = OUTPUT_DIR / path.name
        shutil.copy2(path, destino)
        log.info("[OUTPUT] Entregando '%s' a Facturacion -> %s",
                 path.name, destino)
    else:
        log.warning("[VALIDACION FAIL] archivo='%s' motivo='%s'",
                    path.name, motivo)
        destino = ERROR_DIR / path.name
        shutil.copy2(path, destino)
        (ERROR_DIR / f"{path.name}.motivo.txt").write_text(
            f"Archivo: {path.name}\n"
            f"Motivo : {motivo}\n"
            f"Fecha  : {dt.datetime.now():%Y-%m-%d %H:%M:%S}\n",
            encoding="utf-8",
        )
        log.warning("[ERROR] Movido a carpeta error + reporte generado")

    archivo_archivado = ARCHIVE_DIR / archive_name
    shutil.copy2(path, archivo_archivado)
    log.info("[ARCHIVE] Archivado como '%s'", archive_name)

    processing = INPUT_DIR / ".processing"
    processing.mkdir(exist_ok=True)
    try:
        shutil.move(str(path), str(processing / path.name))
        log.info("[FIN] Procesamiento completo de '%s' "
                 "(original movido a input/.processing/ -> no se reprocesa)",
                 path.name)
    except Exception as e:
        log.warning("[FIN] No se pudo mover '%s' a .processing: %s. "
                    "El original permanece en input/.", path.name, e)

    return result


def main() -> None:
    for d in (OUTPUT_DIR, ARCHIVE_DIR, ERROR_DIR):
        d.mkdir(parents=True, exist_ok=True)

    csvs: List[Path] = sorted(INPUT_DIR.glob("*.csv"))
    if not csvs:
        log.info("No hay archivos CSV en input/. Fin.")
        return

    log.info("=" * 68)
    log.info(" SIMULACION CAMEL - Clinica SaludVital - %s",
             dt.datetime.now().strftime("%Y-%m-%d %H:%M:%S"))
    log.info("=" * 68)

    resultados = [procesar(p) for p in csvs]

    log.info("=" * 68)
    log.info(" RESUMEN DEL PROCESAMIENTO")
    log.info("=" * 68)
    for r in resultados:
        estado = "VALIDO  " if r["valido"] else "INVALIDO"
        log.info(" %s | %-32s | filas=%d | archivado=%s",
                 estado, r["archivo"], r["filas_validas"], r["archive_name"])
        if not r["valido"]:
            log.info("          motivo: %s", r["motivo"])

    log.info("")
    log.info("Contenido final data/output  : %s",
             sorted(p.name for p in OUTPUT_DIR.iterdir()))
    log.info("Contenido final data/archive : %s",
             sorted(p.name for p in ARCHIVE_DIR.iterdir()))
    log.info("Contenido final data/error   : %s",
             sorted(p.name for p in ERROR_DIR.iterdir()))
    log.info("Contenido final data/input   : %s",
             sorted(p.name for p in INPUT_DIR.iterdir() if p.is_file()))


if __name__ == "__main__":
    main()
