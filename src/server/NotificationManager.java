package server;

import java.util.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ============================================================================
 * GESTOR DE NOTIFICAÇÕES EM TEMPO REAL
 * ============================================================================
 *
 * CONTEXTO E MOTIVAÇÃO:
 * ---------------------
 * Em muitos sistemas, os clientes precisam de ser NOTIFICADOS quando certas
 * condições ocorrem, em vez de fazer polling repetido. Exemplos:
 *   - Stock trading: notificar quando preço atinge um threshold
 *   - Chat: notificar quando nova mensagem chega
 *   - IoT: notificar quando sensor deteta anomalia
 *   - Este sistema: notificar quando certos padrões de venda ocorrem
 *
 * PROBLEMA DO POLLING:
 * --------------------
 * Se clientes fizessem polling ("já aconteceu? e agora? e agora?"):
 *   - Desperdício de CPU e rede
 *   - Latência alta (só descobre na próxima poll)
 *   - Escalabilidade pobre (N clientes × M polls/segundo)
 *
 * SOLUÇÃO - PADRÃO PUBLISH-SUBSCRIBE:
 * -----------------------------------
 *   1. Cliente SUBSCREVE uma condição (ex: "avisa quando Laptop e iPhone vendidos")
 *   2. Servidor REGISTA a subscrição e bloqueia a thread do cliente
 *   3. Quando condição ocorre, servidor ACORDA as threads relevantes
 *   4. Thread do cliente retorna com o resultado
 *
 * DIAGRAMA DE FUNCIONAMENTO:
 * --------------------------
 *
 *   Cliente-1                          Servidor                         Cliente-2
 *      │                                  │                                 │
 *      │ waitSimultaneous("A","B")        │                                 │
 *      ├─────────────────────────────────►│                                 │
 *      │                                  │◄─ registerSale("A") ────────────┤
 *      │     [BLOQUEADO]                  │                                 │
 *      │                                  │◄─ registerSale("B") ────────────┤
 *      │◄───────────── return true ───────┤                                 │
 *      │                                  │                                 │
 *
 * TIPOS DE NOTIFICAÇÃO SUPORTADOS:
 * --------------------------------
 *
 * 1. SIMULTANEOUS (waitSimultaneous):
 *    Bloqueia até que DOIS produtos específicos sejam vendidos no mesmo dia.
 *    Exemplo: "avisa quando cliente comprar Laptop E iPhone no mesmo dia"
 *
 * 2. CONSECUTIVE (waitConsecutive):
 *    Bloqueia até que UM produto seja vendido N vezes consecutivas.
 *    Exemplo: "avisa quando houver 5 vendas seguidas do mesmo produto"
 *
 * MECANISMO DE SINCRONIZAÇÃO:
 * ---------------------------
 * Usamos ReentrantLock + Condition (monitor pattern):
 *
 *   ┌──────────────────────────────────────────────────────────────────────┐
 *   │                         ReentrantLock                               │
 *   │  ┌────────────────────────────────────────────────────────────────┐ │
 *   │  │                       Condition 'change'                       │ │
 *   │  │                                                                │ │
 *   │  │   Thread-A: await() ──┐                                        │ │
 *   │  │   Thread-B: await() ──┼───► [FILA DE ESPERA]                   │ │
 *   │  │   Thread-C: await() ──┘                                        │ │
 *   │  │                                                                │ │
 *   │  │   signalAll() ────────────► Acorda TODAS as threads            │ │
 *   │  └────────────────────────────────────────────────────────────────┘ │
 *   └──────────────────────────────────────────────────────────────────────┘
 *
 * PORQUÊ signalAll() E NÃO signal()?
 * ----------------------------------
 * - signal() acorda UMA thread arbitrária
 * - signalAll() acorda TODAS as threads
 *
 * Usamos signalAll() porque diferentes threads esperam condições diferentes:
 *   - Thread-A: espera "Laptop E iPhone"
 *   - Thread-B: espera "5 consecutivas"
 *   - Thread-C: espera "PS5 E Xbox"
 *
 * Quando "Laptop" é vendido:
 *   - signal() poderia acordar Thread-B que não se interessa
 *   - signalAll() acorda todas; cada uma verifica sua condição
 *
 * Alternativa mais eficiente: conditions separadas por tipo de condição,
 * mas adiciona complexidade. Para este volume, signalAll() é adequado.
 *
 * CICLO DE VIDA DO DIA:
 * ---------------------
 *   1. Início do dia: estado limpo (soldToday vazio, consecutiveCount=0)
 *   2. Vendas: registerSale() atualiza estado e notifica waiters
 *   3. Fim do dia: newDay() reseta estado e notifica waiters
 *   4. Waiters em espera recebem resultado (sucesso ou dia terminou)
 *
 * GARANTIAS:
 * ----------
 * - Thread-safe: todas as operações protegidas pelo lock
 * - Sem deadlock: lock único, sem nested locking
 * - Sem livelock: conditions garantem progresso
 * - Sem spurious wakeups: loop while re-verifica condição
 *
 * LIMITAÇÕES:
 * -----------
 * - Notificações não persistem entre restarts do servidor
 * - Não há histórico de notificações já entregues
 * - Todas as threads acordam em cada venda (broadcast)
 */
