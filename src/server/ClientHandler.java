package server;

import common.FramedStream;
import common.Protocol;
import java.io.*;
import java.net.SocketTimeoutException;
import java.util.*;

/**
 * Gestor de sessão individual para cada cliente conectado.
 *
 * Processa pedidos de um cliente específico, delegando o trabalho pesado
 * ao pool de threads para permitir processamento paralelo de múltiplos
 * pedidos do mesmo cliente.
 */
public class ClientHandler implements Runnable {

    private final FramedStream stream;
    private final UserManager userManager;
    private final StorageEngine storage;
    private final NotificationManager notify;
    private final ThreadPool workerPool;
    private final int D;

    /** Indica se a sessão está autenticada. Volátil para visibilidade entre threads. */
    private volatile boolean authenticated = false;

    /** Indica se o handler deve continuar a processar pedidos. */
    private volatile boolean running = true;

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

    /**
     * Ciclo principal de leitura de pedidos.
     * Lê frames continuamente e delega o processamento ao pool de threads,
     * permitindo que múltiplos pedidos do mesmo cliente sejam processados
     * em paralelo.
     *
     * Trata timeouts de socket para detetar clientes inativos e evitar
     * bloqueios indefinidos.
     */
    @Override
    public void run() {
        String clientInfo = "cliente desconhecido";
        try {
            while (running) {
                try {
                    FramedStream.Frame f = stream.receive();
                    workerPool.submit(() -> handleRequest(f));
                } catch (SocketTimeoutException e) {
                    // Timeout de leitura - verificar se devemos continuar
                    if (!running) {
                        break;
                    }
                    // Timeout mas ainda ativo - continuar a aguardar
                    // Isto permite que o servidor detete clientes lentos/inativos
                    continue;
                }
            }
        } catch (EOFException e) {
            // Cliente fechou a conexão graciosamente
            System.out.println("[ClientHandler] Cliente desconectou: " + clientInfo);
        } catch (IOException e) {
            // Erro de rede - cliente provavelmente desconectou
            if (running) {
                System.err.println("[ClientHandler] Erro de comunicação: " + e.getMessage());
            }
        } finally {
            cleanup();
        }
    }

    /**
     * Limpa recursos quando o handler termina.
     */
    private void cleanup() {
        running = false;
        try {
            stream.close();
        } catch (IOException ignored) {}
    }

    /**
     * Despacha um pedido para o handler apropriado.
     *
     * @param f Frame recebido
     */
    private void handleRequest(FramedStream.Frame f) {
        try {
            DataInputStream in = new DataInputStream(
                    new ByteArrayInputStream(f.payload)
            );

            // Verificação de autenticação para operações de negócio
            if (!authenticated &&
                    f.type != Protocol.REGISTER &&
                    f.type != Protocol.LOGIN) {
                sendError(f.tag, "Não autenticado");
                return;
            }

            switch (f.type) {
                case Protocol.REGISTER -> handleRegister(f.tag, in);
                case Protocol.LOGIN -> handleLogin(f.tag, in);
                case Protocol.ADD_EVENT -> handleAddEvent(f.tag, in);
                case Protocol.NEW_DAY -> handleNewDay(f.tag);
                case Protocol.AGGR_QTY,
                     Protocol.AGGR_VOL,
                     Protocol.AGGR_AVG,
                     Protocol.AGGR_MAX -> handleAggregation(f, in);
                case Protocol.FILTER -> handleFilter(f.tag, in);
                case Protocol.WAIT_SIMUL -> handleWaitSimul(f.tag, in);
                case Protocol.WAIT_CONSEC -> handleWaitConsec(f.tag, in);
                case Protocol.GET_CURRENT_DAY -> handleGetCurrentDay(f.tag);
            }
        } catch (Exception e) {
            sendError(f.tag, e.getMessage());
        }
    }

    /**
     * Processa pedido de registo de utilizador.
     *
     * @param tag Tag do pedido
     * @param in  Stream de dados
     * @throws IOException Se ocorrer erro de comunicação
     */
    private void handleRegister(int tag, DataInputStream in)
            throws IOException {
        boolean ok = userManager.register(in.readUTF(), in.readUTF());
        sendResponse(tag, new byte[] { (byte) (ok ? 1 : 0) });
    }

    /**
     * Processa pedido de autenticação.
     *
     * @param tag Tag do pedido
     * @param in  Stream de dados
     * @throws IOException Se ocorrer erro de comunicação
     */
    private void handleLogin(int tag, DataInputStream in) throws IOException {
        authenticated = userManager.authenticate(in.readUTF(), in.readUTF());
        sendResponse(tag, new byte[] { (byte) (authenticated ? 1 : 0) });
    }

    /**
     * Processa registo de evento de venda.
     *
     * @param tag Tag do pedido
     * @param in  Stream de dados
     * @throws IOException Se ocorrer erro de comunicação
     */
    private void handleAddEvent(int tag, DataInputStream in)
            throws IOException {
        String p = in.readUTF();
        storage.addEvent(p, in.readInt(), in.readDouble());
        notify.registerSale(p);
        sendResponse(tag, new byte[0]);
    }

    /**
     * Processa comando de encerramento do dia.
     *
     * @param tag Tag do pedido
     * @throws IOException Se ocorrer erro de comunicação ou persistência
     */
    private void handleNewDay(int tag) throws IOException {
        storage.persistDay();
        notify.newDay();
        sendResponse(tag, new byte[0]);
    }

