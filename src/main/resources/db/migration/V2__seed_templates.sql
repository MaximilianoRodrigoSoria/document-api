-- ─────────────────────────────────────────────────────────────────────────────
-- V2__seed_templates.sql
-- Templates HTML Mustache de ejemplo para el catálogo inicial.
--
-- Templates incluidos:
--   1. compra-y-gana-personas-v1   — Misión con escala de tramos para personas
--   2. compra-y-gana-empresas-v1   — Misión con escala de facturación para empresas
--   3. cashback-mensual-v1         — Liquidación mensual de cashback
--   4. programa-referidos-v1       — Comprobante de recompensas por referidos
--
-- Nota: el contenido HTML usa dollar-quoting ($Tn$) para evitar conflictos
--       con comillas simples dentro del HTML/CSS.
-- ─────────────────────────────────────────────────────────────────────────────


-- ─── 1. Compra y Gana — Personas (dos etapas) ────────────────────────────────
-- Variables: {{periodoDesde}}, {{periodoHasta}}, {{nombre}}, {{rut}}, {{mes}},
--            {{#categorias}}: {{categoria}},
--              {{etapa1SiGastas}}, {{etapa1Premio}}, {{etapa1Acumulado}},
--              {{etapa2SiGastas}}, {{etapa2Premio}}, {{etapa2Acumulado}}
--            {{condiciones}}
INSERT INTO data.document_templates (id, name, domain, version, status, description, content)
VALUES (
    'a1b2c3d4-e5f6-7890-abcd-ef1234567890',
    'compra-y-gana-personas-v1',
    'MISIONES',
    '1.0.0',
    'ACTIVE',
    'Misión Compra y Gana para personas naturales. Tabla de dos etapas por categoría: monto mínimo, premio y acumulado por etapa.',
    $T1$<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN"
    "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="es">
<head>
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/>
    <title>Compra y Gana - Personas</title>
    <style type="text/css">
        body    { font-family: Arial, Helvetica, sans-serif; font-size: 9pt; color: #1a1a2e; margin: 28pt; }
        .header { background-color: #1B3A5C; color: #fff; padding: 14pt 20pt 10pt 20pt; text-align: center; }
        .header h1 { font-size: 16pt; margin: 0; color: #fff; }
        .header p  { font-size: 8pt; color: #b0c4d8; margin: 3pt 0 0 0; }
        .accent    { background-color: #1A7A4A; height: 4pt; font-size: 1pt; }
        .period    { width: 100%; border: 1pt solid #D0D8E4; background-color: #E8F5EE; }
        .period td { padding: 6pt 10pt; }
        .lbl       { font-size: 7pt; font-weight: bold; color: #5a6a7a; display: block; }
        .val       { font-size: 9pt; color: #1B3A5C; display: block; }
        .section   { margin-top: 12pt; }
        .sec-title { font-size: 8pt; font-weight: bold; color: #1B3A5C; border-left: 4pt solid #1B3A5C; padding-left: 7pt; margin-bottom: 5pt; }
        .info      { width: 100%; border: 1pt solid #D0D8E4; }
        .info td   { padding: 6pt 10pt; }

        /* ── Tabla dos etapas ── */
        .etapas       { width: 100%; border-collapse: collapse; border: 1pt solid #C0CAD4; font-size: 8pt; }
        /* cabecera de etapa (ETAPA 1 / ETAPA 2) */
        .th-etapa     { background-color: #1B3A5C; color: #fff; text-align: center; font-size: 8pt;
                        font-weight: bold; padding: 5pt 4pt; border: 1pt solid #2A4A6C; }
        /* cabecera vacía de la columna Categoría */
        .th-cat-blank { background-color: #1B3A5C; border: 1pt solid #2A4A6C; padding: 5pt; }
        /* sub-cabeceras (Si gastas / Premio / Acumulados) */
        .th-sub       { background-color: #2A4A6C; color: #fff; text-align: center; font-size: 7pt;
                        padding: 4pt 4pt; border: 1pt solid #3A5A7C; white-space: nowrap; }
        .th-cat-sub   { background-color: #2A4A6C; color: #fff; font-size: 7pt; font-weight: bold;
                        padding: 4pt 6pt; border: 1pt solid #3A5A7C; }
        /* separador visual entre etapas */
        .sep          { border-left: 2pt solid #1B3A5C; }
        /* filas de datos */
        .td-cat       { font-weight: bold; padding: 5pt 6pt; border: 1pt solid #D8E2EA; background-color: #F2F6FA; }
        .td-val       { text-align: center; padding: 5pt 4pt; border: 1pt solid #D8E2EA; color: #1a1a2e; }
        .td-acc       { text-align: center; padding: 5pt 4pt; border: 1pt solid #D8E2EA; color: #1A7A4A; font-weight: bold; }
        .row-even     { background-color: #FAFCFE; }
        .row-odd      { background-color: #FFFFFF; }

        .cond   { border: 1pt solid #D0D8E4; padding: 7pt 10pt; font-size: 8pt; min-height: 36pt; }
        .footer { margin-top: 20pt; border-top: 1pt solid #D0D8E4; padding-top: 6pt;
                  text-align: center; font-size: 7pt; color: #9aaabb; }
    </style>
</head>
<body>
    <div class="header">
        <h1>COMPRA Y GANA &#8212; PERSONAS</h1>
        <p>Misi&#243;n de Recompensas &#183; Clientes Naturales</p>
    </div>
    <div class="accent">&#160;</div>

    <!-- Período -->
    <table class="period" cellpadding="0" cellspacing="0">
        <tr>
            <td width="34%"><span class="lbl">PER&#205;ODO DE VIGENCIA</span><span class="val">&#160;</span></td>
            <td width="33%"><span class="lbl">DESDE</span><span class="val">{{periodoDesde}}</span></td>
            <td width="33%"><span class="lbl">HASTA</span><span class="val">{{periodoHasta}}</span></td>
        </tr>
    </table>

    <!-- Datos del cliente -->
    <div class="section">
        <div class="sec-title">DATOS DEL CLIENTE</div>
        <table class="info" cellpadding="0" cellspacing="0">
            <tr>
                <td width="40%"><span class="lbl">NOMBRE</span><span class="val">{{nombre}}</span></td>
                <td width="30%"><span class="lbl">RUT</span><span class="val">{{rut}}</span></td>
                <td width="30%"><span class="lbl">MES</span><span class="val">{{mes}}</span></td>
            </tr>
        </table>
    </div>

    <!-- Tabla de dos etapas -->
    <div class="section">
        <div class="sec-title">ESCALA DE RECOMPENSAS POR ETAPA</div>
        <table class="etapas" cellpadding="0" cellspacing="0">
            <thead>
                <!-- Fila 1: encabezados de etapa con colspan -->
                <tr>
                    <th class="th-cat-blank" rowspan="2" width="16%">&#160;</th>
                    <th class="th-etapa" colspan="3">ETAPA 1</th>
                    <th class="th-etapa sep" colspan="3">ETAPA 2</th>
                </tr>
                <!-- Fila 2: sub-encabezados -->
                <tr>
                    <th class="th-sub" width="14%">Si gastas</th>
                    <th class="th-sub" width="14%">Premio en<br/>Tenpesos</th>
                    <th class="th-sub" width="14%">Tenpesos<br/>Acumulados</th>
                    <th class="th-sub sep" width="14%">Si gastas</th>
                    <th class="th-sub" width="14%">Premio en<br/>Tenpesos</th>
                    <th class="th-sub" width="14%">Tenpesos<br/>Acumulados</th>
                </tr>
            </thead>
            <tbody>
                {{#categorias}}
                <tr>
                    <td class="td-cat">{{categoria}}</td>
                    <td class="td-val">{{etapa1SiGastas}}</td>
                    <td class="td-val">{{etapa1Premio}}</td>
                    <td class="td-acc">{{etapa1Acumulado}}</td>
                    <td class="td-val sep">{{etapa2SiGastas}}</td>
                    <td class="td-val">{{etapa2Premio}}</td>
                    <td class="td-acc">{{etapa2Acumulado}}</td>
                </tr>
                {{/categorias}}
            </tbody>
        </table>
    </div>

    <!-- Condiciones -->
    <div class="section">
        <div class="sec-title">CONDICIONES Y OBSERVACIONES</div>
        <div class="cond">{{condiciones}}</div>
    </div>

    <div class="footer">Documento generado autom&#225;ticamente &#183; V&#225;lido con firma digital</div>
</body>
</html>$T1$
)
ON CONFLICT (name) DO NOTHING;


-- ─── 2. Compra y Gana — Empresas ─────────────────────────────────────────────
-- Variables: {{periodoDesde}}, {{periodoHasta}}, {{razonSocial}}, {{rut}}, {{mes}},
--            {{#tramosFacturacion}}: {{tramo}}, {{facturacionMinima}}, {{cashback}}, {{tope}}
--            {{condiciones}}
INSERT INTO data.document_templates (id, name, domain, version, status, description, content)
VALUES (
    'b2c3d4e5-f6a7-8901-bcde-f12345678901',
    'compra-y-gana-empresas-v1',
    'MISIONES',
    '1.0.0',
    'ACTIVE',
    'Misión Compra y Gana para empresas. Escala de facturación con porcentaje de cashback y tope máximo.',
    $T2$<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN"
    "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="es">
<head>
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/>
    <title>Compra y Gana - Empresas</title>
    <style type="text/css">
        body { font-family: Arial, Helvetica, sans-serif; font-size: 11pt; color: #1a1a2e; margin: 36pt; }
        .header { background-color: #0D2B45; color: #fff; padding: 16pt 20pt 12pt 20pt; text-align: center; }
        .header h1 { font-size: 18pt; margin: 0; color: #fff; }
        .header p  { font-size: 9pt; color: #90aac5; margin: 4pt 0 0 0; }
        .accent    { background-color: #C67D1A; height: 4pt; font-size: 1pt; }
        .period    { width: 100%; border: 1pt solid #D0D8E4; background-color: #FFF8EE; margin-top: 0; }
        .period td { padding: 8pt 12pt; }
        .lbl       { font-size: 8pt; font-weight: bold; color: #5a6a7a; display: block; }
        .val       { font-size: 10pt; color: #0D2B45; display: block; }
        .section   { margin-top: 14pt; }
        .sec-title { font-size: 9pt; font-weight: bold; color: #0D2B45; border-left: 4pt solid #C67D1A; padding-left: 8pt; margin-bottom: 5pt; }
        .info      { width: 100%; border: 1pt solid #D0D8E4; }
        .info td   { padding: 8pt 12pt; }
        .tramos    { width: 100%; border-collapse: collapse; border: 1pt solid #D0D8E4; }
        .tramos th { background-color: #0D2B45; color: #fff; font-size: 9pt; text-align: left; padding: 7pt 10pt; }
        .tramos td { font-size: 10pt; padding: 8pt 10pt; border-bottom: 1pt solid #D0D8E4; }
        .td-acc    { border-left: 4pt solid #C67D1A; font-weight: bold; }
        .cond      { border: 1pt solid #D0D8E4; padding: 8pt 12pt; font-size: 10pt; min-height: 40pt; }
        .footer    { margin-top: 24pt; border-top: 1pt solid #D0D8E4; padding-top: 8pt; text-align: center; font-size: 8pt; color: #9aaabb; }
    </style>
</head>
<body>
    <div class="header">
        <h1>COMPRA Y GANA &#8212; EMPRESAS</h1>
        <p>Misi&#243;n de Recompensas &#183; Clientes Empresariales</p>
    </div>
    <div class="accent">&#160;</div>

    <table class="period" cellpadding="0" cellspacing="0">
        <tr>
            <td width="34%"><span class="lbl">PER&#205;ODO DE VIGENCIA</span><span class="val">&#160;</span></td>
            <td width="33%"><span class="lbl">DESDE</span><span class="val">{{periodoDesde}}</span></td>
            <td width="33%"><span class="lbl">HASTA</span><span class="val">{{periodoHasta}}</span></td>
        </tr>
    </table>

    <div class="section">
        <div class="sec-title">DATOS DE LA EMPRESA</div>
        <table class="info" cellpadding="0" cellspacing="0">
            <tr>
                <td width="50%"><span class="lbl">RAZ&#211;N SOCIAL</span><span class="val">{{razonSocial}}</span></td>
                <td width="25%"><span class="lbl">RUT</span><span class="val">{{rut}}</span></td>
                <td width="25%"><span class="lbl">MES</span><span class="val">{{mes}}</span></td>
            </tr>
        </table>
    </div>

    <div class="section">
        <div class="sec-title">ESCALA DE FACTURACI&#211;N</div>
        <table class="tramos" cellpadding="0" cellspacing="0">
            <thead>
                <tr>
                    <th width="22%">Tramo</th>
                    <th width="28%">Facturaci&#243;n m&#237;nima</th>
                    <th width="25%">Cashback %</th>
                    <th width="25%">Tope m&#225;x.</th>
                </tr>
            </thead>
            <tbody>
                {{#tramosFacturacion}}
                <tr>
                    <td class="td-acc">{{tramo}}</td>
                    <td>{{facturacionMinima}}</td>
                    <td>{{cashback}}</td>
                    <td>{{tope}}</td>
                </tr>
                {{/tramosFacturacion}}
            </tbody>
        </table>
    </div>

    <div class="section">
        <div class="sec-title">CONDICIONES Y OBSERVACIONES</div>
        <div class="cond">{{condiciones}}</div>
    </div>

    <div class="footer">Documento generado autom&#225;ticamente &#183; V&#225;lido con firma digital</div>
</body>
</html>$T2$
)
ON CONFLICT (name) DO NOTHING;


-- ─── 3. Cashback Mensual ──────────────────────────────────────────────────────
-- Variables: {{mes}}, {{anio}}, {{nombre}}, {{rut}},
--            {{totalCompras}}, {{porcentajeCashback}}, {{montoCashback}},
--            {{fechaAcreditacion}}, {{condiciones}}
INSERT INTO data.document_templates (id, name, domain, version, status, description, content)
VALUES (
    'c3d4e5f6-a7b8-9012-cdef-123456789012',
    'cashback-mensual-v1',
    'MISIONES',
    '1.0.0',
    'ACTIVE',
    'Liquidación mensual de cashback. Muestra total de compras, porcentaje aplicado, monto a acreditar y fecha de acreditación.',
    $T3$<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN"
    "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="es">
<head>
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/>
    <title>Cashback Mensual</title>
    <style type="text/css">
        body { font-family: Arial, Helvetica, sans-serif; font-size: 11pt; color: #1a1a2e; margin: 36pt; }
        .header { background-color: #1A5C3A; color: #fff; padding: 16pt 20pt 12pt 20pt; text-align: center; }
        .header h1 { font-size: 18pt; margin: 0; color: #fff; }
        .header p  { font-size: 9pt; color: #90c5a8; margin: 4pt 0 0 0; }
        .accent    { background-color: #F5A623; height: 4pt; font-size: 1pt; }
        .section   { margin-top: 14pt; }
        .sec-title { font-size: 9pt; font-weight: bold; color: #1A5C3A; border-left: 4pt solid #1A5C3A; padding-left: 8pt; margin-bottom: 5pt; }
        .info      { width: 100%; border: 1pt solid #D0D8E4; }
        .info td   { padding: 8pt 12pt; }
        .lbl       { font-size: 8pt; font-weight: bold; color: #5a6a7a; display: block; }
        .val       { font-size: 10pt; color: #1a1a2e; display: block; }
        .summary   { width: 100%; border-collapse: collapse; margin-top: 0; }
        .summary td { padding: 12pt 16pt; border: 1pt solid #D0D8E4; font-size: 11pt; }
        .summary .amount { font-size: 22pt; font-weight: bold; color: #1A5C3A; text-align: center; }
        .summary .amount-lbl { font-size: 8pt; color: #5a6a7a; text-align: center; display: block; }
        .detail    { width: 100%; border: 1pt solid #D0D8E4; background-color: #F7FBF8; }
        .detail td { padding: 10pt 14pt; border-bottom: 1pt solid #E0EAE4; }
        .d-lbl     { font-size: 9pt; color: #5a6a7a; }
        .d-val     { font-size: 11pt; font-weight: bold; color: #1a1a2e; text-align: right; }
        .highlight { background-color: #E8F5EE; }
        .cond      { border: 1pt solid #D0D8E4; padding: 8pt 12pt; font-size: 10pt; min-height: 40pt; }
        .footer    { margin-top: 24pt; border-top: 1pt solid #D0D8E4; padding-top: 8pt; text-align: center; font-size: 8pt; color: #9aaabb; }
    </style>
</head>
<body>
    <div class="header">
        <h1>CASHBACK MENSUAL</h1>
        <p>Liquidaci&#243;n de recompensas &#183; {{mes}} {{anio}}</p>
    </div>
    <div class="accent">&#160;</div>

    <div class="section">
        <div class="sec-title">DATOS DEL CLIENTE</div>
        <table class="info" cellpadding="0" cellspacing="0">
            <tr>
                <td width="60%"><span class="lbl">NOMBRE</span><span class="val">{{nombre}}</span></td>
                <td width="40%"><span class="lbl">RUT</span><span class="val">{{rut}}</span></td>
            </tr>
        </table>
    </div>

    <div class="section">
        <div class="sec-title">RESUMEN DE CASHBACK</div>
        <table class="summary" cellpadding="0" cellspacing="0">
            <tr>
                <td width="50%">
                    <span class="lbl">TOTAL DE COMPRAS EN EL PERIODO</span>
                    <span class="val">{{totalCompras}}</span>
                </td>
                <td width="25%">
                    <span class="lbl">CASHBACK APLICADO</span>
                    <span class="val">{{porcentajeCashback}}</span>
                </td>
                <td width="25%" class="amount">
                    {{montoCashback}}
                    <span class="amount-lbl">MONTO A ACREDITAR</span>
                </td>
            </tr>
        </table>
    </div>

    <div class="section">
        <div class="sec-title">DETALLE DE ACREDITACI&#211;N</div>
        <table class="detail" cellpadding="0" cellspacing="0">
            <tr class="highlight">
                <td class="d-lbl">Fecha de acreditaci&#243;n estimada</td>
                <td class="d-val">{{fechaAcreditacion}}</td>
            </tr>
            <tr>
                <td class="d-lbl">Monto acreditado en cuenta Tenpo</td>
                <td class="d-val">{{montoCashback}}</td>
            </tr>
            <tr>
                <td class="d-lbl">Concepto</td>
                <td class="d-val">Cashback {{mes}} {{anio}}</td>
            </tr>
        </table>
    </div>

    <div class="section">
        <div class="sec-title">CONDICIONES Y OBSERVACIONES</div>
        <div class="cond">{{condiciones}}</div>
    </div>

    <div class="footer">Documento generado autom&#225;ticamente &#183; V&#225;lido con firma digital</div>
</body>
</html>$T3$
)
ON CONFLICT (name) DO NOTHING;


-- ─── 4. Programa de Referidos ─────────────────────────────────────────────────
-- Variables: {{nombre}}, {{rut}}, {{periodo}},
--            {{#referidos}}: {{nombreReferido}}, {{fechaRegistro}}, {{estado}}, {{premio}}
--            {{totalPremio}}, {{condiciones}}
INSERT INTO data.document_templates (id, name, domain, version, status, description, content)
VALUES (
    'd4e5f6a7-b8c9-0123-defa-234567890123',
    'programa-referidos-v1',
    'MISIONES',
    '1.0.0',
    'ACTIVE',
    'Comprobante de recompensas por programa de referidos. Lista dinámica de referidos con estado y premio por cada uno.',
    $T4$<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.0 Strict//EN"
    "http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd">
<html xmlns="http://www.w3.org/1999/xhtml" xml:lang="es">
<head>
    <meta http-equiv="Content-Type" content="text/html; charset=UTF-8"/>
    <title>Programa de Referidos</title>
    <style type="text/css">
        body { font-family: Arial, Helvetica, sans-serif; font-size: 11pt; color: #1a1a2e; margin: 36pt; }
        .header { background-color: #4A1A7A; color: #fff; padding: 16pt 20pt 12pt 20pt; text-align: center; }
        .header h1 { font-size: 18pt; margin: 0; color: #fff; }
        .header p  { font-size: 9pt; color: #c0a8e0; margin: 4pt 0 0 0; }
        .accent    { background-color: #E8A020; height: 4pt; font-size: 1pt; }
        .section   { margin-top: 14pt; }
        .sec-title { font-size: 9pt; font-weight: bold; color: #4A1A7A; border-left: 4pt solid #4A1A7A; padding-left: 8pt; margin-bottom: 5pt; }
        .info      { width: 100%; border: 1pt solid #D0D8E4; }
        .info td   { padding: 8pt 12pt; }
        .lbl       { font-size: 8pt; font-weight: bold; color: #5a6a7a; display: block; }
        .val       { font-size: 10pt; color: #1a1a2e; display: block; }
        .refs      { width: 100%; border-collapse: collapse; border: 1pt solid #D0D8E4; }
        .refs th   { background-color: #4A1A7A; color: #fff; font-size: 9pt; text-align: left; padding: 7pt 10pt; }
        .refs td   { font-size: 10pt; padding: 8pt 10pt; border-bottom: 1pt solid #D0D8E4; }
        .td-acc    { border-left: 4pt solid #E8A020; }
        .total-row { background-color: #F3EEF9; }
        .total-row td { font-weight: bold; font-size: 11pt; padding: 10pt; border-top: 2pt solid #4A1A7A; }
        .cond      { border: 1pt solid #D0D8E4; padding: 8pt 12pt; font-size: 10pt; min-height: 40pt; }
        .footer    { margin-top: 24pt; border-top: 1pt solid #D0D8E4; padding-top: 8pt; text-align: center; font-size: 8pt; color: #9aaabb; }
    </style>
</head>
<body>
    <div class="header">
        <h1>PROGRAMA DE REFERIDOS</h1>
        <p>Comprobante de Recompensas &#183; {{periodo}}</p>
    </div>
    <div class="accent">&#160;</div>

    <div class="section">
        <div class="sec-title">DATOS DEL TITULAR</div>
        <table class="info" cellpadding="0" cellspacing="0">
            <tr>
                <td width="60%"><span class="lbl">NOMBRE</span><span class="val">{{nombre}}</span></td>
                <td width="40%"><span class="lbl">RUT</span><span class="val">{{rut}}</span></td>
            </tr>
        </table>
    </div>

    <div class="section">
        <div class="sec-title">DETALLE DE REFERIDOS</div>
        <table class="refs" cellpadding="0" cellspacing="0">
            <thead>
                <tr>
                    <th width="35%">Referido</th>
                    <th width="22%">Fecha de registro</th>
                    <th width="20%">Estado</th>
                    <th width="23%">Premio</th>
                </tr>
            </thead>
            <tbody>
                {{#referidos}}
                <tr>
                    <td class="td-acc">{{nombreReferido}}</td>
                    <td>{{fechaRegistro}}</td>
                    <td>{{estado}}</td>
                    <td>{{premio}}</td>
                </tr>
                {{/referidos}}
                <tr class="total-row">
                    <td colspan="3">TOTAL ACUMULADO</td>
                    <td>{{totalPremio}}</td>
                </tr>
            </tbody>
        </table>
    </div>

    <div class="section">
        <div class="sec-title">CONDICIONES Y OBSERVACIONES</div>
        <div class="cond">{{condiciones}}</div>
    </div>

    <div class="footer">Documento generado autom&#225;ticamente &#183; V&#225;lido con firma digital</div>
</body>
</html>$T4$
)
ON CONFLICT (name) DO NOTHING;
