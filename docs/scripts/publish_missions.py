"""
publish_missions.py
-------------------
Publica N mensajes al topic Kafka 'document.generation.requested'.
Los campos (fields) usan la estructura Mustache: listas Python para secciones
{{#lista}}...{{/lista}} y strings para variables simples {{variable}}.

Sin argumentos: menú interactivo.
Con argumentos:
    python publish_missions.py --template 1 --count 10
    python publish_missions.py --template 3 --count 1 --dry-run
    python publish_missions.py --list
    python publish_missions.py --template 2 --mision Gastronomia --count 5
"""

import argparse
import json
import os
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

TOPIC           = "document.generation.requested"
DOMAIN          = "MISIONES"
DEFAULT_BROKER  = "localhost:9092"
REPORT_INTERVAL = 100

# Corrida de stress: cada valor = cantidad de FILAS dentro de un único PDF
# Límite práctico ~6.000 filas x 120 bytes ≈ 720 KB (margen sobre el 1 MB default de Kafka)
ESCALATE_ROW_COUNTS = [1_000, 2_500, 5_000, 6_000]

# ── Índice de templates — debe coincidir con el campo "name" en document_templates ──

TEMPLATES = {
    1: "compra-y-gana-personas-v1",
    2: "compra-y-gana-empresas-v1",
    3: "cashback-mensual-v1",
    4: "programa-referidos-v1",
}

# ── Datos aleatorios compartidos ──────────────────────────────────────────────

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

_EMPRESAS = [
    ("Supermercados del Sur SA",  "76.543.210-K"),
    ("Gastronomia Express Ltda",  "77.111.222-3"),
    ("Combustibles Copec SpA",    "78.999.000-1"),
    ("Farmacia Central SA",       "79.888.777-2"),
    ("MercadoDigital Ltda",       "80.321.654-K"),
    ("Viajes y Turismo SA",       "81.456.789-3"),
]

_MESES = [
    ("Enero",      "01"), ("Febrero",   "02"), ("Marzo",      "03"),
    ("Abril",      "04"), ("Mayo",      "05"), ("Junio",      "06"),
    ("Julio",      "07"), ("Agosto",    "08"), ("Septiembre", "09"),
    ("Octubre",    "10"), ("Noviembre", "11"), ("Diciembre",  "12"),
]

_ANIO = "2026"


def _periodo():
    """Retorna (mes_nombre, anio, periodoDesde, periodoHasta) del mes actual."""
    mes_nombre, mes_num = random.choice(_MESES)
    desde = "01/{}/{}".format(mes_num, _ANIO)
    hasta = "30/{}/{}".format(mes_num, _ANIO)
    return mes_nombre, _ANIO, desde, hasta


# ── Template 1: Compra y Gana — Personas (tabla dos etapas) ──────────────────
#
# Mustache espera en "fields":
#   - strings simples: nombre, rut, mes, periodoDesde, periodoHasta, condiciones
#   - lista "categorias": [{categoria, etapa1SiGastas, etapa1Premio, etapa1Acumulado,
#                                       etapa2SiGastas, etapa2Premio, etapa2Acumulado}, ...]

def _campos_personas_dos_etapas(categorias_data, condiciones=""):
    nombre, rut = random.choice(_PERSONAS)
    mes_nombre, _, desde, hasta = _periodo()
    return {
        "nombre":       nombre,
        "rut":          rut,
        "mes":          "{} {}".format(mes_nombre, _ANIO),
        "periodoDesde": desde,
        "periodoHasta": hasta,
        "categorias":   categorias_data,
        "condiciones":  condiciones,
    }