public class NotificationManager {

    // ==========================================================================
    // PRIMITIVAS DE SINCRONIZAÇÃO
    // ==========================================================================

    /**
     * Lock para proteger todo o estado do manager.
     *
     * PORQUÊ ReentrantLock?
     * ---------------------
     * Precisamos de Conditions para implementar await/signal, e Conditions
     * só podem ser criadas a partir de ReentrantLock (ou implementação própria).
     *
     * ALTERNATIVA - synchronized + wait/notify:
     * -----------------------------------------
     *   synchronized(this) {
     *       while (!condition) {
     *           this.wait();  // equivalente a condition.await()
     *       }
     *   }
     *
     *   synchronized(this) {
     *       this.notifyAll();  // equivalente a condition.signalAll()
     *   }
     *
     * Desvantagens de synchronized:
     *   - Apenas UM wait-set por objeto
     *   - Menos flexível que múltiplas Conditions
     *   - Menos funcionalidades (tryLock, lockInterruptibly)
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Condition para notificar mudanças de estado.
     *
     * COMO FUNCIONA UMA CONDITION:
     * ----------------------------
     * Uma Condition é essencialmente uma fila de threads em espera:
     *
     *   await():
     *     1. Liberta o lock
     *     2. Coloca a thread na fila de espera
     *     3. Bloqueia a thread
     *     4. Quando sinalizada, readquire o lock
     *     5. Retorna
     *
     *   signal():
     *     1. Remove UMA thread da fila
     *     2. Move-a para a fila de threads prontas a adquirir lock
     *
     *   signalAll():
     *     1. Remove TODAS as threads da fila
     *     2. Move-as para a fila de threads prontas
     *
     * SPURIOUS WAKEUPS:
     * -----------------
     * Threads podem acordar sem signal (spurious wakeup) devido a:
     *   - Interrupção do sistema operativo
     *   - Otimizações da JVM
     *   - Comportamento do scheduler
     *
     * Por isso, SEMPRE usamos while loop, não if:
     *
     *   while (!myCondition) {  // NÃO: if (!myCondition)
     *       condition.await();
     *   }
     *
     * Se acordar spuriously, o while re-verifica a condição.
     */
    private final Condition change = lock.newCondition();

    // ==========================================================================
    // ESTADO DO DIA ATUAL
    // ==========================================================================

