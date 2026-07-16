import json
import os

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BRANDING_PATH = os.path.join(BASE_DIR, "data", "branding.json")

PADROES = {
    "nome_marca": "KL Acesso Remoto",
    "titulo_painel": "Painel de Dispositivos",
    "titulo_espelho": "Espelho Remoto",
    "titulo_gerar_apk": "Gerar Aplicativo",
    "subtitulo_gerar_apk": "Crie um APK personalizado com nome e icone.",
    "nome_padrao_apk": "KL Acesso Remoto",
    "texto_botao_apk": "Gerar Aplicativo",
    "texto_voltar": "Voltar ao Painel",
}


def obter_branding():
    dados = dict(PADROES)
    if os.path.exists(BRANDING_PATH):
        try:
            with open(BRANDING_PATH, encoding="utf-8") as arquivo:
                salvo = json.load(arquivo)
            if isinstance(salvo, dict):
                dados.update({k: v for k, v in salvo.items() if k in PADROES and v})
        except Exception:
            pass
    return dados


def salvar_branding(dados):
    limpo = dict(PADROES)
    for chave in PADROES:
        valor = dados.get(chave)
        if valor is not None:
            limpo[chave] = str(valor).strip()[:80] or PADROES[chave]
    os.makedirs(os.path.dirname(BRANDING_PATH), exist_ok=True)
    with open(BRANDING_PATH, "w", encoding="utf-8") as arquivo:
        json.dump(limpo, arquivo, ensure_ascii=False, indent=2)
    return limpo
