package test;

import client.ClientLib;
import common.Protocol;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Teste de Stress Massivo com logs detalhados (Verbo) para monitorização da concorrência.
 */
public class StressTest {

    private static final String HOST = "localhost";
    private static final int PORT = 12345;

    // Configurações da carga
    private static final int NUM_CLIENTS = 5;
    private static final int THREADS_PER_CLIENT = 10;
    private static final int INSERTIONS_PER_THREAD = 100000;
    private static final String PRODUCT_NAME = "SuperCPU";

    private static final AtomicLong expectedQty = new AtomicLong(0);
    private static final DoubleAdder expectedVol = new DoubleAdder();
    private static final AtomicLong completedThreads = new AtomicLong(0);

    public static void main(String[] args) {
        System.out.println("=== TESTE DE STRESS ===");
        int totalThreads = NUM_CLIENTS * THREADS_PER_CLIENT;
        int totalExpected = totalThreads * INSERTIONS_PER_THREAD;

        System.out.printf(
            "[CONFIG] Clientes: %d | Threads/Cliente: %d | Total Threads: %d%n",
            NUM_CLIENTS,
            THREADS_PER_CLIENT,
            totalThreads
        );
        System.out.printf(
            "[CONFIG] Inserções p/ thread: %d | Objetivo total: %d eventos%n",
            INSERTIONS_PER_THREAD,
            totalExpected
        );

        ExecutorService executor = Executors.newFixedThreadPool(totalThreads);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch endLatch = new CountDownLatch(totalThreads);
        List<ClientLib> workerClients = new ArrayList<>();

        try {
            // 1. Inicialização de Clientes
            System.out.println(
                "\n[1/4] A estabelecer ligações e autenticar clientes..."
            );
            for (int c = 0; c < NUM_CLIENTS; c++) {
                final int cid = c;
                ClientLib client = new ClientLib(HOST, PORT);
                workerClients.add(client);

                String user = "stress_user_" + cid;
                client.register(user, "pass");
                if (client.login(user, "pass")) {
                    System.out.printf(
                        "  > Cliente %d ligado como '%s'%n",
                        cid,
                        user
                    );
                }

                for (int t = 0; t < THREADS_PER_CLIENT; t++) {
                    final int tid = t;
                    executor.execute(() -> {
                        try {
                            startLatch.await(); // Aguarda o tiro de partida
                            for (int i = 1; i <= INSERTIONS_PER_THREAD; i++) {
                                client.addEvent(PRODUCT_NAME, 1, 10.0);
                                expectedQty.incrementAndGet();
                                expectedVol.add(10.0);

                                // Log de progresso a cada 25% de cada thread
                                if (i % (INSERTIONS_PER_THREAD / 4) == 0) {
                                    System.out.printf(
                                        "[THREAD C%d-T%d] Progresso: %d/%d eventos.%n",
                                        cid,
                                        tid,
                                        i,
                                        INSERTIONS_PER_THREAD
                                    );
                                }
                            }
                        } catch (Exception e) {
                            System.err.printf(
                                "[ERRO C%d-T%d] %s%n",
                                cid,
                                tid,
                                e.getMessage()
                            );
                        } finally {
                            endLatch.countDown();
                            long finished = completedThreads.incrementAndGet();
                            System.out.printf(
                                "[INFO] Thread C%d-T%d terminada. (%d/%d concluídas)%n",
                                cid,
                                tid,
                                finished,
                                totalThreads
                            );
                        }
                    });
                }
            }

            // 2. Execução
            System.out.println(
                "\n[2/4] DISPARO: A iniciar inserções concorrentes..."
            );
            long startTime = System.currentTimeMillis();
            startLatch.countDown();

            if (!endLatch.await(1, TimeUnit.MINUTES)) {
                System.out.println(
                    "[AVISO] Timeout atingido! Algumas threads não terminaram."
                );
            }
            long duration = System.currentTimeMillis() - startTime;
            System.out.printf(
                "[SUCESSO] Inserções massivas concluídas em %d ms.%n",
                duration
            );

            // 3. Persistência
            System.out.println(
                "\n[3/4] A solicitar fecho do dia (NEW_DAY) para persistência..."
            );
            try (ClientLib admin = new ClientLib(HOST, PORT)) {
                admin.login("stress_user_0", "pass");
                admin.newDay(); // Garante que os dados vão para o disco
                System.out.println("  > Dados persistidos com sucesso.");

                // 4. Validação
                System.out.println(
                    "\n[4/4] A validar consistência de dados com o servidor..."
                );
                double serverQty = admin.getAggregation(
                    Protocol.AGGR_QTY,
                    PRODUCT_NAME,
                    1
                );
                double serverVol = admin.getAggregation(
                    Protocol.AGGR_VOL,
                    PRODUCT_NAME,
                    1
                );

                boolean qtyOk = serverQty == expectedQty.get();
                boolean volOk =
                    Math.abs(serverVol - expectedVol.doubleValue()) < 0.001;

                System.out.println(
                    "---------------- VALIDACÃO ----------------"
                );
                System.out.printf(
                    "  Quantidade -> Esperada: %d | No Servidor: %.0f [%s]%n",
                    expectedQty.get(),
                    serverQty,
                    qtyOk ? "OK" : "ERRO"
                );
                System.out.printf(
                    "  Volume     -> Esperado: %.2f | No Servidor: %.2f [%s]%n",
                    expectedVol.doubleValue(),
                    serverVol,
                    volOk ? "OK" : "ERRO"
                );
                System.out.println(
                    "-------------------------------------------"
                );

                if (qtyOk && volOk) {
                    System.out.println(
                        "RESULTADO FINAL: TESTE PASSOU. Concorrência íntegra."
                    );
                } else {
                    System.err.println(
                        "RESULTADO FINAL: TESTE FALHOU. Houve perda de dados!"
                    );
                }
            }
        } catch (Exception e) {
            System.err.println("[ERRO FATAL] " + e.getMessage());
            e.printStackTrace();
        } finally {
            System.out.println(
                "\n[LIMPEZA] A encerrar ligações e pool de threads..."
            );
            for (ClientLib c : workerClients) {
                try {
                    c.close();
                } catch (Exception ignored) {}
            }
            executor.shutdownNow();
            System.out.println("=== TESTE DE STRESS CONCLUÍDO ===");
        }
    }
}
