package client;

import common.FramedStream;
import common.Protocol;
import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ================================================================================
 * CLIENTLIB.JAVA - Biblioteca Cliente de Alto Nível
 * ================================================================================
 *
 * PAPEL NO SISTEMA:
 * -----------------
 * Esta classe é a API pública que as aplicações cliente usam. Ela ABSTRAI toda
 * a complexidade de:
 * - Comunicação de rede (sockets, TCP)
 * - Protocolo binário (framing, serialização)
 * - Concorrência (demultiplexing, timeouts)
 *
 * O utilizador da biblioteca apenas chama métodos como login(), addEvent(),
 * getAggregation() sem se preocupar com os detalhes de implementação.
 *
 * ARQUITETURA:
 * ------------
 *                                   +-----------------+
 *     Aplicação (UI.java)  ------> |   ClientLib     |
 *                                   +-----------------+
 *                                          |
 *                                          v
 *                                   +-----------------+
 *                                   |  Demultiplexer  | ← Thread de leitura dedicada
 *                                   +-----------------+
 *                                          |
 *                                          v
 *                                   +-----------------+
 *                                   |  FramedStream   |
 *                                   +-----------------+
 *                                          |
 *                                          v
 *                                   +-----------------+
 *                                   |     Socket      | ← Conexão TCP
 *                                   +-----------------+
 *                                          |
 *                                          v
 *                                      [Servidor]
 *
 * THREAD-SAFETY:
 * --------------
 * A classe é completamente thread-safe. Múltiplas threads de aplicação podem
 * chamar métodos simultaneamente sobre a mesma instância de ClientLib.
 *
 * Isto é conseguido através de:
 * 1. Demultiplexer que gere concorrência nas respostas
 * 2. tagLock que protege a geração de tags únicas
 * 3. FramedStream que protege escritas no socket
 *
 * PADRÃO DE USO TÍPICO:
 * ---------------------
 * try (ClientLib client = new ClientLib("localhost", 12345)) {
 *     if (client.login("user", "pass")) {
 *         client.addEvent("produto", 10, 5.99);
 *         double total = client.getAggregation(Protocol.AGGR_VOL, "produto", 30);
 *     }
 * }  // Auto-close fecha a conexão
 */
public class ClientLib implements AutoCloseable {

    // ==================== CONSTANTES DE TIMEOUT ====================

    /**
     * Timeout para operações normais (30 segundos).
     *
     * JUSTIFICAÇÃO:
     * - Login, addEvent, agregações: tipicamente < 1 segundo
     * - 30 segundos dá margem para rede lenta ou servidor ocupado
     * - Se demorar mais, algo está errado (rede partida, servidor morto)
     */
    private static final long DEFAULT_TIMEOUT_MS = 30000;

    /**
     * Timeout para operações bloqueantes como notificações (24 horas).
     *
     * PORQUÊ 24 HORAS?
     * As operações waitSimultaneous() e waitConsecutive() podem bloquear
     * durante todo um dia de trading. O dia só termina quando alguém
     * chama newDay(). Em sistemas reais isto pode levar horas.
     *
     * 24 horas é um limite razoável que:
     * - Permite esperas longas legítimas
     * - Não bloqueia para sempre se algo correr mal
     */
    private static final long BLOCKING_TIMEOUT_MS = 24 * 60 * 60 * 1000;

    // ==================== CAMPOS DA CLASSE ====================

    /**
     * Demultiplexer que gere a comunicação com o servidor.
     *
     * Responsável por:
     * - Manter a thread de leitura dedicada
     * - Correlacionar respostas com pedidos através de tags
     * - Gerir timeouts de receção
     */
    private final Demultiplexer demux;

    /**
     * Contador para gerar tags únicas.
     *
     * ESTRATÉGIA:
     * Incrementamos sequencialmente. Como é protegido por lock, cada
     * pedido recebe um número único na ordem em que foi feito.
     *
     * OVERFLOW:
     * Em teoria, após 2^31 pedidos o contador faz wrap-around.
     * Na prática, 2 mil milhões de pedidos levaria anos de operação
     * contínua, e mesmo com wrap-around funciona (as tags antigas
     * já foram removidas do mapa de pending).
     */
    private int tagCounter = 0;

