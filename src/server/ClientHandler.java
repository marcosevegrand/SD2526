package server;

import common.FramedStream;
import common.Protocol;
import java.io.*;
import java.net.SocketTimeoutException;
import java.util.*;

/**
 * ================================================================================
 * CLIENTHANDLER.JAVA - Gestor de Sessão Individual
 * ================================================================================
 *
 * PAPEL NO SISTEMA:
 * -----------------
 * Cada cliente conectado tem uma instância dedicada de ClientHandler que:
 * 1. Mantém a conexão TCP aberta
 * 2. Lê pedidos do cliente em loop
 * 3. Delega processamento ao ThreadPool
 * 4. Mantém estado de autenticação da sessão
 *
 * CICLO DE VIDA:
 * --------------
 * ┌──────────────┐     ┌──────────────────┐     ┌──────────────┐
 * │    Server    │────▶│  ClientHandler   │────▶│   Termina    │
 * │   accept()   │     │     run()        │     │   cleanup()  │
 * └──────────────┘     └──────────────────┘     └──────────────┘
 *                              │
 *                              │ Para cada frame:
 *                              ▼
 *                      ┌───────────────────┐
 *                      │    ThreadPool     │
 *                      │  handleRequest()  │
 *                      └───────────────────┘
 *
 * THREAD MODEL:
 * -------------
 * - A thread do ClientHandler apenas LÊ do socket
 * - O PROCESSAMENTO é delegado ao ThreadPool
 * - Isto permite que:
 *   1. Múltiplos pedidos do mesmo cliente sejam processados em paralelo
 *   2. A leitura continue enquanto pedidos anteriores são processados
 *   3. Um pedido lento não bloqueia os seguintes
 *
 * ESTADO DA SESSÃO:
 * -----------------
 * Cada ClientHandler mantém se a sessão está autenticada.
 * Isto é feito com uma variável volatile (thread-safe para reads/writes simples).
 *
 * NOTA SOBRE CONCORRÊNCIA:
 * O campo 'authenticated' pode ser lido pela thread de leitura e escrito
 * por threads do pool (no handleLogin). 'volatile' garante visibilidade
 * mas não atomicidade. Para este caso simples (boolean) é suficiente.
 */
public class ClientHandler implements Runnable {

    // ===== DEPENDÊNCIAS INJETADAS =====
    // Passadas no construtor pelo Server

    /**
     * Canal de comunicação com o cliente específico.
     * Cada ClientHandler tem o seu próprio FramedStream.
     */
    private final FramedStream stream;

    /**
     * Gestor de utilizadores (partilhado entre todos os handlers).
     * Usado para register() e login().
     */
    private final UserManager userManager;

    /**
     * Motor de armazenamento (partilhado).
     * Usado para eventos, agregações, filtros.
     */
    private final StorageEngine storage;

    /**
     * Gestor de notificações (partilhado).
     * Usado para waitSimultaneous() e waitConsecutive().
     */
    private final NotificationManager notify;

    /**
     * Pool de threads para processamento (partilhado).
     * O handler submete tarefas ao pool em vez de processar diretamente.
     */
    private final ThreadPool workerPool;

    /**
     * Janela de retenção em dias.
     * Usado para validação de parâmetros nas agregações e filtros.
     */
    private final int D;

    // ===== ESTADO DA SESSÃO =====

    /**
     * Indica se a sessão está autenticada.
     *
     * VOLATILE porque:
     * - Escrito pela thread do pool (após login bem-sucedido)
     * - Lido pela thread do handler (para verificar autenticação)
     * - Volatile garante que escritas são visíveis imediatamente
     *
     * INICIALMENTE FALSE: cliente começa não autenticado
     */
    private volatile boolean authenticated = false;

    /**
     * Indica se o handler deve continuar a processar pedidos.
     *
     * VOLATILE para permitir que outras threads sinalizem o fim:
     * - sendResponse() marca false se falhar a enviar
     * - cleanup() marca false para garantir que o loop para
     */
    private volatile boolean running = true;

