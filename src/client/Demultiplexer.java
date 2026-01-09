package client;

import common.FramedStream;
import common.Protocol;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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

    /** Timeout por omissão para aguardar respostas (30 segundos). */
    public static final long DEFAULT_RECEIVE_TIMEOUT_MS = 30000;

    private final FramedStream stream;
    private final ReentrantLock lock = new ReentrantLock();
    private final Map<Integer, Entry> pending = new HashMap<>();
    private volatile IOException error = null;
    private volatile boolean closed = false;
    private Thread readerThread = null;

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
        readerThread = new Thread(() -> {
            try {
                while (!closed && !stream.isClosed()) {
                    try {
                        FramedStream.Frame frame = stream.receive();
                        deliverFrame(frame);
                    } catch (java.net.SocketTimeoutException e) {
                        // Timeout de leitura - continuar a aguardar
                        // Isto permite verificar periodicamente se devemos fechar
                        continue;
                    }
                }
            } catch (IOException e) {
                if (!closed) {
                    handleReaderError(e);
                }
            }
        }, "Demultiplexer-Reader");

        readerThread.setDaemon(true);
        readerThread.start();
    }

    /**
     * Entrega um frame à thread que o aguarda.
     *
     * @param frame Frame recebido do servidor
     */
    private void deliverFrame(FramedStream.Frame frame) {
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

    /**
     * Trata erros na thread de leitura, notificando todas as threads pendentes.
     *
     * @param e Exceção que causou a falha
     */
    private void handleReaderError(IOException e) {
        lock.lock();
        try {
            this.error = e;
            // Acordar TODAS as threads pendentes para que possam ver o erro
            for (Entry entry : pending.values()) {
                entry.cond.signalAll();
            }
        } finally {
            lock.unlock();
        }
        System.err.println("[Demultiplexer] Thread de leitura terminou com erro: " + e.getMessage());
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
     * Aguarda a resposta para uma tag específica com timeout por omissão.
     *
     * @param tag Identificador do pedido
     * @return Payload da resposta
     * @throws IOException          Se ocorrer erro de rede ou o servidor indicar erro
     * @throws InterruptedException Se a thread for interrompida
     */
    public byte[] receive(int tag) throws IOException, InterruptedException {
        try {
            return receive(tag, DEFAULT_RECEIVE_TIMEOUT_MS);
        } catch (TimeoutException e) {
            throw new IOException("Timeout ao aguardar resposta para tag " + tag, e);
        }
    }

    /**
     * Aguarda a resposta para uma tag específica com timeout configurável.
     * Bloqueia a thread atual até que a resposta correspondente chegue,
     * ocorra um erro de rede, ou o timeout expire.
     *
     * @param tag       Identificador do pedido
     * @param timeoutMs Timeout em milissegundos (0 = infinito)
     * @return Payload da resposta
     * @throws IOException          Se ocorrer erro de rede ou o servidor indicar erro
     * @throws InterruptedException Se a thread for interrompida
     * @throws TimeoutException     Se o timeout expirar antes de receber resposta
     */
    public byte[] receive(int tag, long timeoutMs)
            throws IOException, InterruptedException, TimeoutException {
        lock.lock();
        try {
            Entry e = pending.get(tag);
            if (e == null) {
                throw new IllegalStateException("Tag não foi registada: " + tag);
            }

            long deadlineNanos = (timeoutMs > 0)
                    ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
                    : Long.MAX_VALUE;

            while (e.data == null && error == null && !closed) {
                if (timeoutMs > 0) {
                    long remainingNanos = deadlineNanos - System.nanoTime();
                    if (remainingNanos <= 0) {
                        pending.remove(tag);
                        throw new TimeoutException(
                                "Timeout ao aguardar resposta (tag=" + tag + ", timeout=" + timeoutMs + "ms)"
                        );
                    }
                    e.cond.awaitNanos(remainingNanos);
                } else {
                    e.cond.await();
                }
            }

            // Verificar condições de erro
            if (closed) {
                pending.remove(tag);
                throw new IOException("Demultiplexer foi fechado");
            }

            if (error != null) {
                pending.remove(tag);
                throw error;
            }

            if (e.type == Protocol.STATUS_ERR) {
                String msg = e.data.length > 0 ? new String(e.data) : "Erro do servidor";
                pending.remove(tag);
                throw new IOException(msg);
            }

            byte[] result = e.data;
            pending.remove(tag);
            return result;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Verifica se o demultiplexer está ativo e a funcionar.
     *
     * @return true se estiver operacional
     */
    public boolean isHealthy() {
        return !closed && error == null && readerThread != null && readerThread.isAlive();
    }

    /**
     * Encerra o canal de comunicação e a thread de leitura.
     *
     * @throws IOException Se ocorrer erro ao fechar
     */
    @Override
    public void close() throws IOException {
        closed = true;

        // Acordar todas as threads pendentes
        lock.lock();
        try {
            for (Entry entry : pending.values()) {
                entry.cond.signalAll();
            }
        } finally {
            lock.unlock();
        }

        // Fechar o stream (vai causar IOException na thread de leitura)
        stream.close();

        // Aguardar que a thread de leitura termine
        if (readerThread != null) {
            try {
                readerThread.join(1000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
    }
}