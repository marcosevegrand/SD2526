package server;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * ============================================================================
 * POOL DE THREADS PERSONALIZADO - PADRÃO PRODUTOR-CONSUMIDOR
 * ============================================================================
 *
 * CONTEXTO E MOTIVAÇÃO:
 * ---------------------
 * Numa aplicação servidor que recebe milhares de pedidos por segundo, criar uma
 * nova thread para cada pedido seria extremamente ineficiente:
 *   - Criação de threads tem overhead significativo (alocação de stack, registo no SO)
 *   - Threads consomem memória (~1MB de stack por thread por defeito em Java)
 *   - Sem limite, o sistema pode ficar sem recursos (OutOfMemoryError)
 *   - Context switching excessivo degrada o desempenho
 *
 * SOLUÇÃO - THREAD POOL:
 * ----------------------
 * Um pool de threads pré-cria um número fixo de threads trabalhadoras que:
 *   1. Ficam à espera de tarefas numa fila partilhada
 *   2. Quando uma tarefa chega, uma thread disponível executa-a
 *   3. Após concluir, a thread volta a aguardar por mais trabalho
 *
 * Este padrão é chamado "Produtor-Consumidor":
 *   - PRODUTORES: Threads de leitura (ClientHandler) submetem tarefas
 *   - CONSUMIDORES: Threads do pool executam as tarefas
 *   - BUFFER: Fila de tarefas pendentes
 *
 * PORQUÊ IMPLEMENTAÇÃO PRÓPRIA E NÃO ExecutorService?
 * ----------------------------------------------------
 * O Java fornece java.util.concurrent.ExecutorService que faz exatamente isto.
 * Razões para implementar manualmente (contexto académico):
 *   1. Demonstrar compreensão do padrão produtor-consumidor
 *   2. Praticar sincronização com locks e conditions
 *   3. Controlo total sobre o comportamento
 *
 * Em produção, usaríamos: Executors.newFixedThreadPool(nThreads)
 *
 * DIAGRAMA DE FUNCIONAMENTO:
 * --------------------------
 *
 *   ClientHandler-1 ─┐
 *   ClientHandler-2 ─┼──► [FILA DE TAREFAS] ──► Worker-1 ──► Executa
 *   ClientHandler-3 ─┤          ▲               Worker-2 ──► Executa
 *   ClientHandler-N ─┘          │               Worker-3 ──► Executa
 *                               │               Worker-N ──► Executa
 *                          submit()                  │
 *                                                    ▼
 *                                              workerLoop()
 *
 * GARANTIAS DE THREAD-SAFETY:
 * ---------------------------
 * - A fila é protegida por um ReentrantLock
 * - Workers aguardam com Condition.await() (não fazem busy-waiting)
 * - Shutdown é sinalizado atomicamente a todas as threads
 */
public class ThreadPool {

    // ==========================================================================
    // ESTRUTURAS DE DADOS DO POOL
    // ==========================================================================

    /**
     * Fila de tarefas pendentes aguardando execução.
     *
     * PORQUÊ LinkedList E NÃO ArrayList?
     * ----------------------------------
     * LinkedList implementa a interface Queue e oferece operações O(1) para:
     *   - addLast() / add() - adicionar no fim (produtor)
     *   - pollFirst() / poll() - remover do início (consumidor)
     *
     * ArrayList teria O(n) para remover do início (shift de todos os elementos).
     *
     * PORQUÊ NÃO BlockingQueue (ex: LinkedBlockingQueue)?
     * ----------------------------------------------------
     * BlockingQueue já tem sincronização interna e métodos bloqueantes,
     * mas aqui queremos demonstrar a implementação manual com locks.
     * Em produção, usaríamos BlockingQueue para código mais simples e robusto.
     */
    private final LinkedList<Runnable> taskQueue;

    /**
     * Lista de todas as threads trabalhadoras do pool.
     * Guardamos referências para poder fazer interrupt() no shutdown.
     *
     * NOTA: Esta lista é apenas lida após construção, por isso não precisa
     * de sincronização adicional.
     */
    private final List<Thread> workers;

