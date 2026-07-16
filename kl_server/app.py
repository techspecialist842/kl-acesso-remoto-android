from flask import Flask

from routes.dispositivos import bp as dispositivos_bp
from routes.esqueleto import bp as esqueleto_bp
from routes.comandos import bp as comandos_bp
from routes.painel import bp as painel_bp
from routes.overlay import bp as overlay_bp
from routes.apk import bp as apk_bp
from routes.branding import bp as branding_bp


app = Flask(__name__)


@app.context_processor
def injetar_branding():
    from services.branding import obter_branding
    return {"branding": obter_branding()}


app.register_blueprint(dispositivos_bp)
app.register_blueprint(esqueleto_bp)
app.register_blueprint(comandos_bp)
app.register_blueprint(painel_bp)
app.register_blueprint(overlay_bp)
app.register_blueprint(apk_bp)
app.register_blueprint(branding_bp)


@app.get("/")
def inicio():
    return "KL SERVER ONLINE"


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=9001)
