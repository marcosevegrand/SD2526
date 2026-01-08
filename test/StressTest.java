package test;

import client.ClientLib;
import common.Protocol;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Suite de Teste de Stress de Alta Capacidade.
 * Melhorias em relação à versão anterior:
 * 1. Redução de contenção no cliente (acumulação local vs global).
 * 2. Simulação de "Hotspots" (muita escrita no mesmo item) vs "High Cardinality" (muitos itens distintos).
 * 3. Cálculo preciso de Throughput.
 * 4. Validação granular por produto.
 */
public class StressTest {

    private static final String HOST = "localhost";
    private static final int PORT = 12345;

    // --- CONFIGURAÇÃO DE CARGA ---
    // Ajuste estes valores conforme a capacidade da sua máquina
    private static final int NUM_CLIENTS = 10;           // Ex: 10 Clientes (Conexões)
    private static final int THREADS_PER_CLIENT = 5;     // Ex: 5 Threads por Cliente
    private static final int INSERTIONS_PER_THREAD = 20_000; // Ex: 20k ops por thread

    // Configuração dos Produtos
    private static final String HOTSPOT_PRODUCT = "SuperItem_HOTSPOT";
    private static final int DISTINCT_PRODUCTS_COUNT = 100; // Gera produtos Produto_0 a Produto_99

    // Totais calculados
    private static final int TOTAL_THREADS = NUM_CLIENTS * THREADS_PER_CLIENT;
    private static final int TOTAL_OPERATIONS = TOTAL_THREADS * INSERTIONS_PER_THREAD;

    // Estruturas de Validação (Thread-Safe para leitura final)
    // Mapa: NomeProduto -> Quantidade Esperada
    private static final ConcurrentHashMap<String, AtomicLong> expectedQtyMap = new ConcurrentHashMap<>();
    // Mapa: NomeProduto -> Volume Esperado
    private static final ConcurrentHashMap<String, DoubleAdder> expectedVolMap = new ConcurrentHashMap<>();

    private static int testsPassed = 0;
    private static int testsFailed = 0;

