import time

ONLINE_LIMITE_SEGUNDOS = 15


def dispositivo_online(dados, agora=None):
    agora = agora or time.time()
    ultimo = dados.get("ultimo_contato") or 0
    if not ultimo:
        return False
    return (agora - ultimo) <= ONLINE_LIMITE_SEGUNDOS


def nome_exibicao(dados):
    nome = (dados.get("nome") or "").strip()
    if nome:
        return nome
    return dados.get("modelo") or "Dispositivo"


def proprietario_label(dados):
    if dados.get("cadastro_adm"):
        return "Cadastrado pelo ADM"
    usuario_nome = (dados.get("usuario_nome") or "").strip()
    if usuario_nome:
        return f'Dispositivo do usuario "{usuario_nome}"'
    return "Sem usuario vinculado"


def dispositivo_visivel_para_usuario(dados, usuario):
    if not usuario:
        return False
    if usuario.get("is_admin"):
        return True
    return dados.get("usuario_id") == usuario.get("id")


def enriquecer_dispositivo(dados, agora=None, usuario=None):
    agora = agora or time.time()
    online = dispositivo_online(dados, agora)
    ultimo = dados.get("ultimo_contato") or 0
    segundos_atras = int(agora - ultimo) if ultimo else None
    return {
        **dados,
        "nome_exibicao": nome_exibicao(dados),
        "proprietario_label": proprietario_label(dados),
        "online": online,
        "status": "online" if online else "offline",
        "segundos_atras": segundos_atras,
        "ultimo_contato_fmt": _formatar_hora(ultimo) if ultimo else "-",
        "eh_admin": bool(usuario and usuario.get("is_admin")),
    }


def _formatar_hora(timestamp):
    from datetime import datetime, timezone
    return datetime.fromtimestamp(timestamp, tz=timezone.utc).strftime("%H:%M:%S")


def listar_dispositivos_monitoramento(usuario=None):
    from routes.dispositivos import obter_dispositivos_ativos

    agora = time.time()
    ativos = obter_dispositivos_ativos().values()
    lista = [
        enriquecer_dispositivo(d, agora, usuario)
        for d in ativos
        if dispositivo_visivel_para_usuario(d, usuario)
    ]
    lista.sort(
        key=lambda item: (
            item.get("ultimo_contato") or 0,
            item.get("id") or "",
        ),
        reverse=True,
    )
    return {
        "dispositivos": lista,
        "total": len(lista),
        "total_online": sum(1 for d in lista if d["online"]),
        "total_offline": sum(1 for d in lista if not d["online"]),
        "atualizado_em": int(agora),
        "eh_admin": bool(usuario and usuario.get("is_admin")),
    }
