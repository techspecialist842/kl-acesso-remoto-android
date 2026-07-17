import os
import sqlite3
import time
import uuid
from datetime import datetime, timezone

from werkzeug.security import check_password_hash, generate_password_hash

BASE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BANCO = os.path.join(BASE_DIR, "data", "usuarios.db")

PLANOS_VALIDOS = {7, 15, 30}


def conectar():
    os.makedirs(os.path.dirname(BANCO), exist_ok=True)
    return sqlite3.connect(BANCO)


def criar_banco():
    conexao = conectar()
    cursor = conexao.cursor()
    cursor.execute(
        """
        CREATE TABLE IF NOT EXISTS usuarios (
            id TEXT PRIMARY KEY,
            usuario TEXT UNIQUE NOT NULL,
            senha_hash TEXT NOT NULL,
            nome TEXT NOT NULL,
            plano_dias INTEGER NOT NULL,
            criado_em REAL NOT NULL,
            expira_em REAL NOT NULL,
            ativo INTEGER NOT NULL DEFAULT 1,
            is_admin INTEGER NOT NULL DEFAULT 0
        )
        """
    )
    conexao.commit()
    conexao.close()


def _agora():
    return time.time()


def _formatar_data(timestamp):
    if not timestamp:
        return "-"
    return datetime.fromtimestamp(timestamp, tz=timezone.utc).strftime("%d/%m/%Y %H:%M")


def _linha_para_dict(linha):
    return {
        "id": linha[0],
        "usuario": linha[1],
        "nome": linha[3],
        "plano_dias": linha[4],
        "criado_em": linha[5],
        "expira_em": linha[6],
        "criado_em_fmt": _formatar_data(linha[5]),
        "expira_em_fmt": _formatar_data(linha[6]),
        "ativo": bool(linha[7]),
        "is_admin": bool(linha[8]),
        "expirado": linha[6] <= _agora(),
        "dias_restantes": max(0, int((linha[6] - _agora()) / 86400)),
    }


def garantir_admin_padrao():
    criar_banco()
    conexao = conectar()
    cursor = conexao.cursor()
    cursor.execute("SELECT COUNT(*) FROM usuarios")
    total = cursor.fetchone()[0]
    if total > 0:
        conexao.close()
        return

    usuario = os.environ.get("KL_ADMIN_USER", "admin")
    senha = os.environ.get("KL_ADMIN_PASS", "admin123")
    agora = _agora()
    cursor.execute(
        """
        INSERT INTO usuarios (
            id, usuario, senha_hash, nome, plano_dias,
            criado_em, expira_em, ativo, is_admin
        ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, 1)
        """,
        (
            uuid.uuid4().hex,
            usuario,
            generate_password_hash(senha),
            "Administrador",
            30,
            agora,
            agora + 30 * 86400,
        ),
    )
    conexao.commit()
    conexao.close()


def buscar_por_usuario(usuario):
    criar_banco()
    conexao = conectar()
    cursor = conexao.cursor()
    cursor.execute(
        "SELECT id, usuario, senha_hash, nome, plano_dias, criado_em, expira_em, ativo, is_admin "
        "FROM usuarios WHERE usuario = ?",
        (usuario.strip().lower(),),
    )
    linha = cursor.fetchone()
    conexao.close()
    if not linha:
        return None
    dados = _linha_para_dict(linha)
    dados["senha_hash"] = linha[2]
    return dados


def buscar_por_id(user_id):
    criar_banco()
    conexao = conectar()
    cursor = conexao.cursor()
    cursor.execute(
        "SELECT id, usuario, senha_hash, nome, plano_dias, criado_em, expira_em, ativo, is_admin "
        "FROM usuarios WHERE id = ?",
        (user_id,),
    )
    linha = cursor.fetchone()
    conexao.close()
    if not linha:
        return None
    return _linha_para_dict(linha)


