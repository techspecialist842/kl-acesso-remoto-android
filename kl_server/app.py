from flask import Flask

from routes.dispositivos import bp as dispositivos_bp
from routes.esqueleto import bp as esqueleto_bp
from routes.comandos import bp as comandos_bp
from routes.painel import bp as painel_bp
from routes.overlay import bp as overlay_bp


app = Flask(__name__)


app.register_blueprint(dispositivos_bp)
app.register_blueprint(esqueleto_bp)
app.register_blueprint(comandos_bp)
app.register_blueprint(painel_bp)
app.register_blueprint(overlay_bp)


@app.get("/")
def inicio():
    return "KL SERVER ONLINE"


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=9001)