MISIONES_PERSONAS = [
    {
        "nombre":      "Compras-Diarias",
        "descripcion": "Categorias de gasto cotidiano con escalada por etapa.",
        "fields": _campos_personas_dos_etapas(
            categorias_data=[
                {"categoria": "Categoria 1",  "etapa1SiGastas": "$50.000",  "etapa1Premio": "$1.000", "etapa1Acumulado": "$1.000",  "etapa2SiGastas": "$50.000",  "etapa2Premio": "$1.000", "etapa2Acumulado": "$2.000"},
                {"categoria": "Categoria 2",  "etapa1SiGastas": "$100.000", "etapa1Premio": "$1.000", "etapa1Acumulado": "$2.000",  "etapa2SiGastas": "$50.000",  "etapa2Premio": "$1.000", "etapa2Acumulado": "$3.000"},
                {"categoria": "Categoria 3",  "etapa1SiGastas": "$100.000", "etapa1Premio": "$2.000", "etapa1Acumulado": "$4.000",  "etapa2SiGastas": "$50.000",  "etapa2Premio": "$1.000", "etapa2Acumulado": "$4.000"},
                {"categoria": "Categoria 4",  "etapa1SiGastas": "$100.000", "etapa1Premio": "$4.000", "etapa1Acumulado": "$8.000",  "etapa2SiGastas": "$50.000",  "etapa2Premio": "$1.000", "etapa2Acumulado": "$5.000"},
                {"categoria": "Categoria 5",  "etapa1SiGastas": "$100.000", "etapa1Premio": "$1.000", "etapa1Acumulado": "$9.000",  "etapa2SiGastas": "$50.000",  "etapa2Premio": "$1.000", "etapa2Acumulado": "$6.000"},
                {"categoria": "Categoria 6",  "etapa1SiGastas": "$100.000", "etapa1Premio": "$1.000", "etapa1Acumulado": "$10.000", "etapa2SiGastas": "$50.000",  "etapa2Premio": "$1.000", "etapa2Acumulado": "$7.000"},
                {"categoria": "Categoria 7",  "etapa1SiGastas": "$100.000", "etapa1Premio": "$2.000", "etapa1Acumulado": "$12.000", "etapa2SiGastas": "$50.000",  "etapa2Premio": "$1.000", "etapa2Acumulado": "$8.000"},
                {"categoria": "Categoria 8",  "etapa1SiGastas": "$100.000", "etapa1Premio": "$2.000", "etapa1Acumulado": "$14.000", "etapa2SiGastas": "$50.000",  "etapa2Premio": "$1.000", "etapa2Acumulado": "$9.000"},
                {"categoria": "Categoria 9",  "etapa1SiGastas": "$100.000", "etapa1Premio": "$3.000", "etapa1Acumulado": "$17.000", "etapa2SiGastas": "$50.000",  "etapa2Premio": "$1.000", "etapa2Acumulado": "$10.000"},
                {"categoria": "Categoria 10", "etapa1SiGastas": "$100.000", "etapa1Premio": "$4.000", "etapa1Acumulado": "$21.000", "etapa2SiGastas": "$50.000",  "etapa2Premio": "$1.000", "etapa2Acumulado": "$11.000"},
                {"categoria": "Categoria 11", "etapa1SiGastas": "$100.000", "etapa1Premio": "$1.000", "etapa1Acumulado": "$22.000", "etapa2SiGastas": "$100.000", "etapa2Premio": "$1.000", "etapa2Acumulado": "$12.000"},
                {"categoria": "Categoria 12", "etapa1SiGastas": "$100.000", "etapa1Premio": "$1.000", "etapa1Acumulado": "$23.000", "etapa2SiGastas": "$100.000", "etapa2Premio": "$1.000", "etapa2Acumulado": "$13.000"},
            ],
            condiciones="Aplica para personas naturales con cuenta activa. Minimo 3 transacciones en el periodo.",
        ),
    },
    {
        "nombre":      "Entretenimiento",
        "descripcion": "Streaming, cine y ocio con dos etapas de recompensa.",
        "fields": _campos_personas_dos_etapas(
            categorias_data=[
                {"categoria": "Categoria 1", "etapa1SiGastas": "$15.000", "etapa1Premio": "$500",   "etapa1Acumulado": "$500",   "etapa2SiGastas": "$15.000", "etapa2Premio": "$750",   "etapa2Acumulado": "$1.250"},
                {"categoria": "Categoria 2", "etapa1SiGastas": "$30.000", "etapa1Premio": "$1.000", "etapa1Acumulado": "$1.500", "etapa2SiGastas": "$25.000", "etapa2Premio": "$1.250", "etapa2Acumulado": "$2.500"},
                {"categoria": "Categoria 3", "etapa1SiGastas": "$50.000", "etapa1Premio": "$2.000", "etapa1Acumulado": "$3.500", "etapa2SiGastas": "$40.000", "etapa2Premio": "$2.000", "etapa2Acumulado": "$4.500"},
                {"categoria": "Categoria 4", "etapa1SiGastas": "$75.000", "etapa1Premio": "$3.000", "etapa1Acumulado": "$6.500", "etapa2SiGastas": "$60.000", "etapa2Premio": "$3.000", "etapa2Acumulado": "$7.500"},
                {"categoria": "Categoria 5", "etapa1SiGastas": "$100.000","etapa1Premio": "$5.000", "etapa1Acumulado": "$11.500","etapa2SiGastas": "$80.000", "etapa2Premio": "$4.000", "etapa2Acumulado": "$11.500"},
            ],
            condiciones="Incluye suscripciones streaming, cine y plataformas de entretenimiento adheridas.",
        ),
    },
    {
        "nombre":      "Salud-Bienestar",
        "descripcion": "Salud, deporte y bienestar con dos etapas.",
        "fields": _campos_personas_dos_etapas(
            categorias_data=[
                {"categoria": "Categoria 1", "etapa1SiGastas": "$25.000",  "etapa1Premio": "$1.000", "etapa1Acumulado": "$1.000",  "etapa2SiGastas": "$20.000",  "etapa2Premio": "$1.000", "etapa2Acumulado": "$2.000"},
                {"categoria": "Categoria 2", "etapa1SiGastas": "$50.000",  "etapa1Premio": "$2.000", "etapa1Acumulado": "$3.000",  "etapa2SiGastas": "$40.000",  "etapa2Premio": "$2.000", "etapa2Acumulado": "$4.000"},
                {"categoria": "Categoria 3", "etapa1SiGastas": "$75.000",  "etapa1Premio": "$3.000", "etapa1Acumulado": "$6.000",  "etapa2SiGastas": "$60.000",  "etapa2Premio": "$3.000", "etapa2Acumulado": "$7.000"},
                {"categoria": "Categoria 4", "etapa1SiGastas": "$100.000", "etapa1Premio": "$4.000", "etapa1Acumulado": "$10.000", "etapa2SiGastas": "$80.000",  "etapa2Premio": "$4.000", "etapa2Acumulado": "$11.000"},
                {"categoria": "Categoria 5", "etapa1SiGastas": "$150.000", "etapa1Premio": "$6.000", "etapa1Acumulado": "$16.000", "etapa2SiGastas": "$120.000", "etapa2Premio": "$5.000", "etapa2Acumulado": "$16.000"},
            ],
            condiciones="Aplica en farmacias, gimnasios, clinicas y centros de bienestar adheridos.",
        ),
    },
]