    /**
     * Lock para proteger acesso à fila de tarefas.
     *
     * PORQUÊ ReentrantLock E NÃO synchronized?
     * ----------------------------------------
     * Ambos funcionariam, mas ReentrantLock oferece:
     *   1. Conditions múltiplas (podíamos ter notEmpty E notFull se limitássemos a fila)
     *   2. Tentativa de lock com timeout (tryLock)
     *   3. Verificação de quem tem o lock (isHeldByCurrentThread)
     *   4. Fairness opcional (threads esperam por ordem FIFO)
     *
     * Neste caso simples, synchronized seria suficiente, mas ReentrantLock
     * é mais explícito e demonstra conhecimento de concorrência avançada.
     */
    private final ReentrantLock lock = new ReentrantLock();

    /**
     * Condition para sinalizar que a fila tem tarefas.
     *
     * COMO FUNCIONA UMA CONDITION?
     * ----------------------------
     * É como um "ponto de espera" associado a um lock:
     *   - await(): liberta o lock e bloqueia a thread até ser sinalizada
     *   - signal(): acorda UMA thread em espera nesta condition
     *   - signalAll(): acorda TODAS as threads em espera
     *
     * VANTAGEM SOBRE wait()/notify():
     * --------------------------------
     * Com synchronized, só existe um "wait set" por objeto.
     * Com ReentrantLock, podemos ter múltiplas Conditions:
     *   - Condition notEmpty: workers esperam por tarefas
     *   - Condition notFull: produtores esperam por espaço (se fila limitada)
     *
     * Isto permite sinalização mais precisa (acordar só quem interessa).
     */
    private final Condition notEmpty = lock.newCondition();

    /**
     * Flag que indica se o pool está em processo de encerramento.
     *
     * PORQUÊ NÃO É VOLATILE?
     * ----------------------
     * Porque todos os acessos a esta variável são feitos dentro do lock,
     * que já garante visibilidade e ordering. O lock implica uma memory barrier.
     *
     * Se acedêssemos fora do lock, teria de ser volatile.
     */
    private boolean isShutdown = false;

    // ==========================================================================
    // CONSTRUTOR - INICIALIZAÇÃO DO POOL
    // ==========================================================================

    /**
     * Cria um pool com o número especificado de threads trabalhadoras.
     *
     * COMO ESCOLHER O NÚMERO DE THREADS?
     * -----------------------------------
     * Depende do tipo de trabalho:
     *
     * 1. TAREFAS CPU-BOUND (cálculos intensivos):
     *    - nThreads = número de CPUs (Runtime.getRuntime().availableProcessors())
     *    - Mais threads não ajudam (competem pelo mesmo CPU)
     *
     * 2. TAREFAS I/O-BOUND (espera por rede, disco, BD):
     *    - nThreads = número de CPUs × (1 + tempo_espera / tempo_cálculo)
     *    - Enquanto uma thread espera I/O, outras podem usar o CPU
     *
     * O nosso servidor é maioritariamente I/O-bound (rede, disco), por isso
     * pode beneficiar de mais threads do que CPUs.
     *
     * REGRA PRÁTICA: Começar com 2×CPUs e ajustar com base em métricas.
     *
     * @param nThreads Número de threads no pool (recomendado: 50-200 para servidores I/O-bound)
     */
    public ThreadPool(int nThreads) {
        // Inicializar a fila de tarefas (vazia inicialmente)
        this.taskQueue = new LinkedList<>();

        // Lista para guardar referências às threads (para shutdown posterior)
        // ArrayList com capacidade pré-definida evita realocações
        this.workers = new ArrayList<>(nThreads);

        // Criar e iniciar todas as threads trabalhadoras
        for (int i = 0; i < nThreads; i++) {
            // NOTA: Passar referência ao método workerLoop como Runnable
            // Isto é equivalente a: new Thread(() -> this.workerLoop())
            // Mas mais conciso usando method reference (Java 8+)
            Thread worker = new Thread(this::workerLoop, "PoolWorker-" + i);

            // PORQUÊ NOMEAR AS THREADS?
            // -------------------------
            // 1. Facilita debugging (stack traces mostram "PoolWorker-3" em vez de "Thread-47")
            // 2. Ferramentas de monitorização (JConsole, VisualVM) mostram nomes legíveis
            // 3. Logs ficam mais informativos

            workers.add(worker);

            // IMPORTANTE: start() apenas agenda a thread para execução.
            // A thread começará a executar workerLoop() quando o scheduler do SO decidir.
            worker.start();
        }

        // Neste ponto, todas as threads estão criadas e a executar workerLoop().
        // Como a fila está vazia, todas estão bloqueadas em notEmpty.await().
    }

