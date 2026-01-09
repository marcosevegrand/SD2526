package client;

import common.FramedStream;
import common.Protocol;
import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Biblioteca cliente para interação com o serviço de séries temporais.
 *
 * Abstrai a complexidade do protocolo binário e da comunicação em rede,
 * expondo uma API de alto nível com métodos para todas as operações de negócio:
 * autenticação, registo de eventos, consultas estatísticas e notificações.
 *
 * A classe é thread-safe, permitindo que múltiplas threads de aplicação
 * executem operações concorrentemente sobre a mesma instância.
 */
public class ClientLib implements AutoCloseable {

    /** Timeout para operações normais (30 segundos). */
    private static final long DEFAULT_TIMEOUT_MS = 30000;

    /** Timeout para operações bloqueantes como notificações (24 horas). */
    private static final long BLOCKING_TIMEOUT_MS = 24 * 60 * 60 * 1000;

    private final Demultiplexer demux;
    private int tagCounter = 0;
    private final ReentrantLock tagLock = new ReentrantLock();

    /**
     * Estabelece ligação com o servidor remoto.
     *
     * @param host Endereço de rede do servidor
     * @param port Porto de escuta do servidor
     * @throws IOException Se não for possível estabelecer a ligação
     */
    public ClientLib(String host, int port) throws IOException {
        this.demux = new Demultiplexer(
                new FramedStream(new Socket(host, port))
        );
        this.demux.start();
    }

    /**
     * Gera um identificador único para correlação de pedidos.
     *
     * @return Tag única para a sessão
     */
    private int nextTag() {
        tagLock.lock();
        try {
            return ++tagCounter;
        } finally {
            tagLock.unlock();
        }
    }