# ── Template 2: Compra y Gana — Empresas ─────────────────────────────────────
#
# Mustache espera en "fields":
#   - strings simples: razonSocial, rut, mes, periodoDesde, periodoHasta, condiciones
#   - lista "tramosFacturacion": [{tramo, facturacionMinima, cashback, tope}, ...]

def _campos_empresas(tramos_data, condiciones=""):
    razon, rut = random.choice(_EMPRESAS)
    mes_nombre, _, desde, hasta = _periodo()
    return {
        "razonSocial":        razon,
        "rut":                rut,
        "mes":                "{} {}".format(mes_nombre, _ANIO),
        "periodoDesde":       desde,
        "periodoHasta":       hasta,
        "tramosFacturacion":  tramos_data,
        "condiciones":        condiciones,
    }


MISIONES_EMPRESAS = [
    {
        "nombre":      "Supermercados",
        "descripcion": "Escala de facturacion para comercios del rubro supermercados.",
        "fields": _campos_empresas(
            tramos_data=[
                {"tramo": "Tramo 1", "facturacionMinima": "$500.000",    "cashback": "1,5%", "tope": "$7.500"},
                {"tramo": "Tramo 2", "facturacionMinima": "$1.000.000",  "cashback": "2,0%", "tope": "$20.000"},
                {"tramo": "Tramo 3", "facturacionMinima": "$2.000.000",  "cashback": "2,5%", "tope": "$50.000"},
                {"tramo": "Tramo 4", "facturacionMinima": "$5.000.000",  "cashback": "3,0%", "tope": "$150.000"},
                {"tramo": "Tramo 5", "facturacionMinima": "$10.000.000", "cashback": "3,5%", "tope": "$350.000"},
            ],
            condiciones="Aplica en comercios del rubro supermercados con facturacion electronica.",
        ),
    },
    {
        "nombre":      "Gastronomia",
        "descripcion": "Restaurantes, cafeterias y delivery.",
        "fields": _campos_empresas(
            tramos_data=[
                {"tramo": "Tramo 1", "facturacionMinima": "$300.000",   "cashback": "2,0%", "tope": "$6.000"},
                {"tramo": "Tramo 2", "facturacionMinima": "$700.000",   "cashback": "2,5%", "tope": "$17.500"},
                {"tramo": "Tramo 3", "facturacionMinima": "$1.500.000", "cashback": "3,0%", "tope": "$45.000"},
                {"tramo": "Tramo 4", "facturacionMinima": "$3.000.000", "cashback": "3,5%", "tope": "$105.000"},
            ],
            condiciones="Incluye restaurantes, cafeterias, bares y apps de delivery adheridas.",
        ),
    },
    {
        "nombre":      "E-Commerce",
        "descripcion": "Plataformas de comercio electronico.",
        "fields": _campos_empresas(
            tramos_data=[
                {"tramo": "Tramo 1", "facturacionMinima": "$1.000.000",  "cashback": "2,5%", "tope": "$25.000"},
                {"tramo": "Tramo 2", "facturacionMinima": "$3.000.000",  "cashback": "3,0%", "tope": "$90.000"},
                {"tramo": "Tramo 3", "facturacionMinima": "$7.000.000",  "cashback": "3,5%", "tope": "$245.000"},
                {"tramo": "Tramo 4", "facturacionMinima": "$15.000.000", "cashback": "4,0%", "tope": "$600.000"},
            ],
            condiciones="Valido en plataformas de e-commerce adheridas con pago via Tenpo.",
        ),
    },
]