    /**
     * Lock para proteger a geração de tags.
     *
     * PORQUÊ LOCK SEPARADO?
     * A geração de tags deve ser atómica, mas queremos que múltiplas
     * threads possam gerar tags em paralelo (é uma operação muito rápida).
     * Usar o lock do Demultiplexer seria demasiado restritivo.
     *
     * ALTERNATIVA:
     * Poderíamos usar AtomicInteger.incrementAndGet(), mas o ReentrantLock
     * é mais explícito e permite extensão futura se precisarmos de
     * sincronizar mais lógica.
     */
    private final ReentrantLock tagLock = new ReentrantLock();

    // ==================== CONSTRUTOR ====================

    /**
     * Estabelece ligação com o servidor remoto.
     *
     * PROCESSO DE CONEXÃO:
     * 1. Criar Socket TCP para o host:port
     * 2. Criar FramedStream sobre o socket
     * 3. Criar Demultiplexer sobre o FramedStream
     * 4. Iniciar a thread de leitura do Demultiplexer
     *
     * @param host Endereço de rede do servidor (hostname ou IP)
     * @param port Porto de escuta do servidor (1024-65535)
     * @throws IOException Se não for possível estabelecer a ligação
     *
     * POSSÍVEIS FALHAS:
     * - UnknownHostException: hostname não existe
     * - ConnectException: servidor não está a correr ou porta bloqueada
     * - SocketTimeoutException: servidor não responde
     * - IOException: outros erros de rede
     */
    public ClientLib(String host, int port) throws IOException {
        // ===== CRIAR CONEXÃO TCP =====
        // new Socket(host, port) faz:
        // 1. Resolução DNS (se host for hostname)
        // 2. TCP 3-way handshake (SYN, SYN-ACK, ACK)
        // 3. Retorna socket conectado ou lança exceção
        Socket socket = new Socket(host, port);

        // ===== CRIAR STACK DE COMUNICAÇÃO =====
        // FramedStream adiciona framing sobre o socket raw
        FramedStream stream = new FramedStream(socket);

        // ===== CRIAR DEMULTIPLEXER =====
        this.demux = new Demultiplexer(stream);

        // ===== INICIAR THREAD DE LEITURA =====
        // A partir daqui, o Demultiplexer está a ler do servidor
        this.demux.start();
    }

    // ==================== GERAÇÃO DE TAGS ====================

    /**
     * Gera um identificador único para correlação de pedidos.
     *
     * GARANTIAS:
     * - Cada chamada retorna um valor diferente
     * - Thread-safe: pode ser chamado de múltiplas threads
     * - Monotonicamente crescente (até overflow)
     *
     * @return Tag única para esta sessão de cliente
     */
    private int nextTag() {
        tagLock.lock();
        try {
            // Pré-incremento: primeiro incrementa, depois retorna
            // Isto garante que tags começam em 1, não em 0
            // (0 poderia ser confundido com "sem tag" em debugging)
            return ++tagCounter;
        } finally {
            tagLock.unlock();
        }
    }

    // ==================== MÉTODO REQUEST (CORE) ====================

    /**
     * Executa o ciclo completo de pedido-resposta com timeout padrão.
     *
     * Este é um método de conveniência que usa DEFAULT_TIMEOUT_MS.
     *
     * @param type Código da operação (Protocol.LOGIN, etc.)
     * @param data Payload serializado em bytes
     * @return Payload da resposta
     * @throws IOException          Se ocorrer erro de rede
     * @throws InterruptedException Se a thread for interrompida
     */
    private byte[] request(int type, byte[] data)
            throws IOException, InterruptedException {
        return request(type, data, DEFAULT_TIMEOUT_MS);
    }

