package client;

import common.FramedStream;
import common.Protocol;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Multiplexador de respostas para cliente multi-threaded.
 *
 * Permite que múltiplas threads de aplicação partilhem uma única conexão TCP,
 * associando respostas às threads que as solicitaram através de identificadores
 * únicos (tags). Uma thread dedicada lê continuamente do socket e distribui
 * as respostas para as threads corretas.
 *
 * Este padrão resolve o problema de head-of-line blocking, onde uma operação
 * lenta (como uma notificação bloqueante) não impede outras operações do
 * mesmo cliente.
 */
public class Demultiplexer implements AutoCloseable {

    private final FramedStream stream;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<Integer, Entry> pending = new HashMap<>();
    private IOException error = null;

    /**
     * Contentor para estado de um pedido pendente.
     * Armazena os dados da resposta quando chegam e a condição de sinalização
     * para acordar a thread que aguarda.
     */
    private static class Entry {
        byte[] data;
        int type;
        final Condition cond;

        Entry(Condition c) {
            this.cond = c;
        }
    }

    /**
     * Constrói um demultiplexador sobre um FramedStream.
     *
     * @param stream Canal de comunicação framed
     */
    public Demultiplexer(FramedStream stream) {
        this.stream = stream;
    }

    /**
     * Inicia a thread de leitura dedicada.
     * Esta thread executa em loop infinito, lendo frames do servidor e
     * distribuindo-os às threads de aplicação correspondentes. Em caso de
     * erro de rede, todas as threads pendentes são notificadas.
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
                            e.type = frame.type;
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
                    for (Entry entry : pending.values()) {
                        entry.cond.signalAll();
                    }
                } finally {
                    lock.unlock();
                }
            }
        }).start();
    }

    /**
     * Regista uma tag para receber resposta.
     * Deve ser chamado antes de enviar o pedido para evitar race conditions
     * onde a resposta chega antes do registo.
     *
     * @param tag Identificador único do pedido
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
     * Envia um frame através do canal partilhado.
     *
     * @param tag  Identificador do pedido
     * @param type Código da operação
     * @param data Payload serializado
     * @throws IOException Se ocorrer erro de escrita
     */
    public void send(int tag, int type, byte[] data) throws IOException {
        stream.send(tag, type, data);
    }

    /**
     * Aguarda a resposta para uma tag específica.
     * Bloqueia a thread atual até que a resposta correspondente chegue ou
     * ocorra um erro de rede. Se o servidor responder com STATUS_ERR, é
     * lançada uma exceção com a mensagem de erro.
     *
     * @param tag Identificador do pedido
     * @return Payload da resposta
     * @throws IOException          Se ocorrer erro de rede ou o servidor indicar erro
     * @throws InterruptedException Se a thread for interrompida
     * @throws IllegalStateException Se a tag não foi previamente registada
     */
    public byte[] receive(int tag) throws IOException, InterruptedException {
        lock.lock();
        try {
            Entry e = pending.get(tag);
            if (e == null) {
                throw new IllegalStateException("Tag não foi registada: " + tag);
            }

            while (e.data == null && error == null) {
                e.cond.await();
            }

            if (error != null) {
                throw error;
            }

            if (e.type == Protocol.STATUS_ERR) {
                String msg = e.data.length > 0 ? new String(e.data) : "Erro do servidor";
                throw new IOException(msg);
            }

            return e.data;
        } finally {
            pending.remove(tag);
            lock.unlock();
        }
    }

    /**
     * Encerra o canal de comunicação.
     *
     * @throws IOException Se ocorrer erro ao fechar
     */
    @Override
    public void close() throws IOException {
        stream.close();
    }
}