# ── Template 3: Cashback Mensual ──────────────────────────────────────────────
#
# Mustache espera solo variables simples (sin listas):
#   mes, anio, nombre, rut, totalCompras, porcentajeCashback,
#   montoCashback, fechaAcreditacion, condiciones

def _campos_cashback(total_compras_clp, porcentaje_decimal, condiciones=""):
    nombre, rut = random.choice(_PERSONAS)
    mes_nombre, anio, _, hasta = _periodo()
    monto = int(total_compras_clp * porcentaje_decimal)
    # Fecha de acreditacion: dia 5 del mes siguiente
    mes_num = _MESES[[m[0] for m in _MESES].index(mes_nombre)][1]
    mes_sig = str(int(mes_num) % 12 + 1).zfill(2)
    fecha_acred = "05/{}/{}".format(mes_sig, anio)
    return {
        "mes":               mes_nombre,
        "anio":              anio,
        "nombre":            nombre,
        "rut":               rut,
        "totalCompras":      "${:,.0f}".format(total_compras_clp).replace(",", "."),
        "porcentajeCashback": "{:.0f}%".format(porcentaje_decimal * 100),
        "montoCashback":     "${:,.0f}".format(monto).replace(",", "."),
        "fechaAcreditacion": fecha_acred,
        "condiciones":       condiciones,
    }


MISIONES_CASHBACK = [
    {
        "nombre":      "Cashback-2pct",
        "descripcion": "Liquidacion mensual con 2% de cashback sobre compras.",
        "fields": _campos_cashback(
            total_compras_clp=random.randint(150_000, 800_000),
            porcentaje_decimal=0.02,
            condiciones="El cashback se acredita automaticamente en tu cuenta Tenpo el dia 5 del mes siguiente.",
        ),
    },
    {
        "nombre":      "Cashback-5pct",
        "descripcion": "Liquidacion mensual con 5% de cashback sobre compras premium.",
        "fields": _campos_cashback(
            total_compras_clp=random.randint(500_000, 2_000_000),
            porcentaje_decimal=0.05,
            condiciones="Aplica exclusivamente en comercios adheridos al programa premium. Tope mensual $50.000.",
        ),
    },
    {
        "nombre":      "Cashback-3pct",
        "descripcion": "Liquidacion mensual con 3% de cashback sobre compras seleccionadas.",
        "fields": _campos_cashback(
            total_compras_clp=random.randint(200_000, 1_000_000),
            porcentaje_decimal=0.03,
            condiciones="Valido en rubros seleccionados: supermercados, farmacia y gastronomia.",
        ),
    },
]


# ── Template 4: Programa de Referidos ────────────────────────────────────────
#
# Mustache espera en "fields":
#   - strings simples: nombre, rut, periodo, totalPremio, condiciones
#   - lista "referidos": [{nombreReferido, fechaRegistro, estado, premio}, ...]