    /**
     * Executa o ciclo completo de pedido-resposta com timeout padrão.
     *
     * @param type Código da operação
     * @param data Payload serializado
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
     * Regista a tag antes do envio para garantir que a resposta não é perdida
     * caso chegue antes de estarmos prontos para a receber.
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
        int tag = nextTag();
        demux.register(tag);
        try {
            demux.send(tag, type, data);
            return demux.receive(tag, timeoutMs);
        } catch (TimeoutException e) {
            throw new IOException("Operação excedeu o tempo limite", e);
        }
    }

    /**
     * Regista um novo utilizador no sistema.
     *
     * @param user Nome de utilizador
     * @param pass Palavra-passe
     * @return true se o registo foi bem-sucedido, false se o utilizador já existe
     * @throws IOException          Se ocorrer erro de comunicação
     * @throws InterruptedException Se a operação for interrompida
     */
    public boolean register(String user, String pass)
            throws IOException, InterruptedException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeUTF(user);
        out.writeUTF(pass);
        byte[] res = request(Protocol.REGISTER, baos.toByteArray());
        return res.length > 0 && res[0] == 1;
    }

    /**
     * Autentica o utilizador para acesso às operações de negócio.
     *
     * @param user Nome de utilizador
     * @param pass Palavra-passe
     * @return true se as credenciais são válidas
     * @throws IOException          Se ocorrer erro de comunicação
     * @throws InterruptedException Se a operação for interrompida
     */
    public boolean login(String user, String pass)
            throws IOException, InterruptedException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeUTF(user);
        out.writeUTF(pass);
        byte[] res = request(Protocol.LOGIN, baos.toByteArray());
        return res.length > 0 && res[0] == 1;
    }

    /**
     * Regista um evento de venda no dia atual.
     *
     * @param prod  Identificador do produto
     * @param qty   Quantidade vendida
     * @param price Preço unitário
     * @throws IOException          Se ocorrer erro de comunicação
     * @throws InterruptedException Se a operação for interrompida
     */
    public void addEvent(String prod, int qty, double price)
            throws IOException, InterruptedException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeUTF(prod);
        out.writeInt(qty);
        out.writeDouble(price);
        request(Protocol.ADD_EVENT, baos.toByteArray());
    }

    /**
     * Encerra o dia atual e persiste os dados em disco.
     * Esta operação é atómica e despoleta a notificação de todas as threads
     * em espera por eventos do dia corrente.
     *
     * @throws IOException          Se ocorrer erro de comunicação
     * @throws InterruptedException Se a operação for interrompida
     */
    public void newDay() throws IOException, InterruptedException {
        request(Protocol.NEW_DAY, new byte[0]);
    }

    /**
     * Consulta uma agregação estatística sobre vendas históricas.
     *
     * @param type Tipo de agregação (AGGR_QTY, AGGR_VOL, AGGR_AVG, AGGR_MAX)
     * @param prod Produto a analisar
     * @param days Número de dias retroativos
     * @return Valor calculado da agregação
     * @throws IOException          Se ocorrer erro de comunicação
     * @throws InterruptedException Se a operação for interrompida
     */
    public double getAggregation(int type, String prod, int days)
            throws IOException, InterruptedException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeUTF(prod);
        out.writeInt(days);
        byte[] res = request(type, baos.toByteArray());
        return new DataInputStream(new ByteArrayInputStream(res)).readDouble();
    }

    /**
     * Recupera eventos filtrados de um dia específico.
     * Os dados são recebidos com compressão por dicionário, onde strings
     * repetidas são substituídas por índices numéricos para reduzir o
     * tamanho da transmissão.
     *
     * @param day       Dia histórico a consultar
     * @param filterSet Conjunto de produtos a incluir no resultado
     * @return Lista de strings formatadas com os detalhes das vendas
     * @throws IOException          Se ocorrer erro de comunicação
     * @throws InterruptedException Se a operação for interrompida
     */
    public List<String> getEvents(int day, Set<String> filterSet)
            throws IOException, InterruptedException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeInt(day);
        out.writeInt(filterSet.size());
        for (String s : filterSet) {
            out.writeUTF(s);
        }

        byte[] res = request(Protocol.FILTER, baos.toByteArray());
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(res));

        // Reconstrução do dicionário de compressão
        int dictSize = in.readInt();
        String[] dictionary = new String[dictSize];
        for (int i = 0; i < dictSize; i++) {
            dictionary[i] = in.readUTF();
        }

        int numEvents = in.readInt();
        List<String> events = new ArrayList<>();
        for (int i = 0; i < numEvents; i++) {
            String p = dictionary[in.readInt()];
            int q = in.readInt();
            double pr = in.readDouble();
            events.add(p + " | Qtd: " + q + " | Preço: " + pr);
        }
        return events;
    }

    /**
     * Aguarda a venda simultânea de dois produtos no mesmo dia.
     * Bloqueia a thread até que ambos os produtos sejam vendidos ou até
     * que o dia termine.
     *
     * NOTA: Esta é uma operação bloqueante com timeout alargado.
     *
     * @param p1 Primeiro produto
     * @param p2 Segundo produto
     * @return true se ambos foram vendidos, false se o dia terminou
     * @throws IOException          Se ocorrer erro de comunicação
     * @throws InterruptedException Se a operação for interrompida
     */
    public boolean waitSimultaneous(String p1, String p2)
            throws IOException, InterruptedException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeUTF(p1);
        out.writeUTF(p2);
        // Usar timeout alargado para operações bloqueantes
        byte[] res = request(Protocol.WAIT_SIMUL, baos.toByteArray(), BLOCKING_TIMEOUT_MS);
        return res.length > 0 && res[0] == 1;
    }

    /**
     * Aguarda uma sequência de N vendas consecutivas do mesmo produto.
     * Bloqueia a thread até que a condição seja satisfeita ou até que
     * o dia termine.
     *
     * NOTA: Esta é uma operação bloqueante com timeout alargado.
     *
     * @param n Número mínimo de vendas consecutivas
     * @return Nome do produto que atingiu a meta, ou null se o dia terminou
     * @throws IOException          Se ocorrer erro de comunicação
     * @throws InterruptedException Se a operação for interrompida
     */
    public String waitConsecutive(int n)
            throws IOException, InterruptedException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeInt(n);
        // Usar timeout alargado para operações bloqueantes
        byte[] res = request(Protocol.WAIT_CONSEC, baos.toByteArray(), BLOCKING_TIMEOUT_MS);
        if (res.length == 0) {
            return null;
        }
        return new DataInputStream(new ByteArrayInputStream(res)).readUTF();
    }

    /**
     * Consulta o dia atual do servidor.
     *
     * @return Número do dia corrente
     * @throws IOException          Se ocorrer erro de comunicação
     * @throws InterruptedException Se a operação for interrompida
     */
    public int getCurrentDay() throws IOException, InterruptedException {
        byte[] res = request(Protocol.GET_CURRENT_DAY, new byte[0]);
        return new DataInputStream(new ByteArrayInputStream(res)).readInt();
    }

    /**
     * Encerra a ligação com o servidor.
     *
     * @throws IOException Se ocorrer erro ao fechar a conexão
     */
    @Override
    public void close() throws IOException {
        demux.close();
    }
}