package server;

import common.FramedStream;
import java.io.File;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * ================================================================================
 * SERVER.JAVA - Ponto de Entrada do Servidor
 * ================================================================================
 *
 * PAPEL NO SISTEMA:
 * -----------------
 * Esta é a classe principal do servidor. Responsável por:
 * 1. Inicializar todos os componentes do sistema
 * 2. Aceitar conexões de clientes
 * 3. Criar handlers para cada conexão
 *
 * ARQUITETURA DO SERVIDOR:
 * ------------------------
 *
 *    ┌─────────────────────────────────────────────────────────────────────┐
 *    │                           Server.java                               │
 *    │  - Aceita conexões (blocking accept())                              │
 *    │  - Cria uma thread ClientHandler por conexão                        │
 *    └─────────────────────────────────────────────────────────────────────┘
 *                                    │
 *                                    ▼
 *    ┌─────────────────────────────────────────────────────────────────────┐
 *    │                        ClientHandler.java                           │
 *    │  - Uma instância por cliente conectado                              │
 *    │  - Lê pedidos e delega ao ThreadPool                                │
 *    └─────────────────────────────────────────────────────────────────────┘
 *                                    │
 *                                    ▼
 *    ┌─────────────────────────────────────────────────────────────────────┐
 *    │                          ThreadPool.java                            │
 *    │  - Pool de workers fixo                                             │
 *    │  - Executa a lógica de negócio                                      │
 *    └─────────────────────────────────────────────────────────────────────┘
 *             │                      │                      │
 *             ▼                      ▼                      ▼
 *    ┌─────────────┐       ┌─────────────────┐    ┌──────────────────────┐
 *    │ UserManager │       │ StorageEngine   │    │ NotificationManager  │
 *    │             │       │                 │    │                      │
 *    │ - Registo   │       │ - Eventos       │    │ - waitSimultaneous   │
 *    │ - Login     │       │ - Agregações    │    │ - waitConsecutive    │
 *    │ - Password  │       │ - Persistência  │    │ - newDay signals     │
 *    └─────────────┘       └─────────────────┘    └──────────────────────┘
 *
 *
 * MODELO DE THREADS:
 * ------------------
 *
 * Thread Principal (main):
 *   - Aceita conexões em loop infinito (blocking)
 *   - Cria novas threads ClientHandler
 *
 * Threads ClientHandler (1 por cliente):
 *   - Lê frames do cliente em loop
 *   - Submete tarefas ao ThreadPool
 *   - Termina quando o cliente desconecta
 *
 * Threads Worker (pool fixo):
 *   - Executam a lógica de negócio
 *   - Partilhadas entre todos os clientes
 *   - Número configurável (argumento 'threads')
 *
 * PORQUÊ ESTE MODELO?
 * -------------------
 *
 * Alternativa 1: Uma thread por pedido
 *   - Problema: 1000 clientes × 10 pedidos/segundo = 10000 threads
 *   - Custo de criação/destruição de threads é alto
 *   - Overhead de memória (stack por thread)
 *
 * Alternativa 2: Thread única (event loop)
 *   - Problema: Não aproveita múltiplos CPUs
 *   - Operações bloqueantes param tudo
 *
 * Nossa solução: Pool de threads + 1 handler por conexão
 *   - Número limitado de threads workers (não explode)
 *   - Aproveita múltiplos CPUs
 *   - Handlers dedicados mantêm conexões TCP abertas
 *   - Operações bloqueantes (notificações) não afetam outras
 */
public class Server {

    /**
     * Timeout de socket para detetar clientes inativos (2 minutos).
     *
     * PROPÓSITO:
     * Se um cliente parar de comunicar (crash, rede partida, etc.),
     * sem timeout ficaríamos bloqueados em read() para sempre.
     *
     * Com timeout, read() lança SocketTimeoutException após 2 min.
     * O ClientHandler pode então verificar se deve continuar ou terminar.
     *
     * PORQUÊ 2 MINUTOS?
     * - Suficientemente longo para clientes com rede lenta
     * - Curto o suficiente para detetar problemas em tempo útil
     * - Valor típico para aplicações cliente-servidor
     */
    private static final int SOCKET_TIMEOUT_MS = 120000;

