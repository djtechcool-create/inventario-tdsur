import os
import json
import uuid
import base64
import requests
from datetime import datetime
from flask import Flask, render_template, request, redirect, url_for, jsonify, send_file, session
from openpyxl import load_workbook
from openpyxl.styles import Font, PatternFill, Alignment, Border, Side
from openpyxl.utils import get_column_letter
import openpyxl

app = Flask(__name__)
app.secret_key = 'cuadrar-inventario-tdsur-2026'
app.config['UPLOAD_FOLDER'] = os.path.join(os.path.dirname(__file__), 'uploads')
app.config['REPORTES_FOLDER'] = os.path.join(os.path.dirname(__file__), 'reportes')
app.config['DATA_FOLDER'] = os.path.join(os.path.dirname(__file__), 'data')
app.config['MAX_CONTENT_LENGTH'] = 16 * 1024 * 1024

os.makedirs(app.config['UPLOAD_FOLDER'], exist_ok=True)
os.makedirs(app.config['REPORTES_FOLDER'], exist_ok=True)
os.makedirs(app.config['DATA_FOLDER'], exist_ok=True)

USERS_FILE = os.path.join(app.config['DATA_FOLDER'], 'usuarios.json')
PRODUCTS_FILE = os.path.join(app.config['DATA_FOLDER'], 'productos.json')
HISTORIAL_FILE = os.path.join(app.config['DATA_FOLDER'], 'historial.json')
CONFIG_FILE = os.path.join(os.path.dirname(__file__), 'config.json')

GITHUB_REPO = 'djtechcool-create/inventario-tdsur'


def load_json(filepath, default=None):
    if default is None:
        default = {}
    if os.path.exists(filepath):
        with open(filepath, 'r', encoding='utf-8') as f:
            return json.load(f)
    return default


def save_json(filepath, data):
    with open(filepath, 'w', encoding='utf-8') as f:
        json.dump(data, f, ensure_ascii=False, indent=2)


def get_github_token():
    config = load_json(CONFIG_FILE, {})
    return config.get('github_token', '')


def github_api(endpoint, method='GET', data=None):
    token = get_github_token()
    if not token:
        return None

    headers = {
        'Authorization': f'token {token}',
        'Accept': 'application/vnd.github.v3+json'
    }

    url = f'https://api.github.com/repos/{GITHUB_REPO}{endpoint}'

    if method == 'GET':
        resp = requests.get(url, headers=headers, timeout=30)
    elif method == 'PUT':
        resp = requests.put(url, headers=headers, json=data, timeout=30)
    elif method == 'DELETE':
        resp = requests.delete(url, headers=headers, timeout=30)
    else:
        return None

    if resp.status_code in (200, 201):
        return resp.json()
    return None


def sync_to_github(filepath, repo_path, message):
    token = get_github_token()
    if not token:
        return False

    try:
        with open(filepath, 'r', encoding='utf-8') as f:
            content = f.read()

        existing = github_api(f'/contents/{repo_path}')
        sha = existing.get('sha') if existing else None

        encoded = base64.b64encode(content.encode('utf-8')).decode('utf-8')

        payload = {
            'message': message,
            'content': encoded
        }
        if sha:
            payload['sha'] = sha

        result = github_api(f'/contents/{repo_path}', method='PUT', data=payload)
        return result is not None
    except Exception as e:
        print(f"Error syncing to GitHub: {e}")
        return False


def sync_users_to_github():
    return sync_to_github(USERS_FILE, 'data/usuarios.json', 'Actualizar usuarios')


def sync_products_to_github():
    return sync_to_github(PRODUCTS_FILE, 'data/productos.json', 'Actualizar productos asignados')


@app.route('/')
def index():
    if 'usuario' not in session:
        return redirect(url_for('login'))
    return render_template('index.html')