_ESTADOS_REFERIDO = ["Completado", "Completado", "Completado", "En proceso", "Pendiente"]

_NOMBRES_REFERIDOS = [
    "Lucia Valdes", "Tomas Bravo", "Isidora Pino", "Rodrigo Saez",
    "Fernanda Rios", "Andres Vega", "Daniela Mora", "Felipe Nunez",
    "Constanza Ibanez", "Sebastian Munoz", "Paula Rojas", "Javier Soto",
    "Camila Herrera", "Nicolas Fuentes", "Valentina Castro", "Diego Morales",
    "Antonia Espinoza", "Matias Gonzalez", "Javiera Ortiz", "Benjamin Tapia",
    "Catalina Reyes", "Ignacio Flores", "Renata Sanchez", "Vicente Ramirez",
    "Macarena Diaz", "Cristobal Perez", "Amanda Silva", "Emilio Torres",
    "Francisca Gutierrez", "Alonso Mendez",
]

# Fechas de registro distribuidas aleatoriamente en el primer semestre 2026
_FECHAS_REGISTRO = [
    "{:02d}/{:02d}/2026".format(d, m)
    for m in range(1, 6)
    for d in range(1, 29)
]


def _campos_referidos(n_referidos, premio_unitario_clp, condiciones=""):
    nombre, rut = random.choice(_PERSONAS)
    mes_nombre, _, _, _ = _periodo()
    fechas = random.sample(_FECHAS_REGISTRO, min(n_referidos, len(_FECHAS_REGISTRO)))
    # Si n_referidos > len(fechas), rellenar con muestras adicionales con reemplazo
    while len(fechas) < n_referidos:
        fechas.append(random.choice(_FECHAS_REGISTRO))
    fechas.sort()
    referidos_data = []
    total = 0
    for i in range(n_referidos):
        estado = random.choice(_ESTADOS_REFERIDO)
        premio = premio_unitario_clp if estado == "Completado" else 0
        total += premio
        referidos_data.append({
            "nombreReferido": random.choice(_NOMBRES_REFERIDOS),
            "fechaRegistro":  fechas[i],
            "estado":         estado,
            "premio":         "${:,.0f}".format(premio).replace(",", ".") if premio > 0 else "-",
        })
    return {
        "nombre":      nombre,
        "rut":         rut,
        "periodo":     "{} {}".format(mes_nombre, "2026"),
        "referidos":   referidos_data,
        "totalPremio": "${:,.0f}".format(total).replace(",", "."),
        "condiciones": condiciones,
    }


def _mision_referidos(premio_unitario_clp, condiciones):
    """Genera una misión con cantidad de referidos aleatoria entre 20 y 100."""
    n = random.randint(20, 100)
    return {
        "nombre":      "Referidos-{}".format(n),
        "descripcion": "Comprobante de {} referidos con premio de ${:,.0f} c/u.".format(
            n, premio_unitario_clp).replace(",", "."),
        "fields": _campos_referidos(
            n_referidos=n,
            premio_unitario_clp=premio_unitario_clp,
            condiciones=condiciones,
        ),
    }


MISIONES_REFERIDOS = [
    _mision_referidos(
        premio_unitario_clp=2_000,
        condiciones="Premio de $2.000 por cada referido que active su cuenta y realice su primera compra.",
    ),
    _mision_referidos(
        premio_unitario_clp=3_000,
        condiciones="Premio de $3.000 por referido completado.",
    ),
    _mision_referidos(
        premio_unitario_clp=5_000,
        condiciones="Premio de $5.000 por referido. El monto se acredita en Tenpesos dentro de 48 horas.",
    ),
]


# ── Mapa de pools por template ────────────────────────────────────────────────

POOLS = {
    1: MISIONES_PERSONAS,
    2: MISIONES_EMPRESAS,
    3: MISIONES_CASHBACK,
    4: MISIONES_REFERIDOS,
}

# ── Construcción de evento Kafka ──────────────────────────────────────────────

def build_event(mision, template_name):
    return {
        "idempotencyId": str(uuid.uuid4()),
        "domain":        DOMAIN,
        "templateName":  template_name,
        "fields":        mision["fields"],
    }

# ── Publicación Kafka ─────────────────────────────────────────────────────────

