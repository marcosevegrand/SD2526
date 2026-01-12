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
 * ================================================================================
 * DEMULTIPLEXER.JAVA - Multiplexador de Respostas para Cliente Multi-threaded
 * ================================================================================
 *
 * PROBLEMA QUE RESOLVE:
 * ---------------------
 * Queremos que múltiplas threads de aplicação possam usar a MESMA conexão TCP
 * para enviar pedidos ao servidor. Mas como sabemos qual resposta pertence a
 * qual thread?
 *
 * CENÁRIO SEM DEMULTIPLEXER:
 * --------------------------
 * Thread A: envia pedido de LOGIN
 * Thread B: envia pedido de ADD_EVENT
 * Thread A: lê do socket... recebe resposta do ADD_EVENT! (ERRO!)
 *
 * O problema é que as respostas chegam pela mesma "pipe" e podem chegar
 * em qualquer ordem (especialmente se o servidor processar em paralelo).
 *
 * SOLUÇÃO - CORRELAÇÃO POR TAGS:
 * ------------------------------
 * 1. Cada thread gera uma TAG única antes de enviar o pedido
 * 2. O servidor inclui a MESMA tag na resposta
 * 3. Uma thread dedicada (reader thread) lê TODAS as respostas
 * 4. Quando chega uma resposta, a reader thread olha para a tag e
 *    "entrega" a resposta à thread correta
 *
 * ANALOGIA:
 * Imagine um restaurante onde todos os pedidos vão para a cozinha por uma única
 * janela, e a comida sai pela mesma janela. Cada cliente (thread) tem um número
 * (tag). Quando a comida sai, o empregado (reader thread) vê o número e leva
 * ao cliente certo.
 *
 * PADRÃO UTILIZADO:
 * -----------------
 * Este é o padrão "Asynchronous Request-Reply" ou "Correlating Messages".
 * É comum em:
 * - Protocolos de mensagens (AMQP, JMS)
 * - Sistemas RPC (gRPC, Thrift)
 * - Protocolos HTTP/2 (streams multiplexados)
 *
 * BENEFÍCIOS:
 * -----------
 * 1. UMA SÓ CONEXÃO: Menos recursos (sockets, portas, file descriptors)
 * 2. EVITA HEAD-OF-LINE BLOCKING: Uma operação lenta (notificação bloqueante)
 *    não impede que outras operações do mesmo cliente completem
 * 3. PERFORMANCE: Reutilizar conexão evita overhead de TCP handshake
 */
public class Demultiplexer implements AutoCloseable {

    /**
     * Timeout por omissão para aguardar respostas (30 segundos).
     *
     * PORQUÊ 30 SEGUNDOS?
     * - Operações normais (login, add_event) completam em milissegundos
     * - 30s dá margem para rede lenta ou servidor ocupado
     * - Se demorar mais, provavelmente há um problema
     *
     * EXCEÇÃO: Operações bloqueantes (WAIT_SIMUL, WAIT_CONSEC) usam
     * timeouts muito mais longos definidos no ClientLib.
     */
    public static final long DEFAULT_RECEIVE_TIMEOUT_MS = 30000;

    // ==================== CAMPOS DA CLASSE ====================

    /**
     * Canal de comunicação com o servidor.
     * O Demultiplexer usa o FramedStream para:
     * - Enviar frames (delegado diretamente)
     * - Receber frames (na thread de leitura dedicada)
     */
    private final FramedStream stream;

    /**
     * Lock principal para sincronização de estado.
     * Protege: pending, error, closed
     *
     * NOTA: Usamos um único lock porque as operações sobre estes campos
     * são rápidas e precisam de ser atómicas entre si.
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Mapa de pedidos pendentes.
     * Key: tag do pedido
     * Value: Entry com condição e dados da resposta
     *
     * CICLO DE VIDA DE UMA ENTRY:
     * 1. Thread de aplicação chama register(tag) → Entry criada com data=null
     * 2. Thread de aplicação chama send(tag, ...) → Pedido enviado
     * 3. Thread de aplicação chama receive(tag) → Bloqueia na Condition
     * 4. Reader thread recebe resposta → Preenche data, sinaliza Condition
     * 5. Thread de aplicação acorda, lê data, remove entry
     */
    private final Map<Integer, Entry> pending = new HashMap<>();

    /**
     * Erro que ocorreu na thread de leitura, se algum.
     *
     * PROPÓSITO:
     * Se a reader thread encontrar um erro (conexão fechada, etc.),
     * guarda aqui para que threads de aplicação possam ver e re-lançar.
     *
     * VOLATILE:
     * Garante que alterações são visíveis a outras threads imediatamente,
     * sem necessidade de adquirir o lock para verificar.
     */
    private volatile IOException error = null;