    // ===== CONSTRUTOR =====

    /**
     * Cria um novo handler para um cliente.
     *
     * @param s  Canal de comunicação com o cliente
     * @param um Gestor de utilizadores
     * @param se Motor de armazenamento
     * @param nm Gestor de notificações
     * @param wp Pool de threads para processamento
     * @param D  Janela de retenção em dias
     */
    public ClientHandler(
            FramedStream s,
            UserManager um,
            StorageEngine se,
            NotificationManager nm,
            ThreadPool wp,
            int D
    ) {
        this.stream = s;
        this.userManager = um;
        this.storage = se;
        this.notify = nm;
        this.workerPool = wp;
        this.D = D;
    }

    // ===== CICLO PRINCIPAL =====

    /**
     * Ciclo principal de leitura de pedidos.
     *
     * PADRÃO:
     * 1. Ler frame do cliente (blocking)
     * 2. Submeter ao pool para processamento (non-blocking)
     * 3. Repetir até desconexão ou erro
     *
     * TRATAMENTO DE TIMEOUT:
     * SocketTimeoutException não é erro - apenas significa que passou
     * o tempo de timeout sem receber dados. Verificamos se devemos
     * continuar e voltamos a tentar.
     *
     * TRATAMENTO DE EOF:
     * EOFException significa que o cliente fechou a conexão de forma
     * limpa. Terminamos graciosamente.
     *
     * TRATAMENTO DE OUTROS ERROS:
     * Qualquer outra IOException indica problema de rede. Se ainda
     * estávamos em running=true, logamos o erro.
     */
    @Override
    public void run() {
        try {
            // ===== LOOP DE LEITURA =====
            while (running) {
                try {
                    // ===== LEITURA BLOQUEANTE =====
                    // stream.receive() bloqueia até:
                    // - Um frame completo chegar
                    // - Timeout expirar (SocketTimeoutException)
                    // - Conexão fechar (EOFException)
                    // - Erro de rede (IOException)
                    FramedStream.Frame f = stream.receive();

                    // ===== DELEGAÇÃO AO POOL =====
                    // Lambda: () -> handleRequest(f)
                    // O processamento acontece em thread do pool, não aqui
                    // Isto permite:
                    // - Continuar a ler o próximo frame imediatamente
                    // - Processar múltiplos frames em paralelo
                    workerPool.submit(() -> handleRequest(f));

                } catch (SocketTimeoutException e) {
                    // ===== TIMEOUT - NÃO É ERRO =====
                    // Apenas passou tempo sem dados. Verificar estado.
                    if (!running) {
                        // Fomos sinalizados para parar
                        break;
                    }
                    // Ainda ativo - continuar a aguardar
                    // Este comportamento permite detetar clientes "zombies"
                    // que deixaram a conexão aberta mas não comunicam
                    continue;
                }
            }
        } catch (EOFException e) {
            // ===== CLIENTE DESCONECTOU GRACIOSAMENTE =====
            // O cliente fechou a conexão de forma limpa (FIN TCP)
            System.out.println("[ClientHandler] Cliente desconectou");
        } catch (IOException e) {
            // ===== ERRO DE REDE =====
            // Apenas logar se não foi pedido para parar
            if (running) {
                System.err.println("[ClientHandler] Erro de comunicação: " + e.getMessage());
            }
        } finally {
            // ===== CLEANUP GARANTIDO =====
            // Sempre executado, mesmo em caso de exceção
            cleanup();
        }
    }

    /**
     * Limpa recursos quando o handler termina.
     *
     * OPERAÇÕES:
     * 1. Marcar como não-running (para que outras threads saibam)
     * 2. Fechar o stream (liberta socket)
     */
    private void cleanup() {
        running = false;
        try {
            stream.close();
        } catch (IOException ignored) {
            // Ignorar erros ao fechar - já estamos a terminar
        }
    }

    // ===== DESPACHO DE PEDIDOS =====

