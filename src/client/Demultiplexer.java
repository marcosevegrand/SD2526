package client;

import common.FramedStream;
import common.Protocol;
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
     * A intenção é manter o payload, o tipo de resposta e a condição de sinalização.
     */
    private static class Entry {
        byte[] data;
        int type;
        final Condition cond;

        Entry(Condition c) {
            this.cond = c;
        }
    }

    public Demultiplexer(FramedStream stream) {
        this.stream = stream;
    }

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
                    for (Entry entry : pending.values()) entry.cond.signalAll();
                } finally {
                    lock.unlock();
                }
            }
        }).start();
    }

    public void register(int tag) {
        lock.lock();
        try {
            pending.putIfAbsent(tag, new Entry(lock.newCondition()));
        } finally {
            lock.unlock();
        }
    }

    public void send(int tag, int type, byte[] data) throws IOException {
        stream.send(tag, type, data);
    }

    /**
     * Bloqueia a thread atual até que os dados com a tag correspondente cheguem.
     * Lança exceção se o servidor responder com STATUS_ERR.
     */
    public byte[] receive(int tag) throws IOException, InterruptedException {
        lock.lock();
        try {
            Entry e = pending.get(tag);
            if (e == null) throw new IllegalStateException(
                    "Tag não foi registada: " + tag
            );

            while (e.data == null && error == null) {
                e.cond.await();
            }

            if (error != null) throw error;

            // Verificar se o servidor indicou erro
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

    @Override
    public void close() throws IOException {
        stream.close();
    }
}