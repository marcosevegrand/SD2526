package server;

import common.FramedStream;
import java.io.File;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Orquestrador central do serviço de backend.
 * A intenção desta classe é inicializar os motores de armazenamento, segurança e rede,
 * aceitando novas conexões de forma ininterrupta.
 */
public class Server {

    /**
     * Método principal que configura as dependências e inicia o loop de aceitação TCP.
     * @param args Não utilizado.
     * @throws Exception Em caso de falhas críticas de hardware ou rede.
     */
    public static void main(String[] args) throws Exception {
        // Configurações de limite de sistema
        int S = 10; // Séries em RAM
        int D = 365; // Dias de retenção
        int port = 12345;

        // Garante a existência da pasta de dados para evitar FileNotFoundException
        File dataDir = new File("data");
        if (!dataDir.exists()) dataDir.mkdir();

        // O pool de 100 threads permite lidar com um volume elevado de pedidos concorrentes
        ExecutorService workerPool = Executors.newFixedThreadPool(100);

        UserManager um = new UserManager();
        StorageEngine se = new StorageEngine(S, D);
        NotificationManager nm = new NotificationManager();

        try (ServerSocket ss = new ServerSocket(port)) {
            System.out.println("Servidor ativo no porto: " + port);

            while (true) {
                Socket s = ss.accept();
                // Cada socket tem a sua própria thread de escuta para garantir isolamento
                new Thread(
                    new ClientHandler(
                        new FramedStream(s),
                        um,
                        se,
                        nm,
                        workerPool
                    )
                ).start();
            }
        } finally {
            workerPool.shutdown();
        }
    }
}
