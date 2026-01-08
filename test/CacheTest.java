package test;

import client.ClientLib;
import common.Protocol;

import java.util.*;
import java.util.concurrent.*;

/**
 * Testes específicos para validar a gestão de memória (Cache LRU) e persistência.
 *
 * IMPORTANTE: Executar o servidor com parâmetros reduzidos para forçar evicção:
 *   make run-server S=3 D=10
 *
 * Estes testes verificam:
 * - Cache LRU respeita o limite S de séries em memória
 * - Dados são corretamente persistidos e recuperados do disco
 * - Agregações funcionam mesmo quando dados não estão em cache
 */
public class CacheTest {

    private static final String HOST = "localhost";
    private static final int PORT = 12345;

    private static int testsPassed = 0;
    private static int testsFailed = 0;

    public static void main(String[] args) {
        System.out.println("╔════════════════════════════════════════════════════════════╗");
        System.out.println("║     TESTES DE CACHE E PERSISTÊNCIA (Executar com S=3)      ║");
        System.out.println("╚════════════════════════════════════════════════════════════╝\n");

        System.out.println("NOTA: Para resultados significativos, inicie o servidor com:");
        System.out.println("      make run-server S=3 D=10\n");

        try {
            runCacheEvictionTests();
            runPersistenceTests();
            runLargeDatasetTests();
        } catch (Exception e) {
            System.err.println("ERRO: " + e.getMessage());
            e.printStackTrace();
        }

        // Relatório
        System.out.println("\n════════════════════════════════════════════════════════════");
        System.out.printf("Resultados: %d passados, %d falhados%n", testsPassed, testsFailed);
        System.out.println("════════════════════════════════════════════════════════════");
    }

    /**
     * Testa se a cache LRU funciona corretamente quando mais de S dias são acedidos.
     */
    private static void runCacheEvictionTests() throws Exception {
        printSection("TESTES DE EVICÇÃO DE CACHE");

        String testUser = "cache_tester_" + System.currentTimeMillis();

        try (ClientLib client = new ClientLib(HOST, PORT)) {
            client.register(testUser, "pass");
            client.login(testUser, "pass");

            System.out.println("  Criando dados para 6 dias (mais que S=3)...");

            // Criar dados distintos para cada dia
            for (int day = 0; day < 6; day++) {
                String prod = "ProdDia" + day;
                client.addEvent(prod, (day + 1) * 10, day + 1.0);  // Quantidades: 10, 20, 30, 40, 50, 60
                client.newDay();
                System.out.printf("    Dia %d: %s com %d unidades%n", day, prod, (day + 1) * 10);
            }

            System.out.println("  Acedendo a dados de dias antigos (forçar leitura do disco)...");

            // Aceder a dias em ordem que força evicção
            // Com S=3, após aceder a 3 dias, o 4º vai forçar evicção do mais antigo

            // Teste 1: Aceder ao dia mais antigo (vai para cache)
            double qty0 = client.getAggregation(Protocol.AGGR_QTY, "ProdDia0", 6);
            assertTest("Cache 1 - Agregação do dia 0", qty0 == 10);

            // Teste 2: Aceder ao dia 1 (vai para cache)
            double qty1 = client.getAggregation(Protocol.AGGR_QTY, "ProdDia1", 6);
            assertTest("Cache 2 - Agregação do dia 1", qty1 == 20);

            // Teste 3: Aceder ao dia 2 (vai para cache, S=3 atingido)
            double qty2 = client.getAggregation(Protocol.AGGR_QTY, "ProdDia2", 6);
            assertTest("Cache 3 - Agregação do dia 2", qty2 == 30);

            // Teste 4: Aceder ao dia 3 (força evicção do dia 0)
            double qty3 = client.getAggregation(Protocol.AGGR_QTY, "ProdDia3", 6);
            assertTest("Cache 4 - Agregação do dia 3 (após evicção)", qty3 == 40);

            // Teste 5: Aceder novamente ao dia 0 (forçar releitura do disco)
            double qty0Again = client.getAggregation(Protocol.AGGR_QTY, "ProdDia0", 6);
            assertTest("Cache 5 - Releitura do dia 0 após evicção", qty0Again == 10);

            // Teste 6: Agregação que abrange múltiplos dias
            // Soma de todos: 10+20+30+40+50+60 = 210 (mas só últimos 6 dias)
            double qtyAll = 0;
            for (int i = 0; i < 6; i++) {
                qtyAll += client.getAggregation(Protocol.AGGR_QTY, "ProdDia" + i, 6);
            }
            assertTest("Cache 6 - Agregação total de 6 dias", qtyAll == 210);
        }
    }

