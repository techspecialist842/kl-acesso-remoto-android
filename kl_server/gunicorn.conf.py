import os

_env_file = os.path.join(os.path.dirname(__file__), "apk_builder.env")
if os.path.exists(_env_file):
    with open(_env_file, encoding="utf-8") as arquivo:
        for linha in arquivo:
            linha = linha.strip()
            if not linha or linha.startswith("#") or "=" not in linha:
                continue
            chave, _, valor = linha.partition("=")
            chave = chave.strip()
            valor = valor.strip()
            if chave and valor and chave not in os.environ:
                os.environ[chave] = valor

bind = "127.0.0.1:9001"
workers = 2
timeout = 1200