    /**
     * Conjunto de produtos vendidos no dia atual.
     *
     * USO: Verificar se dois produtos específicos foram vendidos (waitSimultaneous).
     *
     * OPERAÇÕES:
     * ----------
     * - registerSale("Laptop"): soldToday.add("Laptop")
     * - waitSimultaneous("Laptop", "iPhone"):
     *     soldToday.contains("Laptop") && soldToday.contains("iPhone")
     * - newDay(): soldToday.clear()
     *
     * PORQUÊ HashSet?
     * ---------------
     * - contains() é O(1) em média
     * - add() é O(1) em média
     * - Não permite duplicados (mas não precisamos de contagem aqui)
     *
     * ALTERNATIVA - TreeSet:
     * ----------------------
     * Se precisássemos de ordenação ou range queries, usaríamos TreeSet.
     * Mas para simples membership test, HashSet é mais rápido.
     */
    private final Set<String> soldToday = new HashSet<>();

    /**
     * Último produto vendido (para tracking de consecutivos).
     *
     * SEMÂNTICA:
     * ----------
     * - null: nenhuma venda ainda no dia
     * - "Laptop": última venda foi de Laptop
     *
     * Usado em conjunto com consecutiveCount para determinar se
     * estamos numa sequência de vendas do mesmo produto.
     */
    private String lastProductSold = null;

    /**
     * Contagem de vendas consecutivas do produto atual.
     *
     * EXEMPLO:
     * --------
     *   Vendas: A, A, A, B, B, A
     *
     *   Após A:   lastProductSold="A", consecutiveCount=1
     *   Após A:   lastProductSold="A", consecutiveCount=2
     *   Após A:   lastProductSold="A", consecutiveCount=3  ← 3 consecutivos de A
     *   Após B:   lastProductSold="B", consecutiveCount=1  ← reset!
     *   Após B:   lastProductSold="B", consecutiveCount=2
     *   Após A:   lastProductSold="A", consecutiveCount=1  ← reset de novo
     */
    private int consecutiveCount = 0;

    /**
     * Dia atual (contador simples para identificar mudanças de dia).
     *
     * USO:
     * ----
     * Waiters guardam o dia em que iniciaram a espera (startDay).
     * Se currentDay != startDay quando acordam, o dia terminou.
     *
     * NOTA: Este contador é independente do currentDay do StorageEngine.
     * Poderia ser sincronizado, mas para este caso não é necessário.
     */
    private int currentDay = 0;

    // ==========================================================================
    // HISTÓRICO DE STREAKS
    // ==========================================================================

    /**
     * Histórico de produtos que atingiram cada contagem consecutiva durante o dia.
     *
     * ESTRUTURA:
     * ----------
     *   streaksReached: Map<Integer, Set<String>>
     *                   └─ contagem ───► produtos que atingiram
     *
     * EXEMPLO:
     * --------
     *   Vendas: A, A, A, B, A, A
     *
     *   Após todas as vendas:
     *     streaksReached = {
     *       1: {"A", "B"},      // A atingiu 1, B atingiu 1
     *       2: {"A"},           // A atingiu 2 (duas vezes no dia)
     *       3: {"A"}            // A atingiu 3
     *     }
     *
     * PORQUÊ GUARDAR HISTÓRICO?
     * -------------------------
     * PROBLEMA: Se uma thread inicia waitConsecutive(3) DEPOIS de já ter
     * havido 3 vendas consecutivas de "A", ela ficaria bloqueada para sempre
     * (a condição já passou mas ela não viu).
     *
     * SOLUÇÃO: Guardamos histórico de todas as streaks atingidas.
     * A thread verifica se sua condição JÁ foi satisfeita antes de bloquear.
     *
     * CENÁRIO:
     *   1. Vendas: A, A, A (streak de 3)
     *   2. Thread chama waitConsecutive(3)
     *   3. streaksReached.containsKey(3) == true
     *   4. Thread retorna imediatamente com "A"
     *
     * Sem este histórico, a thread bloquearia indefinidamente.
     */
    private final Map<Integer, Set<String>> streaksReached = new HashMap<>();

    // ==========================================================================
    // OPERAÇÕES DE REGISTO
    // ==========================================================================