def publish(broker, misiones_base, template_name, count, dry_run=False):
    mision_stream = cycle(misiones_base)

    if dry_run:
        print("\n[DRY-RUN] Primeros {} mensajes (de {:,} a publicar):\n".format(
            min(count, 3), count))
        for i in range(min(count, 3)):
            m  = next(mision_stream)
            ev = build_event(m, template_name)
            print("  [{:>3}] mision={:<25s}  id={}".format(
                i + 1, m["nombre"], ev["idempotencyId"]))
            print("        fields keys: {}".format(
                ", ".join(str(k) + ("[]" if isinstance(v, list) else "")
                          for k, v in ev["fields"].items())))
        if count > 3:
            print("  ...  ({} mensajes mas)".format(count - 3))
        print()
        return

    producer = KafkaProducer(
        bootstrap_servers=broker,
        value_serializer=lambda v: json.dumps(v, ensure_ascii=False).encode("utf-8"),
        key_serializer=lambda k: k.encode("utf-8") if k else None,
        api_version=(2, 5, 0),
        acks="all",
        retries=5,
        max_block_ms=30_000,
        buffer_memory=67_108_864,
        batch_size=65_536,
        linger_ms=10,
    )

    print("\nConectado a {} -- publicando {:,} mensajes".format(broker, count))
    print("Template : {}".format(template_name))
    print("Topic    : {}\n".format(TOPIC))

    sent    = 0
    errors  = 0
    futures = []
    t_start = time.time()

    for i in range(count):
        mision = next(mision_stream)
        event  = build_event(mision, template_name)
        key    = event["idempotencyId"]

        try:
            future = producer.send(TOPIC, key=key, value=event)
            futures.append((i + 1, mision["nombre"], key, future))
            sent += 1
        except KafkaError as exc:
            errors += 1
            print("  [ERR] [{:>5}] ERROR al encolar: {}".format(i + 1, exc))

        if (i + 1) % REPORT_INTERVAL == 0:
            elapsed = time.time() - t_start
            rate    = (i + 1) / elapsed if elapsed > 0 else 0
            print("  --> Encolados {:>6,} / {:,}  ({:.0f} msg/s)".format(
                i + 1, count, rate), end="\r")

    print("\n  --> Flush... ({:,} mensajes pendientes de ACK)".format(len(futures)))
    producer.flush(timeout=120)

    ack_errors = 0
    for seq, nombre, key, future in futures:
        try:
            future.get(timeout=5)
        except KafkaError as exc:
            ack_errors += 1
            if ack_errors <= 10:
                print("  [ERR] [{:>5}] [{}] ACK failed: {}".format(seq, nombre, exc))

    producer.close()

    elapsed = time.time() - t_start
    ok      = sent - ack_errors
    rate    = ok / elapsed if elapsed > 0 else 0

    print("\n" + "-" * 55)
    print("  Publicados exitosamente : {:>6,}".format(ok))
    print("  Errores                 : {:>6,}".format(errors + ack_errors))
    print("  Tiempo total            : {:>6.1f}s".format(elapsed))
    print("  Throughput              : {:>6.0f} msg/s".format(rate))
    print("-" * 55 + "\n")

# ── Corrida de stress escalonada ─────────────────────────────────────────────

def _campos_referidos_stress(n_referidos, premio_unitario_clp=2_000):
    """
    Genera campos con n_referidos filas en la lista.
    Cicla sobre nombres para soportar listas de cualquier tamaño.
    """
    nombre, rut = random.choice(_PERSONAS)
    mes_nombre, _, _, _ = _periodo()
    nombres_cycle = cycle(_NOMBRES_REFERIDOS)
    fechas_cycle  = cycle(_FECHAS_REGISTRO)

    referidos_data = []
    total = 0
    for _ in range(n_referidos):
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
        "condiciones": "Corrida de stress — {:,} referidos generados automaticamente.".format(n_referidos),
    }