    /**
     * Despacha um pedido para o handler apropriado.
     *
     * ESTE MÉTODO É EXECUTADO EM THREAD DO POOL (não na thread do handler).
     *
     * PADRÃO DISPATCHER:
     * Switch sobre o tipo de operação para chamar o método correto.
     *
     * @param f Frame recebido contendo tag, tipo e payload
     */
    private void handleRequest(FramedStream.Frame f) {
        try {
            // ===== DESSERIALIZAR PAYLOAD =====
            // DataInputStream permite ler tipos Java de um byte array
            DataInputStream in = new DataInputStream(
                    new ByteArrayInputStream(f.payload)
            );

            // ===== VERIFICAÇÃO DE AUTENTICAÇÃO =====
            // Operações de negócio requerem login prévio
            // EXCEÇÕES: REGISTER e LOGIN podem ser feitos sem autenticação
            if (!authenticated &&
                    f.type != Protocol.REGISTER &&
                    f.type != Protocol.LOGIN) {
                sendError(f.tag, "Não autenticado");
                return;
            }

            // ===== DESPACHO POR TIPO DE OPERAÇÃO =====
            // Arrow syntax do switch (Java 14+) é mais limpa que case/break
            switch (f.type) {
                // --- Autenticação ---
                case Protocol.REGISTER -> handleRegister(f.tag, in);
                case Protocol.LOGIN -> handleLogin(f.tag, in);

                // --- Dados ---
                case Protocol.ADD_EVENT -> handleAddEvent(f.tag, in);
                case Protocol.NEW_DAY -> handleNewDay(f.tag);

                // --- Agregações ---
                // Todos os tipos de agregação são tratados pelo mesmo método
                case Protocol.AGGR_QTY,
                     Protocol.AGGR_VOL,
                     Protocol.AGGR_AVG,
                     Protocol.AGGR_MAX -> handleAggregation(f, in);

                // --- Consultas ---
                case Protocol.FILTER -> handleFilter(f.tag, in);
                case Protocol.GET_CURRENT_DAY -> handleGetCurrentDay(f.tag);

                // --- Notificações ---
                case Protocol.WAIT_SIMUL -> handleWaitSimul(f.tag, in);
                case Protocol.WAIT_CONSEC -> handleWaitConsec(f.tag, in);

                // NOTA: Tipos desconhecidos são silenciosamente ignorados
                // Em produção, poderíamos enviar STATUS_ERR com "Unknown command"
            }
        } catch (Exception e) {
            // ===== TRATAMENTO GENÉRICO DE ERROS =====
            // Qualquer exceção não tratada é convertida em erro para o cliente
            sendError(f.tag, e.getMessage());
        }
    }

    // ===== HANDLERS DE AUTENTICAÇÃO =====

    /**
     * Processa pedido de registo de utilizador.
     *
     * @param tag Tag do pedido (para correlação da resposta)
     * @param in  Stream de dados com username e password
     * @throws IOException Se ocorrer erro de comunicação
     */
    private void handleRegister(int tag, DataInputStream in)
            throws IOException {

        // ===== LER PARÂMETROS =====
        // Ordem deve corresponder ao que o cliente enviou
        String username = in.readUTF();
        String password = in.readUTF();

        // ===== DELEGAR AO USERMANAGER =====
        // UserManager.register() é thread-safe
        boolean ok = userManager.register(username, password);

        // ===== ENVIAR RESPOSTA =====
        // 1 byte: 1 = sucesso, 0 = user já existe
        sendResponse(tag, new byte[] { (byte) (ok ? 1 : 0) });
    }

