package client;

import common.FramedStream;
import common.Protocol;
import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Biblioteca de alto nível para interação com o serviço de séries temporais.
 * Esta classe abstrai a complexidade da rede e do protocolo binário, permitindo que a UI
 * ou outros módulos consumam o serviço através de chamadas de métodos simples.
 */
public class ClientLib implements AutoCloseable {

    private final Demultiplexer demux;
    private int tagCounter = 0;
    private final ReentrantLock tagLock = new ReentrantLock();

    /**
     * Inicializa a ligação com o servidor remoto.
     * É necessário estabelecer a base da comunicação TCP antes de iniciar o demultiplexador.
     * @param host Endereço de rede do servidor.
     * @param port Porto de escuta do servidor.
     * @throws IOException Se não for possível estabelecer a ligação física com o socket.
     */
    public ClientLib(String host, int port) throws IOException {
        this.demux = new Demultiplexer(
            new FramedStream(new Socket(host, port))
        );
        this.demux.start();
    }

    /**
     * Garante a unicidade de identificação para cada pedido enviado.
     * Utiliza-se um lock para evitar que duas threads obtenham a mesma tag simultaneamente.
     * @return Um identificador numérico único para a sessão atual.
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
     * Coordena o ciclo completo de envio de pedido e receção de resposta.
     * Este método centraliza a lógica de registo na tabela de pendentes antes do envio,
     * prevenindo que a resposta chegue antes de estarmos prontos para a receber.
     * @param type O código da operação definido no protocolo.
     * @param data O payload de dados já serializado.
     * @return O array de bytes contendo a resposta do servidor.
     * @throws IOException Caso ocorra uma falha de rede.
     * @throws InterruptedException Caso a thread seja interrompida durante a espera pela resposta.
     */
    private byte[] request(int type, byte[] data)
        throws IOException, InterruptedException {
        int tag = nextTag();
        demux.register(tag);
        demux.send(tag, type, data);
        return demux.receive(tag);
    }

    /**
     * Solicita a criação de uma nova conta de utilizador.
     * @param user Nome de utilizador desejado.
     * @param pass Palavra-passe associada.
     * @return true se o servidor confirmou o registo com sucesso, false caso contrário.
     * @throws IOException Se houver erro na transmissão de dados.
     * @throws InterruptedException Se a operação for interrompida.
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
     * Tenta autenticar o utilizador para permitir acesso às funções de negócio.
     * @param user Nome de utilizador.
     * @param pass Palavra-passe.
     * @return true se as credenciais forem válidas, false em caso de erro.
     * @throws IOException Erro de comunicação com o servidor.
     * @throws InterruptedException Thread interrompida durante a espera.
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
     * Regista uma nova ocorrência de venda no sistema.
     * @param prod Identificador do produto vendido.
     * @param qty Quantidade transacionada.
     * @param price Valor unitário da venda.
     * @throws IOException Se falhar o envio do evento.
     * @throws InterruptedException Se a thread for interrompida.
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
     * Sinaliza ao servidor que o dia atual deve ser fechado e persistido.
     * Esta operação é fundamental para mover dados da memória volátil para o armazenamento permanente.
     * @throws IOException Erro ao contactar o servidor.
     * @throws InterruptedException Se a thread for interrompida.
     */
    public void newDay() throws IOException, InterruptedException {
        request(Protocol.NEW_DAY, new byte[0]);
    }

    /**
     * Solicita um cálculo estatístico sobre o histórico de vendas de um produto.
     * @param type O tipo de agregação (Soma, Máximo, Média ou Volume).
     * @param prod O produto a analisar.
     * @param days O número de dias retroativos para o cálculo.
     * @return O resultado numérico da agregação calculada pelo servidor.
     * @throws IOException Caso ocorra erro de rede ou processamento remoto.
     * @throws InterruptedException Se a thread for interrompida.
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
     * Recupera eventos históricos filtrados e reconstrói as strings a partir do dicionário.
     * O uso de dicionário é uma otimização para evitar o envio repetido de strings longas na rede.
     * @param day O dia histórico pretendido.
     * @param filterSet O conjunto de produtos que desejamos filtrar.
     * @return Uma lista de strings formatadas com o detalhe das vendas.
     * @throws IOException Erro na desserialização ou rede.
     * @throws InterruptedException Se a thread for interrompida.
     */
    public List<String> getEvents(int day, Set<String> filterSet)
        throws IOException, InterruptedException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeInt(day);
        out.writeInt(filterSet.size());
        for (String s : filterSet) out.writeUTF(s);

        byte[] res = request(Protocol.FILTER, baos.toByteArray());
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(res));

        // Reconstrução do dicionário enviado pelo servidor para poupar largura de banda
        int dictSize = in.readInt();
        String[] dictionary = new String[dictSize];
        for (int i = 0; i < dictSize; i++) dictionary[i] = in.readUTF();

        int numEvents = in.readInt();
        List<String> events = new ArrayList<>();
        for (int i = 0; i < numEvents; i++) {
            // Cada evento vem com um índice inteiro que aponta para o dicionário global da mensagem
            String p = dictionary[in.readInt()];
            int q = in.readInt();
            double pr = in.readDouble();
            events.add(p + " | Qtd: " + q + " | Preço: " + pr);
        }
        return events;
    }

    /**
     * Coloca a thread em suspensão até que os dois produtos indicados sejam vendidos no mesmo dia.
     * @param p1 Primeiro produto.
     * @param p2 Segundo produto.
     * @return true se o evento ocorreu no dia, false se o dia terminou sem a ocorrência.
     * @throws IOException Falha de comunicação.
     * @throws InterruptedException Interrupção da thread durante o bloqueio.
     */
    public boolean waitSimultaneous(String p1, String p2)
        throws IOException, InterruptedException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeUTF(p1);
        out.writeUTF(p2);
        byte[] res = request(Protocol.WAIT_SIMUL, baos.toByteArray());
        return res.length > 0 && res[0] == 1;
    }

    /**
     * Aguarda que ocorra uma sequência ininterrupta de vendas de um mesmo produto.
     * @param n Número de vendas consecutivas necessárias.
     * @return O nome do produto que atingiu a meta, ou null se o dia encerrou primeiro.
     * @throws IOException Erro de rede.
     * @throws InterruptedException Interrupção da thread.
     */
    public String waitConsecutive(int n)
        throws IOException, InterruptedException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);
        out.writeInt(n);
        byte[] res = request(Protocol.WAIT_CONSEC, baos.toByteArray());
        if (res.length == 0) return null;
        return new DataInputStream(new ByteArrayInputStream(res)).readUTF();
    }

    /**
     * Encerra todos os recursos de rede associados à biblioteca.
     * @throws IOException Se ocorrer um erro ao fechar o demultiplexador ou socket.
     */
    @Override
    public void close() throws IOException {
        demux.close();
    }
}
