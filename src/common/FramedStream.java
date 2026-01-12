package common;

import java.io.*;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ================================================================================
 * FRAMEDSTREAM.JAVA - Implementação de Framing sobre TCP
 * ================================================================================
 *
 * PROBLEMA QUE RESOLVE:
 * ---------------------
 * O TCP é um protocolo de STREAM de bytes, não de mensagens. Isto significa que:
 *
 * 1. Se o cliente enviar "HELLO" seguido de "WORLD", o servidor pode receber:
 *    - "HELLOWORLD" (tudo junto)
 *    - "HE" + "LLOWOR" + "LD" (fragmentado)
 *    - "HELLO" + "WORLD" (por sorte)
 *
 * 2. Não há delimitação natural entre mensagens - o TCP apenas garante que os
 *    bytes chegam na ordem correta e sem erros.
 *
 * SOLUÇÃO - MESSAGE FRAMING:
 * --------------------------
 * Cada mensagem é prefixada com metadados que permitem reconstruí-la:
 *
 * +--------+--------+--------+------------------+
 * |  TAG   |  TYPE  | LENGTH |     PAYLOAD      |
 * | 4 bytes| 4 bytes| 4 bytes|   LENGTH bytes   |
 * +--------+--------+--------+------------------+
 *
 * - TAG: Identificador único para correlacionar pedidos com respostas
 *        (essencial para o Demultiplexer)
 * - TYPE: Código da operação (definido em Protocol.java)
 * - LENGTH: Tamanho do payload em bytes
 * - PAYLOAD: Dados da mensagem (formato depende do TYPE)
 *
 * O receptor:
 * 1. Lê 4 bytes → tag
 * 2. Lê 4 bytes → type
 * 3. Lê 4 bytes → length
 * 4. Lê exatamente 'length' bytes → payload
 *
 * Assim, mesmo que o TCP fragmente os dados, conseguimos reconstruir mensagens
 * completas porque sabemos exatamente quantos bytes esperar.
 *
 * THREAD-SAFETY:
 * --------------
 * A classe usa DOIS locks separados (writeLock e readLock) em vez de um único.
 *
 * PORQUÊ?
 * TCP é full-duplex - podemos ler e escrever simultaneamente no mesmo socket.
 * Se usássemos um único lock, uma thread a escrever bloquearia outra a ler,
 * desperdiçando esta capacidade e criando potenciais deadlocks.
 *
 * Com locks separados:
 * - Thread A pode enviar enquanto Thread B recebe
 * - Múltiplas threads que querem enviar são serializadas (writeLock)
 * - Múltiplas threads que querem receber são serializadas (readLock)
 *
 * NOTA: Na prática, apenas uma thread lê (a thread do Demultiplexer) e
 * múltiplas threads podem escrever (as threads de aplicação).
 */
public class FramedStream implements AutoCloseable {

    /**
     * Timeout por omissão para operações de socket (60 segundos).
     *
     * PORQUÊ 60 SEGUNDOS?
     * - Suficientemente longo para operações normais
     * - Suficientemente curto para detetar conexões mortas
     * - Se um cliente não responder em 60s, provavelmente há problemas de rede
     *
     * NOTA: Para operações bloqueantes (notificações), o ClientLib usa
     * timeouts muito mais longos (24 horas).
     */
    public static final int DEFAULT_TIMEOUT_MS = 60000;

    // ==================== CAMPOS DA CLASSE ====================

    /**
     * Socket TCP subjacente.
     * Guardamos referência para poder verificar se está fechado e para
     * fechar no método close().
     */
    private final Socket socket;

    /**
     * Stream de entrada decorada com DataInputStream.
     *
     * DECORAÇÃO:
     * socket.getInputStream() → BufferedInputStream → DataInputStream
     *
     * - BufferedInputStream: Reduz chamadas de sistema ao ler em blocos
     *   (por defeito 8KB). Sem buffer, cada readInt() faria 4 chamadas
     *   de sistema separadas.
     *
     * - DataInputStream: Fornece métodos convenientes como readInt(),
     *   readUTF(), readFully() que abstraem a conversão de bytes.
     */
    private final DataInputStream in;

