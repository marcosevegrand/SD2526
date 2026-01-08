package server;

import common.FramedStream;
import common.Protocol;
import java.io.*;
import java.util.*;

/**
 * Gere a sessão de comunicação individual de um cliente ligado ao servidor.
 * Desacopla a leitura física do socket da execução lógica, permitindo que
 * pedidos de um único cliente sejam processados em paralelo no pool de threads.
 */
public class ClientHandler implements Runnable {

    private final FramedStream stream;
    private final UserManager userManager;
    private final StorageEngine storage;
    private final NotificationManager notify;
    private final ThreadPool workerPool;
    // Nota: Parâmetros S e D são validados no ClientHandler conforme necessário
    private final int D;
    // Flag volátil que indica se a sessão foi autenticada.
    private volatile boolean authenticated = false;

    /**
     * @param s Fluxo binário com o cliente.
     * @param um Sistema de gestão de utilizadores.
     * @param se Motor de armazenamento e cache.
     * @param nm Sistema de notificações em tempo real.
     * @param wp Pool global de threads para processamento.
     * @param D Janela de retenção de dias históricos.
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
     * Ciclo principal de escuta de comandos do cliente. Consome frames o mais
     * rápido possível e delega o trabalho ao pool, libertando o canal para
     * novos comandos enquanto os anteriores são processados em paralelo.
     * Este loop é intencional: run() deve escutar continuamente até desconexão.
     */
    @Override
    public void run() {
        try {
            while (true) {
                FramedStream.Frame f = stream.receive();
                workerPool.submit(() -> handleRequest(f));
            }
        } catch (IOException e) {
            // Ligacão fechada pelo cliente
        } finally {
            try { stream.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Despacha o frame para a função lógica correta e valida a autenticação.
     * @param f O frame recebido da rede.
     */
    private void handleRequest(FramedStream.Frame f) {
        try {
            DataInputStream in = new DataInputStream(
                    new ByteArrayInputStream(f.payload)
            );

            // Regra de segurança: Apenas registo e login são permitidos sem autenticação
            if (
                    !authenticated &&
                            f.type != Protocol.REGISTER &&
                            f.type != Protocol.LOGIN
            ) {
                stream.send(
                        f.tag,
                        Protocol.STATUS_ERR,
                        "Não autenticado".getBytes()
                );
                return;
            }

            switch (f.type) {
                case Protocol.REGISTER -> handleRegister(f.tag, in);
                case Protocol.LOGIN -> handleLogin(f.tag, in);
                case Protocol.ADD_EVENT -> handleAddEvent(f.tag, in);
                case Protocol.NEW_DAY -> handleNewDay(f.tag);
                case
                        Protocol.AGGR_QTY,
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
     * Regista um novo utilizador no sistema.
     * @param tag Tag do pedido.
     * @param in Stream de dados serializados.
     * @throws IOException Erro de leitura ou rede.
     */
    private void handleRegister(int tag, DataInputStream in)
            throws IOException {
        boolean ok = userManager.register(in.readUTF(), in.readUTF());
        stream.send(
                tag,
                Protocol.STATUS_OK,
                new byte[] { (byte) (ok ? 1 : 0) }
        );
    }

    /**
     * Verifica credenciais e autoriza a sessão atual.
     * @param tag Tag do pedido.
     * @param in Stream de dados.
     * @throws IOException Erro de leitura ou rede.
     */
    private void handleLogin(int tag, DataInputStream in) throws IOException {
        authenticated = userManager.authenticate(in.readUTF(), in.readUTF());
        stream.send(
                tag,
                Protocol.STATUS_OK,
                new byte[] { (byte) (authenticated ? 1 : 0) }
        );
    }

    /**
     * Regista venda e despoleta notificações em tempo real.
     * @param tag Tag do pedido.
     * @param in Stream de dados.
     * @throws IOException Erro de leitura ou rede.
     */
    private void handleAddEvent(int tag, DataInputStream in)
            throws IOException {
        String p = in.readUTF();
        storage.addEvent(p, in.readInt(), in.readDouble());
        notify.registerSale(p);
        stream.send(tag, Protocol.STATUS_OK, new byte[0]);
    }

    /**
     * Comando administrativo para encerrar ciclo temporal e avançar para o próximo dia.
     * @param tag Tag do pedido.
     * @throws IOException Erro ao persistir ou rede.
     */
    private void handleNewDay(int tag) throws IOException {
        storage.persistDay();
        notify.newDay();
        stream.send(tag, Protocol.STATUS_OK, new byte[0]);
    }

    /**
     * Processa pedidos estatísticos através do StorageEngine. Valida que o
     * parâmetro 'days' (dias) está no intervalo válido [1, D] conforme
     * configurado no servidor.
     * @param f Frame original.
     * @param in Stream de dados.
     * @throws IOException Erro de rede.
     */
    private void handleAggregation(FramedStream.Frame f, DataInputStream in)
            throws IOException {
        String prod = in.readUTF();
        int days = in.readInt();

        if (days < 1 || days > D) {
            sendError(f.tag, "Parâmetro 'days' inválido: " + days + ". Esperado [1, " + D + "]");
            return;
        }

        // FIX: Additional validation - warn if requesting more days than available
        int currentDay = storage.getCurrentDay();
        if (days > currentDay) {
            // Still process but only with available days - the storage engine handles this
            // This is informational, not an error
        }

        double res = storage.aggregate(f.type, prod, days);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        new DataOutputStream(b).writeDouble(res);
        stream.send(f.tag, Protocol.STATUS_OK, b.toByteArray());
    }

    /**
     * Gere a pesquisa histórica com otimização de largura de banda através
     * de compressão por dicionário.
     *
     * FIX: Proper validation for filter day:
     * - Day must be >= 0 (day 0 is the first persisted day)
     * - Day must be < currentDay (can't filter current day - not yet persisted)
     * - Day must be within retention window (>= currentDay - D)
     *
     * @param tag Tag do pedido.
     * @param in Stream de dados.
     * @throws IOException Erro de rede.
     */
    private void handleFilter(int tag, DataInputStream in) throws IOException {
        int day = in.readInt();
        int size = in.readInt();

        int currentDay = storage.getCurrentDay();

        // FIX: Proper day validation
        // 1. Day cannot be negative
        if (day < 0) {
            sendError(tag, "Parâmetro 'day' inválido: " + day + ". O dia não pode ser negativo.");
            return;
        }

        // 2. Day must be a previous day (< currentDay), not the current day being written to
        if (day >= currentDay) {
            sendError(tag, "Parâmetro 'day' inválido: " + day +
                    ". Apenas dias anteriores podem ser filtrados (dia atual: " + currentDay + ").");
            return;
        }

        // 3. Day must be within retention window
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
        for (int i = 0; i < size; i++) set.add(in.readUTF());
        List<StorageEngine.Sale> events = storage.getEventsForDay(day, set);
        sendFilteredEvents(tag, events);
    }

    /**
     * Bloqueia o processamento do pedido até que a condição de simultaneidade ocorra.
     * @param tag Tag do pedido.
     * @param in Stream de dados.
     * @throws Exception Erro de rede ou interrupção.
     */
    private void handleWaitSimul(int tag, DataInputStream in) throws Exception {
        String p1 = in.readUTF();
        String p2 = in.readUTF();

        if (p1.isEmpty() || p2.isEmpty()) {
            sendError(tag, "Nomes de produtos inválidos");
            return;
        }

        boolean sim = notify.waitSimultaneous(p1, p2);
        stream.send(
                tag,
                Protocol.STATUS_OK,
                new byte[] { (byte) (sim ? 1 : 0) }
        );
    }

    /**
     * Bloqueia o processamento até que a meta de vendas consecutivas do mesmo
     * produto seja atingida. Valida que n está num intervalo razoável.
     * @param tag Tag do pedido.
     * @param in Stream de dados.
     * @throws Exception Erro de rede ou interrupção.
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
        if (prod != null) new DataOutputStream(b).writeUTF(prod);
        stream.send(tag, Protocol.STATUS_OK, b.toByteArray());
    }

    /**
     * Responde ao cliente com o valor inteiro do dia atual do servidor.
     * @param tag Tag do pedido original.
     * @throws IOException Erro de rede.
     */
    private void handleGetCurrentDay(int tag) throws IOException {
        int day = storage.getCurrentDay();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        new DataOutputStream(b).writeInt(day);
        stream.send(tag, Protocol.STATUS_OK, b.toByteArray());
    }

    /**
     * Auxiliar para envio de mensagens de erro formatadas.
     * @param tag Tag do pedido original.
     * @param msg Mensagem de erro.
     */
    private void sendError(int tag, String msg) {
        try {
            stream.send(
                    tag,
                    Protocol.STATUS_ERR,
                    msg != null ? msg.getBytes() : "Erro desconhecido".getBytes()
            );
        } catch (IOException ignored) {}
    }

    /**
     * Implementa compressão por dicionário para envio de eventos. Substitui
     * strings repetitivas por identificadores numéricos, reduzindo significativamente
     * o tamanho da resposta em listas grandes de eventos.
     * @param tag Tag da resposta.
     * @param events Lista de vendas processadas.
     * @throws IOException Erro na transmissão.
     */
    private void sendFilteredEvents(int tag, List<StorageEngine.Sale> events)
            throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(b);
        Map<String, Integer> dict = new HashMap<>();
        List<String> dictList = new ArrayList<>();

        // Primeiro passo: Identificar todos os produtos únicos e atribuir-lhes um índice
        for (StorageEngine.Sale s : events) {
            if (!dict.containsKey(s.prod)) {
                dict.put(s.prod, dictList.size());
                dictList.add(s.prod);
            }
        }

        // Escreve o dicionário no cabeçalho para que o cliente saiba decifrar os IDs
        out.writeInt(dictList.size());
        for (String s : dictList) out.writeUTF(s);

        // Escreve os dados das vendas usando os índices inteiros em vez das strings
        out.writeInt(events.size());
        for (StorageEngine.Sale s : events) {
            out.writeInt(dict.get(s.prod));
            out.writeInt(s.qty);
            out.writeDouble(s.price);
        }
        stream.send(tag, Protocol.STATUS_OK, b.toByteArray());
    }
}