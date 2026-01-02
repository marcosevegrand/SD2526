package client;

import common.FramedStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Gestor de concorrência que permite que várias threads partilhem a mesma ligação TCP.
 * Resolve o problema de mapear respostas assíncronas vindas do servidor de volta
 * às threads que as solicitaram, usando identificadores (tags).
 */
public class Demultiplexer implements AutoCloseable {

    private final FramedStream stream;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<Integer, Entry> pending = new HashMap<>();
    private IOException error = null;

    /**
     * Contentor para armazenar o estado de um pedido pendente.
     * A intenção é manter o payload e a condição de sinalização para a thread em espera.
     */
    private static class Entry {

        byte[] data;
        final Condition cond;

        /**
         * Cria uma entrada com uma condição associada ao lock do Demultiplexer.
         * @param c A condição para bloqueio/sinalização.
         */
        Entry(Condition c) {
            this.cond = c;
        }
    }

    /**
     * Inicializa o demultiplexador sobre um fluxo estruturado.
     * @param stream A stream com frames sobre a qual se vai operar.
     */
    public Demultiplexer(FramedStream stream) {
        this.stream = stream;
    }

    /**
     * Inicia a thread de recepção contínua.
     * Esta thread corre em background e é a única autorizada a ler do socket,
     * distribuindo os dados pelas entradas pendentes conforme a tag recebida.
     */
    public void start() {
        new Thread(() -> {
            try {
                while (true) {
                    FramedStream.Frame frame = stream.receive();
                    lock.lock();
                    try {
                        Entry e = pending.get(frame.tag);
                        if (e != null) {
                            e.data = frame.payload;
                            // Acorda a thread específ ica que espera por esta tag
                            e.cond.signal();
                        }
                    } finally {
                        lock.unlock();
                    }
                }
            } catch (IOException e) {
                lock.lock();
                try {
                    this.error = e;
                    // Se a rede falhar, sinalizamos todas as threads para que saibam que a ligação morreu
                    for (Entry entry : pending.values()) entry.cond.signalAll();
                } finally {
                    lock.unlock();
                }
            }
        })
            .start();
    }

    /**
     * Reserva um espaço na tabela de pendentes para uma tag específ ica.
     * Este passo deve ocorrer antes do envio físico para evitar que o servidor responda
     * mais rápido do que a nossa capacidade de registar a tag.
     * @param tag O identificador da mensagem a registar.
     */
    public void register(int tag) {
        lock.lock();
        try {
            pending.putIfAbsent(tag, new Entry(lock.newCondition()));
        } finally {
            lock.unlock();
        }
    }

    /**
     * Realiza o envio do frame para a rede.
     * @param tag Identificador.
     * @param type Tipo de operação.
     * @param data Payload.
     * @throws IOException Em caso de falha de transmissão.
     */
    public void send(int tag, int type, byte[] data) throws IOException {
        stream.send(tag, type, data);
    }

    /**
     * Bloqueia a thread atual até que os dados com a tag correspondente cheguem.
     * CORRIGIDO: Remove a entrada da tabela 'pending' ANTES de realizar a espera,
     * evitando fuga de memória se uma exceção ocorrer durante a espera.
     * @param tag A tag do pedido que enviamos.
     * @return Os bytes da resposta recebida.
     * @throws IOException Se houver erro de rede ou se a ligação cair durante a espera.
     * @throws InterruptedException Se a thread for interrompida externamente.
     * @throws IllegalStateException Se a tag não tiver sido previamente registada.
     */
    public byte[] receive(int tag) throws IOException, InterruptedException {
        lock.lock();
        try {
            // CORRIGIDO: Remove ANTES para garantir que será feita mesmo se houver exceção
            Entry e = pending.remove(tag);
            if (e == null) throw new IllegalStateException(
                "Tag não foi registada: " + tag
            );

            // Ciclo de espera para lidar com despertares espuríos ou demora na rede
            while (e.data == null && error == null) {
                e.cond.await();
            }

            if (error != null) throw error;
            return e.data;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Encerra a stream subjacente e liberta os recursos.
     * @throws IOException Se houver erro no fecho da stream.
     */
    @Override
    public void close() throws IOException {
        stream.close();
    }
}