    /**
     * Flag que indica se o Demultiplexer foi fechado.
     *
     * VOLATILE:
     * Threads podem verificar este valor sem adquirir o lock, útil para
     * loops que verificam continuamente se devem terminar.
     */
    private volatile boolean closed = false;

    /**
     * Referência à thread de leitura dedicada.
     * Guardamos para poder fazer join() no close() e esperar que termine.
     */
    private Thread readerThread = null;

    // ==================== CLASSE INTERNA: ENTRY ====================

    /**
     * Contentor para estado de um pedido pendente.
     *
     * Cada pedido em curso tem uma Entry associada que:
     * 1. Guarda a Condition onde a thread de aplicação espera
     * 2. Recebe os dados quando a resposta chega
     * 3. Guarda o tipo da resposta para detetar erros
     */
    private static class Entry {
        /**
         * Dados da resposta, preenchidos pela reader thread.
         * null enquanto a resposta não chegou.
         */
        byte[] data;

        /**
         * Tipo da resposta (STATUS_OK ou STATUS_ERR).
         * Permite à thread de aplicação saber se houve erro.
         */
        int type;

        /**
         * Condition associada a este pedido.
         * A thread de aplicação espera nesta condition.
         * A reader thread sinaliza quando a resposta chega.
         *
         * PORQUÊ UMA CONDITION POR ENTRY?
         * Permite acordar APENAS a thread que espera esta resposta específica,
         * em vez de acordar TODAS as threads (que seria o caso com um único
         * Object.notifyAll()).
         */
        final Condition cond;

        /**
         * Cria uma nova entry com a Condition fornecida.
         *
         * @param c Condition criada a partir do lock principal
         */
        Entry(Condition c) {
            this.cond = c;
        }
    }

    // ==================== CONSTRUTOR ====================

    /**
     * Constrói um demultiplexador sobre um FramedStream.
     *
     * NOTA: Não inicia a thread de leitura automaticamente.
     * Deve chamar start() depois de construir.
     *
     * @param stream Canal de comunicação framed já estabelecido
     */
    public Demultiplexer(FramedStream stream) {
        this.stream = stream;
    }

    // ==================== INICIAÇÃO ====================

    /**
     * Inicia a thread de leitura dedicada.
     *
     * Esta thread executa em loop infinito:
     * 1. Lê um frame do servidor
     * 2. Procura a Entry correspondente à tag
     * 3. Preenche os dados e sinaliza a Condition
     * 4. Repete
     *
     * TRATAMENTO DE TIMEOUT:
     * O SocketTimeoutException não é erro - apenas significa que passou o
     * tempo de timeout sem receber dados. Aproveitamos para verificar se
     * devemos fechar e depois continuamos a esperar.
     *
     * TRATAMENTO DE ERROS:
     * Qualquer outro IOException significa problema sério (conexão fechada,
     * erro de rede). Guardamos o erro e notificamos TODAS as threads pendentes.
     */
    public void start() {
        readerThread = new Thread(() -> {
            try {
                // Loop principal - continua até ser fechado ou haver erro
                while (!closed && !stream.isClosed()) {
                    try {
                        // ===== LEITURA BLOQUEANTE =====
                        // Bloqueia até receber um frame ou timeout
                        FramedStream.Frame frame = stream.receive();

                        // ===== ENTREGA DO FRAME =====
                        // Encontra a thread que espera esta resposta e acorda-a
                        deliverFrame(frame);

                    } catch (java.net.SocketTimeoutException e) {
                        // ===== TIMEOUT - NÃO É ERRO =====
                        // Apenas passou o tempo sem receber dados.
                        // Verificamos se devemos fechar e continuamos.
                        // Isto permite "polling" periódico do estado closed.
                        if (!closed) {
                            continue;  // Volta ao início do loop
                        }
                        break;  // Se fechado, sai do loop
                    }
                }
            } catch (IOException e) {
                // ===== ERRO DE I/O =====
                // Só tratamos como erro se não foi pedido para fechar
                // (fechar o stream causa IOException que é esperada)
                if (!closed) {
                    handleReaderError(e);
                }
            }
        }, "Demultiplexer-Reader");  // Nome da thread para debugging

        // ===== CONFIGURAR COMO DAEMON =====
        // Threads daemon não impedem a JVM de terminar.
        // Se a aplicação principal terminar, esta thread morre automaticamente.
        // Isto evita que a aplicação fique "pendurada" por causa desta thread.
        readerThread.setDaemon(true);

        // Iniciar a thread
        readerThread.start();
    }

    // ==================== ENTREGA DE FRAMES ====================