    /**
     * Executa o ciclo completo de pedido-resposta com timeout configurável.
     *
     * ESTE É O MÉTODO CENTRAL DA BIBLIOTECA.
     * Todos os métodos públicos (login, addEvent, etc.) eventualmente
     * chamam este método.
     *
     * SEQUÊNCIA DE OPERAÇÕES:
     * ┌─────────────────────────────────────────────────────────────┐
     * │ 1. Gerar tag única                                         │
     * │ 2. Registar tag no Demultiplexer (ANTES de enviar!)        │
     * │ 3. Enviar frame [tag, type, data]                          │
     * │ 4. Esperar resposta (bloqueante com timeout)               │
     * │ 5. Retornar payload da resposta                            │
     * └─────────────────────────────────────────────────────────────┘
     *
     * PORQUÊ REGISTAR ANTES DE ENVIAR?
     * Evita race condition: se o servidor for muito rápido, a resposta
     * pode chegar ANTES de chamarmos receive(). Se não tivermos registado
     * a tag, a resposta seria perdida.
     *
     * @param type      Código da operação
     * @param data      Payload serializado
     * @param timeoutMs Timeout em milissegundos
     * @return Payload da resposta
     * @throws IOException          Se ocorrer erro de rede ou timeout
     * @throws InterruptedException Se a thread for interrompida
     */
    private byte[] request(int type, byte[] data, long timeoutMs)
            throws IOException, InterruptedException {

        // ===== GERAR TAG ÚNICA =====
        int tag = nextTag();

        // ===== REGISTAR TAG =====
        // Criar entry no mapa de pending ANTES de enviar
        // Assim, quando a resposta chegar, já há onde a guardar
        demux.register(tag);

        try {
            // ===== ENVIAR PEDIDO =====
            // send() é thread-safe (FramedStream usa writeLock)
            demux.send(tag, type, data);

            // ===== AGUARDAR RESPOSTA =====
            // receive() bloqueia até:
            // - Resposta com esta tag chegar
            // - Timeout expirar
            // - Erro de rede
            return demux.receive(tag, timeoutMs);

        } catch (TimeoutException e) {
            // ===== TIMEOUT EXPIROU =====
            // Converter para IOException com mensagem clara
            // O Demultiplexer já limpou a entry do mapa
            throw new IOException("Operação excedeu o tempo limite", e);
        }
        // NOTA: Não precisamos de finally para limpar a tag - o Demultiplexer
        // remove automaticamente a entry quando a resposta é processada ou
        // quando ocorre timeout/erro.
    }

    // ==================== AUTENTICAÇÃO ====================

    /**
     * Regista um novo utilizador no sistema.
     *
     * PROTOCOLO:
     * Envio:    [tag][REGISTER][username UTF][password UTF]
     * Resposta: [tag][STATUS_OK][1 byte: 1=sucesso, 0=existe]
     *
     * @param user Nome de utilizador (deve ser único no sistema)
     * @param pass Palavra-passe
     * @return true se o registo foi bem-sucedido, false se o utilizador já existe
     * @throws IOException          Se ocorrer erro de comunicação
     * @throws InterruptedException Se a operação for interrompida
     *
     * NOTA DE SEGURANÇA:
     * A password é enviada em plain text. Em produção, deveria:
     * 1. Usar TLS/SSL para encriptar a conexão
     * 2. Fazer hash da password no cliente antes de enviar
     * 3. O servidor armazenaria apenas o hash (nunca a password real)
     */
    public boolean register(String user, String pass)
            throws IOException, InterruptedException {

        // ===== SERIALIZAR PAYLOAD =====
        // ByteArrayOutputStream: buffer em memória que cresce automaticamente
        // DataOutputStream: métodos convenientes para escrever tipos Java
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);

        // writeUTF(): escreve string em formato modified UTF-8
        // Formato: [2 bytes length][bytes UTF-8]
        // Máximo: 65535 bytes após codificação
        out.writeUTF(user);
        out.writeUTF(pass);

        // ===== ENVIAR E RECEBER =====
        byte[] res = request(Protocol.REGISTER, baos.toByteArray());

