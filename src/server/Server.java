package server;

import common.FramedStream;
import java.io.File;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Ponto de entrada do servidor de séries temporais.
 *
 * Inicializa todos os componentes do sistema (gestão de utilizadores,
 * motor de armazenamento, notificações e pool de threads) e aceita
 * conexões de clientes de forma contínua.
 */
public class Server {

    /**
     * Método principal que configura e inicia o servidor.
     *
     * @param args Argumentos de linha de comando: [port] [S] [D] [threads]
     *             port: Porto TCP (default: 12345)
     *             S: Número máximo de séries em memória (default: 10)
     *             D: Janela de retenção em dias (default: 365)
     *             threads: Tamanho do pool de workers (default: 100)
     * @throws Exception Em caso de falha crítica de inicialização
     */
    public static void main(String[] args) throws Exception {
        int port = 12345;
        int S = 10;
        int D = 365;
        int threads = 100;

        // Processamento de argumentos com validação
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
                    System.err.println("ERRO: S (séries em memória) deve ser >= 1.");
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
            try {
                threads = Integer.parseInt(args[3]);
                if (threads < 1) {
                    System.err.println("ERRO: Threads deve ser >= 1.");
                    System.exit(1);
                }
            } catch (NumberFormatException e) {
                System.err.println("ERRO: Threads inválido: " + args[3]);
                System.exit(1);
            }
        }

        if (args.length > 4) {
            System.err.println("AVISO: Argumentos excedentes ignorados.");
        }

        // Criação do diretório de dados
        File dataDir = new File("data");
        if (!dataDir.exists()) {
            dataDir.mkdir();
        }

        // Inicialização dos componentes principais
        UserManager um = new UserManager();
        StorageEngine se = new StorageEngine(S, D);
        NotificationManager nm = new NotificationManager();
        ThreadPool wp = new ThreadPool(threads);

        try (ServerSocket ss = new ServerSocket(port)) {
            System.out.println("========================================");
            System.out.println("Servidor ativo em: localhost:" + port);
            System.out.println("Mecanismo de persistência iniciado com:");
            System.out.println("  S (séries em memória): " + S);
            System.out.println("  D (dias históricos): " + D);
            System.out.println("  Pool de workers: " + threads + " threads");
            System.out.println("========================================\n");
            System.out.println("À espera de conexões...");

            // Loop de aceitação de conexões
            while (true) {
                Socket s = ss.accept();
                new Thread(
                        new ClientHandler(new FramedStream(s), um, se, nm, wp, D)
                ).start();
            }
        } finally {
            wp.shutdown();
        }
    }
}