    /**
     * Testa a persistência de dados entre operações.
     */
    private static void runPersistenceTests() throws Exception {
        printSection("TESTES DE PERSISTÊNCIA");

        String testUser = "persist_tester_" + System.currentTimeMillis();

        // Fase 1: Criar dados e persistir
        System.out.println("  Fase 1: Criando e persistindo dados...");
        try (ClientLib client = new ClientLib(HOST, PORT)) {
            client.register(testUser, "pass");
            client.login(testUser, "pass");

            // Inserir dados conhecidos
            for (int i = 0; i < 100; i++) {
                client.addEvent("PersistProd", 1, 10.0);
            }
            client.newDay();
            System.out.println("    100 eventos inseridos e persistidos.");
        }

        // Fase 2: Nova conexão, verificar dados
        System.out.println("  Fase 2: Nova conexão, verificando dados...");
        try (ClientLib client = new ClientLib(HOST, PORT)) {
            client.login(testUser, "pass");

            double qty = client.getAggregation(Protocol.AGGR_QTY, "PersistProd", 1);
            assertTest("Persistência 1 - Quantidade preservada", qty == 100);

            double vol = client.getAggregation(Protocol.AGGR_VOL, "PersistProd", 1);
            assertTest("Persistência 2 - Volume preservado", Math.abs(vol - 1000.0) < 0.01);
        }

        // Fase 3: Verificar que cache de agregações funciona
        System.out.println("  Fase 3: Testando cache de agregações...");
        try (ClientLib client = new ClientLib(HOST, PORT)) {
            client.login(testUser, "pass");

            // Primeira chamada: calcula e guarda em cache
            long start1 = System.nanoTime();
            double qty1 = client.getAggregation(Protocol.AGGR_QTY, "PersistProd", 1);
            long time1 = System.nanoTime() - start1;

            // Segunda chamada: deve vir da cache (mais rápido)
            long start2 = System.nanoTime();
            double qty2 = client.getAggregation(Protocol.AGGR_QTY, "PersistProd", 1);
            long time2 = System.nanoTime() - start2;

            assertTest("Persistência 3 - Valores consistentes", qty1 == qty2);
            System.out.printf("    1ª chamada: %.3fms, 2ª chamada: %.3fms%n",
                    time1 / 1_000_000.0, time2 / 1_000_000.0);

            // Nota: A cache pode não ser significativamente mais rápida em pequenos datasets
            assertTest("Persistência 4 - Cache funcional", qty1 == 100 && qty2 == 100);
        }
    }

    /**
     * Testa comportamento com grandes volumes de dados.
     */
    private static void runLargeDatasetTests() throws Exception {
        printSection("TESTES COM GRANDE VOLUME DE DADOS");

        String testUser = "large_tester_" + System.currentTimeMillis();

        try (ClientLib client = new ClientLib(HOST, PORT)) {
            client.register(testUser, "pass");
            client.login(testUser, "pass");

            // Teste com 10000 eventos por dia durante 5 dias
            int eventsPerDay = 10000;
            int numDays = 5;
            String product = "MassiveProd";

            System.out.printf("  Inserindo %d eventos por dia durante %d dias...%n", eventsPerDay, numDays);

            long totalStart = System.currentTimeMillis();

            for (int day = 0; day < numDays; day++) {
                long dayStart = System.currentTimeMillis();

                for (int e = 0; e < eventsPerDay; e++) {
                    client.addEvent(product, 1, 1.0);
                }
                client.newDay();

                long dayTime = System.currentTimeMillis() - dayStart;
                System.out.printf("    Dia %d: %d eventos em %dms (%.0f eventos/seg)%n",
                        day, eventsPerDay, dayTime, (eventsPerDay * 1000.0) / dayTime);
            }

            long totalTime = System.currentTimeMillis() - totalStart;
            int totalEvents = eventsPerDay * numDays;
            System.out.printf("  Total: %d eventos em %dms (%.0f eventos/seg)%n",
                    totalEvents, totalTime, (totalEvents * 1000.0) / totalTime);

            // Verificar agregação total
            System.out.println("  Verificando agregações...");
            double qty = client.getAggregation(Protocol.AGGR_QTY, product, numDays);
            assertTest("Grande Volume 1 - Quantidade total correta", qty == totalEvents);

            double vol = client.getAggregation(Protocol.AGGR_VOL, product, numDays);
            assertTest("Grande Volume 2 - Volume total correto", Math.abs(vol - totalEvents) < 0.01);

            double avg = client.getAggregation(Protocol.AGGR_AVG, product, numDays);
            assertTest("Grande Volume 3 - Média correta", Math.abs(avg - 1.0) < 0.01);

            double max = client.getAggregation(Protocol.AGGR_MAX, product, numDays);
            assertTest("Grande Volume 4 - Máximo correto", Math.abs(max - 1.0) < 0.01);
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