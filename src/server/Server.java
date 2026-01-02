package server;

import common.FramedStream;
import java.io.File;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import server.ThreadPool;

/**
 * Orquestrador central do serviço de backend.
 * A intenção desta classe é inicializar os motores de armazenamento, segurança e rede,
 * aceitando novas conexões de forma ininterrupta.
 */
public class Server {

    /**
     * Método principal que configura as dependências e inicia o loop de aceitação TCP.
     * @param args Argumentos de linha de comando: [port] [S] [D]
     *             - port: porta TCP onde o servidor escuta (default: 12345)
     *             - S: número máximo de séries em memória (default: 10)
     *             - D: janela de retenção de dias históricos (default: 365)
     * @throws Exception Em caso de falhas críticas de hardware ou rede.
     */
    public static void main(String[] args) throws Exception {
        // Parse command-line arguments with defaults
        int port = 12345;
        int S = 10;
        int D = 365;

        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);
                if (port < 1024 || port > 65535) {
                    System.err.println("ERRO: Porto deve estar entre 1024 e 65535.");
                    System.exit(1);
                }
            } catch (NumberFormatException e) {
                System.err.println("ERRO: Porto inválido: " + args[0]);
                System.exit(1);
            }
        }

        if (args.length > 1) {
            try {
                S = Integer.parseInt(args[1]);
                if (S < 1) {
                    System.err.println("ERRO: S (série em memória) deve ser >= 1.");
                    System.exit(1);
                }
            } catch (NumberFormatException e) {
                System.err.println("ERRO: S inválido: " + args[1]);
                System.exit(1);
            }
        }

        if (args.length > 2) {
            try {
                D = Integer.parseInt(args[2]);
                if (D < 1) {
                    System.err.println("ERRO: D (dias históricos) deve ser >= 1.");
                    System.exit(1);
                }
            } catch (NumberFormatException e) {
                System.err.println("ERRO: D inválido: " + args[2]);
                System.exit(1);
            }
        }

        if (args.length > 3) {
            System.err.println("AVISO: Argumentos excedentes ignorados.");
        }

        // Garante a existência da pasta de dados para evitar FileNotFoundException
        File dataDir = new File("data");
        if (!dataDir.exists()) dataDir.mkdir();

        // A inicialização destas classes dispara o carregamento automático do estado persistente
        UserManager um = new UserManager();
        StorageEngine se = new StorageEngine(S, D);
        NotificationManager nm = new NotificationManager();
        // A pool agora de 100 threads permite lidar com um volume elevado de pedidos concorrentes
        ThreadPool wp = new ThreadPool(100);

        try (ServerSocket ss = new ServerSocket(port)) {
            System.out.println("========================================");
            System.out.println("Servidor ativo em: localhost:" + port);
            System.out.println("Mecanismo de persistência iniciado com:");
            System.out.println("  S (séries em memória): " + S);
            System.out.println("  D (dias históricos): " + D);
            System.out.println("  Pool de workers: 100 threads");
            System.out.println("========================================\n");
            System.out.println("À espera de conexões...");

            while (true) {
                Socket s = ss.accept();
                // Cada socket tem a sua própria thread de escuta para garantir isolamento
                new Thread(
                    new ClientHandler(new FramedStream(s), um, se, nm, wp, S, D)
                ).start();
            }
        } finally {
            wp.shutdown();
        }
    }
}