def listar_usuarios():
    criar_banco()
    conexao = conectar()
    cursor = conexao.cursor()
    cursor.execute(
        "SELECT id, usuario, senha_hash, nome, plano_dias, criado_em, expira_em, ativo, is_admin "
        "FROM usuarios ORDER BY criado_em DESC"
    )
    linhas = cursor.fetchall()
    conexao.close()
    return [_linha_para_dict(linha) for linha in linhas]


def usuario_pode_acessar(dados):
    if not dados:
        return False, "Usuario nao encontrado"
    if not dados.get("ativo"):
        return False, "Conta desativada"
    if dados.get("expira_em", 0) <= _agora():
        return False, "Plano expirado. Renove o acesso."
    return True, ""


def autenticar(usuario, senha):
    dados = buscar_por_usuario(usuario)
    if not dados:
        return None, "Usuario ou senha invalidos"
    if not check_password_hash(dados["senha_hash"], senha):
        return None, "Usuario ou senha invalidos"
    ok, motivo = usuario_pode_acessar(dados)
    if not ok:
        return None, motivo
    dados.pop("senha_hash", None)
    return dados, ""


def criar_usuario(usuario, senha, nome, plano_dias, is_admin=False):
    usuario = usuario.strip().lower()
    nome = (nome or usuario).strip()
    plano_dias = int(plano_dias)
    if plano_dias not in PLANOS_VALIDOS and not is_admin:
        raise ValueError("Plano invalido. Use 7, 15 ou 30 dias.")
    if len(usuario) < 3:
        raise ValueError("Usuario deve ter pelo menos 3 caracteres")
    if len(senha) < 4:
        raise ValueError("Senha deve ter pelo menos 4 caracteres")

    agora = _agora()
    criar_banco()
    conexao = conectar()
    cursor = conexao.cursor()
    try:
        cursor.execute(
            """
            INSERT INTO usuarios (
                id, usuario, senha_hash, nome, plano_dias,
                criado_em, expira_em, ativo, is_admin
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 1, ?)
            """,
            (
                uuid.uuid4().hex,
                usuario,
                generate_password_hash(senha),
                nome,
                plano_dias,
                agora,
                agora + plano_dias * 86400,
                1 if is_admin else 0,
            ),
        )
        conexao.commit()
    except sqlite3.IntegrityError as exc:
        raise ValueError("Usuario ja existe") from exc
    finally:
        conexao.close()


def atualizar_usuario(user_id, nome=None, senha=None, plano_dias=None, ativo=None, renovar=False):
    dados = buscar_por_id(user_id)
    if not dados:
        raise ValueError("Usuario nao encontrado")
    if dados["is_admin"] and ativo is False:
        raise ValueError("Nao e possivel desativar o administrador")

    campos = []
    valores = []

    if nome is not None:
        campos.append("nome = ?")
        valores.append(nome.strip())
    if senha:
        campos.append("senha_hash = ?")
        valores.append(generate_password_hash(senha))
    if plano_dias is not None:
        plano_dias = int(plano_dias)
        if plano_dias not in PLANOS_VALIDOS:
            raise ValueError("Plano invalido. Use 7, 15 ou 30 dias.")
        campos.append("plano_dias = ?")
        valores.append(plano_dias)
        if renovar:
            campos.append("expira_em = ?")
            valores.append(_agora() + plano_dias * 86400)
    if ativo is not None:
        campos.append("ativo = ?")
        valores.append(1 if ativo else 0)

    if not campos:
        return dados

    valores.append(user_id)
    conexao = conectar()
    cursor = conexao.cursor()
    cursor.execute(
        f"UPDATE usuarios SET {', '.join(campos)} WHERE id = ?",
        valores,
    )
    conexao.commit()
    conexao.close()
    return buscar_por_id(user_id)


def excluir_usuario(user_id):
    dados = buscar_por_id(user_id)
    if not dados:
        raise ValueError("Usuario nao encontrado")
    if dados["is_admin"]:
        raise ValueError("Nao e possivel excluir o administrador")

    conexao = conectar()
    cursor = conexao.cursor()
    cursor.execute("DELETE FROM usuarios WHERE id = ?", (user_id,))
    conexao.commit()
    conexao.close()