    /**
     * Processa pedido de agregação estatística.
     * Valida que o parâmetro 'days' está no intervalo [1, D].
     *
     * @param f  Frame original
     * @param in Stream de dados
     * @throws IOException Se ocorrer erro de comunicação
     */
    private void handleAggregation(FramedStream.Frame f, DataInputStream in)
            throws IOException {
        String prod = in.readUTF();
        int days = in.readInt();

        if (days < 1 || days > D) {
            sendError(f.tag, "Parâmetro 'days' inválido: " + days + ". Esperado [1, " + D + "]");
            return;
        }

        double res = storage.aggregate(f.type, prod, days);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        new DataOutputStream(b).writeDouble(res);
        sendResponse(f.tag, b.toByteArray());
    }

    /**
     * Processa pedido de filtragem de eventos históricos.
     * Valida que o dia solicitado está dentro do intervalo válido:
     *   - Não pode ser negativo
     *   - Deve ser anterior ao dia atual (não é possível filtrar o dia em curso)
     *   - Deve estar dentro da janela de retenção D
     *
     * @param tag Tag do pedido
     * @param in  Stream de dados
     * @throws IOException Se ocorrer erro de comunicação
     */
    private void handleFilter(int tag, DataInputStream in) throws IOException {
        int day = in.readInt();
        int size = in.readInt();

        int currentDay = storage.getCurrentDay();

        if (day < 0) {
            sendError(tag, "Parâmetro 'day' inválido: " + day + ". O dia não pode ser negativo.");
            return;
        }

        if (day >= currentDay) {
            sendError(tag, "Parâmetro 'day' inválido: " + day +
                    ". Apenas dias anteriores podem ser filtrados (dia atual: " + currentDay + ").");
            return;
        }

        int oldestRetainedDay = Math.max(0, currentDay - D);
        if (day < oldestRetainedDay) {
            sendError(tag, "Parâmetro 'day' inválido: " + day +
                    ". O dia está fora da janela de retenção [" + oldestRetainedDay + ", " + (currentDay - 1) + "].");
            return;
        }

        if (size < 0 || size > 10000) {
            sendError(tag, "Tamanho do filtro inválido: " + size);
            return;
        }

        Set<String> set = new HashSet<>();
        for (int i = 0; i < size; i++) {
            set.add(in.readUTF());
        }
        List<StorageEngine.Sale> events = storage.getEventsForDay(day, set);
        sendFilteredEvents(tag, events);
    }

    /**
     * Processa pedido de notificação de venda simultânea.
     *
     * @param tag Tag do pedido
     * @param in  Stream de dados
     * @throws Exception Se ocorrer erro de comunicação ou interrupção
     */
    private void handleWaitSimul(int tag, DataInputStream in) throws Exception {
        String p1 = in.readUTF();
        String p2 = in.readUTF();

        if (p1.isEmpty() || p2.isEmpty()) {
            sendError(tag, "Nomes de produtos inválidos");
            return;
        }

        boolean sim = notify.waitSimultaneous(p1, p2);
        sendResponse(tag, new byte[] { (byte) (sim ? 1 : 0) });
    }

    /**
     * Processa pedido de notificação de vendas consecutivas.
     *
     * @param tag Tag do pedido
     * @param in  Stream de dados
     * @throws Exception Se ocorrer erro de comunicação ou interrupção
     */
    private void handleWaitConsec(int tag, DataInputStream in)
            throws Exception {
        int n = in.readInt();

        if (n < 1 || n > 100000) {
            sendError(tag, "Número de vendas consecutivas inválido: " + n);
            return;
        }

        String prod = notify.waitConsecutive(n);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        if (prod != null) {
            new DataOutputStream(b).writeUTF(prod);
        }
        sendResponse(tag, b.toByteArray());
    }

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

    /**
     * Envia uma resposta de sucesso ao cliente.
     * Se falhar o envio, marca o handler para terminar.
     *
     * @param tag  Tag do pedido original
     * @param data Payload da resposta
     */
    private void sendResponse(int tag, byte[] data) {
        try {
            stream.send(tag, Protocol.STATUS_OK, data);
        } catch (IOException e) {
            // Falha ao enviar - cliente provavelmente desconectou
            running = false;
        }
    }

    /**
     * Envia uma mensagem de erro ao cliente.
     *
     * @param tag Tag do pedido original
     * @param msg Mensagem de erro
     */
    private void sendError(int tag, String msg) {
        try {
            stream.send(
                    tag,
                    Protocol.STATUS_ERR,
                    msg != null ? msg.getBytes() : "Erro desconhecido".getBytes()
            );
        } catch (IOException e) {
            // Falha ao enviar - cliente provavelmente desconectou
            running = false;
        }
    }

    /**
     * Envia eventos filtrados com compressão por dicionário.
     * Substitui strings de produtos repetidas por índices numéricos para
     * reduzir o tamanho da mensagem.
     *
     * @param tag    Tag da resposta
     * @param events Lista de eventos a enviar
     */
    private void sendFilteredEvents(int tag, List<StorageEngine.Sale> events) {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(b);
            Map<String, Integer> dict = new HashMap<>();
            List<String> dictList = new ArrayList<>();

            // Construção do dicionário de compressão
            for (StorageEngine.Sale s : events) {
                if (!dict.containsKey(s.prod)) {
                    dict.put(s.prod, dictList.size());
                    dictList.add(s.prod);
                }
            }

            // Escrita do dicionário
            out.writeInt(dictList.size());
            for (String s : dictList) {
                out.writeUTF(s);
            }

            // Escrita dos eventos com referências ao dicionário
            out.writeInt(events.size());
            for (StorageEngine.Sale s : events) {
                out.writeInt(dict.get(s.prod));
                out.writeInt(s.qty);
                out.writeDouble(s.price);
            }
            sendResponse(tag, b.toByteArray());
        } catch (IOException e) {
            sendError(tag, "Erro ao serializar eventos: " + e.getMessage());
        }
    }
}