@app.route('/login', methods=['GET', 'POST'])
def login():
    if request.method == 'POST':
        usuario = request.form.get('usuario', '').strip()
        clave = request.form.get('clave', '')

        users = load_json(USERS_FILE, {})
        user = users.get(usuario)

        if user and user.get('clave') == clave and user.get('activo'):
            session['usuario'] = user
            return redirect(url_for('index'))
        else:
            return render_template('login.html', error='Usuario o clave incorrectos')

    return render_template('login.html')


@app.route('/logout')
def logout():
    session.clear()
    return redirect(url_for('login'))


@app.route('/usuarios')
def usuarios():
    if 'usuario' not in session or session['usuario'].get('rol') != 'admin':
        return redirect(url_for('index'))
    users = load_json(USERS_FILE, {})
    return render_template('usuarios.html', usuarios=users)


@app.route('/usuarios/crear', methods=['POST'])
def crear_usuario():
    if 'usuario' not in session or session['usuario'].get('rol') != 'admin':
        return redirect(url_for('index'))

    usuario = request.form.get('usuario', '').strip()
    clave = request.form.get('clave', '').strip()
    nombre = request.form.get('nombre', '').strip()
    rol = request.form.get('rol', 'trabajador')

    if not usuario or not clave or not nombre:
        return redirect(url_for('usuarios'))

    users = load_json(USERS_FILE, {})
    users[usuario] = {
        'usuario': usuario,
        'clave': clave,
        'nombre': nombre,
        'rol': rol,
        'activo': True
    }
    save_json(USERS_FILE, users)
    sync_users_to_github()

    return redirect(url_for('usuarios'))


@app.route('/usuarios/toggle/<usuario>', methods=['POST'])
def toggle_usuario(usuario):
    if 'usuario' not in session or session['usuario'].get('rol') != 'admin':
        return redirect(url_for('index'))

    users = load_json(USERS_FILE, {})
    if usuario in users and usuario != 'admin':
        users[usuario]['activo'] = not users[usuario].get('activo', True)
        save_json(USERS_FILE, users)
        sync_users_to_github()

    return redirect(url_for('usuarios'))


@app.route('/usuarios/eliminar/<usuario>', methods=['POST'])
def eliminar_usuario(usuario):
    if 'usuario' not in session or session['usuario'].get('rol') != 'admin':
        return redirect(url_for('index'))

    if usuario == 'admin':
        return redirect(url_for('usuarios'))

    users = load_json(USERS_FILE, {})
    if usuario in users:
        del users[usuario]
        save_json(USERS_FILE, users)
        sync_users_to_github()

    return redirect(url_for('usuarios'))


@app.route('/asignar')
def asignar():
    if 'usuario' not in session or session['usuario'].get('rol') != 'admin':
        return redirect(url_for('index'))

    users = load_json(USERS_FILE, {})
    trabajadores = {k: v for k, v in users.items() if v.get('rol') == 'trabajador'}

    products = load_json(PRODUCTS_FILE, {})
    productos = products.get('productos', [])

    return render_template('asignar.html', trabajadores=trabajadores, productos=productos)


@app.route('/asignar/guardar', methods=['POST'])
def guardar_asignacion():
    if 'usuario' not in session or session['usuario'].get('rol') != 'admin':
        return redirect(url_for('index'))

    products_data = load_json(PRODUCTS_FILE, {'productos': []})
    products_data['productos'] = []

    usuarios_seleccionados = request.form.getlist('usuarios[]')

    for codigo_lote in usuarios_seleccionados:
        parts = codigo_lote.split('|')
        if len(parts) == 3:
            codigo, lote, usuario = parts
            saldo = request.form.get(f'saldo_{codigo_lote}', 0)

            products_data['productos'].append({
                'codigo': codigo,
                'producto': request.form.get(f'producto_{codigo_lote}', ''),
                'lote': lote,
                'saldo_sistema': float(saldo),
                'usuario': usuario,
                'activo': True,
                'buenos': 0,
                'danados': 0,
                'arreglados': 0,
                'reempacados': 0,
                'observaciones': ''
            })

    save_json(PRODUCTS_FILE, products_data)
    sync_products_to_github()

    return redirect(url_for('asignar'))