    /**
     * Entrega um frame à thread que o aguarda.
     *
     * PROCESSO:
     * 1. Adquire o lock (para aceder ao mapa de forma segura)
     * 2. Procura a Entry pela tag
     * 3. Se encontrada, preenche os dados e sinaliza
     * 4. Se não encontrada, ignora (pode ser resposta órfã após timeout)
     *
     * @param frame Frame recebido do servidor
     */
    private void deliverFrame(FramedStream.Frame frame) {
        lock.lock();
        try {
            // Procurar a Entry correspondente a esta tag
            Entry e = pending.get(frame.tag);

            if (e != null) {
                // ===== ENTREGAR DADOS =====
                // Preencher os campos da Entry
                e.data = frame.payload;
                e.type = frame.type;

                // ===== ACORDAR A THREAD =====
                // signal() acorda UMA thread que espera nesta Condition
                // (que é a thread que fez o pedido com esta tag)
                e.cond.signal();
            }
            // Se e == null, a resposta chegou mas ninguém espera por ela.
            // Pode acontecer se o cliente deu timeout e desistiu.
            // Simplesmente ignoramos.

        } finally {
            lock.unlock();
        }
    }

    // ==================== TRATAMENTO DE ERROS ====================

    /**
     * Trata erros na thread de leitura, notificando todas as threads pendentes.
     *
     * PORQUÊ NOTIFICAR TODAS?
     * Se a reader thread morre, NENHUM pedido vai receber resposta.
     * Todas as threads de aplicação precisam ser acordadas para ver o erro
     * e poderem lançar exceção para o código que as chamou.
     *
     * @param e Exceção que causou a falha
     */
    private void handleReaderError(IOException e) {
        lock.lock();
        try {
            // Guardar o erro para threads futuras também o verem
            this.error = e;

            // ===== ACORDAR TODAS AS THREADS =====
            // Usamos signalAll() em cada Condition porque cada thread
            // está a esperar na sua própria Condition
            for (Entry entry : pending.values()) {
                entry.cond.signalAll();
            }
        } finally {
            lock.unlock();
        }

        // Log do erro para debugging
        System.err.println("[Demultiplexer] Thread de leitura terminou com erro: " + e.getMessage());
    }

    // ==================== REGISTO DE TAGS ====================

    /**
     * Regista uma tag para receber resposta.
     *
     * PORQUÊ REGISTAR ANTES DE ENVIAR?
     * Existe uma race condition potencial:
     *
     * 1. Thread A envia pedido (tag=5)
     * 2. Servidor processa MUITO rápido
     * 3. Reader thread recebe resposta (tag=5)
     * 4. Reader thread procura Entry para tag=5... NÃO EXISTE!
     * 5. Thread A só agora chama receive() e regista tag=5
     * 6. Resposta foi perdida!
     *
     * SOLUÇÃO:
     * Registar a tag ANTES de enviar garante que quando a resposta
     * chegar, a Entry já existe para a receber.
     *
     * @param tag Identificador único do pedido
     */
    public void register(int tag) {
        lock.lock();
        try {
            // putIfAbsent: só adiciona se a tag não existir
            // (previne duplicados se chamado múltiplas vezes)
            //
            // lock.newCondition() cria uma Condition associada a este lock
            // Só pode ser usada enquanto o lock está held
            pending.putIfAbsent(tag, new Entry(lock.newCondition()));
        } finally {
            lock.unlock();
        }
    }

    // ==================== ENVIO ====================

    /**
     * Envia um frame através do canal partilhado.
     *
     * Este método é um wrapper simples que delega ao FramedStream.
     * O FramedStream já trata da sincronização de escritas.
     *
     * @param tag  Identificador do pedido
     * @param type Código da operação
     * @param data Payload serializado
     * @throws IOException Se ocorrer erro de escrita
     */
    public void send(int tag, int type, byte[] data) throws IOException {
        stream.send(tag, type, data);
    }

    // ==================== RECEÇÃO ====================