    /**
     * Stream de saída decorada com DataOutputStream.
     *
     * DECORAÇÃO:
     * socket.getOutputStream() → BufferedOutputStream → DataOutputStream
     *
     * - BufferedOutputStream: Acumula escritas pequenas antes de enviar.
     *   IMPORTANTE: Requer flush() explícito para garantir envio!
     *
     * - DataOutputStream: Métodos como writeInt(), writeUTF() para
     *   serialização portável (big-endian, formato definido).
     */
    private final DataOutputStream out;

    /**
     * Lock para operações de escrita.
     * Garante que frames de diferentes threads não se intercalam.
     *
     * EXEMPLO DO PROBLEMA SEM LOCK:
     * Thread A escreve: [tag1][type1][len1]
     * Thread B interrompe e escreve: [tag2][type2]
     * Thread A continua: [payload1]
     * Resultado: Frame corrompido impossível de decodificar
     */
    private final ReentrantLock writeLock = new ReentrantLock();

    /**
     * Lock para operações de leitura.
     * Na nossa arquitetura, apenas a thread do Demultiplexer lê, mas
     * mantemos o lock para segurança e possíveis extensões futuras.
     */
    private final ReentrantLock readLock = new ReentrantLock();

    // ==================== CONSTRUTORES ====================

    /**
     * Constrói um FramedStream sobre um socket existente com timeout por omissão.
     *
     * @param socket Socket TCP ativo para comunicação
     * @throws IOException Se ocorrer erro ao obter as streams do socket
     *
     * NOTA: O socket deve estar já conectado. Esta classe não faz a conexão.
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
        // Guardamos referência ao socket para poder fechá-lo depois
        this.socket = socket;

        // ===== CONFIGURAÇÃO DO TIMEOUT =====
        // setSoTimeout define quanto tempo read() bloqueia antes de lançar
        // SocketTimeoutException. Isto é CRUCIAL para:
        // 1. Detetar clientes que desconectaram sem fechar a conexão
        // 2. Permitir que o servidor verifique periodicamente se deve terminar
        // 3. Evitar bloqueios indefinidos em caso de problemas de rede
        if (timeoutMs > 0) {
            socket.setSoTimeout(timeoutMs);
        }

        // ===== DESATIVAR NAGLE'S ALGORITHM =====
        // O algoritmo de Nagle agrupa pequenas escritas para enviar menos
        // pacotes TCP. Isto AUMENTA A LATÊNCIA porque espera:
        // - Até acumular dados suficientes, OU
        // - Até receber ACK do pacote anterior
        //
        // Para um protocolo interativo de pedido-resposta, queremos que
        // cada mensagem seja enviada IMEDIATAMENTE, por isso desativamos.
        //
        // TRADE-OFF: Mais pacotes pequenos = mais overhead de rede, mas
        // latência muito menor para mensagens pequenas.
        socket.setTcpNoDelay(true);

        // ===== CONSTRUÇÃO DAS STREAMS DECORADAS =====
        // A ordem é importante: primeiro criar os buffers sobre as streams
        // do socket, depois os DataStreams sobre os buffers.
        this.in = new DataInputStream(
                new BufferedInputStream(socket.getInputStream())
        );
        this.out = new DataOutputStream(
                new BufferedOutputStream(socket.getOutputStream())
        );
    }

    // ==================== OPERAÇÕES DE ENVIO ====================

    /**
     * Envia um frame completo de forma atómica.
     *
     * FORMATO DO FRAME:
     * +--------+--------+--------+------------------+
     * |  TAG   |  TYPE  | LENGTH |     PAYLOAD      |
     * | 4 bytes| 4 bytes| 4 bytes|   LENGTH bytes   |
     * +--------+--------+--------+------------------+
     *
     * @param tag     Identificador único do pedido para correlação
     * @param type    Código da operação (ver Protocol.java)
     * @param payload Dados binários da mensagem
     * @throws IOException Se ocorrer erro de escrita na rede ou timeout
     *
     * THREAD-SAFETY:
     * O writeLock garante que mesmo que múltiplas threads chamem send()
     * simultaneamente, cada frame é escrito completamente antes do próximo.
     *
     * ATOMICIDADE:
     * Do ponto de vista do receptor, um frame ou chega completo ou não chega.
     * Não há frames "parciais" porque:
     * 1. O lock impede intercalação de escritas
     * 2. O flush() no final força o envio de tudo
     */
    public void send(int tag, int type, byte[] payload) throws IOException {
        // Adquirir lock ANTES de qualquer escrita
        writeLock.lock();
        try {
            // ===== ESCRITA DO CABEÇALHO =====
            // writeInt() escreve 4 bytes em big-endian (network byte order)
            // Este é o formato padrão para protocolos de rede.
            out.writeInt(tag);              // 4 bytes: identificador de correlação
            out.writeInt(type);             // 4 bytes: código da operação
            out.writeInt(payload.length);   // 4 bytes: tamanho do payload

            // ===== ESCRITA DO PAYLOAD =====
            // write(byte[]) escreve todos os bytes do array
            out.write(payload);

            // ===== FLUSH OBRIGATÓRIO =====
            // Como usamos BufferedOutputStream, os dados podem ficar no buffer
            // até acumular o suficiente. O flush() força o envio imediato.
            // SEM FLUSH: O cliente poderia ficar à espera eternamente porque
            // a resposta está "presa" no buffer do servidor.
            out.flush();
        } finally {
            // SEMPRE libertar o lock, mesmo em caso de exceção
            writeLock.unlock();
        }
    }