@app.route('/subir-inventario', methods=['POST'])
def subir_inventario():
    if 'usuario' not in session or session['usuario'].get('rol') != 'admin':
        return redirect(url_for('index'))

    if 'archivo_virtual' not in request.files:
        return redirect(url_for('index'))

    archivo = request.files['archivo_virtual']
    if archivo.filename == '' or not archivo.filename.endswith(('.xlsx', '.xls')):
        return redirect(url_for('index'))

    filename = f"virtual_{uuid.uuid4().hex[:8]}.xlsx"
    filepath = os.path.join(app.config['UPLOAD_FOLDER'], filename)
    archivo.save(filepath)

    header_row = 1
    wb = load_workbook(filepath, data_only=True)
    ws = wb[wb.sheetnames[0]]

    for row_idx, row in enumerate(ws.iter_rows(min_row=1, max_row=20, values_only=True), start=1):
        row_str = [str(c).strip().lower() if c else '' for c in row]
        if 'codigo' in row_str or 'código' in row_str:
            header_row = row_idx
            break

    headers = []
    for row in ws.iter_rows(min_row=header_row, max_row=header_row, values_only=True):
        headers = [str(h).strip() if h else '' for h in row]
        break

    registros = []
    for row in ws.iter_rows(min_row=header_row + 1, values_only=True):
        if row[0] is None:
            continue
        registro = {}
        for i, val in enumerate(row):
            if i < len(headers) and headers[i]:
                registro[headers[i]] = val
        registros.append(registro)

    productos = {}
    for reg in registros:
        codigo = ''
        for k in reg:
            if 'c' in k.lower() and 'digo' in k.lower():
                codigo = str(reg[k] or '').strip()
                break

        producto = ''
        for k in reg:
            if 'producto' in k.lower():
                producto = str(reg[k] or '').strip()
                break

        lote = ''
        for k in reg:
            if 'lote' in k.lower():
                lote = str(reg[k] or '').strip()
                break

        saldo = 0
        for k in reg:
            if 'saldo' in k.lower():
                try:
                    saldo = float(reg[k] or 0)
                except (ValueError, TypeError):
                    saldo = 0
                break

        buenos_sys = 0
        for k in reg:
            if 'bueno' in k.lower():
                try:
                    buenos_sys = float(reg[k] or 0)
                except (ValueError, TypeError):
                    buenos_sys = 0
                break

        danados_sys = 0
        for k in reg:
            if 'da' in k.lower() and 'ado' in k.lower():
                try:
                    danados_sys = float(reg[k] or 0)
                except (ValueError, TypeError):
                    danados_sys = 0
                break

        arreglados_sys = 0
        for k in reg:
            if 'arreglado' in k.lower():
                try:
                    arreglados_sys = float(reg[k] or 0)
                except (ValueError, TypeError):
                    arreglados_sys = 0
                break

        reempacados_sys = 0
        for k in reg:
            if 'reempacado' in k.lower():
                try:
                    reempacados_sys = float(reg[k] or 0)
                except (ValueError, TypeError):
                    reempacados_sys = 0
                break

        if not codigo and not lote:
            continue

        key = f"{codigo}|{lote}"
        productos[key] = {
            'codigo': codigo,
            'producto': producto,
            'lote': lote,
            'saldo_sistema': saldo,
            'buenos_sistema': buenos_sys,
            'danados_sistema': danados_sys,
            'arreglados_sistema': arreglados_sys,
            'reempacados_sistema': reempacados_sys,
        }

    session['productos_virtuales'] = productos
    session['inventario_filename'] = archivo.filename

    return redirect(url_for('asignar'))