    /**
     * Processa pedido de autenticação.
     *
     * @param tag Tag do pedido
     * @param in  Stream de dados com username e password
     * @throws IOException Se ocorrer erro de comunicação
     */
    private void handleLogin(int tag, DataInputStream in) throws IOException {
        String username = in.readUTF();
        String password = in.readUTF();

        // ===== VERIFICAR CREDENCIAIS =====
        // UserManager.authenticate() é thread-safe
        boolean ok = userManager.authenticate(username, password);

        // ===== ATUALIZAR ESTADO DA SESSÃO =====
        // Se login bem-sucedido, marcar sessão como autenticada
        // NOTA: Escrita em volatile é atómica para boolean
        authenticated = ok;

        sendResponse(tag, new byte[] { (byte) (authenticated ? 1 : 0) });
    }

    // ===== HANDLERS DE DADOS =====

    /**
     * Processa registo de evento de venda.
     *
     * @param tag Tag do pedido
     * @param in  Stream de dados com produto, quantidade, preço
     * @throws IOException Se ocorrer erro de comunicação
     */
    private void handleAddEvent(int tag, DataInputStream in)
            throws IOException {

        // ===== LER PARÂMETROS =====
        String prod = in.readUTF();
        int qty = in.readInt();
        double price = in.readDouble();

        // ===== ADICIONAR AO STORAGE =====
        // StorageEngine.addEvent() é thread-safe (usa lock interno)
        storage.addEvent(prod, qty, price);

        // ===== NOTIFICAR O NOTIFICATION MANAGER =====
        // Para verificar condições de notificação (simul, consec)
        // notify.registerSale() pode acordar threads bloqueadas
        notify.registerSale(prod);

        // ===== RESPOSTA VAZIA = SUCESSO =====
        sendResponse(tag, new byte[0]);
    }

    /**
     * Processa comando de encerramento do dia.
     *
     * @param tag Tag do pedido
     * @throws IOException Se ocorrer erro de comunicação ou persistência
     */
    private void handleNewDay(int tag) throws IOException {
        // ===== PERSISTIR DADOS DO DIA =====
        // Operação atómica: escreve ficheiro, incrementa dia, limpa buffer
        storage.persistDay();

        // ===== NOTIFICAR FIM DO DIA =====
        // Acorda todas as threads bloqueadas em notificações
        // Reset do estado de vendas do dia
        notify.newDay();

        sendResponse(tag, new byte[0]);
    }

    // ===== HANDLERS DE AGREGAÇÃO =====

    /**
     * Processa pedido de agregação estatística.
     *
     * NOTA: Este método trata os 4 tipos de agregação porque a lógica
     * é quase idêntica - apenas o tipo passado ao StorageEngine muda.
     *
     * @param f  Frame original (precisamos do tipo específico)
     * @param in Stream de dados com produto e dias
     * @throws IOException Se ocorrer erro de comunicação
     */
    private void handleAggregation(FramedStream.Frame f, DataInputStream in)
            throws IOException {

        String prod = in.readUTF();
        int days = in.readInt();

        // ===== VALIDAÇÃO DO PARÂMETRO 'days' =====
        // Deve estar no intervalo [1, D]
        // - < 1: Não faz sentido (0 dias ou negativo)
        // - > D: Dados mais antigos foram eliminados
        if (days < 1 || days > D) {
            sendError(f.tag, "Parâmetro 'days' inválido: " + days + ". Esperado [1, " + D + "]");
            return;
        }

        // ===== CALCULAR AGREGAÇÃO =====
        // StorageEngine.aggregate() usa cache interna para performance
        double res = storage.aggregate(f.type, prod, days);

        // ===== SERIALIZAR RESPOSTA =====
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        new DataOutputStream(b).writeDouble(res);

        sendResponse(f.tag, b.toByteArray());
    }

    // ===== HANDLER DE FILTRO =====

