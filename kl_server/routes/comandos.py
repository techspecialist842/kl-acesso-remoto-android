from flask import Blueprint, request, jsonify
import sqlite3
import os


print("CARREGOU COMANDOS SQLITE NOVO")


bp = Blueprint("comandos", __name__)


BASE_DIR = os.path.dirname(
    os.path.abspath(__file__)
)


BANCO = os.path.join(
    BASE_DIR,
    "comandos.db"
)



def conectar():

    return sqlite3.connect(BANCO)



def criar_banco():

    conexao = conectar()

    cursor = conexao.cursor()

    cursor.execute("""
        CREATE TABLE IF NOT EXISTS comandos (

            id INTEGER PRIMARY KEY AUTOINCREMENT,

            dispositivo TEXT NOT NULL,

            comando TEXT NOT NULL

        )
    """)

    conexao.commit()

    conexao.close()



def enfileirar_comando(dispositivo, comando):
    criar_banco()
    conexao = conectar()
    cursor = conexao.cursor()
    cursor.execute(
        """
        INSERT INTO comandos (dispositivo, comando)
        VALUES (?, ?)
        """,
        (dispositivo, comando),
    )
    conexao.commit()
    conexao.close()



criar_banco()





@bp.post("/enviar_comando")
def enviar():

    dados = request.get_json(silent=True)


    if not dados:
        return jsonify({
            "erro": "dados inválidos"
        }), 400



    dispositivo = dados.get("id")

    comando = dados.get("comando")



    if not dispositivo or not comando:

        return jsonify({
            "erro": "id e comando obrigatórios"
        }), 400



    conexao = conectar()

    cursor = conexao.cursor()



    print(
        "SALVANDO COMANDO:",
        dispositivo,
        comando
    )



    cursor.execute(
        """
        INSERT INTO comandos
        (
            dispositivo,
            comando
        )

        VALUES (?, ?)
        """,
        (
            dispositivo,
            comando
        )
    )



    conexao.commit()


    cursor.execute(
        "SELECT * FROM comandos"
    )

    print(
        "BANCO APOS INSERT:",
        cursor.fetchall()
    )


    conexao.close()



    return jsonify({
        "status": "ok"
    })







@bp.get("/obter_comando")
def obter():

    dispositivo = request.args.get("id")



    if not dispositivo:

        return "nenhum"



    print(
        "BANCO NA LEITURA:",
        BANCO
    )


    print(
        "BUSCANDO DISPOSITIVO:",
        dispositivo
    )



    conexao = conectar()

    cursor = conexao.cursor()



    cursor.execute(
        """
        SELECT id, comando

        FROM comandos

        WHERE dispositivo = ?

        ORDER BY id ASC

        LIMIT 1
        """,
        (
            dispositivo,
        )
    )



    resultado = cursor.fetchone()



    print(
        "RESULTADO BUSCA:",
        resultado
    )



    if not resultado:

        conexao.close()

        return "nenhum"



    id_comando = resultado[0]

    texto_comando = resultado[1]



    cursor.execute(
        """
        DELETE FROM comandos

        WHERE id = ?
        """,
        (
            id_comando,
        )
    )



    conexao.commit()

    conexao.close()



    print(
        "ENTREGANDO:",
        texto_comando
    )



    return texto_comando