    // ==========================================================================
    // CICLO DE VIDA DOS WORKERS - O CORAÇÃO DO POOL
    // ==========================================================================

    /**
     * Ciclo principal de cada thread trabalhadora.
     *
     * FLUXO DE EXECUÇÃO:
     * ------------------
     * 1. Adquirir o lock
     * 2. Verificar se há tarefas na fila:
     *    - SE SIM: retirar uma tarefa e executá-la
     *    - SE NÃO: esperar na condition até ser sinalizada
     * 3. Repetir até shutdown
     *
     * PADRÃO DE ESPERA (GUARD PATTERN):
     * ---------------------------------
     * O while em vez de if é CRÍTICO para evitar "spurious wakeups":
     *
     *   ERRADO (pode causar bugs):
     *   if (taskQueue.isEmpty()) {
     *       notEmpty.await();  // Pode acordar sem motivo!
     *   }
     *   task = taskQueue.poll();  // Pode retornar null!
     *
     *   CORRETO:
     *   while (taskQueue.isEmpty()) {
     *       notEmpty.await();  // Se acordar sem motivo, verifica de novo
     *   }
     *   task = taskQueue.poll();  // Garantidamente não-null
     *
     * SPURIOUS WAKEUPS: O sistema operativo pode acordar threads sem
     * que signal() tenha sido chamado. É comportamento permitido pelo
     * Java Memory Model para otimização de performance.
     */
    private void workerLoop() {
        // Loop infinito - só termina com shutdown ou exceção não tratada
        while (true) {
            Runnable task;  // Tarefa a executar nesta iteração

            // ===== SECÇÃO CRÍTICA: ACESSO À FILA =====
            lock.lock();
            try {
                // PADRÃO DE ESPERA: while (condição_não_satisfeita) { await(); }
                // Continuar a esperar enquanto:
                //   1. A fila está vazia E
                //   2. O pool não está em shutdown
                while (taskQueue.isEmpty() && !isShutdown) {
                    try {
                        // COMPORTAMENTO DE await():
                        // 1. Liberta o lock atomicamente
                        // 2. Bloqueia a thread (não consome CPU)
                        // 3. Quando sinalizada, re-adquire o lock antes de retornar
                        //
                        // IMPORTANTE: A thread fica bloqueada aqui até:
                        //   - submit() chamar signal() após adicionar tarefa
                        //   - shutdown() chamar signalAll()
                        //   - Ocorrer uma InterruptedException
                        //   - Spurious wakeup (por isso usamos while)
                        notEmpty.await();
                    } catch (InterruptedException e) {
                        // A thread foi interrompida - verificar se devemos terminar
                        if (isShutdown) {
                            // Pool está a encerrar - sair do loop principal
                            return;
                        }
                        // Não é shutdown - ignorar interrupção e continuar
                        // (pode ser um signal() que usou interrupt por engano)

                        // NOTA: Podíamos também restaurar o estado de interrupção:
                        // Thread.currentThread().interrupt();
                        // Mas neste caso preferimos ignorar se não é shutdown.
                    }
                }

                // Após sair do while, uma de duas coisas aconteceu:
                //   1. Há tarefas na fila (taskQueue.isEmpty() == false)
                //   2. É shutdown (isShutdown == true)

                // Se é shutdown E a fila está vazia, terminar graciosamente
                // NOTA: Mesmo em shutdown, processamos tarefas pendentes!
                if (isShutdown && taskQueue.isEmpty()) {
                    return;  // Sair do workerLoop() - thread termina
                }

                // Retirar a próxima tarefa da fila (FIFO - First In, First Out)
                // poll() retorna null se vazia, mas sabemos que não está vazia
                // porque passámos o while acima
                task = taskQueue.poll();

                // PORQUÊ poll() E NÃO remove()?
                // ------------------------------
                // - poll(): retorna null se vazia (nunca lança exceção)
                // - remove(): lança NoSuchElementException se vazia
                //
                // Ambos funcionariam aqui, mas poll() é mais seguro por defeito.

            } finally {
                // SEMPRE libertar o lock, mesmo que ocorra exceção
                // Este padrão try-finally é OBRIGATÓRIO com ReentrantLock
                lock.unlock();
            }
            // ===== FIM DA SECÇÃO CRÍTICA =====

            // Executar a tarefa FORA da secção crítica
            // PORQUÊ FORA DO LOCK?
            // --------------------
            // Se executássemos dentro do lock:
            //   1. Outras threads não poderiam aceder à fila
            //   2. O pool seria essencialmente single-threaded!
            //   3. Uma tarefa lenta bloquearia todas as outras
            //
            // Ao libertar o lock antes de executar:
            //   1. Outras workers podem retirar tarefas em paralelo
            //   2. Produtores podem adicionar novas tarefas
            //   3. Verdadeira execução paralela

            if (task != null) {
                try {
                    // Executar a tarefa
                    // task.run() executa sincronamente - bloqueia até concluir
                    task.run();

                    // NOTA: run() e não start()!
                    // - run(): executa o código na thread atual
                    // - start(): cria nova thread (não é o que queremos)

                } catch (Exception e) {
                    // TRATAMENTO DE EXCEÇÕES EM TAREFAS:
                    // -----------------------------------
                    // Se uma tarefa lança exceção, NÃO queremos que o worker morra.
                    // Capturamos, registamos, e continuamos para a próxima tarefa.
                    //
                    // Isto garante que uma tarefa mal comportada não reduz
                    // permanentemente o tamanho do pool.
                    //
                    // ALTERNATIVAS:
                    // 1. Re-lançar: o worker morre, pool fica menor (mau)
                    // 2. Recriar worker: mais complexo, overhead de criação
                    // 3. Ignorar: simples, pool mantém-se estável (escolhido)

                    System.err.println(
                            "[" + Thread.currentThread().getName() + "] Erro na execução: " + e.getMessage()
                    );
                    // NOTA: Em produção, usaríamos logging framework (SLF4J, Log4j)
                    // e possivelmente um callback de erro para o ClientHandler
                }
            }
            // Voltar ao início do while(true) para processar próxima tarefa
        }
    }

