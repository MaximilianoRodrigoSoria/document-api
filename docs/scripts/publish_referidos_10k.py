"""
publish_referidos_10k.py
------------------------
Publica N mensajes al topic Kafka 'document.generation.requested',
cada uno con 10.000 filas de referidos en el template 'programa-referidos-v1'.

ADVERTENCIA DE TAMAÑO:
  10.000 filas × ~120 bytes ≈ 1,2 MB por mensaje.
  El limite default de Kafka es 1 MB. Para ejecutar este script se requiere:

  1. Broker  — aumentar 'message.max.bytes' en el servidor Kafka:
       message.max.bytes=2097152   # 2 MB

  2. Topic (si ya existe) — ajustar 'max.message.bytes' a nivel de topico:
       kafka-configs.sh --bootstrap-server localhost:9092 \
           --entity-type topics --entity-name document.generation.requested \
           --alter --add-config max.message.bytes=2097152

  El producer ya configura max_request_size=2 MB automaticamente.

Uso:
    python publish_referidos_10k.py
    python publish_referidos_10k.py --count 5
    python publish_referidos_10k.py --count 1 --dry-run
    python publish_referidos_10k.py --broker localhost:9092 --count 3
"""

import argparse
import json
import random
import sys
import time
import uuid
from itertools import cycle

try:
    from kafka import KafkaProducer
    from kafka.errors import KafkaError
except ImportError:
    raise SystemExit(
        "\n[ERROR] Falta la libreria kafka-python.\n"
        "        Instalala con:  pip install kafka-python\n"
    )

# ── Configuracion ─────────────────────────────────────────────────────────────

TOPIC           = "document.generation.requested"
DOMAIN          = "MISIONES"
TEMPLATE_NAME   = "programa-referidos-v1"
DEFAULT_BROKER  = "localhost:9092"
ROWS_PER_PDF    = 10_000

# 2 MB — necesario para payloads de 10 k referidos (~1,2 MB serializados en JSON)
MAX_REQUEST_SIZE = 2 * 1024 * 1024

REPORT_INTERVAL = 1   # reportar cada mensaje (volumenes bajos, PDFs pesados)

# ── Datos aleatorios ──────────────────────────────────────────────────────────

_PERSONAS = [
    ("Juan Perez",        "12.345.678-9"),
    ("Maria Gonzalez",    "9.876.543-2"),
    ("Carlos Lopez",      "15.432.100-K"),
    ("Ana Rodriguez",     "8.765.432-1"),
    ("Pedro Martinez",    "11.222.333-4"),
    ("Sofia Herrera",     "14.111.222-3"),
    ("Diego Fuentes",     "13.444.555-6"),
    ("Valentina Castro",  "16.777.888-9"),
    ("Matias Morales",    "10.999.000-K"),
    ("Camila Torres",     "7.654.321-8"),
]

_NOMBRES_REFERIDOS = [
    "Lucia Valdes",      "Tomas Bravo",       "Isidora Pino",      "Rodrigo Saez",
    "Fernanda Rios",     "Andres Vega",        "Daniela Mora",      "Felipe Nunez",
    "Constanza Ibanez",  "Sebastian Munoz",    "Paula Rojas",       "Javier Soto",
    "Camila Herrera",    "Nicolas Fuentes",    "Valentina Castro",  "Diego Morales",
    "Antonia Espinoza",  "Matias Gonzalez",    "Javiera Ortiz",     "Benjamin Tapia",
    "Catalina Reyes",    "Ignacio Flores",     "Renata Sanchez",    "Vicente Ramirez",
    "Macarena Diaz",     "Cristobal Perez",    "Amanda Silva",      "Emilio Torres",
    "Francisca Gutierrez", "Alonso Mendez",
]

_ESTADOS_REFERIDO = ["Completado", "Completado", "Completado", "En proceso", "Pendiente"]

# Fechas de registro distribuidas en los primeros 5 meses de 2026
_FECHAS_REGISTRO = [
    "{:02d}/{:02d}/2026".format(d, m)
    for m in range(1, 6)
    for d in range(1, 29)
]