    /**
     * Regista uma venda e verifica se as metas de notificação foram atingidas.
     *
     * @param product Nome do produto vendido
     *
     * FLUXO:
     * ------
     *   1. Adquirir lock
     *   2. Adicionar produto ao conjunto soldToday
     *   3. Atualizar contagem de consecutivos
     *   4. Registar streak atingida no histórico
     *   5. Notificar TODAS as threads em espera (signalAll)
     *   6. Libertar lock
     *
     * CHAMADO POR: ClientHandler quando processa ADD_EVENT
     *
     * COMPLEXIDADE: O(1) para operações, O(n) implícito para acordar threads
     *
     * NOTA SOBRE signalAll():
     * -----------------------
     * signalAll() acorda TODAS as threads em wait, mesmo que muitas não
     * estejam interessadas nesta venda específica. É um trade-off:
     *
     *   Alternativa mais eficiente:
     *     - Manter mapa de conditions por tipo de condição
     *     - Só sinalizar threads relevantes
     *
     *   Desvantagem:
     *     - Complexidade de código muito maior
     *     - Possíveis bugs em condições de corrida
     *
     * Para o volume esperado deste sistema, signalAll() é adequado.
     * O custo de acordar threads desnecessárias é baixo se N é pequeno.
     */
    public void registerSale(String product) {
        lock.lock();
        try {
            // 1. Adicionar ao conjunto de produtos vendidos hoje
            // HashSet.add() retorna true se elemento era novo
            soldToday.add(product);

            // 2. Atualizar tracking de vendas consecutivas
            if (product.equals(lastProductSold)) {
                // Mesmo produto que a última venda - incrementar contador
                consecutiveCount++;
            } else {
                // Produto diferente - iniciar nova sequência
                lastProductSold = product;
                consecutiveCount = 1;
            }

            // 3. Registar que esta contagem foi atingida por este produto
            // Isto permite que waitConsecutive funcione mesmo se a thread
            // chegar depois da condição já ter ocorrido
            //
            // putIfAbsent: cria Set se não existir
            // get: obtém o Set existente
            // add: adiciona o produto ao Set de produtos que atingiram esta contagem
            streaksReached.putIfAbsent(consecutiveCount, new HashSet<>());
            streaksReached.get(consecutiveCount).add(product);

            // 4. Acordar TODAS as threads em espera
            // Cada thread vai verificar se sua condição específica foi satisfeita
            // A maioria vai voltar a dormir se não for relevante
            change.signalAll();

        } finally {
            lock.unlock();
        }
    }

    /**
     * Reinicia o estado diário e notifica o fim do ciclo às threads em espera.
     *
     * CHAMADO POR: ClientHandler quando processa NEW_DAY
     *
     * FLUXO:
     * ------
     *   1. Incrementar contador de dia
     *   2. Limpar todos os conjuntos e contadores
     *   3. Notificar todas as threads em espera
     *
     * IMPACTO NAS THREADS EM ESPERA:
     * ------------------------------
     * Threads bloqueadas em waitSimultaneous ou waitConsecutive vão:
     *   1. Acordar devido a signalAll()
     *   2. Verificar currentDay != startDay
     *   3. Retornar false/null indicando que dia terminou sem sucesso
     *
     * SEMÂNTICA:
     * ----------
     * "O dia terminou" é um resultado válido para qualquer espera.
     * O cliente pode decidir:
     *   - Tentar novamente no próximo dia
     *   - Desistir
     *   - Fazer algo diferente
     */
    public void newDay() {
        lock.lock();
        try {
            // Avançar contador de dia
            // Threads em espera vão detetar que currentDay mudou
            currentDay++;

            // Limpar estado do dia
            soldToday.clear();           // Produtos vendidos
            streaksReached.clear();      // Histórico de streaks
            lastProductSold = null;      // Reset tracking de consecutivos
            consecutiveCount = 0;        // Reset contador

            // Acordar todas as threads em espera
            // Vão verificar currentDay e retornar com resultado "dia terminou"
            change.signalAll();

            System.out.println("[NotificationManager] Novo dia iniciado: " + currentDay);

        } finally {
            lock.unlock();
        }
    }