    /**
     * Processa pedido de filtragem de eventos históricos.
     *
     * @param tag Tag do pedido
     * @param in  Stream de dados com dia e lista de produtos
     * @throws IOException Se ocorrer erro de comunicação
     */
    private void handleFilter(int tag, DataInputStream in) throws IOException {
        // ===== LER PARÂMETROS =====
        int day = in.readInt();
        int size = in.readInt();  // Número de produtos no filtro

        // ===== OBTER DIA ATUAL PARA VALIDAÇÃO =====
        int currentDay = storage.getCurrentDay();

        // ===== VALIDAÇÃO DO DIA =====

        // Não pode ser negativo
        if (day < 0) {
            sendError(tag, "Parâmetro 'day' inválido: " + day + ". O dia não pode ser negativo.");
            return;
        }

        // Não pode ser o dia atual ou futuro (dados ainda não persistidos)
        if (day >= currentDay) {
            sendError(tag, "Parâmetro 'day' inválido: " + day +
                    ". Apenas dias anteriores podem ser filtrados (dia atual: " + currentDay + ").");
            return;
        }

        // Deve estar dentro da janela de retenção D
        int oldestRetainedDay = Math.max(0, currentDay - D);
        if (day < oldestRetainedDay) {
            sendError(tag, "Parâmetro 'day' inválido: " + day +
                    ". O dia está fora da janela de retenção [" + oldestRetainedDay + ", " + (currentDay - 1) + "].");
            return;
        }

        // ===== VALIDAÇÃO DO TAMANHO DO FILTRO =====
        // Prevenir ataques de denial-of-service com filtros gigantes
        if (size < 0 || size > 10000) {
            sendError(tag, "Tamanho do filtro inválido: " + size);
            return;
        }

        // ===== LER PRODUTOS DO FILTRO =====
        Set<String> set = new HashSet<>();
        for (int i = 0; i < size; i++) {
            set.add(in.readUTF());
        }

        // ===== CONSULTAR EVENTOS =====
        List<StorageEngine.Sale> events = storage.getEventsForDay(day, set);

        // ===== ENVIAR COM COMPRESSÃO POR DICIONÁRIO =====
        sendFilteredEvents(tag, events);
    }

    // ===== HANDLERS DE NOTIFICAÇÃO =====

    /**
     * Processa pedido de notificação de venda simultânea.
     *
     * ATENÇÃO: Este método pode bloquear a thread do pool durante muito tempo!
     * O cliente fica à espera até que a condição seja satisfeita ou o dia termine.
     *
     * @param tag Tag do pedido
     * @param in  Stream de dados com os dois produtos
     * @throws Exception Se ocorrer erro de comunicação ou interrupção
     */
    private void handleWaitSimul(int tag, DataInputStream in) throws Exception {
        String p1 = in.readUTF();
        String p2 = in.readUTF();

        // ===== VALIDAÇÃO =====
        if (p1.isEmpty() || p2.isEmpty()) {
            sendError(tag, "Nomes de produtos inválidos");
            return;
        }

        // ===== ESPERA BLOQUEANTE =====
        // NotificationManager.waitSimultaneous() bloqueia até:
        // - Ambos os produtos serem vendidos → retorna true
        // - newDay() ser chamado → retorna false
        //
        // A thread do pool fica ocupada durante este tempo!
        // É por isso que o pool precisa de muitas threads.
        boolean sim = notify.waitSimultaneous(p1, p2);

        sendResponse(tag, new byte[] { (byte) (sim ? 1 : 0) });
    }

    /**
     * Processa pedido de notificação de vendas consecutivas.
     *
     * @param tag Tag do pedido
     * @param in  Stream de dados com N
     * @throws Exception Se ocorrer erro de comunicação ou interrupção
     */
    private void handleWaitConsec(int tag, DataInputStream in)
            throws Exception {

        int n = in.readInt();

        // ===== VALIDAÇÃO =====
        // N deve ser razoável (1 a 100000)
        if (n < 1 || n > 100000) {
            sendError(tag, "Número de vendas consecutivas inválido: " + n);
            return;
        }

        // ===== ESPERA BLOQUEANTE =====
        // Retorna o nome do produto que atingiu N consecutivas, ou null
        String prod = notify.waitConsecutive(n);

        // ===== SERIALIZAR RESPOSTA =====
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        if (prod != null) {
            new DataOutputStream(b).writeUTF(prod);
        }
        // Se prod == null, payload vazio indica que o dia terminou

        sendResponse(tag, b.toByteArray());
    }