@app.route('/reportes')
def reportes():
    if 'usuario' not in session or session['usuario'].get('rol') != 'admin':
        return redirect(url_for('index'))

    reportes_github = []
    token = get_github_token()

    if token:
        result = github_api('/contents/reportes')
        if result and isinstance(result, list):
            for item in result:
                if item['name'].endswith('.json'):
                    file_data = github_api(f'/contents/reportes/{item["name"]}')
                    if file_data and 'content' in file_data:
                        content = base64.b64decode(file_data['content']).decode('utf-8')
                        reporte = json.loads(content)
                        reportes_github.append(reporte)

    reportes_github.sort(key=lambda x: x.get('fecha', ''), reverse=True)

    return render_template('reportes.html', reportes=reportes_github)


@app.route('/reportes/comparar/<reporte_id>')
def comparar_reporte(reporte_id):
    if 'usuario' not in session or session['usuario'].get('rol') != 'admin':
        return redirect(url_for('index'))

    reporte_trabajador = None
    token = get_github_token()

    if token:
        file_data = github_api(f'/contents/reportes/{reporte_id}.json')
        if file_data and 'content' in file_data:
            content = base64.b64decode(file_data['content']).decode('utf-8')
            reporte_trabajador = json.loads(content)

    if not reporte_trabajador:
        return redirect(url_for('reportes'))

    productos_virtuales = session.get('productos_virtuales', {})

    comparacion = []
    for item in reporte_trabajador.get('productos', []):
        key = f"{item['codigo']}|{item['lote']}"
        virtual = productos_virtuales.get(key, {})

        saldo_virtual = virtual.get('saldo_sistema', item.get('saldo_sistema', 0))
        total_fisico = item.get('total_fisico',
            (item.get('buenos', 0) + item.get('danados', 0) +
             item.get('arreglados', 0) + item.get('reempacados', 0)))

        comparacion.append({
            'codigo': item['codigo'],
            'producto': item['producto'],
            'lote': item['lote'],
            'saldo_sistema': saldo_virtual,
            'buenos_sistema': virtual.get('buenos_sistema', 0),
            'danados_sistema': virtual.get('danados_sistema', 0),
            'arreglados_sistema': virtual.get('arreglados_sistema', 0),
            'reempacados_sistema': virtual.get('reempacados_sistema', 0),
            'buenos': item.get('buenos', 0),
            'danados': item.get('danados', 0),
            'arreglados': item.get('arreglados', 0),
            'reempacados': item.get('reempacados', 0),
            'total_fisico': total_fisico,
            'diferencia': total_fisico - saldo_virtual,
            'observaciones': item.get('observaciones', '')
        })

    reporte = {
        'id': reporte_id,
        'trabajador': reporte_trabajador.get('nombre', reporte_trabajador.get('usuario', '')),
        'fecha': reporte_trabajador.get('fecha', ''),
        'detalle': comparacion,
        'resumen': generar_resumen(comparacion)
    }

    return render_template('dashboard.html', reporte=reporte)