    // ==========================================================================
    // OPERAÇÕES DE ESPERA
    // ==========================================================================

    /**
     * Aguarda até que ambos os produtos sejam vendidos no mesmo dia.
     *
     * @param p1 Primeiro produto a monitorizar
     * @param p2 Segundo produto a monitorizar
     * @return true se ambos foram vendidos, false se o dia terminou primeiro
     * @throws InterruptedException Se a thread for interrompida enquanto espera
     *
     * EXEMPLO DE USO:
     * ---------------
     *   // Thread do cliente bloqueia aqui
     *   boolean ambosVendidos = manager.waitSimultaneous("Laptop", "iPhone");
     *
     *   if (ambosVendidos) {
     *       System.out.println("Cliente comprou ambos!");
     *   } else {
     *       System.out.println("Dia terminou, tentar amanhã");
     *   }
     *
     * ALGORITMO:
     * ----------
     *   1. Guardar dia atual (startDay)
     *   2. Loop:
     *      a. Se ainda no mesmo dia E (p1 não vendido OU p2 não vendido):
     *         - await() (liberta lock e bloqueia)
     *      b. Senão: sair do loop
     *   3. Retornar: ainda estamos no mesmo dia? (se sim, condição satisfeita)
     *
     * CONDIÇÃO DE SAÍDA:
     * ------------------
     * O loop termina quando:
     *   - Dia mudou (currentDay != startDay): retorna false
     *   - Ambos vendidos (soldToday contém p1 E p2): retorna true
     *
     * SPURIOUS WAKEUPS:
     * -----------------
     * O while loop protege contra spurious wakeups e também contra
     * sinalizações irrelevantes (ex: outro produto foi vendido).
     *
     * Sequência típica:
     *   1. await() - bloqueia
     *   2. signalAll() - acorda (venda de X)
     *   3. while re-verifica: p1 e p2 vendidos? Não, X não é relevante
     *   4. await() - bloqueia novamente
     *   5. signalAll() - acorda (venda de p1)
     *   6. while re-verifica: p1 vendido? Sim. p2 vendido? Não.
     *   7. await() - bloqueia novamente
     *   8. signalAll() - acorda (venda de p2)
     *   9. while re-verifica: p1 e p2 vendidos? SIM!
     *   10. Sai do loop, retorna true
     */
    public boolean waitSimultaneous(String p1, String p2)
            throws InterruptedException {
        lock.lock();
        try {
            // Guardar dia em que iniciamos a espera
            // Usado para detetar se dia mudou durante a espera
            int startDay = currentDay;

            // Loop de espera com verificação de condição
            // Continua enquanto:
            //   - Ainda estamos no mesmo dia (currentDay == startDay)
            //   - E a condição não foi satisfeita (falta p1 OU p2)
            while (
                    currentDay == startDay &&              // Mesmo dia
                            (!soldToday.contains(p1) ||            // p1 não vendido
                                    !soldToday.contains(p2))              // OU p2 não vendido
            ) {
                // Bloquear até ser sinalizado
                // await() liberta o lock enquanto bloqueia
                // Quando acorda, readquire o lock antes de retornar
                change.await();

                // Após acordar, o while re-verifica a condição
                // Se ainda não satisfeita, volta a dormir
            }

            // Chegamos aqui por uma de duas razões:
            //   1. currentDay != startDay: dia terminou → retornar false
            //   2. soldToday contém p1 E p2: condição satisfeita → retornar true

            // Se ainda estamos no mesmo dia, a condição foi satisfeita
            return currentDay == startDay;

        } finally {
            // SEMPRE libertar o lock, mesmo em caso de InterruptedException
            lock.unlock();
        }
    }