    // ===== HANDLER DE DIA ATUAL =====

    /**
     * Processa consulta do dia atual.
     *
     * @param tag Tag do pedido
     * @throws IOException Se ocorrer erro de comunicação
     */
    private void handleGetCurrentDay(int tag) throws IOException {
        int day = storage.getCurrentDay();

        ByteArrayOutputStream b = new ByteArrayOutputStream();
        new DataOutputStream(b).writeInt(day);

        sendResponse(tag, b.toByteArray());
    }

    // ===== MÉTODOS DE ENVIO =====

    /**
     * Envia uma resposta de sucesso ao cliente.
     *
     * TRATAMENTO DE FALHAS:
     * Se falhar o envio, marca running=false para terminar o handler.
     * O cliente provavelmente desconectou.
     *
     * @param tag  Tag do pedido original (para correlação)
     * @param data Payload da resposta
     */
    private void sendResponse(int tag, byte[] data) {
        try {
            stream.send(tag, Protocol.STATUS_OK, data);
        } catch (IOException e) {
            // ===== FALHA NO ENVIO =====
            // Cliente provavelmente desconectou
            // Marcar para terminar na próxima iteração do loop
            running = false;
        }
    }

    /**
     * Envia uma mensagem de erro ao cliente.
     *
     * @param tag Tag do pedido original
     * @param msg Mensagem de erro legível
     */
    private void sendError(int tag, String msg) {
        try {
            // Converter mensagem para bytes (null-safe)
            byte[] payload = msg != null ? msg.getBytes() : "Erro desconhecido".getBytes();
            stream.send(tag, Protocol.STATUS_ERR, payload);
        } catch (IOException e) {
            running = false;
        }
    }

    /**
     * Envia eventos filtrados com compressão por dicionário.
     *
     * ALGORITMO DE COMPRESSÃO:
     * 1. Construir dicionário: mapear cada produto único para um índice
     * 2. Enviar dicionário: [tamanho][string1][string2]...
     * 3. Enviar eventos: [índice do produto][quantidade][preço]...
     *
     * ECONOMIA:
     * Em vez de enviar "iPhone 15 Pro Max" 1000 vezes (22 bytes × 1000 = 22KB),
     * enviamos a string 1 vez no dicionário, e depois índices (4 bytes × 1000 = 4KB).
     * Economia: ~80% para muitos eventos do mesmo produto.
     *
     * @param tag    Tag da resposta
     * @param events Lista de eventos a enviar
     */
    private void sendFilteredEvents(int tag, List<StorageEngine.Sale> events) {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(b);

            // ===== FASE 1: CONSTRUIR DICIONÁRIO =====
            // Map: produto → índice
            // List: índice → produto (para escrita ordenada)
            Map<String, Integer> dict = new HashMap<>();
            List<String> dictList = new ArrayList<>();

            for (StorageEngine.Sale s : events) {
                if (!dict.containsKey(s.prod)) {
                    // Novo produto: adicionar ao dicionário
                    dict.put(s.prod, dictList.size());  // índice = posição atual
                    dictList.add(s.prod);
                }
            }

            // ===== FASE 2: ESCREVER DICIONÁRIO =====
            out.writeInt(dictList.size());  // Número de entradas
            for (String s : dictList) {
                out.writeUTF(s);  // Cada string do dicionário
            }

            // ===== FASE 3: ESCREVER EVENTOS =====
            out.writeInt(events.size());  // Número de eventos
            for (StorageEngine.Sale s : events) {
                out.writeInt(dict.get(s.prod));  // Índice no dicionário (não a string!)
                out.writeInt(s.qty);
                out.writeDouble(s.price);
            }

            sendResponse(tag, b.toByteArray());

        } catch (IOException e) {
            sendError(tag, "Erro ao serializar eventos: " + e.getMessage());
        }
    }
}