@app.route('/exportar-excel/<reporte_id>')
def exportar_excel(reporte_id):
    if 'usuario' not in session:
        return redirect(url_for('login'))

    reporte_trabajador = None
    token = get_github_token()

    if token:
        file_data = github_api(f'/contents/reportes/{reporte_id}.json')
        if file_data and 'content' in file_data:
            content = base64.b64decode(file_data['content']).decode('utf-8')
            reporte_trabajador = json.loads(content)

    if not reporte_trabajador:
        return redirect(url_for('reportes'))

    productos_virtuales = session.get('productos_virtuales', {})

    wb = openpyxl.Workbook()
    ws = wb.active
    ws.title = "Detalle"

    header_font = Font(bold=True, color="FFFFFF", size=11)
    header_fill = PatternFill(start_color="2C3E50", end_color="2C3E50", fill_type="solid")
    normal_font = Font(size=11)
    good_fill = PatternFill(start_color="D5F5E3", end_color="D5F5E3", fill_type="solid")
    bad_fill = PatternFill(start_color="FADBD8", end_color="FADBD8", fill_type="solid")
    thin_border = Border(
        left=Side(style='thin'), right=Side(style='thin'),
        top=Side(style='thin'), bottom=Side(style='thin')
    )

    headers = ['Código', 'Producto', 'Lote', 'Saldo Sistema',
               'Buenos', 'Dañados', 'Arreglados', 'Reempacados',
               'Total Físico', 'Diferencia', 'Estado', 'Observaciones']

    for col, header in enumerate(headers, 1):
        cell = ws.cell(row=1, column=col, value=header)
        cell.font = header_font
        cell.fill = header_fill
        cell.alignment = Alignment(horizontal='center', wrap_text=True)
        cell.border = thin_border

    for row_idx, item in enumerate(reporte_trabajador.get('productos', []), start=2):
        key = f"{item['codigo']}|{item['lote']}"
        virtual = productos_virtuales.get(key, {})
        saldo = virtual.get('saldo_sistema', item.get('saldo_sistema', 0))
        total = item.get('total_fisico',
            (item.get('buenos', 0) + item.get('danados', 0) +
             item.get('arreglados', 0) + item.get('reempacados', 0)))
        diff = total - saldo
        estado = "CUADRA" if diff == 0 else ("SOBRANTE" if diff > 0 else "FALTANTE")

        values = [item['codigo'], item['producto'], item['lote'], saldo,
                  item.get('buenos', 0), item.get('danados', 0),
                  item.get('arreglados', 0), item.get('reempacados', 0),
                  total, diff, estado, item.get('observaciones', '')]

        for col, val in enumerate(values, 1):
            cell = ws.cell(row=row_idx, column=col, value=val)
            cell.font = normal_font
            cell.border = thin_border
            if col == 12:
                cell.alignment = Alignment(wrap_text=True)

        if diff > 0:
            ws.cell(row=row_idx, column=10).fill = good_fill
        elif diff < 0:
            ws.cell(row=row_idx, column=10).fill = bad_fill

    for col in range(1, len(headers) + 1):
        ws.column_dimensions[get_column_letter(col)].width = 16
    ws.column_dimensions['L'].width = 40

    fecha = datetime.now().strftime('%d-%m-%Y')
    filename = f"REPORTE_INVENTARIO_{fecha}.xlsx"
    filepath = os.path.join(app.config['REPORTES_FOLDER'], filename)
    wb.save(filepath)

    return send_file(filepath, as_attachment=True, download_name=filename)


def generar_resumen(detalle):
    total_productos = len(detalle)
    total_sacos_sistema = sum(d.get('saldo_sistema', 0) for d in detalle)
    total_fisico = sum(d.get('total_fisico', 0) for d in detalle)
    total_buenos = sum(d.get('buenos', 0) for d in detalle)
    total_danados = sum(d.get('danados', 0) for d in detalle)
    total_arreglados = sum(d.get('arreglados', 0) for d in detalle)
    total_reempacados = sum(d.get('reempacados', 0) for d in detalle)

    con_diferencia = [d for d in detalle if d.get('diferencia', 0) != 0]
    sobrantes = [d for d in detalle if d.get('diferencia', 0) > 0]
    faltantes = [d for d in detalle if d.get('diferencia', 0) < 0]

    return {
        'total_productos': total_productos,
        'total_sacos_sistema': total_sacos_sistema,
        'total_fisico': total_fisico,
        'total_buenos': total_buenos,
        'total_danados': total_danados,
        'total_arreglados': total_arreglados,
        'total_reempacados': total_reempacados,
        'con_diferencia': len(con_diferencia),
        'sin_diferencia': total_productos - len(con_diferencia),
        'sobrantes_count': len(sobrantes),
        'faltantes_count': len(faltantes),
        'diferencia_total': total_fisico - total_sacos_sistema
    }


if __name__ == '__main__':
    app.run(host='0.0.0.0', port=8080, debug=True)