    // ==========================================================================
    // SUBMISSÃO DE TAREFAS - INTERFACE DO PRODUTOR
    // ==========================================================================

    /**
     * Submete uma tarefa para execução assíncrona.
     *
     * COMPORTAMENTO:
     * --------------
     * 1. Adiciona a tarefa ao fim da fila
     * 2. Sinaliza UMA thread worker para acordar
     * 3. Retorna imediatamente (não espera pela execução)
     *
     * THREAD-SAFETY:
     * --------------
     * Múltiplas threads podem chamar submit() concorrentemente.
     * O lock garante que a fila não é corrompida.
     *
     * ORDEM DE EXECUÇÃO:
     * ------------------
     * As tarefas são executadas aproximadamente em ordem FIFO, mas não há
     * garantias estritas porque múltiplos workers competem pela fila.
     *
     * @param task Tarefa a executar (implementação de Runnable)
     *
     * EXEMPLO DE USO:
     *   pool.submit(() -> processRequest(clientRequest));
     *   pool.submit(() -> {
     *       int result = compute();
     *       sendResponse(result);
     *   });
     */
    public void submit(Runnable task) {
        lock.lock();
        try {
            // Ignorar submissões após shutdown
            // PORQUÊ IGNORAR E NÃO LANÇAR EXCEÇÃO?
            // -------------------------------------
            // Durante o shutdown, podem haver tarefas "in-flight" que tentam
            // submeter sub-tarefas. Lançar exceção complicaria o código cliente.
            // Ignorar silenciosamente é mais gracioso para shutdown.
            //
            // Em produção, poderíamos retornar um Future que indica rejeição.
            if (isShutdown) {
                return;
            }

            // Adicionar tarefa ao fim da fila (FIFO)
            taskQueue.add(task);

            // PORQUÊ signal() E NÃO signalAll()?
            // -----------------------------------
            // signal(): acorda apenas UMA thread em espera
            // signalAll(): acorda TODAS as threads em espera
            //
            // Como adicionámos apenas UMA tarefa, só precisamos de UM worker.
            // Acordar todas seria desperdício:
            //   - Todas competiriam pelo lock
            //   - Apenas uma obteria a tarefa
            //   - As outras voltariam a dormir
            //
            // signal() é mais eficiente para este caso.
            //
            // QUANDO USAR signalAll()?
            // -------------------------
            // - Quando a condição pode satisfazer múltiplas threads
            // - No shutdown (todas devem verificar se devem terminar)
            // - Quando não sabemos quantas threads beneficiam
            notEmpty.signal();

        } finally {
            lock.unlock();
        }
    }