    // ==================== OPERAÇÕES DE RECEÇÃO ====================

    /**
     * Recebe um frame completo da rede.
     *
     * COMPORTAMENTO BLOQUEANTE:
     * Este método bloqueia até que:
     * 1. Um frame completo seja recebido, OU
     * 2. O timeout expire (lança SocketTimeoutException), OU
     * 3. A conexão seja fechada (lança EOFException), OU
     * 4. Ocorra erro de I/O (lança IOException)
     *
     * @return Frame contendo tag, tipo e payload
     * @throws IOException            Se ocorrer erro de leitura ou fecho de conexão
     * @throws SocketTimeoutException Se o timeout for atingido durante a leitura
     *
     * NOTA SOBRE readFully():
     * O método readFully() do DataInputStream é ESSENCIAL aqui. Ele garante
     * que EXATAMENTE o número pedido de bytes é lido, bloqueando se necessário.
     *
     * Diferença entre read(byte[], 0, n) e readFully(byte[]):
     * - read() pode retornar MENOS que n bytes se não houver mais disponíveis
     * - readFully() SEMPRE lê exatamente n bytes ou lança exceção
     *
     * Sem readFully(), teríamos de implementar um loop:
     *   while (bytesLidos < n) {
     *       bytesLidos += in.read(buffer, bytesLidos, n - bytesLidos);
     *   }
     */
    public Frame receive() throws IOException {
        // Adquirir lock de leitura
        // Na nossa arquitetura só uma thread lê, mas é boa prática
        readLock.lock();
        try {
            // ===== LEITURA DO CABEÇALHO =====
            // readInt() lê exatamente 4 bytes e converte de big-endian para int
            // Se a conexão fechar antes de ler 4 bytes, lança EOFException
            int tag = in.readInt();         // Quem enviou este pedido?
            int type = in.readInt();        // Que operação é?
            int len = in.readInt();         // Quantos bytes de dados?

            // ===== LEITURA DO PAYLOAD =====
            // Alocamos um array do tamanho exato e usamos readFully()
            // para garantir que lemos TODOS os bytes do payload.
            byte[] payload = new byte[len];
            in.readFully(payload);          // Bloqueia até ler EXATAMENTE len bytes

            // ===== RETORNO DO FRAME =====
            // Empacotamos tudo num objeto Frame imutável
            return new Frame(tag, type, payload);
        } finally {
            readLock.unlock();
        }
    }

    // ==================== MÉTODOS AUXILIARES ====================