_MESES = [
    ("Enero",   "01"), ("Febrero",  "02"), ("Marzo",     "03"),
    ("Abril",   "04"), ("Mayo",     "05"), ("Junio",     "06"),
    ("Julio",   "07"), ("Agosto",   "08"), ("Septiembre","09"),
    ("Octubre", "10"), ("Noviembre","11"), ("Diciembre", "12"),
]

# ── Generacion de campos ──────────────────────────────────────────────────────

def _campos_referidos_10k(premio_unitario_clp=2_000):
    """
    Genera el payload de fields para un PDF de programa-referidos-v1
    con exactamente ROWS_PER_PDF (10.000) filas en la lista 'referidos'.
    Cicla sobre nombres y fechas para cubrir el volumen sin restricciones.
    """
    nombre, rut = random.choice(_PERSONAS)
    mes_nombre, _ = random.choice(_MESES)

    nombres_cycle = cycle(_NOMBRES_REFERIDOS)
    fechas_cycle  = cycle(_FECHAS_REGISTRO)

    referidos_data = []
    total = 0
    for _ in range(ROWS_PER_PDF):
        estado = random.choice(_ESTADOS_REFERIDO)
        premio = premio_unitario_clp if estado == "Completado" else 0
        total += premio
        referidos_data.append({
            "nombreReferido": next(nombres_cycle),
            "fechaRegistro":  next(fechas_cycle),
            "estado":         estado,
            "premio":         "${:,.0f}".format(premio).replace(",", ".") if premio > 0 else "-",
        })

    return {
        "nombre":      nombre,
        "rut":         rut,
        "periodo":     "{} 2026".format(mes_nombre),
        "referidos":   referidos_data,
        "totalPremio": "${:,.0f}".format(total).replace(",", "."),
        "condiciones": (
            "Stress test — {:,} referidos generados automaticamente. "
            "Premio de ${:,.0f} por referido completado.".format(
                ROWS_PER_PDF, premio_unitario_clp)
        ),
    }


def _build_event(fields):
    return {
        "idempotencyId": str(uuid.uuid4()),
        "domain":        DOMAIN,
        "templateName":  TEMPLATE_NAME,
        "fields":        fields,
    }

# ── ANSI helpers ──────────────────────────────────────────────────────────────

_CYAN   = "\033[96m"
_YELLOW = "\033[93m"
_GREEN  = "\033[92m"
_RED    = "\033[91m"
_GRAY   = "\033[90m"
_RESET  = "\033[0m"


def _c(color, text):
    return "{}{}{}".format(color, text, _RESET) if sys.stdout.isatty() else text

# ── Publicacion ───────────────────────────────────────────────────────────────