    // ==========================================================================
    // ENCERRAMENTO DO POOL - LIFECYCLE MANAGEMENT
    // ==========================================================================

    /**
     * Encerra o pool de forma ordenada.
     *
     * ESTRATÉGIA DE SHUTDOWN:
     * -----------------------
     * 1. Marcar isShutdown = true (novas submissões ignoradas)
     * 2. Acordar todas as threads para verificarem a flag
     * 3. Interromper threads que possam estar bloqueadas noutro sítio
     * 4. Tarefas já na fila são processadas antes de terminar
     *
     * SHUTDOWN GRACIOSO VS ABRUPTO:
     * -----------------------------
     * - GRACIOSO (implementado): processa tarefas pendentes, depois termina
     * - ABRUPTO (não implementado): termina imediatamente, descarta tarefas
     *
     * Para shutdown abrupto, faríamos: taskQueue.clear() antes de signalAll()
     *
     * NOTA: Este método não bloqueia até os workers terminarem.
     * Para esperar, adicionaríamos: workers.forEach(Thread::join)
     */
    public void shutdown() {
        // ===== FASE 1: SINALIZAR SHUTDOWN =====
        lock.lock();
        try {
            // Marcar o pool como em shutdown
            // Esta flag é verificada em:
            //   - submit(): para rejeitar novas tarefas
            //   - workerLoop(): para terminar quando fila vazia
            isShutdown = true;

            // Acordar TODAS as threads trabalhadoras
            // PORQUÊ signalAll() AQUI?
            // -------------------------
            // Todas as threads precisam de:
            //   1. Acordar do await()
            //   2. Verificar isShutdown
            //   3. Decidir se terminam ou processam tarefas restantes
            //
            // Se usássemos signal(), apenas uma thread acordaria, e as outras
            // ficariam bloqueadas indefinidamente.
            notEmpty.signalAll();

        } finally {
            lock.unlock();
        }

        // ===== FASE 2: INTERROMPER THREADS BLOQUEADAS =====
        // As threads podem estar bloqueadas em:
        //   - await(): já foram acordadas por signalAll()
        //   - I/O: precisam de interrupt() para abortar
        //   - sleep(): precisam de interrupt() para acordar
        //   - join(): precisam de interrupt()
        //
        // interrupt() causa InterruptedException em operações bloqueantes,
        // permitindo que a thread verifique isShutdown e termine.
        for (Thread worker : workers) {
            worker.interrupt();
        }

        // NOTA: Após este ponto, as threads vão:
        // 1. Terminar tarefas em execução (não são interrompidas a meio)
        // 2. Processar tarefas restantes na fila
        // 3. Terminar quando fila estiver vazia
        //
        // Este método retorna imediatamente - não espera conclusão.
        // Para aguardar, o chamador pode fazer:
        //   pool.shutdown();
        //   for (Thread w : pool.workers) { w.join(); }
    }
}