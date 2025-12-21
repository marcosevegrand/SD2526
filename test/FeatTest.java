package test;

import client.ClientLib;
import common.Protocol;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class FeatTest {

    private static final String HOST = "localhost";
    private static final int PORT = 12345;

    private static void log(String threadId, String message) {
        System.out.printf("[%s] %s%n", threadId, message);
    }

    public static void main(String[] args) {
        System.out.println("=== INICIANDO TESTE DE STRESS CONTROLADO ===");

        ExecutorService executor = Executors.newCachedThreadPool();
        CountDownLatch noiseLatch = new CountDownLatch(2);
        CountDownLatch totalLatch = new CountDownLatch(3);

        try (ClientLib admin = new ClientLib(HOST, PORT)) {
            String adminId = "MAIN-ADMIN";
            admin.register("admin", "pass123");
            if (!admin.login("admin", "pass123")) return;

            launchSimulMonitor(executor);
            launchConsecMonitor(executor);

            Thread.sleep(1000);

            executor.execute(() -> runNoiseWorker(1, noiseLatch, totalLatch));
            executor.execute(() -> runNoiseWorker(3, noiseLatch, totalLatch));

            log(adminId, "Aguardando fim do ruído...");
            noiseLatch.await(10, TimeUnit.SECONDS);
            Thread.sleep(500);

            executor.execute(() -> runConsecutiveWorker(2, totalLatch));

            // Aguarda que o worker da sequência termine
            totalLatch.await(30, TimeUnit.SECONDS);

            // --- ESTA É A ALTERAÇÃO ---
            // Pequena pausa para dar tempo ao Monitor de imprimir o "ACORDOU"
            // ANTES do Admin fechar o dia e começar as validações.
            log(
                adminId,
                "Sequência terminada. Pausa para sincronização de logs..."
            );
            Thread.sleep(800);

            log(adminId, "A enviar comando NEW_DAY...");
            admin.newDay();

            performValidations(admin, adminId);
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            executor.shutdownNow();
            System.out.println("=== TESTE DE STRESS CONCLUÍDO ===");
        }
    }

    private static void launchSimulMonitor(ExecutorService executor) {
        executor.execute(() -> {
            String tid = "MONITOR-SIMUL";
            try (ClientLib client = new ClientLib(HOST, PORT)) {
                client.login("admin", "pass123");
                log(tid, "A aguardar waitSimultaneous('Banana', 'Maçã')...");
                boolean res = client.waitSimultaneous("Banana", "Maçã");
                log(
                    tid,
                    "!!! ACORDOU !!! Resultado: " +
                        (res ? "Meta atingida!" : "Dia terminou.")
                );
            } catch (Exception e) {
                log(tid, "ERRO: " + e.getMessage());
            }
        });
    }

    private static void launchConsecMonitor(ExecutorService executor) {
        executor.execute(() -> {
            String tid = "MONITOR-CONSEC";
            try (ClientLib client = new ClientLib(HOST, PORT)) {
                client.login("admin", "pass123");
                log(tid, "A aguardar waitConsecutive(3)...");
                String res = client.waitConsecutive(3);
                // Print imediato ao acordar
                log(
                    tid,
                    "!!! ACORDOU !!! Produto que disparou: [" +
                        (res != null ? res : "Fim do Dia") +
                        "]"
                );
            } catch (Exception e) {
                log(tid, "ERRO: " + e.getMessage());
            }
        });
    }

    private static void runNoiseWorker(
        int id,
        CountDownLatch noiseLatch,
        CountDownLatch totalLatch
    ) {
        String tid = "WORKER-NOISE-" + id;
        try (ClientLib client = new ClientLib(HOST, PORT)) {
            client.login("admin", "pass123");
            if (id == 1) {
                client.addEvent("Banana", 10, 1.5);
                Thread.sleep(200);
                client.addEvent("Maçã", 5, 2.0);
            } else {
                client.addEvent("Uva", 20, 5.0);
            }
        } catch (Exception e) {
            log(tid, "ERRO: " + e.getMessage());
        } finally {
            noiseLatch.countDown();
            totalLatch.countDown();
        }
    }

    private static void runConsecutiveWorker(
        int id,
        CountDownLatch totalLatch
    ) {
        String tid = "WORKER-CONSEC-" + id;
        try (ClientLib client = new ClientLib(HOST, PORT)) {
            client.login("admin", "pass123");
            log(tid, "Enviando 3 Laranjas em rajada...");
            client.addEvent("Laranja", 1, 0.5);
            client.addEvent("Laranja", 1, 0.5);
            client.addEvent("Laranja", 1, 0.5);
            log(tid, "Rajada concluída.");
        } catch (Exception e) {
            log(tid, "ERRO: " + e.getMessage());
        } finally {
            totalLatch.countDown();
        }
    }

    private static void performValidations(ClientLib admin, String adminId)
        throws Exception {
        log(adminId, "--- Validações Finais ---");
        double qty = admin.getAggregation(Protocol.AGGR_QTY, "Laranja", 1);
        log(adminId, "Total Laranjas: " + qty);
    }
}