    /**
     * Aguarda a resposta para uma tag específica com timeout por omissão.
     *
     * Versão de conveniência que usa o timeout padrão de 30 segundos.
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
            // Converter TimeoutException para IOException para API mais simples
            throw new IOException("Timeout ao aguardar resposta para tag " + tag, e);
        }
    }

    /**
     * Aguarda a resposta para uma tag específica com timeout configurável.
     *
     * Este é o método principal de receção. A thread de aplicação:
     * 1. Adquire o lock
     * 2. Encontra a sua Entry
     * 3. Entra num loop de espera até:
     *    - Os dados chegarem (e.data != null)
     *    - Ocorrer erro na reader thread (error != null)
     *    - O Demultiplexer ser fechado (closed)
     *    - O timeout expirar
     * 4. Verifica condições de erro
     * 5. Retorna os dados
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
            // ===== OBTER A ENTRY =====
            Entry e = pending.get(tag);
            if (e == null) {
                // Isto é um bug de programação - register() não foi chamado
                throw new IllegalStateException("Tag não foi registada: " + tag);
            }

            // ===== CALCULAR DEADLINE =====
            // Usamos nanosegundos para maior precisão
            // System.nanoTime() é mais preciso que System.currentTimeMillis()
            // para medir intervalos de tempo
            long deadlineNanos = (timeoutMs > 0)
                    ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
                    : Long.MAX_VALUE;  // 0 = sem timeout = espera infinita

            // ===== LOOP DE ESPERA =====
            // Condições para continuar a esperar:
            // 1. e.data == null → resposta ainda não chegou
            // 2. error == null → reader thread ainda não falhou
            // 3. !closed → Demultiplexer ainda está ativo
            while (e.data == null && error == null && !closed) {
                if (timeoutMs > 0) {
                    // ===== ESPERA COM TIMEOUT =====
                    long remainingNanos = deadlineNanos - System.nanoTime();

                    if (remainingNanos <= 0) {
                        // Timeout expirou!
                        // Limpar a Entry para não deixar lixo
                        pending.remove(tag);
                        throw new TimeoutException(
                                "Timeout ao aguardar resposta (tag=" + tag + ", timeout=" + timeoutMs + "ms)"
                        );
                    }

                    // awaitNanos: espera até ser sinalizado OU até o tempo passar
                    // Retorna o tempo restante (ou negativo se expirou)
                    // NOTA: Pode haver spurious wakeups, por isso usamos loop
                    e.cond.awaitNanos(remainingNanos);
                } else {
                    // ===== ESPERA INFINITA =====
                    // Bloqueia até ser sinalizado
                    e.cond.await();
                }
            }

            // ===== VERIFICAR CONDIÇÕES DE SAÍDA =====
            // Saímos do loop - mas porquê? Verificar cada condição.

            if (closed) {
                // O Demultiplexer foi fechado enquanto esperávamos
                pending.remove(tag);
                throw new IOException("Demultiplexer foi fechado");
            }

            if (error != null) {
                // A reader thread encontrou um erro
                pending.remove(tag);
                throw error;  // Re-lançar o erro para o chamador
            }

            // ===== VERIFICAR ERRO DO SERVIDOR =====
            // Mesmo que a resposta tenha chegado, pode ser uma resposta de erro
            if (e.type == Protocol.STATUS_ERR) {
                // O servidor enviou STATUS_ERR com mensagem de erro no payload
                String msg = e.data.length > 0 ? new String(e.data) : "Erro do servidor";
                pending.remove(tag);
                throw new IOException(msg);
            }

            // ===== SUCESSO =====
            // A resposta chegou e é STATUS_OK
            byte[] result = e.data;
            pending.remove(tag);  // Limpar a Entry
            return result;

        } finally {
            lock.unlock();
        }
    }

    // ==================== MÉTODOS DE ESTADO ====================

    /**
     * Verifica se o demultiplexer está ativo e a funcionar.
     *
     * ÚTIL PARA:
     * - Health checks
     * - Decidir se vale a pena tentar enviar pedidos
     * - Debugging
     *
     * @return true se estiver operacional
     */
    public boolean isHealthy() {
        return !closed &&                          // Não foi fechado
                error == null &&                     // Sem erros
                readerThread != null &&              // Thread existe
                readerThread.isAlive();              // Thread está a correr
    }

    // ==================== ENCERRAMENTO ====================

    /**
     * Encerra o canal de comunicação e a thread de leitura.
     *
     * PROCESSO DE ENCERRAMENTO:
     * 1. Marcar como fechado (closed = true)
     * 2. Acordar todas as threads pendentes (para verem que fechámos)
     * 3. Fechar o stream (causa IOException na reader thread)
     * 4. Esperar que a reader thread termine (join com timeout)
     *
     * @throws IOException Se ocorrer erro ao fechar
     */
    @Override
    public void close() throws IOException {
        // ===== MARCAR COMO FECHADO =====
        closed = true;

        // ===== ACORDAR THREADS PENDENTES =====
        lock.lock();
        try {
            for (Entry entry : pending.values()) {
                // signalAll() porque pode haver múltiplas threads
                // a esperar na mesma Condition (embora seja raro)
                entry.cond.signalAll();
            }
        } finally {
            lock.unlock();
        }

        // ===== FECHAR O STREAM =====
        // Isto vai causar uma IOException na reader thread se ela
        // estiver bloqueada em receive(). Ela vai apanhar a exceção,
        // ver que closed=true, e terminar graciosamente.
        stream.close();

        // ===== ESPERAR PELA READER THREAD =====
        if (readerThread != null) {
            try {
                // join(1000): espera no máximo 1 segundo
                // Se a thread não terminar em 1s, continuamos anyway
                // (não queremos bloquear indefinidamente o close())
                readerThread.join(1000);
            } catch (InterruptedException ignored) {
                // Se formos interrompidos durante o join, restaurar flag
                Thread.currentThread().interrupt();
            }
        }
    }
}