    /**
     * Método principal que configura e inicia o servidor.
     *
     * PARÂMETROS DE CONFIGURAÇÃO:
     * - port: Porto TCP onde o servidor escuta (1024-65535)
     * - S: Número máximo de séries (dias) em memória (cache LRU)
     * - D: Janela de retenção em dias (dados mais antigos são apagados)
     * - threads: Tamanho do pool de workers
     *
     * @param args Argumentos de linha de comando: [port] [S] [D] [threads]
     * @throws Exception Em caso de falha crítica de inicialização
     */
    public static void main(String[] args) throws Exception {
        // ===== VALORES POR DEFEITO =====
        // Escolhidos para funcionar bem em cenários típicos
        int port = 12345;       // Porto não privilegiado
        int S = 10;             // 10 dias em memória
        int D = 365;            // 1 ano de histórico
        int threads = 100;      // Workers suficientes para muitos clientes

        // ===== PARSING DO PORTO =====
        if (args.length > 0) {
            try {
                port = Integer.parseInt(args[0]);

                // Validação: portos < 1024 requerem root
                if (port < 1024 || port > 65535) {
                    System.err.println("ERRO: Porto deve estar entre 1024 e 65535.");
                    System.exit(1);
                }
            } catch (NumberFormatException e) {
                System.err.println("ERRO: Porto inválido: " + args[0]);
                System.exit(1);
            }
        }

        // ===== PARSING DO S (SÉRIES EM MEMÓRIA) =====
        // Controla o tamanho da cache LRU no StorageEngine
        if (args.length > 1) {
            try {
                S = Integer.parseInt(args[1]);

                // Mínimo 1, senão a cache não faz sentido
                if (S < 1) {
                    System.err.println("ERRO: S (séries em memória) deve ser >= 1.");
                    System.exit(1);
                }
            } catch (NumberFormatException e) {
                System.err.println("ERRO: S inválido: " + args[1]);
                System.exit(1);
            }
        }

        // ===== PARSING DO D (DIAS HISTÓRICOS) =====
        // Controla a janela de retenção de dados
        if (args.length > 2) {
            try {
                D = Integer.parseInt(args[2]);

                // Mínimo 1 dia de histórico
                if (D < 1) {
                    System.err.println("ERRO: D (dias históricos) deve ser >= 1.");
                    System.exit(1);
                }
            } catch (NumberFormatException e) {
                System.err.println("ERRO: D inválido: " + args[2]);
                System.exit(1);
            }
        }

        // ===== PARSING DO NÚMERO DE THREADS =====
        // Controla quantos workers processam pedidos em paralelo
        if (args.length > 3) {
            try {
                threads = Integer.parseInt(args[3]);

                // Pelo menos 1 worker
                if (threads < 1) {
                    System.err.println("ERRO: Threads deve ser >= 1.");
                    System.exit(1);
                }
            } catch (NumberFormatException e) {
                System.err.println("ERRO: Threads inválido: " + args[3]);
                System.exit(1);
            }
        }

        // Aviso sobre argumentos extra
        if (args.length > 4) {
            System.err.println("AVISO: Argumentos excedentes ignorados.");
        }

        // ===== CRIAÇÃO DO DIRETÓRIO DE DADOS =====
        // Onde ficam guardados os ficheiros de utilizadores e eventos
        File dataDir = new File("data");
        if (!dataDir.exists()) {
            // mkdir() retorna true se criou, false se já existe ou falhou
            dataDir.mkdir();
        }

        // ===== INICIALIZAÇÃO DOS COMPONENTES =====
        // A ordem importa: alguns componentes podem depender de outros

        // UserManager: Gestão de utilizadores e autenticação
        // Carrega utilizadores do disco se existirem
        UserManager um = new UserManager();

        // StorageEngine: Armazenamento e consulta de eventos
        // Carrega estado (dia atual) do disco se existir
        StorageEngine se = new StorageEngine(S, D);

        // NotificationManager: Coordenação de notificações em tempo real
        // Estado puramente em memória (reset a cada reinício)
        NotificationManager nm = new NotificationManager();

        // ThreadPool: Pool de threads workers
        // Cria 'threads' workers que ficam à espera de tarefas
        ThreadPool wp = new ThreadPool(threads);

        // ===== INÍCIO DO SERVIDOR =====
        // ServerSocket escuta num porto específico por conexões TCP
        try (ServerSocket ss = new ServerSocket(port)) {

            // ===== BANNER DE INÍCIO =====
            System.out.println("========================================");
            System.out.println("Servidor ativo em: localhost:" + port);
            System.out.println("Mecanismo de persistência iniciado com:");
            System.out.println("  S (séries em memória): " + S);
            System.out.println("  D (dias históricos): " + D);
            System.out.println("  Pool de workers: " + threads + " threads");
            System.out.println("  Timeout de socket: " + (SOCKET_TIMEOUT_MS / 1000) + " segundos");
            System.out.println("========================================\n");
            System.out.println("À espera de conexões...");

            // ===== LOOP DE ACEITAÇÃO DE CONEXÕES =====
            // Loop infinito que aceita clientes um a um
            while (true) {
                // ===== ACCEPT BLOQUEANTE =====
                // accept() bloqueia até que um cliente se conecte
                // Retorna um Socket representando a conexão
                //
                // NOTA: O ServerSocket NÃO é consumido - pode aceitar múltiplas conexões
                Socket s = ss.accept();

                // ===== CONFIGURAÇÃO DO SOCKET =====

                // Timeout de leitura: após SOCKET_TIMEOUT_MS sem dados,
                // read() lança SocketTimeoutException
                // Isto permite detetar clientes inativos/mortos
                s.setSoTimeout(SOCKET_TIMEOUT_MS);

                // Desativar Nagle's Algorithm para menor latência
                // Ver comentários em FramedStream.java para detalhes
                s.setTcpNoDelay(true);

                // ===== LOG DA CONEXÃO =====
                // getRemoteSocketAddress() retorna IP:porta do cliente
                System.out.println("[Server] Nova conexão de: " + s.getRemoteSocketAddress());

                // ===== CRIAÇÃO DO HANDLER =====
                // Cada cliente tem o seu próprio ClientHandler
                // que corre numa thread dedicada
                //
                // Passamos todos os componentes partilhados:
                // - FramedStream: comunicação com este cliente específico
                // - um, se, nm, wp: componentes partilhados do servidor
                // - D: para validação de parâmetros
                ClientHandler handler = new ClientHandler(
                        new FramedStream(s, SOCKET_TIMEOUT_MS),  // Stream para este cliente
                        um,     // UserManager partilhado
                        se,     // StorageEngine partilhado
                        nm,     // NotificationManager partilhado
                        wp,     // ThreadPool partilhado
                        D       // Janela de retenção para validação
                );

                // ===== LANÇAMENTO DA THREAD =====
                // new Thread(...).start() cria e inicia imediatamente
                // A thread executa handler.run() em paralelo
                // O main() pode voltar a accept() imediatamente
                new Thread(handler).start();
            }

        } finally {
            // ===== CLEANUP =====
            // Este bloco executa quando o servidor termina (Ctrl+C, exceção, etc.)
            // Importante fazer shutdown do pool para não deixar threads órfãs
            wp.shutdown();
        }
    }
}