    public static void main(String[] args) {
        System.out.println("╔════════════════════════════════════════════════════════════╗");
        System.out.println("║            STRESS TEST - ALTA CAPACIDADE                   ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝\n");

        printConfig();

        ExecutorService executor = Executors.newFixedThreadPool(TOTAL_THREADS);
        List<ClientLib> clients = new ArrayList<>();
        CyclicBarrier barrier = new CyclicBarrier(TOTAL_THREADS + 1); // +1 para a main thread

        try {
            // ═══════════════════════════════════════════════════════════════════════════
            // 1. PREPARAÇÃO (SETUP)
            // ═══════════════════════════════════════════════════════════════════════════
            printSection("1. INICIALIZAÇÃO E AQUECIMENTO");

            long setupStart = System.currentTimeMillis();
            for (int c = 0; c < NUM_CLIENTS; c++) {
                final int clientId = c; // Variável final para uso na lambda
                try {
                    ClientLib client = new ClientLib(HOST, PORT);
                    String user = "stress_bot_" + clientId;
                    client.register(user, "pwd");
                    client.login(user, "pwd");
                    clients.add(client);

                    // Prepara as threads para este cliente
                    for (int t = 0; t < THREADS_PER_CLIENT; t++) {
                        final int threadId = t;
                        final ClientLib threadClient = client; // ClientLib deve ser Thread-Safe

                        executor.submit(() -> {
                            runWorker(threadClient, barrier, clientId, threadId);
                        });
                    }
                } catch (Exception e) {
                    System.err.println("  [ERRO] Falha ao criar cliente " + clientId + ": " + e.getMessage());
                }
            }
            long setupTime = System.currentTimeMillis() - setupStart;
            System.out.printf("  ✓ %d Clientes e %d Threads prontos em %d ms.%n",
                    clients.size(), TOTAL_THREADS, setupTime);

            // ═══════════════════════════════════════════════════════════════════════════
            // 2. EXECUÇÃO (LOAD)
            // ═══════════════════════════════════════════════════════════════════════════
            printSection("2. EXECUÇÃO DE CARGA");
            System.out.println("  >> A aguardar sincronização de todas as threads...");

            // Disparo inicial
            barrier.await();
            long startTime = System.currentTimeMillis();
            System.out.println("  >> DISPARO! Inserindo eventos...");

            // A main thread aguarda o fim das threads de trabalho
            // Nota: Como estamos usando CyclicBarrier dentro do runWorker para o inicio,
            // precisamos de um mecanismo para esperar o fim. O executor.shutdown e await faz isso.
            executor.shutdown();
            if (!executor.awaitTermination(5, TimeUnit.MINUTES)) {
                System.err.println("  [ERRO] Timeout atingido! O teste demorou demasiado.");
                executor.shutdownNow();
            }

            long endTime = System.currentTimeMillis();
            long duration = endTime - startTime;
            double throughput = (double) TOTAL_OPERATIONS / (duration / 1000.0);

            System.out.printf("  ✓ Carga concluída.%n");
            System.out.printf("  ✓ Tempo Total: %d ms%n", duration);
            System.out.printf("  ✓ Throughput Estimado: %.2f ops/sec%n", throughput);

            // ═══════════════════════════════════════════════════════════════════════════
            // 3. PERSISTÊNCIA E SINCRONIZAÇÃO
            // ═══════════════════════════════════════════════════════════════════════════
            printSection("3. PERSISTÊNCIA (NEW_DAY)");

            // Usar um cliente novo para o flush administrativo
            try (ClientLib admin = new ClientLib(HOST, PORT)) {
                admin.login("stress_bot_0", "pwd");
                admin.newDay();
                System.out.println("  ✓ Comando newDay() enviado. Dados devem estar em disco/memória estática.");
            }

            // ═══════════════════════════════════════════════════════════════════════════
            // 4. VALIDAÇÃO DE INTEGRIDADE
            // ═══════════════════════════════════════════════════════════════════════════
            printSection("4. VALIDAÇÃO DOS DADOS");

            validateResults();

        } catch (Exception e) {
            System.err.println("[ERRO FATAL] " + e.getMessage());
            e.printStackTrace();
        } finally {
            // Limpeza
            for (ClientLib c : clients) {
                try { c.close(); } catch (Exception ignored) {}
            }
        }

        printReport();
    }

    /**
     * Lógica de cada thread trabalhadora.
     */
    private static void runWorker(ClientLib client, CyclicBarrier barrier, int clientId, int threadId) {
        // Mapas locais para evitar contenção nos Atomics globais durante o loop crítico
        Map<String, Long> localQty = new HashMap<>();
        Map<String, Double> localVol = new HashMap<>();

        try {
            // Sincroniza início
            barrier.await();

            for (int i = 0; i < INSERTIONS_PER_THREAD; i++) {
                String product;
                double price;
                int qty = 1;

                // 30% das vezes escreve no Hotspot (concorrência alta no mesmo recurso)
                // 70% das vezes distribui entre produtos distintos (concorrência no Map global)
                if (i % 10 < 3) {
                    product = HOTSPOT_PRODUCT;
                    price = 10.0;
                } else {
                    int prodId = i % DISTINCT_PRODUCTS_COUNT;
                    product = "Prod_" + prodId;
                    price = 1.0 + (prodId * 0.1);
                }

                client.addEvent(product, qty, price);

                // Atualizar estado local
                localQty.merge(product, (long) qty, Long::sum);
                localVol.merge(product, (double) qty * price, Double::sum);
            }

        } catch (Exception e) {
            System.err.printf("[ERRO C%d-T%d] %s%n", clientId, threadId, e.getMessage());
        } finally {
            // Commit dos dados locais para os mapas globais de validação
            localQty.forEach((k, v) -> {
                expectedQtyMap.computeIfAbsent(k, x -> new AtomicLong(0)).addAndGet(v);
            });
            localVol.forEach((k, v) -> {
                expectedVolMap.computeIfAbsent(k, x -> new DoubleAdder()).add(v);
            });
        }
    }

