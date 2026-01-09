package common;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Implementação de framing sobre streams TCP.
 *
 * Esta classe resolve o problema da natureza orientada a bytes do TCP, onde não
 * existe garantia de que os dados sejam recebidos na mesma granularidade em que
 * foram enviados. Cada mensagem é prefixada com o seu tamanho, permitindo que o
 * receptor saiba exatamente quantos bytes deve ler para reconstruir a mensagem completa.
 *
 * A classe é thread-safe, utilizando locks separados para leitura e escrita,
 * permitindo operações full-duplex simultâneas.
 */
public class FramedStream implements AutoCloseable {

    /** Timeout por omissão para operações de socket (60 segundos). */
    public static final int DEFAULT_TIMEOUT_MS = 60000;

    private final Socket socket;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final ReentrantLock readLock = new ReentrantLock();

    /**
     * Constrói um FramedStream sobre um socket existente com timeout por omissão.
     * As streams são decoradas com buffers para melhorar o desempenho de I/O,
     * reduzindo o número de chamadas de sistema.
     *
     * @param socket Socket TCP ativo para comunicação
     * @throws IOException Se ocorrer erro ao obter as streams do socket
     */
    public FramedStream(Socket socket) throws IOException {
        this(socket, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Constrói um FramedStream sobre um socket existente com timeout configurável.
     *
     * @param socket    Socket TCP ativo para comunicação
     * @param timeoutMs Timeout em milissegundos para operações de leitura (0 = infinito)
     * @throws IOException Se ocorrer erro ao obter as streams do socket
     */
    public FramedStream(Socket socket, int timeoutMs) throws IOException {
        this.socket = socket;

        // Configurar timeout para detetar conexões mortas
        if (timeoutMs > 0) {
            socket.setSoTimeout(timeoutMs);
        }

        // Desativar Nagle's algorithm para reduzir latência
        socket.setTcpNoDelay(true);

        this.in = new DataInputStream(
                new BufferedInputStream(socket.getInputStream())
        );
        this.out = new DataOutputStream(
                new BufferedOutputStream(socket.getOutputStream())
        );
    }

    /**
     * Envia um frame completo de forma atómica.
     *
     * O frame é composto por:
     *   - Tag (4 bytes) - identificador de correlação para respostas
     *   - Tipo (4 bytes) - código da operação conforme Protocol
     *   - Tamanho (4 bytes) - comprimento do payload
     *   - Payload (n bytes) - dados da mensagem
     *
     * O lock de escrita garante que frames de diferentes threads não se intercalam.
     *
     * @param tag     Identificador único do pedido para correlação
     * @param type    Código da operação
     * @param payload Dados binários da mensagem
     * @throws IOException Se ocorrer erro de escrita na rede ou timeout
     */
    public void send(int tag, int type, byte[] payload) throws IOException {
        writeLock.lock();
        try {
            out.writeInt(tag);
            out.writeInt(type);
            out.writeInt(payload.length);
            out.write(payload);
            out.flush();
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Recebe um frame completo da rede.
     * Bloqueia até que todos os bytes do frame estejam disponíveis ou até
     * ocorrer timeout. O método readFully garante que não são devolvidos
     * frames parciais devido a fragmentação TCP.
     *
     * @return Frame contendo tag, tipo e payload
     * @throws IOException            Se ocorrer erro de leitura ou fecho de conexão
     * @throws SocketTimeoutException Se o timeout for atingido durante a leitura
     */
    public Frame receive() throws IOException {
        readLock.lock();
        try {
            int tag = in.readInt();
            int type = in.readInt();
            int len = in.readInt();
            byte[] payload = new byte[len];
            in.readFully(payload);
            return new Frame(tag, type, payload);
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Verifica se o socket está fechado.
     *
     * @return true se o socket estiver fechado
     */
    public boolean isClosed() {
        return socket == null || socket.isClosed();
    }

    /**
     * Encerra todas as streams e o socket subjacente.
     *
     * @throws IOException Se ocorrer erro durante o fecho
     */
    @Override
    public void close() throws IOException {
        try {
            in.close();
        } catch (IOException ignored) {}
        try {
            out.close();
        } catch (IOException ignored) {}
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    /**
     * Estrutura imutável que representa uma mensagem no protocolo.
     * Encapsula os três componentes de uma mensagem: identificador de correlação,
     * tipo de operação e dados binários.
     */
    public static class Frame {

        /** Identificador único para correlação pedido-resposta. */
        public final int tag;

        /** Código da operação conforme Protocol. */
        public final int type;

        /** Dados binários da mensagem. */
        public final byte[] payload;

        /**
         * Constrói um novo frame.
         *
         * @param tag     Identificador de correlação
         * @param type    Código da operação
         * @param payload Dados da mensagem
         */
        public Frame(int tag, int type, byte[] payload) {
            this.tag = tag;
            this.type = type;
            this.payload = payload;
        }
    }
}