        // ===== INTERPRETAR RESPOSTA =====
        // O servidor envia 1 byte: 1 = sucesso, 0 = user já existe
        return res.length > 0 && res[0] == 1;
    }

    /**
     * Autentica o utilizador para acesso às operações de negócio.
     *
     * PROTOCOLO:
     * Envio:    [tag][LOGIN][username UTF][password UTF]
     * Resposta: [tag][STATUS_OK][1 byte: 1=sucesso, 0=falha]
     *
     * IMPORTANTE:
     * Após login bem-sucedido, o SERVIDOR marca a sessão como autenticada.
     * Esta biblioteca não mantém estado de autenticação - cada pedido
     * é independente do ponto de vista do cliente.
     *
     * @param user Nome de utilizador
     * @param pass Palavra-passe
     * @return true se as credenciais são válidas
     * @throws IOException          Se ocorrer erro de comunicação
     * @throws InterruptedException Se a operação for interrompida
     */
    public boolean login(String user, String pass)
            throws IOException, InterruptedException {

        // Serialização idêntica ao register()
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeUTF(user);
        out.writeUTF(pass);

        byte[] res = request(Protocol.LOGIN, baos.toByteArray());

        // 1 = autenticado, 0 = credenciais inválidas
        return res.length > 0 && res[0] == 1;
    }

    // ==================== OPERAÇÕES DE DADOS ====================

    /**
     * Regista um evento de venda no dia atual.
     *
     * PROTOCOLO:
     * Envio:    [tag][ADD_EVENT][produto UTF][quantidade int][preço double]
     * Resposta: [tag][STATUS_OK][vazio]
     *
     * SEMÂNTICA:
     * - O evento é adicionado ao buffer do dia atual no servidor
     * - Ainda NÃO está persistido em disco
     * - Para persistir, é necessário chamar newDay()
     * - O NotificationManager é notificado para verificar condições
     *
     * @param prod  Identificador do produto (string não vazia)
     * @param qty   Quantidade vendida (tipicamente > 0)
     * @param price Preço unitário (tipicamente > 0)
     * @throws IOException          Se ocorrer erro de comunicação
     * @throws InterruptedException Se a operação for interrompida
     *
     * NOTA: Não validamos os valores localmente porque o servidor pode
     * ter regras de validação diferentes. Confiamos no servidor para
     * rejeitar dados inválidos.
     */
    public void addEvent(String prod, int qty, double price)
            throws IOException, InterruptedException {

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);

        out.writeUTF(prod);     // Nome do produto
        out.writeInt(qty);      // Quantidade (4 bytes, big-endian)
        out.writeDouble(price); // Preço (8 bytes, IEEE 754 double)

        // Resposta vazia - apenas confirmação de que foi recebido
        request(Protocol.ADD_EVENT, baos.toByteArray());
    }

    /**
     * Encerra o dia atual e persiste os dados em disco.
     *
     * PROTOCOLO:
     * Envio:    [tag][NEW_DAY][vazio]
     * Resposta: [tag][STATUS_OK][vazio]
     *
     * EFEITOS NO SERVIDOR:
     * 1. StorageEngine.persistDay():
     *    - Escreve todos os eventos do buffer para ficheiro
     *    - Incrementa o contador de dia
     *    - Limpa o buffer de eventos
     *    - Remove ficheiros fora da janela de retenção D
     *
     * 2. NotificationManager.newDay():
     *    - Acorda TODAS as threads bloqueadas em waitSimultaneous/waitConsecutive
     *    - Limpa o set de produtos vendidos no dia
     *    - Reset do contador de vendas consecutivas
     *
     * @throws IOException          Se ocorrer erro de comunicação
     * @throws InterruptedException Se a operação for interrompida
     *
     * ATOMICIDADE:
     * A operação é atómica no servidor. Se falhar a escrita do ficheiro,
     * o dia NÃO avança e é lançada exceção.
     */
    public void newDay() throws IOException, InterruptedException {
        // Payload vazio - o comando não precisa de parâmetros
        request(Protocol.NEW_DAY, new byte[0]);
    }

    // ==================== AGREGAÇÕES ESTATÍSTICAS ====================

    /**
     * Consulta uma agregação estatística sobre vendas históricas.
     *
     * PROTOCOLO:
     * Envio:    [tag][AGGR_XXX][produto UTF][dias int]
     * Resposta: [tag][STATUS_OK][resultado double]
     *
     * TIPOS DE AGREGAÇÃO:
     * - AGGR_QTY: Soma das quantidades vendidas
     * - AGGR_VOL: Volume financeiro (SUM(qtd * preço))
     * - AGGR_AVG: Preço médio ponderado (VOL / QTY)
     * - AGGR_MAX: Preço unitário máximo
     *
     * @param type Tipo de agregação (Protocol.AGGR_QTY, etc.)
     * @param prod Produto a analisar
     * @param days Número de dias retroativos a incluir [1, D]
     * @return Valor calculado da agregação (0.0 se não houver dados)
     * @throws IOException          Se ocorrer erro de comunicação
     * @throws InterruptedException Se a operação for interrompida
     *
     * EXEMPLO:
     * double volume = client.getAggregation(Protocol.AGGR_VOL, "iPhone", 30);
     * // Retorna o valor total de vendas de iPhones nos últimos 30 dias
     *
     * CACHING:
     * O servidor mantém uma cache de agregações calculadas por (dia, produto).
     * Pedidos repetidos são servidos da cache sem recalcular.
     */
    public double getAggregation(int type, String prod, int days)
            throws IOException, InterruptedException {

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);

        out.writeUTF(prod);     // Produto a consultar
        out.writeInt(days);     // Número de dias

        // Enviar pedido com o tipo de agregação especificado
        byte[] res = request(type, baos.toByteArray());

        // ===== DESSERIALIZAR RESPOSTA =====
        // O servidor envia o resultado como double (8 bytes)
        // DataInputStream permite ler de um byte array
        return new DataInputStream(new ByteArrayInputStream(res)).readDouble();
    }

    // ==================== CONSULTA DE EVENTOS ====================

    /**
     * Recupera eventos filtrados de um dia específico.
     *
     * PROTOCOLO:
     * Envio:    [tag][FILTER][dia int][tam filtro int][produtos UTF...]
     * Resposta: [tag][STATUS_OK][dados comprimidos por dicionário]
     *
     * COMPRESSÃO POR DICIONÁRIO:
     * Para reduzir o tamanho da resposta, o servidor usa uma técnica onde
     * strings de produtos repetidas são substituídas por índices numéricos.
     *
     * Formato da resposta:
     * [tam dicionário int]
     * [string1 UTF][string2 UTF]...[stringN UTF]  ← Dicionário
     * [num eventos int]
     * [idx produto int][quantidade int][preço double]...  ← Eventos
     *
     * EXEMPLO:
     * Se tivermos 1000 vendas de "iPhone", em vez de enviar "iPhone" 1000x
     * (7 bytes cada = 7000 bytes), enviamos:
     * - Dicionário: ["iPhone"] = 9 bytes
     * - 1000x índice 0 = 4000 bytes
     * Total: 4009 bytes vs 7000 bytes = 43% de redução
     *
     * @param day       Dia histórico a consultar [0, currentDay-1]
     * @param filterSet Conjunto de produtos a incluir no resultado
     * @return Lista de strings formatadas com os detalhes das vendas
     * @throws IOException          Se ocorrer erro de comunicação
     * @throws InterruptedException Se a operação for interrompida
     */
    public List<String> getEvents(int day, Set<String> filterSet)
            throws IOException, InterruptedException {

        // ===== SERIALIZAR PEDIDO =====
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);

        out.writeInt(day);                  // Dia a consultar
        out.writeInt(filterSet.size());     // Número de produtos no filtro

        // Escrever cada produto do filtro
        for (String s : filterSet) {
            out.writeUTF(s);
        }

        // ===== ENVIAR E RECEBER =====
        byte[] res = request(Protocol.FILTER, baos.toByteArray());

        // ===== DESSERIALIZAR RESPOSTA =====
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(res));

        // --- Reconstruir o dicionário ---
        int dictSize = in.readInt();
        String[] dictionary = new String[dictSize];
        for (int i = 0; i < dictSize; i++) {
            dictionary[i] = in.readUTF();
        }

        // --- Ler eventos usando o dicionário ---
        int numEvents = in.readInt();
        List<String> events = new ArrayList<>();

        for (int i = 0; i < numEvents; i++) {
            // Ler índice do produto no dicionário
            int prodIndex = in.readInt();
            String p = dictionary[prodIndex];  // Traduzir índice → string

            int q = in.readInt();              // Quantidade
            double pr = in.readDouble();       // Preço

            // Formatar para display
            events.add(p + " | Qtd: " + q + " | Preço: " + pr);
        }

        return events;
    }

    // ==================== NOTIFICAÇÕES BLOQUEANTES ====================

    /**
     * Aguarda a venda simultânea de dois produtos no mesmo dia.
     *
     * PROTOCOLO:
     * Envio:    [tag][WAIT_SIMUL][produto1 UTF][produto2 UTF]
     * Resposta: [tag][STATUS_OK][1 byte: 1=condição, 0=dia terminou]
     *
     * SEMÂNTICA:
     * A thread bloqueia até que AMBOS os produtos tenham sido vendidos
     * pelo menos uma vez no dia atual, OU até que newDay() seja chamado.
     *
     * CASO DE USO:
     * Monitorizar quando dois produtos complementares são vendidos juntos.
     * Ex: esperar que tanto "iPhone" como "AirPods" sejam vendidos.
     *
     * @param p1 Primeiro produto
     * @param p2 Segundo produto
     * @return true se ambos foram vendidos, false se o dia terminou primeiro
     * @throws IOException          Se ocorrer erro de comunicação
     * @throws InterruptedException Se a operação for interrompida
     *
     * ATENÇÃO: Esta operação pode bloquear durante HORAS!
     * Por isso usa BLOCKING_TIMEOUT_MS em vez do timeout padrão.
     *
     * IMPLEMENTAÇÃO NO SERVIDOR:
     * O NotificationManager mantém um Set<String> dos produtos vendidos.
     * Quando uma venda é registada, verifica se ambos os produtos do par
     * estão no set. Se sim, sinaliza a thread bloqueada.
     */
    public boolean waitSimultaneous(String p1, String p2)
            throws IOException, InterruptedException {

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);

        out.writeUTF(p1);   // Primeiro produto
        out.writeUTF(p2);   // Segundo produto

        // ===== ENVIAR COM TIMEOUT ALARGADO =====
        // Usa 24 horas em vez dos 30 segundos padrão
        byte[] res = request(Protocol.WAIT_SIMUL, baos.toByteArray(), BLOCKING_TIMEOUT_MS);

        // 1 = ambos vendidos, 0 = dia terminou
        return res.length > 0 && res[0] == 1;
    }

    /**
     * Aguarda uma sequência de N vendas consecutivas do mesmo produto.
     *
     * PROTOCOLO:
     * Envio:    [tag][WAIT_CONSEC][N int]
     * Resposta: [tag][STATUS_OK][produto UTF ou vazio]
     *
     * SEMÂNTICA:
     * A thread bloqueia até que um produto qualquer seja vendido N vezes
     * CONSECUTIVAS (sem vendas de outros produtos entre elas), OU até
     * que newDay() seja chamado.
     *
     * EXEMPLO:
     * Se N=3 e as vendas forem: A, A, B, B, B, C
     * Retorna "B" porque B teve 3 vendas seguidas.
     *
     * @param n Número mínimo de vendas consecutivas (>= 1)
     * @return Nome do produto que atingiu a meta, ou null se o dia terminou
     * @throws IOException          Se ocorrer erro de comunicação
     * @throws InterruptedException Se a operação for interrompida
     *
     * IMPLEMENTAÇÃO NO SERVIDOR:
     * O NotificationManager mantém:
     * - lastProductSold: último produto vendido
     * - consecutiveCount: quantas vezes seguidas
     * - streaksReached: Map<N, Set<produtos que atingiram N>>
     *
     * O streaksReached permite que threads que comecem a esperar DEPOIS
     * da condição já ter sido cumprida sejam notificadas imediatamente.
     */
    public String waitConsecutive(int n)
            throws IOException, InterruptedException {

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);

        out.writeInt(n);    // Número de vendas consecutivas

        // Enviar com timeout alargado
        byte[] res = request(Protocol.WAIT_CONSEC, baos.toByteArray(), BLOCKING_TIMEOUT_MS);

        // ===== INTERPRETAR RESPOSTA =====
        // Se payload vazio: dia terminou sem atingir a meta
        // Se não vazio: contém o nome do produto vencedor
        if (res.length == 0) {
            return null;
        }
        return new DataInputStream(new ByteArrayInputStream(res)).readUTF();
    }

    // ==================== UTILITÁRIOS ====================

    /**
     * Consulta o dia atual do servidor.
     *
     * PROTOCOLO:
     * Envio:    [tag][GET_CURRENT_DAY][vazio]
     * Resposta: [tag][STATUS_OK][dia int]
     *
     * @return Número do dia corrente (começa em 0)
     * @throws IOException          Se ocorrer erro de comunicação
     * @throws InterruptedException Se a operação for interrompida
     *
     * UTILIDADE:
     * - Mostrar ao utilizador em que dia está
     * - Validar parâmetros antes de enviar pedidos
     * - Debugging
     */
    public int getCurrentDay() throws IOException, InterruptedException {
        byte[] res = request(Protocol.GET_CURRENT_DAY, new byte[0]);
        return new DataInputStream(new ByteArrayInputStream(res)).readInt();
    }

    // ==================== ENCERRAMENTO ====================

    /**
     * Encerra a ligação com o servidor.
     *
     * PROCESSO:
     * 1. Fecha o Demultiplexer
     *    - Para a thread de leitura
     *    - Acorda threads pendentes
     *    - Fecha o FramedStream
     *    - Fecha o socket TCP
     *
     * NOTA: Implementamos AutoCloseable para permitir uso com try-with-resources:
     *   try (ClientLib client = new ClientLib(...)) {
     *       // usar client
     *   }  // close() chamado automaticamente aqui
     *
     * @throws IOException Se ocorrer erro ao fechar a conexão
     */
    @Override
    public void close() throws IOException {
        demux.close();
    }
}