def publish(broker, count, dry_run=False):
    print()
    print(_c(_CYAN,   "  +===========================================================+"))
    print(_c(_CYAN,   "  |   publish_referidos_10k  —  programa-referidos-v1         |"))
    print(_c(_CYAN,   "  |   {} filas de referidos por PDF                         |".format(
        "{:,}".format(ROWS_PER_PDF))))
    print(_c(_CYAN,   "  +===========================================================+"))
    print()
    print("  Template  : {}".format(_c(_GREEN, TEMPLATE_NAME)))
    print("  Filas/PDF : {}".format(_c(_GREEN, "{:,}".format(ROWS_PER_PDF))))
    print("  Mensajes  : {}".format(_c(_GREEN, "{:,}".format(count))))
    print("  Topic     : {}".format(_c(_GREEN, TOPIC)))
    print("  Broker    : {}".format(_c(_GREEN, broker)))
    print()

    if dry_run:
        _dry_run_preview(count)
        return

    producer = KafkaProducer(
        bootstrap_servers=broker,
        value_serializer=lambda v: json.dumps(v, ensure_ascii=False).encode("utf-8"),
        key_serializer=lambda k: k.encode("utf-8") if k else None,
        api_version=(2, 5, 0),
        acks="all",
        retries=5,
        max_block_ms=60_000,
        max_request_size=MAX_REQUEST_SIZE,  # 2 MB — necesario para payloads de 10k filas
        buffer_memory=67_108_864,
        linger_ms=0,  # sin batch — cada PDF es un mensaje independiente y pesado
    )

    print(_c(_YELLOW, "  Generando y publicando mensajes...\n"))

    sent    = 0
    errors  = 0
    futures = []
    t_start = time.time()

    for i in range(count):
        seq = i + 1

        # Generar fields — puede tardar decenas de ms para 10k filas
        t_gen  = time.time()
        fields = _campos_referidos_10k()
        event  = _build_event(fields)
        gen_ms = (time.time() - t_gen) * 1000

        payload_kb = len(json.dumps(event, ensure_ascii=False).encode("utf-8")) / 1024
        print("  [{:>3}/{}]  id={}  payload={:.1f}KB  gen={:.0f}ms".format(
            seq, count, event["idempotencyId"], payload_kb, gen_ms), end="  ", flush=True)

        try:
            future = producer.send(TOPIC, key=event["idempotencyId"], value=event)
            futures.append((seq, event["idempotencyId"], future))
            sent += 1
        except KafkaError as exc:
            errors += 1
            print(_c(_RED, "[ERR] {}".format(exc)))
            continue

        print(_c(_GRAY, "encolado"))

    print()
    print(_c(_YELLOW, "  Flush — esperando ACK de {:,} mensajes...".format(len(futures))))
    producer.flush(timeout=120)

    ack_errors = 0
    for seq, mid, future in futures:
        try:
            future.get(timeout=10)
            print("  [{:>3}/{}]  {} {}".format(
                seq, count, mid, _c(_GREEN, "ACK OK")))
        except KafkaError as exc:
            ack_errors += 1
            print("  [{:>3}/{}]  {} {}".format(
                seq, count, mid, _c(_RED, "ACK FAIL: {}".format(exc))))

    producer.close()

    elapsed = time.time() - t_start
    ok      = sent - ack_errors

    print()
    print(_c(_CYAN, "  " + "-" * 53))
    print("  Publicados exitosamente : {:>5,}".format(ok))
    print("  Errores                 : {:>5,}".format(errors + ack_errors))
    print("  Tiempo total            : {:>8.1f}s".format(elapsed))
    print(_c(_GRAY, "  (tiempo de generacion del PDF lo mide el scheduler)"))
    print(_c(_CYAN, "  " + "-" * 53))
    print()


def _dry_run_preview(count):
    print(_c(_YELLOW, "  [DRY-RUN] Generando preview sin publicar al broker...\n"))
    for i in range(min(count, 3)):
        fields = _campos_referidos_10k()
        event  = _build_event(fields)
        payload_kb = len(json.dumps(event, ensure_ascii=False).encode("utf-8")) / 1024
        print("  [{:>3}]  id={}".format(i + 1, event["idempotencyId"]))
        print("         template : {}".format(event["templateName"]))
        print("         titular  : {}  ({})".format(
            event["fields"]["nombre"], event["fields"]["rut"]))
        print("         referidos: {:,} filas".format(len(event["fields"]["referidos"])))
        print("         payload  : {:.1f} KB".format(payload_kb))
        print()
    if count > 3:
        print(_c(_GRAY, "  ... ({} mensajes mas no mostrados)\n".format(count - 3)))

# ── CLI ───────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description=(
            "Publica N mensajes Kafka de 'programa-referidos-v1', "
            "cada uno con {:,} filas de referidos.".format(ROWS_PER_PDF)
        ),
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "Ejemplos:\n"
            "  python publish_referidos_10k.py\n"
            "  python publish_referidos_10k.py --count 5\n"
            "  python publish_referidos_10k.py --count 1 --dry-run\n"
            "  python publish_referidos_10k.py --broker localhost:9092 --count 3\n"
            "\nNOTA: el broker debe tener message.max.bytes >= 2097152 (2 MB).\n"
        ),
    )
    parser.add_argument(
        "--broker", default=DEFAULT_BROKER,
        help="Broker Kafka (default: {})".format(DEFAULT_BROKER),
    )
    parser.add_argument(
        "--count", type=int, default=1, metavar="N",
        help="Cantidad de mensajes a publicar (default: 1)",
    )
    parser.add_argument(
        "--dry-run", action="store_true",
        help="Genera los payloads y muestra preview sin publicar al broker",
    )
    args = parser.parse_args()

    if args.count < 1:
        raise SystemExit("\n[ERROR] --count debe ser >= 1.\n")

    publish(broker=args.broker, count=args.count, dry_run=args.dry_run)


if __name__ == "__main__":
    main()