    /**
     * Valida os resultados consultando o servidor.
     */
    private static void validateResults() {
        try (ClientLib validator = new ClientLib(HOST, PORT)) {
            validator.login("stress_bot_0", "pwd");

            // Validar Hotspot
            validateProduct(validator, HOTSPOT_PRODUCT);

            // Validar Amostra de Produtos Dispersos (para não demorar eternamente validando 1000 produtos)
            int samplesToCheck = Math.min(10, DISTINCT_PRODUCTS_COUNT);
            for (int i = 0; i < samplesToCheck; i++) {
                validateProduct(validator, "Prod_" + i);
            }

        } catch (Exception e) {
            System.err.println("Erro na validação: " + e.getMessage());
        }
    }

    private static void validateProduct(ClientLib client, String product) throws Exception {
        if (!expectedQtyMap.containsKey(product)) return;

        long expQty = expectedQtyMap.get(product).get();
        double expVol = expectedVolMap.get(product).doubleValue();

        // Obtem dados do último dia (d=1, pois fizemos newDay logo após a carga)
        double srvQty = client.getAggregation(Protocol.AGGR_QTY, product, 1);
        double srvVol = client.getAggregation(Protocol.AGGR_VOL, product, 1);

        boolean qtyOk = (long) srvQty == expQty;
        boolean volOk = Math.abs(srvVol - expVol) < 0.01; // Tolerância para ponto flutuante

        // "  OK  " ocupa 6 caracteres, assim como "FALHOU"
        String status = (qtyOk && volOk) ? "  OK  " : "FALHOU";

        System.out.printf("  [%s] Produto: %-20s | Qtd: %5d vs %-5.0f | Vol: %8.2f vs %-8.2f%n",
                status,
                product,
                expQty, srvQty,
                expVol, srvVol
        );

        if (qtyOk && volOk) {
            testsPassed++;
        } else {
            testsFailed++;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UTILITÁRIOS VISUAIS
    // ═══════════════════════════════════════════════════════════════════════════

    private static void printConfig() {
        System.out.println("CONFIGURAÇÃO:");
        System.out.printf("  Clientes:           %d%n", NUM_CLIENTS);
        System.out.printf("  Threads/Cliente:    %d%n", THREADS_PER_CLIENT);
        System.out.printf("  Inserções/Thread:   %d%n", INSERTIONS_PER_THREAD);
        System.out.printf("  Total Eventos:      %d%n", TOTAL_OPERATIONS);
        System.out.printf("  Produtos Distintos: %d (+ Hotspot)%n", DISTINCT_PRODUCTS_COUNT);
        System.out.println("─────────────────────────────────────────────────────────────");
    }

    private static void printSection(String title) {
        System.out.println("\n┌─────────────────────────────────────────────────────────────┐");
        System.out.printf("│ %-59s │%n", title);
        System.out.println("└─────────────────────────────────────────────────────────────┘");
    }

    private static void printReport() {
        System.out.println("\n╔════════════════════════════════════════════════════════════╗");
        System.out.println("║                    RELATÓRIO FINAL                         ║");
        System.out.println("╠════════════════════════════════════════════════════════════╣");
        System.out.printf("║  Testes (Produtos): %-3d                                    ║%n", testsPassed + testsFailed);
        System.out.printf("║  Sucesso:           %-3d                                    ║%n", testsPassed);
        System.out.printf("║  Falhas:            %-3d                                    ║%n", testsFailed);

        double rate = (testsPassed + testsFailed) == 0 ? 0 : (testsPassed * 100.0) / (testsPassed + testsFailed);
        System.out.printf("║  Integridade:       %.1f%%                                   ║%n", rate);
        System.out.println("╚════════════════════════════════════════════════════════════╝");
    }
}