    /**
     * Aguarda por uma sequência de N vendas consecutivas de qualquer produto.
     *
     * @param n Número de vendas consecutivas pretendido
     * @return Nome do produto que atingiu a meta, ou null se o dia terminou
     * @throws InterruptedException Se a thread for interrompida enquanto espera
     *
     * EXEMPLO DE USO:
     * ---------------
     *   // Esperar por 5 vendas consecutivas de qualquer produto
     *   String produto = manager.waitConsecutive(5);
     *
     *   if (produto != null) {
     *       System.out.println(produto + " vendeu 5 vezes seguidas!");
     *   } else {
     *       System.out.println("Dia terminou, nenhum produto atingiu 5 consecutivas");
     *   }
     *
     * DIFERENÇA DE waitSimultaneous:
     * ------------------------------
     * - waitSimultaneous: condição sobre produtos ESPECÍFICOS
     * - waitConsecutive: condição sobre QUALQUER produto
     *
     * Por isso retornamos o nome do produto vencedor, não apenas boolean.
     *
     * VERIFICAÇÃO DE HISTÓRICO:
     * -------------------------
     * ANTES de bloquear, verificamos se a condição JÁ foi satisfeita.
     *
     * Cenário problemático sem esta verificação:
     *   1. Vendas: A, A, A, A, A (5 consecutivas de A)
     *   2. Thread chama waitConsecutive(5)
     *   3. consecutiveCount == 1 (última venda é diferente, por exemplo)
     *   4. Thread bloqueia para sempre!
     *
     * Com histórico (streaksReached):
     *   1. Vendas registam: streaksReached[5] = {"A"}
     *   2. Thread chama waitConsecutive(5)
     *   3. streaksReached.containsKey(5) == true
     *   4. Thread retorna imediatamente "A"
     *
     * ALGORITMO:
     * ----------
     *   1. Guardar dia atual (startDay)
     *   2. Loop:
     *      a. Se mesmo dia E streaksReached não contém n:
     *         - await()
     *      b. Senão: sair
     *   3. Se mesmo dia: retornar primeiro produto em streaksReached[n]
     *   4. Se dia mudou: retornar null
     */
    public String waitConsecutive(int n) throws InterruptedException {
        lock.lock();
        try {
            // Guardar dia de início
            int startDay = currentDay;

            // Loop de espera
            // Sai quando dia mudar OU quando n consecutivas foram atingidas
            while (currentDay == startDay && !streaksReached.containsKey(n)) {
                change.await();
            }

            // Verificar se condição foi satisfeita (mesmo dia)
            if (currentDay == startDay) {
                // Condição satisfeita - obter produto vencedor
                Set<String> products = streaksReached.get(n);

                // Retornar o primeiro produto do conjunto
                // Em caso de empate (improvável mas possível), retorna qualquer um
                if (products != null && !products.isEmpty()) {
                    return products.iterator().next();
                }
            }

            // Dia terminou sem atingir a meta
            return null;

        } finally {
            lock.unlock();
        }
    }

    // ==========================================================================
    // MÉTODOS AUXILIARES (para debug/teste)
    // ==========================================================================

    /**
     * Retorna o dia atual do manager (para debug).
     *
     * @return Número do dia atual
     *
     * NOTA: Este método é principalmente para testes e debug.
     * Em produção, o dia do NotificationManager deveria ser sincronizado
     * com o dia do StorageEngine.
     */
    public int getCurrentDay() {
        lock.lock();
        try {
            return currentDay;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Verifica se um produto foi vendido hoje (para debug).
     *
     * @param product Produto a verificar
     * @return true se foi vendido hoje
     */
    public boolean wasSoldToday(String product) {
        lock.lock();
        try {
            return soldToday.contains(product);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Retorna a contagem atual de consecutivos (para debug).
     *
     * @return Par (produto, contagem) ou null se não há vendas
     */
    public String getCurrentStreak() {
        lock.lock();
        try {
            if (lastProductSold == null) {
                return "Nenhuma venda registada";
            }
            return lastProductSold + " x " + consecutiveCount;
        } finally {
            lock.unlock();
        }
    }
}