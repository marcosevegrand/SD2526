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

/**
 * Script de teste de stress detalhado com monitorização extrema de threads.
 * A intenção é validar a robustez, concorrência e o sistema de notificações do servidor
 * através de logs verbosos que permitem o acompanhamento passo-a-passo de cada thread.
 */
public class StressTest {

    private static final String HOST = "localhost";
    private static final int PORT = 12345;
    private static final int NUM_WORKERS = 3;

    /**
     * Imprime mensagens no console prefixadas pelo identificador da thread.
     * @param threadId Identificador único da thread.
     * @param message Mensagem de log.
     */
    private static void log(String threadId, String message) {
        System.out.printf("[%s] %s%n", threadId, message);
    }

    /**
     * Ponto de entrada do teste de stress.
     * Coordena o setup, o lançamento de monitores e workers, e a validação final.
     * @param args Argumentos.
     */
    public static void main(String[] args) {
        System.out.println("=== INICIANDO TESTE DE STRESS VERBOSO ===");

        ExecutorService executor = Executors.newCachedThreadPool();
        CountDownLatch workerLatch = new CountDownLatch(NUM_WORKERS);

        try (ClientLib admin = new ClientLib(HOST, PORT)) {
            String adminId = "MAIN-ADMIN";

            log(adminId, "Iniciando registo do utilizador 'admin'...");
            boolean reg = admin.register("admin", "pass123");
            log(
                adminId,
                "Resultado do registo: " + (reg ? "Sucesso" : "Já existe/Erro")
            );

            log(adminId, "A tentar efetuar login...");
            if (!admin.login("admin", "pass123")) {
                log(adminId, "ERRO: Login falhou. Encerrando teste.");
                return;
            }
            log(adminId, "Autenticação bem-sucedida.");

            // Lançar Monitores (Waiters)
            log(
                adminId,
                "A lançar threads de monitorização (espera passiva)..."
            );
            launchSimulMonitor(executor);
            launchConsecMonitor(executor);

            // Aguardar para garantir que os monitores chegam ao servidor primeiro
            log(
                adminId,
                "Pausa de 1.5s para garantir sincronização dos monitores..."
            );
            Thread.sleep(1500);

            // Lançar Workers de Inserção
            for (int i = 1; i <= NUM_WORKERS; i++) {
                final int id = i;
                executor.execute(() -> runWorker(id, workerLatch));
            }

            log(
                adminId,
                "A aguardar a conclusão de todos os workers (Timeout 30s)..."
            );
            if (!workerLatch.await(30, TimeUnit.SECONDS)) {
                log(
                    adminId,
                    "AVISO: Timeout atingido antes da conclusão dos workers."
                );
            }
            log(adminId, "Todos os workers terminaram.");

            // Finalização do dia e Validações
            log(adminId, "A enviar comando NEW_DAY...");
            admin.newDay();
            log(adminId, "Dia 0 encerrado e persistido.");

            performValidations(admin, adminId);
        } catch (Exception e) {
            log("MAIN-ERROR", "Ocorreu um erro fatal: " + e.getMessage());
            e.printStackTrace();
        } finally {
            executor.shutdownNow();
            System.out.println("=== TESTE DE STRESS CONCLUÍDO ===");
        }
    }

    /**
     * Monitoriza a ocorrência de vendas simultâneas.
     * @param executor Executor service.
     */
    private static void launchSimulMonitor(ExecutorService executor) {
        executor.execute(() -> {
            String tid = "MONITOR-SIMUL";
            try (ClientLib client = new ClientLib(HOST, PORT)) {
                log(tid, "Ligando e autenticando...");
                client.login("admin", "pass123");
                log(
                    tid,
                    "A enviar pedido: waitSimultaneous('Banana', 'Maçã')..."
                );
                log(tid, "Thread vai bloquear agora...");
                boolean res = client.waitSimultaneous("Banana", "Maçã");
                log(
                    tid,
                    "Thread ACORDOU! Resultado: " +
                        (res ? "Meta atingida!" : "Dia terminou.")
                );
            } catch (Exception e) {
                log(tid, "ERRO: " + e.getMessage());
            }
        });
    }

