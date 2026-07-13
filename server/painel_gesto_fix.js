/**
 * CORREÇÃO DE ARRASTO HORIZONTAL/VERTICAL — substituir no template do painel
 * (arquivo que contém inicioArrasto / fimArrasto no espelho)
 *
 * Problema: mouseup só estava no #celular. Em arrastos horizontais o cursor
 * sai da área do espelho antes de soltar o botão, e o comando nunca era enviado.
 */

// Substituir as funções inicioArrasto e fimArrasto por estas:

function coordenadasNoCelular(event) {
    const celular = document.getElementById("celular");
    const rect = celular.getBoundingClientRect();
    const fator = escala * escalaAtual;
    const x = (event.clientX - rect.left) / fator;
    const y = (event.clientY - rect.top) / fator;
    return {
        x: Math.max(0, Math.min(larguraOriginal, Math.round(x))),
        y: Math.max(0, Math.min(alturaOriginal, Math.round(y)))
    };
}

function finalizarArrasto(event) {
    document.removeEventListener("mouseup", finalizarArrasto);
    document.removeEventListener("mousemove", marcarArrasto);

    const { x: fimX, y: fimY } = coordenadasNoCelular(event);
    const distanciaX = Math.abs(fimX - inicioX);
    const distanciaY = Math.abs(fimY - inicioY);
    const limiarArrasto = 12;

    if (distanciaX >= limiarArrasto || distanciaY >= limiarArrasto) {
        foiArrasto = true;
        enviarComando(`arrastar|${inicioX},${inicioY},${fimX},${fimY}`);
    } else {
        enviarComando(`toque|${fimX},${fimY}`);
    }
}

function marcarArrasto() {
    foiArrasto = true;
}

function inicioArrasto(event) {
    event.preventDefault();
    foiArrasto = false;

    const coords = coordenadasNoCelular(event);
    inicioX = coords.x;
    inicioY = coords.y;

    document.addEventListener("mouseup", finalizarArrasto);
    document.addEventListener("mousemove", marcarArrasto);
}

// Remover onmouseup="fimArrasto(event)" do HTML do #celular.
// Manter apenas: onmousedown="inicioArrasto(event)"
