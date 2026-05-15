<#
.SYNOPSIS
    Publica N mensajes al topic Kafka 'document.generation.requested'.

.DESCRIPTION
    Los campos (fields) usan la estructura Mustache: arrays de hashtables para
    secciones {{#lista}}...{{/lista}} y strings para variables simples {{variable}}.

    Sin argumentos: menu interactivo.

.PARAMETER Broker
    Broker Kafka. Default: localhost:9092

.PARAMETER Template
    Numero de template (1-4). Omitir para usar el menu interactivo.

.PARAMETER Count
    Cantidad de mensajes a publicar. Omitir para usar el menu interactivo.

.PARAMETER Mision
    Publicar solo la mision con ese nombre exacto (case-insensitive).

.PARAMETER List
    Listar templates y misiones disponibles y salir.

.PARAMETER DryRun
    Mostrar los primeros mensajes sin publicar en Kafka.

.PARAMETER KafkaContainer
    Nombre del contenedor Docker con Kafka. Default: document-generator-api-kafka

.EXAMPLE
    .\publish_missions.ps1
    .\publish_missions.ps1 -Template 1 -Count 10
    .\publish_missions.ps1 -Template 3 -Count 1 -DryRun
    .\publish_missions.ps1 -Template 2 -Mision Gastronomia -Count 5
    .\publish_missions.ps1 -List
#>

[CmdletBinding()]
param(
    [string] $Broker         = "localhost:9092",
    [int]    $Template       = 0,
    [int]    $Count          = 0,
    [string] $Mision         = "",
    [switch] $List,
    [switch] $DryRun,
    [string] $KafkaContainer = "document-generator-api-kafka"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# ── Constantes ────────────────────────────────────────────────────────────────

$TOPIC           = "document.generation.requested"
$DOMAIN          = "MISIONES"
$ANIO            = "2026"
$REPORT_INTERVAL = 100

$TEMPLATES = @{
    1 = "compra-y-gana-personas-v1"
    2 = "compra-y-gana-empresas-v1"
    3 = "cashback-mensual-v1"
    4 = "programa-referidos-v1"
}

# ── Colores ANSI ──────────────────────────────────────────────────────────────

$ESC   = [char]27
$CYAN  = "${ESC}[96m"
$YLW   = "${ESC}[93m"
$GRN   = "${ESC}[92m"
$RED   = "${ESC}[91m"
$GRAY  = "${ESC}[90m"
$RST   = "${ESC}[0m"

function col($color, $text) { "${color}${text}${RST}" }

# ── Datos aleatorios ──────────────────────────────────────────────────────────

$PERSONAS = @(
    @{ nombre = "Juan Perez";       rut = "12.345.678-9" }
    @{ nombre = "Maria Gonzalez";   rut = "9.876.543-2"  }
    @{ nombre = "Carlos Lopez";     rut = "15.432.100-K" }
    @{ nombre = "Ana Rodriguez";    rut = "8.765.432-1"  }
    @{ nombre = "Pedro Martinez";   rut = "11.222.333-4" }
    @{ nombre = "Sofia Herrera";    rut = "14.111.222-3" }
    @{ nombre = "Diego Fuentes";    rut = "13.444.555-6" }
    @{ nombre = "Valentina Castro"; rut = "16.777.888-9" }
    @{ nombre = "Matias Morales";   rut = "10.999.000-K" }
    @{ nombre = "Camila Torres";    rut = "7.654.321-8"  }
)

$EMPRESAS = @(
    @{ razon = "Supermercados del Sur SA"; rut = "76.543.210-K" }
    @{ razon = "Gastronomia Express Ltda"; rut = "77.111.222-3" }
    @{ razon = "Combustibles Copec SpA";   rut = "78.999.000-1" }
    @{ razon = "Farmacia Central SA";      rut = "79.888.777-2" }
    @{ razon = "MercadoDigital Ltda";      rut = "80.321.654-K" }
    @{ razon = "Viajes y Turismo SA";      rut = "81.456.789-3" }
)

$MESES = @(
    @{ nombre = "Enero";      num = "01" }
    @{ nombre = "Febrero";    num = "02" }
    @{ nombre = "Marzo";      num = "03" }
    @{ nombre = "Abril";      num = "04" }
    @{ nombre = "Mayo";       num = "05" }
    @{ nombre = "Junio";      num = "06" }
    @{ nombre = "Julio";      num = "07" }
    @{ nombre = "Agosto";     num = "08" }
    @{ nombre = "Septiembre"; num = "09" }
    @{ nombre = "Octubre";    num = "10" }
    @{ nombre = "Noviembre";  num = "11" }
    @{ nombre = "Diciembre";  num = "12" }
)

$NOMBRES_REFERIDOS = @(
    "Lucia Valdes","Tomas Bravo","Isidora Pino","Rodrigo Saez",
    "Fernanda Rios","Andres Vega","Daniela Mora","Felipe Nunez",
    "Constanza Ibanez","Sebastian Munoz","Paula Rojas","Javier Soto",
    "Camila Herrera","Nicolas Fuentes","Valentina Castro","Diego Morales",
    "Antonia Espinoza","Matias Gonzalez","Javiera Ortiz","Benjamin Tapia",
    "Catalina Reyes","Ignacio Flores","Renata Sanchez","Vicente Ramirez",
    "Macarena Diaz","Cristobal Perez","Amanda Silva","Emilio Torres",
    "Francisca Gutierrez","Alonso Mendez"
)

$ESTADOS_REFERIDO = @("Completado","Completado","Completado","En proceso","Pendiente")

# Todas las fechas posibles del primer semestre 2026 (dias 1-28, meses 1-5)
$FECHAS_REGISTRO = @(1..5 | ForEach-Object {
    $m = $_
    1..28 | ForEach-Object { "{0:D2}/{1:D2}/2026" -f $_, $m }
})

# ── Helpers aleatorios ────────────────────────────────────────────────────────

function Get-RandItem($arr) { $arr | Get-Random }

function Get-Periodo {
    $mes = Get-RandItem $MESES
    [PSCustomObject]@{
        nombre = $mes.nombre
        num    = $mes.num
        desde  = "01/$($mes.num)/$ANIO"
        hasta  = "30/$($mes.num)/$ANIO"
    }
}

function Format-CLP([long]$n) {
    # $1.234.567 con puntos como separador de miles
    "$" + ([string]$n -replace "(?<=\d)(?=(\d{3})+$)", ".")
}

# ── Constructores de fields por template ──────────────────────────────────────

function New-CamposPersonas($categoriasData, $condiciones = "") {
    $p   = Get-RandItem $PERSONAS
    $per = Get-Periodo
    @{
        nombre       = $p.nombre
        rut          = $p.rut
        mes          = "$($per.nombre) $ANIO"
        periodoDesde = $per.desde
        periodoHasta = $per.hasta
        categorias   = $categoriasData
        condiciones  = $condiciones
    }
}

function New-CamposEmpresas($tramosData, $condiciones = "") {
    $e   = Get-RandItem $EMPRESAS
    $per = Get-Periodo
    @{
        razonSocial       = $e.razon
        rut               = $e.rut
        mes               = "$($per.nombre) $ANIO"
        periodoDesde      = $per.desde
        periodoHasta      = $per.hasta
        tramosFacturacion = $tramosData
        condiciones       = $condiciones
    }
}

function New-CamposCashback([long]$totalCompras, [double]$porcentaje, $condiciones = "") {
    $p       = Get-RandItem $PERSONAS
    $per     = Get-Periodo
    $monto   = [long]($totalCompras * $porcentaje)
    $mesSig  = ([int]$per.num % 12 + 1).ToString("D2")
    $fechaAc = "05/$mesSig/$ANIO"
    @{
        mes               = $per.nombre
        anio              = $ANIO
        nombre            = $p.nombre
        rut               = $p.rut
        totalCompras      = Format-CLP $totalCompras
        porcentajeCashback = "{0:0}%" -f ($porcentaje * 100)
        montoCashback     = Format-CLP $monto
        fechaAcreditacion = $fechaAc
        condiciones       = $condiciones
    }
}

function New-CamposReferidos([int]$nReferidos, [long]$premioUnitario, $condiciones = "") {
    $p       = Get-RandItem $PERSONAS
    $per     = Get-Periodo
    $fechas  = ($FECHAS_REGISTRO | Get-Random -Count ([Math]::Min($nReferidos, $FECHAS_REGISTRO.Count))) | Sort-Object
    # Rellenar si se necesitan mas fechas que las disponibles
    while ($fechas.Count -lt $nReferidos) {
        $fechas = @($fechas) + @(Get-RandItem $FECHAS_REGISTRO)
        $fechas = $fechas | Sort-Object
    }
    $referidosData = @()
    $total = 0L
    for ($i = 0; $i -lt $nReferidos; $i++) {
        $estado = Get-RandItem $ESTADOS_REFERIDO
        $premio = if ($estado -eq "Completado") { $premioUnitario } else { 0 }
        $total += $premio
        $referidosData += @{
            nombreReferido = Get-RandItem $NOMBRES_REFERIDOS
            fechaRegistro  = $fechas[$i]
            estado         = $estado
            premio         = if ($premio -gt 0) { Format-CLP $premio } else { "-" }
        }
    }
    @{
        nombre      = $p.nombre
        rut         = $p.rut
        periodo     = "$($per.nombre) $ANIO"
        referidos   = $referidosData
        totalPremio = Format-CLP $total
        condiciones = $condiciones
    }
}

# ── Pools de misiones ─────────────────────────────────────────────────────────

$MISIONES_PERSONAS = @(
    @{
        nombre      = "Compras-Diarias"
        descripcion = "Categorias de gasto cotidiano con escalada por etapa."
        fields      = (New-CamposPersonas -categoriasData @(
            @{ categoria="Categoria 1";  etapa1SiGastas="$50.000";   etapa1Premio="$1.000"; etapa1Acumulado="$1.000";  etapa2SiGastas="$50.000";  etapa2Premio="$1.000"; etapa2Acumulado="$2.000"  }
            @{ categoria="Categoria 2";  etapa1SiGastas="$100.000";  etapa1Premio="$1.000"; etapa1Acumulado="$2.000";  etapa2SiGastas="$50.000";  etapa2Premio="$1.000"; etapa2Acumulado="$3.000"  }
            @{ categoria="Categoria 3";  etapa1SiGastas="$100.000";  etapa1Premio="$2.000"; etapa1Acumulado="$4.000";  etapa2SiGastas="$50.000";  etapa2Premio="$1.000"; etapa2Acumulado="$4.000"  }
            @{ categoria="Categoria 4";  etapa1SiGastas="$100.000";  etapa1Premio="$4.000"; etapa1Acumulado="$8.000";  etapa2SiGastas="$50.000";  etapa2Premio="$1.000"; etapa2Acumulado="$5.000"  }
            @{ categoria="Categoria 5";  etapa1SiGastas="$100.000";  etapa1Premio="$1.000"; etapa1Acumulado="$9.000";  etapa2SiGastas="$50.000";  etapa2Premio="$1.000"; etapa2Acumulado="$6.000"  }
            @{ categoria="Categoria 6";  etapa1SiGastas="$100.000";  etapa1Premio="$1.000"; etapa1Acumulado="$10.000"; etapa2SiGastas="$50.000";  etapa2Premio="$1.000"; etapa2Acumulado="$7.000"  }
            @{ categoria="Categoria 7";  etapa1SiGastas="$100.000";  etapa1Premio="$2.000"; etapa1Acumulado="$12.000"; etapa2SiGastas="$50.000";  etapa2Premio="$1.000"; etapa2Acumulado="$8.000"  }
            @{ categoria="Categoria 8";  etapa1SiGastas="$100.000";  etapa1Premio="$2.000"; etapa1Acumulado="$14.000"; etapa2SiGastas="$50.000";  etapa2Premio="$1.000"; etapa2Acumulado="$9.000"  }
            @{ categoria="Categoria 9";  etapa1SiGastas="$100.000";  etapa1Premio="$3.000"; etapa1Acumulado="$17.000"; etapa2SiGastas="$50.000";  etapa2Premio="$1.000"; etapa2Acumulado="$10.000" }
            @{ categoria="Categoria 10"; etapa1SiGastas="$100.000";  etapa1Premio="$4.000"; etapa1Acumulado="$21.000"; etapa2SiGastas="$50.000";  etapa2Premio="$1.000"; etapa2Acumulado="$11.000" }
            @{ categoria="Categoria 11"; etapa1SiGastas="$100.000";  etapa1Premio="$1.000"; etapa1Acumulado="$22.000"; etapa2SiGastas="$100.000"; etapa2Premio="$1.000"; etapa2Acumulado="$12.000" }
            @{ categoria="Categoria 12"; etapa1SiGastas="$100.000";  etapa1Premio="$1.000"; etapa1Acumulado="$23.000"; etapa2SiGastas="$100.000"; etapa2Premio="$1.000"; etapa2Acumulado="$13.000" }
        ) -condiciones "Aplica para personas naturales con cuenta activa. Minimo 3 transacciones en el periodo.")
    }
    @{
        nombre      = "Entretenimiento"
        descripcion = "Streaming, cine y ocio con dos etapas de recompensa."
        fields      = (New-CamposPersonas -categoriasData @(
            @{ categoria="Categoria 1"; etapa1SiGastas="$15.000";  etapa1Premio="$500";   etapa1Acumulado="$500";   etapa2SiGastas="$15.000"; etapa2Premio="$750";   etapa2Acumulado="$1.250" }
            @{ categoria="Categoria 2"; etapa1SiGastas="$30.000";  etapa1Premio="$1.000"; etapa1Acumulado="$1.500"; etapa2SiGastas="$25.000"; etapa2Premio="$1.250"; etapa2Acumulado="$2.500" }
            @{ categoria="Categoria 3"; etapa1SiGastas="$50.000";  etapa1Premio="$2.000"; etapa1Acumulado="$3.500"; etapa2SiGastas="$40.000"; etapa2Premio="$2.000"; etapa2Acumulado="$4.500" }
            @{ categoria="Categoria 4"; etapa1SiGastas="$75.000";  etapa1Premio="$3.000"; etapa1Acumulado="$6.500"; etapa2SiGastas="$60.000"; etapa2Premio="$3.000"; etapa2Acumulado="$7.500" }
            @{ categoria="Categoria 5"; etapa1SiGastas="$100.000"; etapa1Premio="$5.000"; etapa1Acumulado="$11.500";etapa2SiGastas="$80.000"; etapa2Premio="$4.000"; etapa2Acumulado="$11.500"}
        ) -condiciones "Incluye suscripciones streaming, cine y plataformas de entretenimiento adheridas.")
    }
    @{
        nombre      = "Salud-Bienestar"
        descripcion = "Salud, deporte y bienestar con dos etapas."
        fields      = (New-CamposPersonas -categoriasData @(
            @{ categoria="Categoria 1"; etapa1SiGastas="$25.000";  etapa1Premio="$1.000"; etapa1Acumulado="$1.000";  etapa2SiGastas="$20.000";  etapa2Premio="$1.000"; etapa2Acumulado="$2.000"  }
            @{ categoria="Categoria 2"; etapa1SiGastas="$50.000";  etapa1Premio="$2.000"; etapa1Acumulado="$3.000";  etapa2SiGastas="$40.000";  etapa2Premio="$2.000"; etapa2Acumulado="$4.000"  }
            @{ categoria="Categoria 3"; etapa1SiGastas="$75.000";  etapa1Premio="$3.000"; etapa1Acumulado="$6.000";  etapa2SiGastas="$60.000";  etapa2Premio="$3.000"; etapa2Acumulado="$7.000"  }
            @{ categoria="Categoria 4"; etapa1SiGastas="$100.000"; etapa1Premio="$4.000"; etapa1Acumulado="$10.000"; etapa2SiGastas="$80.000";  etapa2Premio="$4.000"; etapa2Acumulado="$11.000" }
            @{ categoria="Categoria 5"; etapa1SiGastas="$150.000"; etapa1Premio="$6.000"; etapa1Acumulado="$16.000"; etapa2SiGastas="$120.000"; etapa2Premio="$5.000"; etapa2Acumulado="$16.000" }
        ) -condiciones "Aplica en farmacias, gimnasios, clinicas y centros de bienestar adheridos.")
    }
)

$MISIONES_EMPRESAS = @(
    @{
        nombre      = "Supermercados"
        descripcion = "Escala de facturacion para comercios del rubro supermercados."
        fields      = (New-CamposEmpresas -tramosData @(
            @{ tramo="Tramo 1"; facturacionMinima="$500.000";    cashback="1,5%"; tope="$7.500"   }
            @{ tramo="Tramo 2"; facturacionMinima="$1.000.000";  cashback="2,0%"; tope="$20.000"  }
            @{ tramo="Tramo 3"; facturacionMinima="$2.000.000";  cashback="2,5%"; tope="$50.000"  }
            @{ tramo="Tramo 4"; facturacionMinima="$5.000.000";  cashback="3,0%"; tope="$150.000" }
            @{ tramo="Tramo 5"; facturacionMinima="$10.000.000"; cashback="3,5%"; tope="$350.000" }
        ) -condiciones "Aplica en comercios del rubro supermercados con facturacion electronica.")
    }
    @{
        nombre      = "Gastronomia"
        descripcion = "Restaurantes, cafeterias y delivery."
        fields      = (New-CamposEmpresas -tramosData @(
            @{ tramo="Tramo 1"; facturacionMinima="$300.000";   cashback="2,0%"; tope="$6.000"   }
            @{ tramo="Tramo 2"; facturacionMinima="$700.000";   cashback="2,5%"; tope="$17.500"  }
            @{ tramo="Tramo 3"; facturacionMinima="$1.500.000"; cashback="3,0%"; tope="$45.000"  }
            @{ tramo="Tramo 4"; facturacionMinima="$3.000.000"; cashback="3,5%"; tope="$105.000" }
        ) -condiciones "Incluye restaurantes, cafeterias, bares y apps de delivery adheridas.")
    }
    @{
        nombre      = "E-Commerce"
        descripcion = "Plataformas de comercio electronico."
        fields      = (New-CamposEmpresas -tramosData @(
            @{ tramo="Tramo 1"; facturacionMinima="$1.000.000";  cashback="2,5%"; tope="$25.000"  }
            @{ tramo="Tramo 2"; facturacionMinima="$3.000.000";  cashback="3,0%"; tope="$90.000"  }
            @{ tramo="Tramo 3"; facturacionMinima="$7.000.000";  cashback="3,5%"; tope="$245.000" }
            @{ tramo="Tramo 4"; facturacionMinima="$15.000.000"; cashback="4,0%"; tope="$600.000" }
        ) -condiciones "Valido en plataformas de e-commerce adheridas con pago via Tenpo.")
    }
)

$MISIONES_CASHBACK = @(
    @{
        nombre      = "Cashback-2pct"
        descripcion = "Liquidacion mensual con 2% de cashback sobre compras."
        fields      = (New-CamposCashback -totalCompras (Get-Random -Minimum 150000 -Maximum 800001) -porcentaje 0.02 `
                        -condiciones "El cashback se acredita automaticamente en tu cuenta Tenpo el dia 5 del mes siguiente.")
    }
    @{
        nombre      = "Cashback-5pct"
        descripcion = "Liquidacion mensual con 5% de cashback sobre compras premium."
        fields      = (New-CamposCashback -totalCompras (Get-Random -Minimum 500000 -Maximum 2000001) -porcentaje 0.05 `
                        -condiciones "Aplica exclusivamente en comercios adheridos al programa premium. Tope mensual $50.000.")
    }
    @{
        nombre      = "Cashback-3pct"
        descripcion = "Liquidacion mensual con 3% de cashback sobre compras seleccionadas."
        fields      = (New-CamposCashback -totalCompras (Get-Random -Minimum 200000 -Maximum 1000001) -porcentaje 0.03 `
                        -condiciones "Valido en rubros seleccionados: supermercados, farmacia y gastronomia.")
    }
)

function New-MisionReferidos([long]$premioUnitario, [string]$condiciones) {
    $n = Get-Random -Minimum 20 -Maximum 101
    @{
        nombre      = "Referidos-$n"
        descripcion = "Comprobante de $n referidos con premio de $(Format-CLP $premioUnitario) c/u."
        fields      = (New-CamposReferidos -nReferidos $n -premioUnitario $premioUnitario -condiciones $condiciones)
    }
}

$MISIONES_REFERIDOS = @(
    (New-MisionReferidos -premioUnitario 2000 -condiciones "Premio de `$2.000 por cada referido que active su cuenta y realice su primera compra.")
    (New-MisionReferidos -premioUnitario 3000 -condiciones "Premio de `$3.000 por referido completado.")
    (New-MisionReferidos -premioUnitario 5000 -condiciones "Premio de `$5.000 por referido. El monto se acredita en Tenpesos dentro de 48 horas.")
)

$POOLS = @{
    1 = $MISIONES_PERSONAS
    2 = $MISIONES_EMPRESAS
    3 = $MISIONES_CASHBACK
    4 = $MISIONES_REFERIDOS
}

# ── Construccion del evento Kafka ─────────────────────────────────────────────

function New-KafkaEvent($mision, [string]$templateName) {
    @{
        idempotencyId = [guid]::NewGuid().ToString()
        domain        = $DOMAIN
        templateName  = $templateName
        fields        = $mision.fields
    }
}

function ConvertTo-KafkaJson($obj) {
    # Depth 10 para serializar arrays anidados correctamente
    $obj | ConvertTo-Json -Depth 10 -Compress
}

# ── Verificar que Docker este disponible ──────────────────────────────────────

function Test-DockerContainer([string]$container) {
    $result = docker inspect --format "{{.State.Running}}" $container 2>&1
    if ($LASTEXITCODE -ne 0 -or $result -ne "true") {
        Write-Host (col $RED "`n[ERROR] El contenedor '$container' no esta corriendo.")
        Write-Host (col $GRAY "        Asegurate de ejecutar: docker-compose up -d`n")
        exit 1
    }
}

# ── Publicacion ───────────────────────────────────────────────────────────────

function Invoke-Publish($pool, [string]$templateName, [int]$count, [bool]$dryRun) {
    $poolSize = $pool.Count
    $sent     = 0
    $errors   = 0
    $tStart   = [datetime]::UtcNow

    if ($dryRun) {
        $preview = [Math]::Min($count, 3)
        Write-Host ""
        Write-Host (col $YLW "[DRY-RUN] Primeros $preview mensajes (de $count a publicar):")
        Write-Host ""
        for ($i = 0; $i -lt $preview; $i++) {
            $mision = $pool[$i % $poolSize]
            $event  = New-KafkaEvent -mision $mision -templateName $templateName
            $keys   = ($event.fields.Keys | ForEach-Object {
                $v = $event.fields[$_]
                if ($v -is [array]) { "$_[]" } else { $_ }
            }) -join ", "
            Write-Host ("  [{0,3}] mision={1,-25}  id={2}" -f ($i+1), $mision.nombre, $event.idempotencyId)
            Write-Host (col $GRAY "        fields keys: $keys")
        }
        if ($count -gt 3) {
            Write-Host (col $GRAY "  ...  ($($count - 3) mensajes mas)")
        }
        Write-Host ""
        return
    }

    Test-DockerContainer $KafkaContainer

    Write-Host ""
    Write-Host "Conectado a $Broker -- publicando $count mensajes"
    Write-Host "Template : $templateName"
    Write-Host "Topic    : $TOPIC"
    Write-Host ""

    # Construir todos los JSON de una vez y pasarlos por stdin
    # kafka-console-producer acepta un mensaje por linea
    $lines = for ($i = 0; $i -lt $count; $i++) {
        $mision = $pool[$i % $poolSize]
        $event  = New-KafkaEvent -mision $mision -templateName $templateName
        ConvertTo-KafkaJson $event
        $sent++

        if (($i + 1) % $REPORT_INTERVAL -eq 0) {
            $elapsed = ([datetime]::UtcNow - $tStart).TotalSeconds
            $rate    = if ($elapsed -gt 0) { [int](($i + 1) / $elapsed) } else { 0 }
            Write-Host ("  --> Construidos {0,6} / {1}  ({2} msg/s)" -f ($i+1), $count, $rate) -NoNewline
            Write-Host "`r" -NoNewline
        }
    }

    Write-Host ""
    Write-Host "  --> Enviando $count mensajes a Kafka via docker exec..."

    try {
        $lines | docker exec -i $KafkaContainer `
            kafka-console-producer `
            --broker-list $Broker `
            --topic $TOPIC `
            --compression-codec snappy 2>&1

        if ($LASTEXITCODE -ne 0) {
            $errors = $count
            Write-Host (col $RED "`n[ERROR] kafka-console-producer retorno exit code $LASTEXITCODE")
        }
    }
    catch {
        $errors = $count
        Write-Host (col $RED "`n[ERROR] Fallo al ejecutar docker exec: $_")
    }

    $elapsed = ([datetime]::UtcNow - $tStart).TotalSeconds
    $ok      = $sent - $errors
    $rate    = if ($elapsed -gt 0) { [int]($ok / $elapsed) } else { 0 }

    Write-Host ""
    Write-Host ("-" * 55)
    Write-Host ("  Publicados exitosamente : {0,6}" -f $ok)
    Write-Host ("  Errores                 : {0,6}" -f $errors)
    Write-Host ("  Tiempo total            : {0,6:F1}s" -f $elapsed)
    Write-Host ("  Throughput              : {0,6} msg/s" -f $rate)
    Write-Host ("-" * 55)
    Write-Host ""
}

# ── Listar templates ──────────────────────────────────────────────────────────

function Show-TemplateList {
    Write-Host ""
    foreach ($tid in 1..4) {
        $pool = $POOLS[$tid]
        Write-Host ("  Template $tid -- " + (col $GRN $TEMPLATES[$tid]))
        foreach ($i in 0..($pool.Count - 1)) {
            $m = $pool[$i]
            Write-Host ("    {0}.  {1,-30} {2}" -f ($i+1), $m.nombre, (col $GRAY $m.descripcion))
        }
        Write-Host ""
    }
}

# ── Menu interactivo ──────────────────────────────────────────────────────────

function Read-Int([string]$prompt, [int]$lo, [int]$hi) {
    while ($true) {
        Write-Host $prompt -NoNewline
        $raw = Read-Host
        if ($raw -match "^\d+$") {
            $val = [int]$raw
            if ($val -ge $lo -and $val -le $hi) { return $val }
        }
        Write-Host (col $RED "  Ingresa un numero entre $lo y $hi.")
    }
}

function Invoke-InteractiveMenu {
    Clear-Host

    Write-Host ""
    Write-Host (col $CYAN "  +===========================================================+")
    Write-Host (col $CYAN "  |   document-generator-api  *  Generador de eventos Kafka   |")
    Write-Host (col $CYAN "  +===========================================================+")
    Write-Host ""

    Write-Host (col $YLW "  Templates disponibles:")
    Write-Host ""
    foreach ($tid in 1..4) {
        $pool = $POOLS[$tid]
        Write-Host ("    [$tid]  " + (col $GRN $TEMPLATES[$tid]))
        Write-Host (col $GRAY ("         $($pool.Count) misiones disponibles"))
        Write-Host ""
    }

    $tChoice      = Read-Int -prompt "  Seleccionar template [1-4]: " -lo 1 -hi 4
    $pool         = $POOLS[$tChoice]
    $templateName = $TEMPLATES[$tChoice]

    Write-Host ""
    Write-Host (col $YLW "  Misiones para template $tChoice`:")
    Write-Host ""
    foreach ($i in 0..($pool.Count - 1)) {
        $m = $pool[$i]
        Write-Host ("    {0}  {1,-30}  {2}" -f (col $GRAY "$($i+1)."), $m.nombre, (col $GRAY $m.descripcion))
    }
    Write-Host ""
    Write-Host (col $GRAY "  (Se rotaran en ciclo si el total supera la cantidad de misiones)")
    Write-Host ""

    $count = Read-Int -prompt "  Cantidad de mensajes a enviar [1-50000]: " -lo 1 -hi 50000

    Write-Host ""
    Write-Host ("  Template  : " + (col $GRN $templateName))
    Write-Host ("  Mensajes  : " + (col $GRN $count))
    Write-Host ("  Topic     : " + (col $GRN $TOPIC))
    Write-Host ("  Broker    : " + (col $GRN $Broker))
    Write-Host ""

    Write-Host "  Confirmar envio? [s/n]: " -NoNewline
    $confirm = Read-Host
    if ($confirm.ToLower() -ne "s") {
        Write-Host (col $RED "`n  Cancelado.`n")
        return
    }

    Write-Host ""
    Invoke-Publish -pool $pool -templateName $templateName -count $count -dryRun $false
}

# ── Entry point ───────────────────────────────────────────────────────────────

if ($List) {
    Show-TemplateList
    exit 0
}

if ($Template -gt 0 -and $Count -gt 0) {
    if ($Template -lt 1 -or $Template -gt 4) {
        Write-Host (col $RED "`n[ERROR] --Template debe ser 1, 2, 3 o 4.`n")
        exit 1
    }
    if ($Count -lt 1) {
        Write-Host (col $RED "`n[ERROR] --Count debe ser >= 1.`n")
        exit 1
    }

    $pool         = $POOLS[$Template]
    $templateName = $TEMPLATES[$Template]
    $misionesBase = $pool

    if ($Mision -ne "") {
        $misionesBase = @($pool | Where-Object { $_.nombre -ieq $Mision })
        if ($misionesBase.Count -eq 0) {
            $nombres = ($pool | ForEach-Object { $_.nombre }) -join ", "
            Write-Host (col $RED "`n[ERROR] Mision '$Mision' no encontrada en template $Template.")
            Write-Host (col $GRAY "        Disponibles: $nombres`n")
            exit 1
        }
    }

    Invoke-Publish -pool $misionesBase -templateName $templateName -count $Count -dryRun $DryRun.IsPresent
    exit 0
}

# Sin argumentos suficientes → menu interactivo
Invoke-InteractiveMenu
