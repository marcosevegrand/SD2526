package common;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Implementa a técnica de "framing" sobre um fluxo TCP byte-stream.
 * A intenção é garantir que as mensagens são lidas inteiras e de forma atómica,
 * prefixando-as com o seu tamanho no envio e bloqueando a leitura até que o payload chegue.
 */
public class FramedStream implements AutoCloseable {

    private final Socket socket;  // FIX: Keep reference to socket for proper closing
    private final DataInputStream in;
    private final DataOutputStream out;
    private final ReentrantLock writeLock = new ReentrantLock();
    private final ReentrantLock readLock = new ReentrantLock();

    /**
     * Envolve as streams de um socket em decorators de buffer e estruturação de dados.
     * @param socket O socket ativo para a comunicação.
     * @throws IOException Se falhar a obtenção das streams do socket.
     */
    public FramedStream(Socket socket) throws IOException {
        this.socket = socket;  // FIX: Store socket reference
        this.in = new DataInputStream(
                new BufferedInputStream(socket.getInputStream())
        );
        this.out = new DataOutputStream(
                new BufferedOutputStream(socket.getOutputStream())
        );
    }

    /**
     * Envia um frame completo de forma atómica para a rede.
     * O lock de escrita é essencial para evitar que bytes de threads diferentes se misturem no buffer.
     * @param tag ID de correlação.
     * @param type Tipo de mensagem.
     * @param payload Conteúdo binário da mensagem.
     * @throws IOException Se a ligação for interrompida.
     */
    public void send(int tag, int type, byte[] payload) throws IOException {
        writeLock.lock();
        try {
            out.writeInt(tag);
            out.writeInt(type);
            out.writeInt(payload.length);
            out.write(payload);
            // O flush garante que os dados saem do buffer Java para o sistema operativo
            out.flush();
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Reconstrói um frame a partir da rede, aguardando que todos os bytes cheguem.
     * @return Uma instância de Frame contendo os dados lidos.
     * @throws IOException Se houver inconsistência nos dados ou fecho do socket.
     */
    public Frame receive() throws IOException {
        readLock.lock();
        try {
            int tag = in.readInt();
            int type = in.readInt();
            int len = in.readInt();
            byte[] payload = new byte[len];
            // readFully impede que leiamos frames parciais devido a fragmentação TCP
            in.readFully(payload);
            return new Frame(tag, type, payload);
        } finally {
            readLock.unlock();
        }
    }

    /**
     * Fecha as streams e o socket associado.
     * FIX: Now explicitly closes the underlying socket to ensure proper cleanup.
     * @throws IOException Se houver erro no fecho.
     */
    @Override
    public void close() throws IOException {
        try {
            in.close();
        } catch (IOException ignored) {}
        try {
            out.close();
        } catch (IOException ignored) {}
        // FIX: Explicitly close the socket
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    /**
     * Estrutura de dados imutável para transporte de mensagens no protocolo.
     */
    public static class Frame {

        public final int tag;
        public final int type;
        public final byte[] payload;

        /**
         * @param tag Identificador.
         * @param type Código da operação.
         * @param payload Dados da mensagem.
         */
        public Frame(int tag, int type, byte[] payload) {
            this.tag = tag;
            this.type = type;
            this.payload = payload;
        }
    }
}