    /**
     * Monitoriza a ocorrência de vendas consecutivas.
     * @param executor Executor service.
     */
    private static void launchConsecMonitor(ExecutorService executor) {
        executor.execute(() -> {
            String tid = "MONITOR-CONSEC";
            try (ClientLib client = new ClientLib(HOST, PORT)) {
                log(tid, "Ligando e autenticando...");
                client.login("admin", "pass123");
                log(tid, "A enviar pedido: waitConsecutive(3)...");
                log(tid, "Thread vai bloquear agora...");
                String res = client.waitConsecutive(3);
                log(
                    tid,
                    "Thread ACORDOU! Produto vencedor: " +
                        (res != null ? res : "Nenhum/Dia terminou")
                );
            } catch (Exception e) {
                log(tid, "ERRO: " + e.getMessage());
            }
        });
    }

    /**
     * Executa a sequência de inserções de um worker.
     * @param id ID do worker.
     * @param latch Latch de sincronização.
     */
    private static void runWorker(int id, CountDownLatch latch) {
        String tid = "WORKER-" + id;
        try (ClientLib client = new ClientLib(HOST, PORT)) {
            log(tid, "Ligando e autenticando...");
            client.login("admin", "pass123");

            log(tid, "Iniciando sequência de tarefas...");
            if (id == 1) {
                log(
                    tid,
                    "Tarefa: Vender Banana -> ADD_EVENT('Banana', 10, 1.5)"
                );
                client.addEvent("Banana", 10, 1.5);
                Thread.sleep(500);
                log(
                    tid,
                    "Tarefa: Vender Maçã (Deve disparar SIMUL) -> ADD_EVENT('Maçã', 5, 2.0)"
                );
                client.addEvent("Maçã", 5, 2.0);
            } else if (id == 2) {
                for (int k = 1; k <= 3; k++) {
                    log(
                        tid,
                        "Tarefa: Venda consecutiva " +
                            k +
                            "/3 -> ADD_EVENT('Laranja', 1, 0.5)"
                    );
                    client.addEvent("Laranja", 1, 0.5);
                    Thread.sleep(300);
                }
                log(tid, "Sequência de Laranja terminada.");
            } else {
                log(tid, "Tarefa: Carga genérica -> ADD_EVENT('Uva', 20, 5.0)");
                client.addEvent("Uva", 20, 5.0);
            }
            log(tid, "Todas as tarefas individuais concluídas com sucesso.");
        } catch (Exception e) {
            log(tid, "FALHA DURANTE EXECUÇÃO: " + e.getMessage());
        } finally {
            latch.countDown();
            log(tid, "Thread a encerrar e sinalizar latch.");
        }
    }

    /**
     * Realiza verificações de agregação e integridade após os eventos.
     * @param admin Cliente admin.
     * @param adminId ID da thread.
     * @throws Exception Erros.
     */
    private static void performValidations(ClientLib admin, String adminId)
        throws Exception {
        log(adminId, "A validar AGGR_QTY para 'Banana'...");
        double qty = admin.getAggregation(Protocol.AGGR_QTY, "Banana", 1);
        log(
            adminId,
            "Resultado QTY Banana: " +
                qty +
                (qty == 10 ? " [CORRETO]" : " [ERRADO]")
        );

        log(adminId, "A validar AGGR_MAX para 'Uva'...");
        double max = admin.getAggregation(Protocol.AGGR_MAX, "Uva", 1);
        log(
            adminId,
            "Resultado MAX Uva: " +
                max +
                (max == 5.0 ? " [CORRETO]" : " [ERRADO]")
        );

        log(adminId, "A testar FILTER para Dia 0 (Banana, Laranja)...");
        Set<String> f = new HashSet<>();
        f.add("Banana");
        f.add("Laranja");
        List<String> evs = admin.getEvents(0, f);
        log(adminId, "Eventos encontrados no filtro: " + evs.size());
        for (String s : evs) log(adminId, "  -> " + s);
    }
}