def publish_staggered(broker, template_name, dry_run=False):
    """
    Publica 4 mensajes Kafka en secuencia.
    Cada mensaje contiene un PDF con ESCALATE_ROW_COUNTS[i] filas de referidos.
    Objetivo: medir el tiempo de generacion de PDFs de distinto tamaño.
    """
    t_global = time.time()

    print()
    print(_c(_CYAN, "  +===========================================================+"))
    print(_c(_CYAN, "  |        STRESS TEST ESCALONADO  —  template 4              |"))
    print(_c(_CYAN, "  |  Cada mensaje = 1 PDF con N filas de referidos            |"))
    print(_c(_CYAN, "  |  Escalones: {:>6,}  {:>7,}  {:>8,}  {:>9,}          |".format(
        *ESCALATE_ROW_COUNTS)))
    print(_c(_CYAN, "  +===========================================================+"))
    print()

    if not dry_run:
        producer = KafkaProducer(
            bootstrap_servers=broker,
            value_serializer=lambda v: json.dumps(v, ensure_ascii=False).encode("utf-8"),
            key_serializer=lambda k: k.encode("utf-8") if k else None,
            api_version=(2, 5, 0),
            acks="all",
            retries=5,
            max_block_ms=30_000,
        )

    for step, n_rows in enumerate(ESCALATE_ROW_COUNTS, 1):
        print(_c(_YELLOW, "  ── Escalon {}/{}: PDF con {:,} filas de referidos ──".format(
            step, len(ESCALATE_ROW_COUNTS), n_rows)))

        print(_c(_GRAY, "     Generando campos... "), end="", flush=True)
        t0     = time.time()
        fields = _campos_referidos_stress(n_rows)
        event  = {
            "idempotencyId": str(uuid.uuid4()),
            "domain":        DOMAIN,
            "templateName":  template_name,
            "fields":        fields,
        }
        gen_ms = (time.time() - t0) * 1000
        print(_c(_GRAY, "ok ({:.0f}ms)".format(gen_ms)))

        payload_kb = len(json.dumps(event, ensure_ascii=False).encode("utf-8")) / 1024
        print("     idempotencyId : {}".format(event["idempotencyId"]))
        print("     Payload size  : {:.1f} KB".format(payload_kb))

        if dry_run:
            print(_c(_GRAY, "     [DRY-RUN] No publicado.\n"))
            continue

        t_pub = time.time()
        future = producer.send(TOPIC, key=event["idempotencyId"], value=event)
        producer.flush(timeout=60)
        future.get(timeout=10)
        pub_ms = (time.time() - t_pub) * 1000
        print(_c(_GREEN, "     Publicado en {:.0f}ms\n".format(pub_ms)))

    if not dry_run:
        producer.close()
        total_elapsed = time.time() - t_global
        print(_c(_CYAN, "  ── Resumen ─────────────────────────────────────────"))
        print("  Mensajes enviados : {:>4}  ({} escalones)".format(
            len(ESCALATE_ROW_COUNTS), len(ESCALATE_ROW_COUNTS)))
        print("  Tiempo total      : {:>7.1f}s".format(total_elapsed))
        print(_c(_GRAY, "  (el tiempo de generacion del PDF lo mide el scheduler)"))
        print(_c(_CYAN, "  ────────────────────────────────────────────────────"))
        print()


# ── Menú interactivo ──────────────────────────────────────────────────────────

_CYAN   = "\033[96m"
_YELLOW = "\033[93m"
_GREEN  = "\033[92m"
_RED    = "\033[91m"
_GRAY   = "\033[90m"
_RESET  = "\033[0m"


def _c(color, text):
    return "{}{}{}".format(color, text, _RESET) if sys.stdout.isatty() else text


def _ask_int(prompt, lo, hi):
    while True:
        raw = input(prompt).strip()
        try:
            val = int(raw)
            if lo <= val <= hi:
                return val
        except ValueError:
            pass
        print(_c(_RED, "  Ingresa un numero entre {} y {:,}.".format(lo, hi)))