    /**
     * Verifica se o socket está fechado.
     *
     * UTILIDADE:
     * Permite que loops de leitura verifiquem se devem continuar:
     *   while (!stream.isClosed()) {
     *       Frame f = stream.receive();
     *       ...
     *   }
     *
     * @return true se o socket estiver fechado ou for null
     */
    public boolean isClosed() {
        return socket == null || socket.isClosed();
    }

    /**
     * Encerra todas as streams e o socket subjacente.
     *
     * ORDEM DE FECHO:
     * 1. Fechar streams de entrada e saída
     * 2. Fechar o socket
     *
     * TRATAMENTO DE EXCEÇÕES:
     * Ignoramos IOExceptions ao fechar as streams porque:
     * 1. Já estamos em processo de cleanup
     * 2. A stream pode já estar corrompida/fechada
     * 3. Não há nada útil que possamos fazer com o erro
     *
     * O importante é garantir que o socket é fechado para libertar
     * recursos do sistema operativo.
     *
     * @throws IOException Se ocorrer erro ao fechar o socket (raro)
     */
    @Override
    public void close() throws IOException {
        // Tentar fechar a stream de entrada
        try {
            in.close();
        } catch (IOException ignored) {
            // Ignorar - já estamos a fechar tudo
        }

        // Tentar fechar a stream de saída
        try {
            out.close();
        } catch (IOException ignored) {
            // Ignorar - já estamos a fechar tudo
        }

        // Fechar o socket se existir e não estiver já fechado
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
    }

    // ==================== CLASSE INTERNA: FRAME ====================

    /**
     * Estrutura imutável que representa uma mensagem no protocolo.
     *
     * PORQUÊ UMA CLASSE SEPARADA?
     * 1. Encapsula os 3 componentes de uma mensagem de forma limpa
     * 2. Campos públicos finais = simples e eficiente (não precisa de getters)
     * 3. Imutabilidade garante thread-safety sem sincronização
     *
     * PORQUÊ CAMPOS PÚBLICOS?
     * Para uma classe de dados simples e imutável, getters são overhead
     * desnecessário. Os campos são final, por isso não podem ser modificados
     * após a construção.
     *
     * ALTERNATIVA MODERNA (Java 16+):
     * Poderíamos usar um Record:
     *   public record Frame(int tag, int type, byte[] payload) {}
     * Mas mantemos compatibilidade com Java mais antigo.
     */
    public static class Frame {

        /**
         * Identificador único para correlação pedido-resposta.
         *
         * O cliente gera uma tag única para cada pedido e o servidor
         * inclui a mesma tag na resposta. Isto permite que o Demultiplexer
         * encaminhe cada resposta para a thread correta.
         *
         * PORQUÊ NECESSÁRIO?
         * Sem tags, se Thread A envia pedido 1 e Thread B envia pedido 2,
         * quando chega uma resposta não sabemos qual thread deve recebê-la.
         */
        public final int tag;

        /**
         * Código da operação conforme definido em Protocol.java.
         *
         * Para pedidos: indica a operação a executar (LOGIN, ADD_EVENT, etc.)
         * Para respostas: indica o status (STATUS_OK ou STATUS_ERR)
         */
        public final int type;

        /**
         * Dados binários da mensagem.
         *
         * O formato depende do tipo de operação. Por exemplo:
         * - LOGIN: username (UTF) + password (UTF)
         * - ADD_EVENT: produto (UTF) + quantidade (int) + preço (double)
         * - STATUS_OK com agregação: resultado (double)
         *
         * NOTA: O array é armazenado por referência. Para verdadeira
         * imutabilidade, deveríamos clonar, mas isso teria custo de
         * performance. Confiamos que quem usa a classe não modifica.
         */
        public final byte[] payload;

        /**
         * Constrói um novo frame.
         *
         * @param tag     Identificador de correlação
         * @param type    Código da operação
         * @param payload Dados da mensagem
         */
        public Frame(int tag, int type, byte[] payload) {
            this.tagpackage common;

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
} = tag;
            this.type = type;
            this.payload = payload;
        }
    }
}