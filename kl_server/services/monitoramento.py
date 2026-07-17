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


def enriquecer_dispositivo(dados, agora=None):
    agora = agora or time.time()
    online = dispositivo_online(dados, agora)
    ultimo = dados.get("ultimo_contato") or 0
    segundos_atras = int(agora - ultimo) if ultimo else None
    return {
        **dados,
        "nome_exibicao": nome_exibicao(dados),
        "online": online,
        "status": "online" if online else "offline",
        "segundos_atras": segundos_atras,
        "ultimo_contato_fmt": _formatar_hora(ultimo) if ultimo else "-",
    }


def _formatar_hora(timestamp):
    from datetime import datetime, timezone
    return datetime.fromtimestamp(timestamp, tz=timezone.utc).strftime("%H:%M:%S")


def listar_dispositivos_monitoramento():
    from routes.dispositivos import dispositivos

    agora = time.time()
    lista = [enriquecer_dispositivo(d, agora) for d in dispositivos.values()]
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
    }