def interactive_menu(broker):
    os.system("cls" if os.name == "nt" else "clear")

    print()
    print(_c(_CYAN, "  +===========================================================+"))
    print(_c(_CYAN, "  |   document-generator-api  *  Generador de eventos Kafka   |"))
    print(_c(_CYAN, "  +===========================================================+"))
    print()

    print(_c(_YELLOW, "  Templates disponibles:"))
    print()
    for key, name in TEMPLATES.items():
        pool = POOLS[key]
        print("    [{}]  {}".format(key, _c(_GREEN, name)))
        print(_c(_GRAY, "         {} misiones disponibles".format(len(pool))))
        print()

    template_choice = _ask_int(
        "  Seleccionar template [1-{}]: ".format(len(TEMPLATES)), 1, len(TEMPLATES))
    pool          = POOLS[template_choice]
    template_name = TEMPLATES[template_choice]

    print()
    print(_c(_YELLOW, "  Misiones para template {}:".format(template_choice)))
    print()
    for i, m in enumerate(pool, 1):
        print("    {}  {:<30s}  {}".format(
            _c(_GRAY, "{}.".format(i)),
            m["nombre"],
            _c(_GRAY, m["descripcion"])))
    print()
    print(_c(_GRAY, "  (Se rotaran en ciclo si el total supera la cantidad de misiones)"))
    print()

    count = _ask_int("  Cantidad de mensajes a enviar [1-50000]: ", 1, 50_000)

    print()
    print("  Template  : {}".format(_c(_GREEN, template_name)))
    print("  Mensajes  : {}".format(_c(_GREEN, "{:,}".format(count))))
    print("  Topic     : {}".format(_c(_GREEN, TOPIC)))
    print("  Broker    : {}".format(_c(_GREEN, broker)))
    print()

    confirm = input("  Confirmar envio? [s/n]: ").strip()
    if confirm.lower() != "s":
        print(_c(_RED, "\n  Cancelado.\n"))
        return

    print()
    publish(broker=broker, misiones_base=pool,
            template_name=template_name, count=count)

# ── CLI ───────────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(
        description="Publica N eventos de generacion de PDF al topic Kafka.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=(
            "Ejemplos:\n"
            "  python publish_missions.py\n"
            "  python publish_missions.py --template 1 --count 10\n"
            "  python publish_missions.py --template 3 --count 1 --dry-run\n"
            "  python publish_missions.py --template 2 --mision Gastronomia --count 5\n"
            "  python publish_missions.py --list\n"
        ),
    )
    parser.add_argument("--broker",   default=DEFAULT_BROKER,
                        help="Broker Kafka (default: {})".format(DEFAULT_BROKER))
    parser.add_argument("--template", type=int, choices=list(TEMPLATES.keys()),
                        help="Numero de template (1-{})  (omitir = menu)".format(len(TEMPLATES)))
    parser.add_argument("--count",    type=int, metavar="N",
                        help="Cantidad de mensajes (omitir = menu)")
    parser.add_argument("--mision",   metavar="NOMBRE",
                        help="Publicar solo la mision con ese nombre")
    parser.add_argument("--list",     action="store_true",
                        help="Listar templates y misiones disponibles y salir")
    parser.add_argument("--dry-run",  action="store_true",
                        help="Mostrar primeros mensajes sin publicar")
    parser.add_argument("--escalate", action="store_true",
                        help=(
                            "Stress test escalonado para template 4: publica 4 mensajes, "
                            "cada uno con {} filas de referidos respectivamente. "
                            "Requiere --template 4.".format(
                                " / ".join("{:,}".format(c) for c in ESCALATE_ROW_COUNTS))
                        ))
    args = parser.parse_args()

    if args.list:
        print()
        for tid, tname in TEMPLATES.items():
            pool = POOLS[tid]
            print("  Template {} -- {}".format(tid, _c(_GREEN, tname)))
            for i, m in enumerate(pool, 1):
                print("    {}.  {:<30s} {}".format(i, m["nombre"], _c(_GRAY, m["descripcion"])))
            print()
        return

    if args.escalate:
        if args.template != 4:
            raise SystemExit(
                "\n[ERROR] --escalate solo es válido con --template 4.\n"
                "        Uso: python publish_missions.py --template 4 --escalate\n"
            )
        template_name = TEMPLATES[4]
        publish_staggered(broker=args.broker, template_name=template_name,
                          dry_run=args.dry_run)
        return

    if args.template is not None and args.count is not None:
        pool          = POOLS[args.template]
        template_name = TEMPLATES[args.template]
        misiones_base = pool

        if args.mision:
            misiones_base = [m for m in pool if m["nombre"].lower() == args.mision.lower()]
            if not misiones_base:
                nombres = ", ".join(m["nombre"] for m in pool)
                raise SystemExit(
                    "\n[ERROR] Mision '{}' no encontrada en template {}.\n"
                    "        Disponibles: {}\n".format(args.mision, args.template, nombres)
                )

        if args.count < 1:
            raise SystemExit("\n[ERROR] --count debe ser >= 1.\n")

        publish(broker=args.broker, misiones_base=misiones_base,
                template_name=template_name, count=args.count, dry_run=args.dry_run)
        return

    interactive_menu(broker=args.broker)


if __name__ == "__main__":
    main()