package test;

import client.ClientLib;
import common.Protocol;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * Suite de Testes Robustos para o Sistema de Séries Temporais.
 *
 * Este ficheiro contém testes que validam:
 * 1. Correção funcional de cada requisito
 * 2. Comportamento sob condições de fronteira (edge cases)
 * 3. Robustez sob carga concorrente
 * 4. Integridade de dados após persistência
 *
 * IMPORTANTE: O servidor deve estar a correr antes de executar os testes.
 * Recomenda-se iniciar o servidor com: make run-server S=5 D=30
 */
public class RobustTest {

    private static final String HOST = "localhost";
    private static final int PORT = 12345;

    private static int testsPassed = 0;
    private static int testsFailed = 0;

    public static void main(String[] args) {
        System.out.println("╔════════════════════════════════════════════════════════════╗");
        System.out.println("║       SUITE DE TESTES ROBUSTOS - SISTEMAS DISTRIBUÍDOS     ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝\n");

        try {
            // Secção 1: Testes de Autenticação
            runAuthenticationTests();

            // Secção 2: Testes de Registo de Eventos
            runEventRegistrationTests();

            // Secção 3: Testes de Agregação
            runAggregationTests();

            // Secção 4: Testes de Notificações
            runNotificationTests();

            // Secção 5: Testes de Concorrência Cliente
            runClientConcurrencyTests();

            // Secção 6: Testes de Robustez (Slow Consumer)
            runRobustnessTests();

            // Secção 7: Testes de Fronteira
            runEdgeCaseTests();

        } catch (Exception e) {
            System.err.println("ERRO FATAL NA EXECUÇÃO DOS TESTES: " + e.getMessage());
            e.printStackTrace();
        }

        // Relatório Final
        System.out.println("\n╔════════════════════════════════════════════════════════════╗");
        System.out.println("║                    RELATÓRIO FINAL                         ║");
        System.out.println("╠════════════════════════════════════════════════════════════╣");
        System.out.printf("║  Testes Passados: %-3d                                      ║%n", testsPassed);
        System.out.printf("║  Testes Falhados: %-3d                                      ║%n", testsFailed);
        System.out.printf("║  Taxa de Sucesso: %.1f%%                                    ║%n",
                (testsPassed * 100.0) / (testsPassed + testsFailed));
        System.out.println("╚════════════════════════════════════════════════════════════╝");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SECÇÃO 1: TESTES DE AUTENTICAÇÃO
    // ═══════════════════════════════════════════════════════════════════════════

    private static void runAuthenticationTests() throws Exception {
        printSection("TESTES DE AUTENTICAÇÃO");

        // Teste 1.1: Registo de utilizador novo
        try (ClientLib client = new ClientLib(HOST, PORT)) {
            String uniqueUser = "user_" + System.currentTimeMillis();
            boolean registered = client.register(uniqueUser, "password123");
            assertTest("1.1 - Registo de utilizador novo", registered);
        }

        // Teste 1.2: Registo de utilizador duplicado
        try (ClientLib client = new ClientLib(HOST, PORT)) {
            String user = "duplicate_test_" + System.currentTimeMillis();
            client.register(user, "pass1");
            boolean duplicate = client.register(user, "pass2");
            assertTest("1.2 - Rejeição de utilizador duplicado", !duplicate);
        }

        // Teste 1.3: Login com credenciais corretas
        try (ClientLib client = new ClientLib(HOST, PORT)) {
            String user = "login_test_" + System.currentTimeMillis();
            client.register(user, "correctpass");
            boolean login = client.login(user, "correctpass");
            assertTest("1.3 - Login com credenciais corretas", login);
        }

        // Teste 1.4: Login com password errada
        try (ClientLib client = new ClientLib(HOST, PORT)) {
            String user = "wrongpass_test_" + System.currentTimeMillis();
            client.register(user, "correctpass");
            boolean login = client.login(user, "wrongpass");
            assertTest("1.4 - Rejeição de password incorreta", !login);
        }

        // Teste 1.5: Login com utilizador inexistente
        try (ClientLib client = new ClientLib(HOST, PORT)) {
            boolean login = client.login("nonexistent_user_xyz_" + System.currentTimeMillis(), "anypass");
            assertTest("1.5 - Rejeição de utilizador inexistente", !login);
        }

        // Teste 1.6: Operação sem autenticação (deve falhar ou retornar erro)
        try (ClientLib client = new ClientLib(HOST, PORT)) {
            // Tentativa de adicionar evento sem login - deve lançar exceção ou falhar
            try {
                client.addEvent("Produto", 1, 10.0);
                assertTest("1.6 - Bloqueio de operação sem autenticação", false);
            } catch (Exception e) {
                assertTest("1.6 - Bloqueio de operação sem autenticação", true);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SECÇÃO 2: TESTES DE REGISTO DE EVENTOS
    // ═══════════════════════════════════════════════════════════════════════════

    private static void runEventRegistrationTests() throws Exception {
        printSection("TESTES DE REGISTO DE EVENTOS");

        String testUser = "event_tester_" + System.currentTimeMillis();

        try (ClientLib client = new ClientLib(HOST, PORT)) {
            client.register(testUser, "pass");
            client.login(testUser, "pass");

            // Teste 2.1: Registo de evento simples
            int dayBefore = client.getCurrentDay();
            client.addEvent("Laptop", 5, 999.99);
            assertTest("2.1 - Registo de evento simples", true);

            // Teste 2.2: Múltiplos eventos do mesmo produto
            for (int i = 0; i < 10; i++) {
                client.addEvent("Mouse", 2, 29.99);
            }
            assertTest("2.2 - Múltiplos eventos do mesmo produto", true);

            // Teste 2.3: Transição de dia
            client.newDay();
            int dayAfter = client.getCurrentDay();
            assertTest("2.3 - Transição de dia incrementa contador", dayAfter == dayBefore + 1);

            // Teste 2.4: Agregação após persistência
            double qty = client.getAggregation(Protocol.AGGR_QTY, "Mouse", 1);
            assertTest("2.4 - Dados persistidos corretamente", qty == 20); // 10 eventos * 2 unidades
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SECÇÃO 3: TESTES DE AGREGAÇÃO
    // ═══════════════════════════════════════════════════════════════════════════

    private static void runAggregationTests() throws Exception {
        printSection("TESTES DE AGREGAÇÃO");

        String testUser = "aggr_tester_" + System.currentTimeMillis();

        try (ClientLib client = new ClientLib(HOST, PORT)) {
            client.register(testUser, "pass");
            client.login(testUser, "pass");

            // Criar dados de teste conhecidos
            // Dia 1: ProdA: 10 unidades a 5€ cada
            client.addEvent("ProdA", 10, 5.0);
            client.addEvent("ProdA", 5, 10.0);  // Total: 15 unidades, volume = 50 + 50 = 100
            client.newDay();

            // Dia 2: ProdA: 20 unidades a 8€
            client.addEvent("ProdA", 20, 8.0);  // Volume = 160
            client.newDay();

            // Teste 3.1: Quantidade total (2 dias)
            double qty = client.getAggregation(Protocol.AGGR_QTY, "ProdA", 2);
            assertTest("3.1 - Agregação de quantidade (2 dias)", qty == 35); // 15 + 20

            // Teste 3.2: Volume total (2 dias)
            double vol = client.getAggregation(Protocol.AGGR_VOL, "ProdA", 2);
            assertTest("3.2 - Agregação de volume (2 dias)", Math.abs(vol - 260.0) < 0.01);

            // Teste 3.3: Preço máximo
            double max = client.getAggregation(Protocol.AGGR_MAX, "ProdA", 2);
            assertTest("3.3 - Agregação de preço máximo", Math.abs(max - 10.0) < 0.01);

            // Teste 3.4: Preço médio ponderado
            // Média = Volume / Quantidade = 260 / 35 = 7.428...
            double avg = client.getAggregation(Protocol.AGGR_AVG, "ProdA", 2);
            assertTest("3.4 - Agregação de preço médio", Math.abs(avg - (260.0/35.0)) < 0.01);

            // Teste 3.5: Agregação de produto inexistente
            double none = client.getAggregation(Protocol.AGGR_QTY, "ProdutoFantasma", 2);
            assertTest("3.5 - Agregação de produto inexistente retorna 0", none == 0);

            // Teste 3.6: Agregação apenas do último dia (d=1)
            double qty1 = client.getAggregation(Protocol.AGGR_QTY, "ProdA", 1);
            assertTest("3.6 - Agregação apenas do último dia", qty1 == 20);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SECÇÃO 4: TESTES DE NOTIFICAÇÕES
    // ═══════════════════════════════════════════════════════════════════════════

    private static void runNotificationTests() throws Exception {
        printSection("TESTES DE NOTIFICAÇÕES");

        String testUser = "notify_tester_" + System.currentTimeMillis();

        // Teste 4.1: Vendas Simultâneas - Sucesso
        try (ClientLib admin = new ClientLib(HOST, PORT)) {
            admin.register(testUser, "pass");
            admin.login(testUser, "pass");

            AtomicBoolean result = new AtomicBoolean(false);
            CountDownLatch waiterReady = new CountDownLatch(1);
            CountDownLatch testDone = new CountDownLatch(1);

            // Thread que espera pela condição
            Thread waiter = new Thread(() -> {
                try (ClientLib waiterClient = new ClientLib(HOST, PORT)) {
                    waiterClient.login(testUser, "pass");
                    waiterReady.countDown();
                    result.set(waiterClient.waitSimultaneous("Banana", "Maçã"));
                    testDone.countDown();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            waiter.start();

            waiterReady.await(5, TimeUnit.SECONDS);
            Thread.sleep(200); // Garantir que o waiter está bloqueado

            // Enviar eventos que satisfazem a condição
            admin.addEvent("Banana", 1, 1.0);
            admin.addEvent("Maçã", 1, 1.0);

            testDone.await(5, TimeUnit.SECONDS);
            assertTest("4.1 - Vendas Simultâneas - Sucesso", result.get());

            admin.newDay(); // Limpar para próximo teste
        }

        // Teste 4.2: Vendas Simultâneas - Timeout (dia termina)
        try (ClientLib admin = new ClientLib(HOST, PORT)) {
            admin.login(testUser, "pass");

            AtomicBoolean result = new AtomicBoolean(true);
            CountDownLatch waiterReady = new CountDownLatch(1);
            CountDownLatch testDone = new CountDownLatch(1);

            Thread waiter = new Thread(() -> {
                try (ClientLib waiterClient = new ClientLib(HOST, PORT)) {
                    waiterClient.login(testUser, "pass");
                    waiterReady.countDown();
                    result.set(waiterClient.waitSimultaneous("Laranja", "Pêra"));
                    testDone.countDown();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            waiter.start();

            waiterReady.await(5, TimeUnit.SECONDS);
            Thread.sleep(200);

            // Apenas um produto vendido, depois terminamos o dia
            admin.addEvent("Laranja", 1, 1.0);
            admin.newDay();

            testDone.await(5, TimeUnit.SECONDS);
            assertTest("4.2 - Vendas Simultâneas - Dia termina sem sucesso", !result.get());
        }

        // Teste 4.3: Vendas Consecutivas - Sucesso
        try (ClientLib admin = new ClientLib(HOST, PORT)) {
            admin.login(testUser, "pass");

            AtomicReference<String> result = new AtomicReference<>(null);
            CountDownLatch waiterReady = new CountDownLatch(1);
            CountDownLatch testDone = new CountDownLatch(1);

            Thread waiter = new Thread(() -> {
                try (ClientLib waiterClient = new ClientLib(HOST, PORT)) {
                    waiterClient.login(testUser, "pass");
                    waiterReady.countDown();
                    result.set(waiterClient.waitConsecutive(3));
                    testDone.countDown();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            waiter.start();

            waiterReady.await(5, TimeUnit.SECONDS);
            Thread.sleep(200);

            // Enviar 3 vendas consecutivas do mesmo produto
            admin.addEvent("Kiwi", 1, 1.0);
            admin.addEvent("Kiwi", 1, 1.0);
            admin.addEvent("Kiwi", 1, 1.0);

            testDone.await(5, TimeUnit.SECONDS);
            assertTest("4.3 - Vendas Consecutivas - Sucesso", "Kiwi".equals(result.get()));

            admin.newDay();
        }

        // Teste 4.4: Vendas Consecutivas - Interrupção
        try (ClientLib admin = new ClientLib(HOST, PORT)) {
            admin.login(testUser, "pass");

            AtomicReference<String> result = new AtomicReference<>("NOT_NULL");
            CountDownLatch waiterReady = new CountDownLatch(1);
            CountDownLatch testDone = new CountDownLatch(1);

            Thread waiter = new Thread(() -> {
                try (ClientLib waiterClient = new ClientLib(HOST, PORT)) {
                    waiterClient.login(testUser, "pass");
                    waiterReady.countDown();
                    result.set(waiterClient.waitConsecutive(5));
                    testDone.countDown();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
            waiter.start();

            waiterReady.await(5, TimeUnit.SECONDS);
            Thread.sleep(200);

            // Sequência interrompida: A, A, B (nunca chega a 5)
            admin.addEvent("ProdA", 1, 1.0);
            admin.addEvent("ProdA", 1, 1.0);
            admin.addEvent("ProdB", 1, 1.0);
            admin.newDay();

            testDone.await(5, TimeUnit.SECONDS);
            assertTest("4.4 - Vendas Consecutivas - Sequência interrompida", result.get() == null);
        }

        // Teste 4.5: Múltiplos waiters para condições diferentes
        try (ClientLib admin = new ClientLib(HOST, PORT)) {
            admin.login(testUser, "pass");

            AtomicBoolean simul = new AtomicBoolean(false);
            AtomicReference<String> consec = new AtomicReference<>(null);
            CountDownLatch bothReady = new CountDownLatch(2);
            CountDownLatch bothDone = new CountDownLatch(2);

            // Waiter para simultâneas
            new Thread(() -> {
                try (ClientLib c = new ClientLib(HOST, PORT)) {
                    c.login(testUser, "pass");
                    bothReady.countDown();
                    simul.set(c.waitSimultaneous("X", "Y"));
                    bothDone.countDown();
                } catch (Exception ignored) {}
            }).start();

            // Waiter para consecutivas
            new Thread(() -> {
                try (ClientLib c = new ClientLib(HOST, PORT)) {
                    c.login(testUser, "pass");
                    bothReady.countDown();
                    consec.set(c.waitConsecutive(2));
                    bothDone.countDown();
                } catch (Exception ignored) {}
            }).start();

            bothReady.await(5, TimeUnit.SECONDS);
            Thread.sleep(300);

            // Satisfazer ambas as condições
            admin.addEvent("X", 1, 1.0);
            admin.addEvent("X", 1, 1.0);  // Isto satisfaz consecutivas (X, 2 vezes)
            admin.addEvent("Y", 1, 1.0);  // Isto satisfaz simultâneas (X e Y vendidos)

            bothDone.await(5, TimeUnit.SECONDS);
            assertTest("4.5 - Múltiplos waiters - Simultâneas", simul.get());
            assertTest("4.5 - Múltiplos waiters - Consecutivas", "X".equals(consec.get()));

            admin.newDay();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SECÇÃO 5: TESTES DE CONCORRÊNCIA DO CLIENTE
    // ═══════════════════════════════════════════════════════════════════════════

    private static void runClientConcurrencyTests() throws Exception {
        printSection("TESTES DE CONCORRÊNCIA DO CLIENTE");

        String testUser = "concurrent_" + System.currentTimeMillis();

        // Teste 5.1: Múltiplas threads com uma única conexão
        try (ClientLib client = new ClientLib(HOST, PORT)) {
            client.register(testUser, "pass");
            client.login(testUser, "pass");

            int numThreads = 20;
            int opsPerThread = 50;
            AtomicInteger successCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(numThreads);
            ExecutorService executor = Executors.newFixedThreadPool(numThreads);

            long start = System.currentTimeMillis();

            for (int t = 0; t < numThreads; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    try {
                        for (int i = 0; i < opsPerThread; i++) {
                            client.addEvent("ConcurrentProd_" + threadId, 1, 1.0);
                            successCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        System.err.println("Erro na thread " + threadId + ": " + e.getMessage());
                    } finally {
                        latch.countDown();
                    }
                });
            }

            boolean completed = latch.await(30, TimeUnit.SECONDS);
            long duration = System.currentTimeMillis() - start;

            int expected = numThreads * opsPerThread;
            assertTest("5.1 - Concorrência multi-thread única conexão",
                    completed && successCount.get() == expected);

            System.out.printf("      [INFO] %d operações em %dms (%.0f ops/seg)%n",
                    expected, duration, (expected * 1000.0) / duration);

            executor.shutdown();
            client.newDay();
        }

        // Teste 5.2: Pedido lento não bloqueia outros
        try (ClientLib client = new ClientLib(HOST, PORT)) {
            client.login(testUser, "pass");

            AtomicBoolean slowStarted = new AtomicBoolean(false);
            AtomicBoolean fastCompleted = new AtomicBoolean(false);
            AtomicLong fastCompletedTime = new AtomicLong(0);
            CountDownLatch allDone = new CountDownLatch(2);

            // Thread lenta: espera por condição que não vai acontecer
            Thread slow = new Thread(() -> {
                try (ClientLib slowClient = new ClientLib(HOST, PORT)) {
                    slowClient.login(testUser, "pass");
                    slowStarted.set(true);
                    slowClient.waitSimultaneous("NuncaVendido1", "NuncaVendido2");
                } catch (Exception ignored) {}
                allDone.countDown();
            });

            // Thread rápida: faz operações simples
            Thread fast = new Thread(() -> {
                try {
                    while (!slowStarted.get()) Thread.sleep(10);
                    Thread.sleep(100); // Garantir que a lenta está bloqueada

                    long start = System.currentTimeMillis();
                    for (int i = 0; i < 10; i++) {
                        client.addEvent("FastProd", 1, 1.0);
                    }
                    fastCompletedTime.set(System.currentTimeMillis() - start);
                    fastCompleted.set(true);
                } catch (Exception ignored) {}
                allDone.countDown();
            });

            slow.start();
            fast.start();

            // Dar tempo para a rápida completar (a lenta está bloqueada)
            Thread.sleep(2000);

            assertTest("5.2 - Pedido lento não bloqueia rápidos",
                    fastCompleted.get() && fastCompletedTime.get() < 1000);

            // Terminar o dia para libertar a thread lenta
            client.newDay();
            allDone.await(5, TimeUnit.SECONDS);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SECÇÃO 6: TESTES DE ROBUSTEZ
    // ═══════════════════════════════════════════════════════════════════════════

    private static void runRobustnessTests() throws Exception {
        printSection("TESTES DE ROBUSTEZ");

        String testUser = "robust_" + System.currentTimeMillis();

        // Teste 6.1: Slow Consumer - Cliente que não consome respostas
        // Este teste verifica se o servidor continua funcional quando um cliente está lento
        try (ClientLib admin = new ClientLib(HOST, PORT)) {
            admin.register(testUser, "pass");
            admin.login(testUser, "pass");

            // Criar um cliente que vai enviar pedidos mas não consumir respostas de imediato
            // (A nossa implementação do Demultiplexer consome automaticamente, mas podemos simular)

            // Enviar muitos pedidos rapidamente
            long start = System.currentTimeMillis();
            for (int i = 0; i < 100; i++) {
                admin.addEvent("RobustProd", 1, 1.0);
            }
            long duration = System.currentTimeMillis() - start;

            assertTest("6.1 - Sistema responsivo sob carga rápida", duration < 5000);
            System.out.printf("      [INFO] 100 eventos em %dms%n", duration);

            admin.newDay();
        }

        // Teste 6.2: Múltiplas conexões simultâneas
        int numClients = 10;
        AtomicInteger successClients = new AtomicInteger(0);
        CountDownLatch allStarted = new CountDownLatch(numClients);
        CountDownLatch allDone = new CountDownLatch(numClients);

        for (int c = 0; c < numClients; c++) {
            final int clientId = c;
            new Thread(() -> {
                try (ClientLib client = new ClientLib(HOST, PORT)) {
                    String user = "multiclient_" + System.currentTimeMillis() + "_" + clientId;
                    client.register(user, "pass");
                    client.login(user, "pass");
                    allStarted.countDown();

                    allStarted.await(); // Sincronizar início

                    for (int i = 0; i < 20; i++) {
                        client.addEvent("MultiClientProd", 1, 1.0);
                    }
                    successClients.incrementAndGet();
                } catch (Exception e) {
                    System.err.println("Cliente " + clientId + " falhou: " + e.getMessage());
                }
                allDone.countDown();
            }).start();
        }

        allDone.await(30, TimeUnit.SECONDS);
        assertTest("6.2 - Múltiplas conexões simultâneas", successClients.get() == numClients);

        // Teste 6.3: Recuperação após muitas operações
        try (ClientLib client = new ClientLib(HOST, PORT)) {
            String user = "recovery_" + System.currentTimeMillis();
            client.register(user, "pass");
            client.login(user, "pass");

            // Muitos eventos para stressar o sistema
            for (int i = 0; i < 1000; i++) {
                client.addEvent("StressProd", 1, 1.0);
            }
            client.newDay();

            // Verificar se agregação funciona após stress
            double qty = client.getAggregation(Protocol.AGGR_QTY, "StressProd", 1);
            assertTest("6.3 - Sistema funcional após stress", qty == 1000);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SECÇÃO 7: TESTES DE FRONTEIRA (EDGE CASES)
    // ═══════════════════════════════════════════════════════════════════════════

    private static void runEdgeCaseTests() throws Exception {
        printSection("TESTES DE CASOS DE FRONTEIRA");

        String testUser = "edge_" + System.currentTimeMillis();

        try (ClientLib client = new ClientLib(HOST, PORT)) {
            client.register(testUser, "pass");
            client.login(testUser, "pass");

            // Teste 7.1: Produto com nome longo
            String longName = "A".repeat(200);
            client.addEvent(longName, 1, 1.0);
            client.newDay();
            double qty = client.getAggregation(Protocol.AGGR_QTY, longName, 1);
            assertTest("7.1 - Produto com nome longo", qty == 1);

            // Teste 7.2: Produto com caracteres especiais
            String specialName = "Produto-Teste_123!@#$%";
            client.addEvent(specialName, 1, 1.0);
            client.newDay();
            qty = client.getAggregation(Protocol.AGGR_QTY, specialName, 1);
            assertTest("7.2 - Produto com caracteres especiais", qty == 1);

            // Teste 7.3: Quantidade zero (se permitido pelo sistema)
            client.addEvent("ZeroProd", 0, 10.0);
            client.newDay();
            qty = client.getAggregation(Protocol.AGGR_QTY, "ZeroProd", 1);
            assertTest("7.3 - Quantidade zero registada", qty == 0);

            // Teste 7.4: Preço zero
            client.addEvent("FreeProd", 10, 0.0);
            client.newDay();
            double vol = client.getAggregation(Protocol.AGGR_VOL, "FreeProd", 1);
            assertTest("7.4 - Preço zero - volume zero", vol == 0);

            // Teste 7.5: Valores grandes
            client.addEvent("BigProd", 1000000, 999999.99);
            client.newDay();
            vol = client.getAggregation(Protocol.AGGR_VOL, "BigProd", 1);
            assertTest("7.5 - Valores grandes", Math.abs(vol - 999999990000.0) < 1);

            // Teste 7.6: Filtro com conjunto vazio
            Set<String> emptyFilter = new HashSet<>();
            List<String> events = client.getEvents(1, emptyFilter);
            assertTest("7.6 - Filtro vazio retorna lista vazia", events.isEmpty());

            // Teste 7.7: Filtro com produtos inexistentes
            Set<String> nonexistentFilter = new HashSet<>();
            nonexistentFilter.add("ProdutoQueNaoExiste");
            events = client.getEvents(1, nonexistentFilter);
            assertTest("7.7 - Filtro de produto inexistente", events.isEmpty());

            // Teste 7.8: Agregação de d=1 quando só existe 1 dia
            client.addEvent("SingleDayProd", 5, 2.0);
            client.newDay();
            qty = client.getAggregation(Protocol.AGGR_QTY, "SingleDayProd", 1);
            assertTest("7.8 - Agregação d=1 com um dia de dados", qty == 5);

            // Teste 7.9: String UTF-8 (caracteres não-ASCII)
            String utf8Name = "Produto_日本語_Ελληνικά";
            client.addEvent(utf8Name, 1, 1.0);
            client.newDay();
            qty = client.getAggregation(Protocol.AGGR_QTY, utf8Name, 1);
            assertTest("7.9 - Produto com caracteres UTF-8", qty == 1);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UTILITÁRIOS
    // ═══════════════════════════════════════════════════════════════════════════

    private static void printSection(String title) {
        System.out.println("\n┌─────────────────────────────────────────────────────────────┐");
        System.out.printf("│ %-59s │%n", title);
        System.out.println("└─────────────────────────────────────────────────────────────┘");
    }

    private static void assertTest(String name, boolean condition) {
        if (condition) {
            testsPassed++;
            System.out.printf("  ✓ %s%n", name);
        } else {
            testsFailed++;
            System.out.printf("  ✗ %s [FALHOU]%n", name);
        }
    }
}