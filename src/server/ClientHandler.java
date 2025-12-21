package server;

import common.FramedStream;
import common.Protocol;
import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;

/**
 * Gere a sessão de comunicação individual de um cliente ligado ao servidor.
 * A intenção é desacoplar a leitura física do socket da execução lógica,
 * permitindo que pedidos de um único cliente sejam processados em paralelo no pool.
 */
public class ClientHandler implements Runnable {

    private final FramedStream stream;
    private final UserManager userManager;
    private final StorageEngine storage;
    private final NotificationManager notify;
    private final ThreadPool workerPool;
    private boolean authenticated = false;

    /**
     * @param s Fluxo binário com o cliente.
     * @param um Sistema de gestão de utilizadores.
     * @param se Motor de armazenamento e cache.
     * @param nm Sistema de notificações em tempo real.
     * @param pool Pool global de threads para processamento.
     */
    public ClientHandler(
        FramedStream s,
        UserManager um,
        StorageEngine se,
        NotificationManager nm,
        ThreadPool wp
    ) {
        this.stream = s;
        this.userManager = um;
        this.storage = se;
        this.notify = nm;
        this.workerPool = wp;
    }

    /**
     * Ciclo principal de escuta de comandos do cliente.
     * A intenção é consumir frames o mais rápido possível e delegar o trabalho ao pool,
     * libertando o canal para novos comandos enquanto os anteriores são processados.
     */
    @Override
    public void run() {
        try {
            while (true) {
                FramedStream.Frame f = stream.receive();
                // Despacha o processamento para não bloquear a receção de mais frames
                workerPool.submit(() -> handleRequest(f));
            }
        } catch (IOException e) {
            // A ligação foi fechada pelo cliente de forma normal ou forçada
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
     * Comando administrativo para encerrar ciclo temporal.
     * @param tag Tag do pedido.
     * @throws IOException Erro ao persistir ou rede.
     */
    private void handleNewDay(int tag) throws IOException {
        storage.persistDay();
        notify.newDay();
        stream.send(tag, Protocol.STATUS_OK, new byte[0]);
    }

    /**
     * Processa pedidos estatísticos através do StorageEngine.
     * @param f Frame original.
     * @param in Stream de dados.
     * @throws IOException Erro de rede.
     */
    private void handleAggregation(FramedStream.Frame f, DataInputStream in)
        throws IOException {
        double res = storage.aggregate(f.type, in.readUTF(), in.readInt());
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        new DataOutputStream(b).writeDouble(res);
        stream.send(f.tag, Protocol.STATUS_OK, b.toByteArray());
    }

    /**
     * Gere a pesquisa histórica com otimização de largura de banda.
     * @param tag Tag do pedido.
     * @param in Stream de dados.
     * @throws IOException Erro de rede.
     */
    private void handleFilter(int tag, DataInputStream in) throws IOException {
        int day = in.readInt();
        int size = in.readInt();
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
        boolean sim = notify.waitSimultaneous(in.readUTF(), in.readUTF());
        stream.send(
            tag,
            Protocol.STATUS_OK,
            new byte[] { (byte) (sim ? 1 : 0) }
        );
    }

    /**
     * Bloqueia o processamento até que a meta de vendas consecutivas seja atingida.
     * @param tag Tag do pedido.
     * @param in Stream de dados.
     * @throws Exception Erro de rede ou interrupção.
     */
    private void handleWaitConsec(int tag, DataInputStream in)
        throws Exception {
        String prod = notify.waitConsecutive(in.readInt());
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
     * Implementa a compressão por dicionário para envio de eventos.
     * A intenção é substituir strings repetitivas por identificadores numéricos,
     * reduzindo significativamente o tamanho da resposta em listas grandes.
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

        // Escreve o dicionário no cabeçalho da mensagem para que o cliente saiba decifrar os IDs
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
